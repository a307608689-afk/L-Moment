package com.leicamoments.app

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer

/**
 * 超分引擎：SwinIR-S x2（0.91M 参数，RGB 输入输出，PSNR 导向，无 GAN 过锐/偏色）。
 *
 * 设计要点：
 *  - 固定 256x256 tile 推理（模型按此尺寸导出），stride 240 / overlap 16，tile 边缘裁 8px 防接缝；
 *  - 低频回注锁色彩：输出 = 双三次放大底图 + 模型高频残差，色彩与低频由原图决定，
 *    模型只贡献细节，数学上保证不偏色；
 *  - 输入先做边缘复制 padding 到 tile 网格，末尾按原尺寸裁回。
 */
object SuperRes {

    private const val TILE = 256
    private const val STRIDE = 240      // TILE - OVERLAP
    private const val CROP = 8          // tile 边缘裁剪（除贴图边界外）
    private const val SCALE = 2
    private const val WS = 8            // SwinIR window size
    private const val SHIFT = 4         // SW-MSA shift = window/2
    private const val NUM_WINDOWS = (TILE / WS) * (TILE / WS)   // 1024
    private const val MODEL_ASSET = SuperResConfig.MODEL_ASSET

    /** 输入长边上限：控制输出位图峰值内存（8192x8192 ARGB ≈ 268MB，largeHeap 下安全） */
    const val MAX_INPUT_EDGE = 4096

    private var env: OrtEnvironment? = null
    private var session: OrtSession? = null
    private var sessionAsset: String? = null      // 当前会话对应的模型
    private var maskTensor: OnnxTensor? = null   // SW-MSA 注意力掩码（仅 SwinIR 需要），随会话重建

    /** 最近一次超分性能汇总（UI 弹窗展示/复制用） */
    var lastSummary: String? = null
        private set

    @Synchronized
    private fun obtainSession(context: Context, fast: Boolean): OrtSession {
        val asset = if (fast) SuperResConfig.FAST_MODEL_ASSET else MODEL_ASSET
        if (session != null && sessionAsset == asset) return session!!
        // 模型切换：释放旧会话与掩码
        try { session?.close() } catch (_: Exception) {}
        try { maskTensor?.close() } catch (_: Exception) {}
        session = null
        maskTensor = null
        val e = OrtEnvironment.getEnvironment()
        val bytes = context.assets.open(asset).use { it.readBytes() }
        env = e
        val opt = OrtSession.SessionOptions()
        try {
            if (SuperResConfig.USE_XNNPACK) {
                opt.addXnnpack(mapOf("intra_op_num_threads" to "4"))
            }
        } catch (t: Throwable) {
            DiagLog.w("SuperRes", "XNNPACK unavailable: ${t.message}")
        }
        try {
            if (SuperResConfig.USE_NNAPI) opt.addNnapi()
        } catch (t: Throwable) {
            DiagLog.w("SuperRes", "NNAPI unavailable: ${t.message}")
        }
        if (SuperResConfig.INTRA_OP_THREADS > 0) opt.setIntraOpNumThreads(SuperResConfig.INTRA_OP_THREADS)
        session = e.createSession(bytes, opt)
        sessionAsset = asset
        if (!fast) {
            // SwinIR 需要 SW-MSA 掩码；SPAN 纯卷积不需要
            maskTensor = OnnxTensor.createTensor(
                e, buildShiftMaskBuffer(),
                longArrayOf(NUM_WINDOWS.toLong(), (WS * WS).toLong(), (WS * WS).toLong())
            )
        }
        return session!!
    }

    /** 释放 native 会话（内存吃紧时可调用，下次推理自动重建） */
    @Synchronized
    fun release() {
        try { session?.close() } catch (_: Exception) {}
        try { maskTensor?.close() } catch (_: Exception) {}
        session = null
        maskTensor = null
    }

    /**
     * x2 超分。onProgress 传 0..100。失败抛异常，由调用方兜底提示。
     * fast=true 走 SPAN 快速模型（无 mask 输入）。返回的 Bitmap 尺寸 = 输入的 2 倍。
     */
    fun upscale(context: Context, src: Bitmap, fast: Boolean, onProgress: (Int) -> Unit): Bitmap {
        val sess = obtainSession(context, fast)
        val w = src.width
        val h = src.height

        // 1. 计算 tile 网格与 padding 后尺寸（边缘复制）
        val nX = if (w <= TILE) 1 else ceilDiv(w - CROP, STRIDE)
        val nY = if (h <= TILE) 1 else ceilDiv(h - CROP, STRIDE)
        val padW = maxOf(w, (nX - 1) * STRIDE + TILE)
        val padH = maxOf(h, (nY - 1) * STRIDE + TILE)

        // 2. 边缘复制 padding 到 padW x padH
        val padded = padEdgeReplicate(src, padW, padH)

        val total = nX * nY
        var done = 0

        // 3. 输出位图（padW*2 x padH*2），最后裁回
        val outW = padW * SCALE
        val outH = padH * SCALE
        val out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)

        val inputBuf = FloatArray(3 * TILE * TILE)
        val outputShape = arrayOf(1L, 3L, (TILE * SCALE).toLong(), (TILE * SCALE).toLong())
        val pipelineStart = android.os.SystemClock.elapsedRealtime()
        var inferMsTotal = 0L
        var inferMsMax = 0L

        try {
            for (ty in 0 until nY) {
                for (tx in 0 until nX) {
                    val x0 = tx * STRIDE
                    val y0 = ty * STRIDE
                    val tTile = if (SuperResConfig.LOG_TILE_TIMING) android.os.SystemClock.elapsedRealtime() else 0L

                    // 3a. 取 tile 像素 → CHW float [0,1]
                    val px = IntArray(TILE * TILE)
                    padded.getPixels(px, 0, TILE, x0, y0, TILE, TILE)
                    fillChw(px, inputBuf)

                    // 3b. 推理（SwinIR：input + 共享 attn mask；SPAN：仅 input）
                    OnnxTensor.createTensor(env, FloatBuffer.wrap(inputBuf), longArrayOf(1, 3, TILE.toLong(), TILE.toLong())).use { t ->
                        val feeds = HashMap<String, OnnxTensor>(2)
                        feeds["input"] = t
                        if (!fast) feeds["mask"] = maskTensor!!
                        sess.run(feeds).use { res ->
                            val outTensor = res[0] as OnnxTensor
                            val fb = outTensor.floatBuffer
                            // 3c. 色彩锁：Y 取模型输出，Cb/Cr 取原图双线性放大 → 色度零漂移
                            writeTileColorLocked(out, padded, fb, x0, y0, TILE * SCALE)
                        }
                    }

                    done++
                    if (SuperResConfig.LOG_TILE_TIMING) {
                        val ms = android.os.SystemClock.elapsedRealtime() - tTile
                        inferMsTotal += ms
                        if (ms > inferMsMax) inferMsMax = ms
                        // 逐块日志降频：仅首块、每 20 块、末块打一行（12MP 超分可达数百块，逐块打点会冲掉诊断环）
                        if (done == 1 || done == total || done % 20 == 0) {
                            DiagLog.i("SuperRes", "tile $done/$total ${ms}ms")
                        }
                    }
                    onProgress(done * 100 / total)
                }
            }
        } finally {
            padded.recycle()
        }

        if (SuperResConfig.LOG_TILE_TIMING) {
            val totalMs = android.os.SystemClock.elapsedRealtime() - pipelineStart
            val avg = if (done > 0) inferMsTotal / done else 0
            val line = java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.US)
                .format(java.util.Date()) +
                " SUMMARY tiles=$done avg=${avg}ms max=${inferMsMax}ms infer=${inferMsTotal}ms " +
                "pipeline=${totalMs}ms (${w}x${h}->${w * SCALE}x${h * SCALE})"
            DiagLog.i("SuperRes", line)
            lastSummary = line
            appendTimingFile(context, line)
        }

        // 4. 裁回原尺寸的 2 倍
        val cropped = Bitmap.createBitmap(out, 0, 0, w * SCALE, h * SCALE)
        if (cropped != out) out.recycle()
        return cropped
    }

    /** 计时汇总落盘：每次新建时间戳文件，先写图片/LeicaMoments，再下载，再应用外部目录 */
    private fun appendTimingFile(context: Context, line: String) {
        val ts = java.text.SimpleDateFormat("HHmmss", java.util.Locale.US).format(java.util.Date())
        val name = "sr_timing_$ts.txt"
        // 1) MediaStore Files → Pictures/LeicaMoments（用户已知目录）
        try {
            if (writeViaMediaStore(context, android.provider.MediaStore.Files.getContentUri("external"), "Pictures/LeicaMoments", name, line)) return
        } catch (t: Throwable) {
            DiagLog.w("SuperRes", "timing pictures write failed: ${t.message}")
        }
        // 2) MediaStore Downloads
        try {
            if (writeViaMediaStore(context, android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, "Download", name, line)) return
        } catch (t: Throwable) {
            DiagLog.w("SuperRes", "timing downloads write failed: ${t.message}")
        }
        // 3) 应用外部目录（Android/data/.../files/）
        try {
            java.io.File(context.getExternalFilesDir(null), name).writeText(line + "\n")
        } catch (t: Throwable) {
            DiagLog.w("SuperRes", "timing external write failed: ${t.message}")
        }
    }

    /** 在指定 MediaStore 集合新建文件并写入；返回是否成功 */
    private fun writeViaMediaStore(context: Context, collection: android.net.Uri, relativePath: String, name: String, line: String): Boolean {
        val resolver = context.contentResolver
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "text/plain")
            if (Build.VERSION.SDK_INT >= 29) {
                put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            }
        }
        val uri = resolver.insert(collection, values) ?: return false
        resolver.openOutputStream(uri)?.use { os -> os.write((line + "\n").toByteArray()) } ?: return false
        return true
    }

    /**
     * 输出位图某 tile 区域 = YCbCr 合成：
     * Y   ← 模型输出的亮度（细节全部来自模型）
     * Cb/Cr ← 原图该区域双线性放大（色度完全绕过模型 → 色彩零漂移）
     */
    private fun writeTileColorLocked(
        out: Bitmap, paddedSrc: Bitmap,
        modelOut: java.nio.FloatBuffer, x0: Int, y0: Int, tileOut: Int
    ) {
        // 原图 tile 区域 → 双线性放大 2x 作为色度底图
        val tileBmp = Bitmap.createBitmap(paddedSrc, x0, y0, TILE, TILE)
        val base = Bitmap.createScaledBitmap(tileBmp, tileOut, tileOut, true)
        tileBmp.recycle()
        val basePx = IntArray(tileOut * tileOut)
        base.getPixels(basePx, 0, tileOut, 0, 0, tileOut, tileOut)
        base.recycle()

        // 写入范围：tile 中心区（边缘 CROP*2 只在非贴图边界裁，防接缝）
        val cropL = if (x0 == 0) 0 else CROP * SCALE
        val cropT = if (y0 == 0) 0 else CROP * SCALE
        val cropR = if (x0 + TILE >= paddedSrc.width) 0 else CROP * SCALE
        val cropB = if (y0 + TILE >= paddedSrc.height) 0 else CROP * SCALE
        val wTile = tileOut - cropL - cropR
        val hTile = tileOut - cropT - cropB
        if (wTile <= 0 || hTile <= 0) return

        val dstX = x0 * SCALE + cropL
        val dstY = y0 * SCALE + cropT

        val rgba = IntArray(wTile * hTile)
        for (yy in 0 until hTile) {
            val srcRow = (yy + cropT) * tileOut + cropL
            for (xx in 0 until wTile) {
                val idx = srcRow + xx
                // 模型输出 CHW float [0,1]：取 R/G/B 三平面加权亮度（0.299/0.587/0.114）
                val plane = tileOut * tileOut
                val rM = modelOut.get(idx)
                val gM = modelOut.get(plane + idx)
                val bM = modelOut.get(2 * plane + idx)
                val yy2 = clamp255(255f * (0.299f * rM + 0.587f * gM + 0.114f * bM))
                val b0 = basePx[idx]
                val r0 = (b0 shr 16) and 0xFF
                val g0 = (b0 shr 8) and 0xFF
                val bch0 = b0 and 0xFF
                val cb = -0.168736f * r0 - 0.331264f * g0 + 0.5f * bch0 + 128f
                val cr = 0.5f * r0 - 0.418688f * g0 - 0.081312f * bch0 + 128f
                val r = clamp255(yy2 + 1.402f * (cr - 128f))
                val g = clamp255(yy2 - 0.344136f * (cb - 128f) - 0.714136f * (cr - 128f))
                val b = clamp255(yy2 + 1.772f * (cb - 128f))
                rgba[yy * wTile + xx] = -0x1000000 or (r.toInt() shl 16) or (g.toInt() shl 8) or b.toInt()
            }
        }
        out.setPixels(rgba, 0, wTile, dstX, dstY, wTile, hTile)
    }

    private fun fillChw(px: IntArray, buf: FloatArray) {
        val n = px.size
        val rOff = 0
        val gOff = n
        val bOff = 2 * n
        for (i in 0 until n) {
            val p = px[i]
            buf[rOff + i] = ((p shr 16) and 0xFF) / 255f
            buf[gOff + i] = ((p shr 8) and 0xFF) / 255f
            buf[bOff + i] = (p and 0xFF) / 255f
        }
    }

    /** 边缘复制 padding：超出原图的像素复制最近的边缘像素 */
    private fun padEdgeReplicate(src: Bitmap, padW: Int, padH: Int): Bitmap {
        if (src.width == padW && src.height == padH) return src.copy(src.config!!, false)
        val out = Bitmap.createBitmap(padW, padH, Bitmap.Config.ARGB_8888)
        val w = src.width
        val h = src.height
        val row = IntArray(w)
        for (y in 0 until padH) {
            val sy = if (y < h) y else h - 1
            src.getPixels(row, 0, w, 0, sy, w, 1)
            // 填本行
            if (w == padW) {
                out.setPixels(row, 0, w, 0, y, w, 1)
            } else {
                val line = IntArray(padW)
                System.arraycopy(row, 0, line, 0, w)
                val last = row[w - 1]
                for (x in w until padW) line[x] = last
                out.setPixels(line, 0, padW, 0, y, padW, 1)
            }
        }
        return out
    }

    private fun ceilDiv(a: Int, b: Int): Int = (a + b - 1) / b
    private fun clamp255(v: Float): Float = if (v < 0f) 0f else if (v > 255f) 255f else v

    /**
     * 构建 SW-MSA 注意力掩码（对应 network_swinir.calculate_mask，tile=256/window=8/shift=4）。
     * 形状 [1024, 64, 64]，窗口间区域 id 相同=0、不同=-100。所有 shifted 块共用同一份。
     */
    private fun buildShiftMaskBuffer(): java.nio.FloatBuffer {
        val nPx = WS * WS                                   // 64
        val region = IntArray(TILE * TILE)                  // 每个 8x8 块一个区域 id
        var cnt = 0
        val hs = listOf(0 to (TILE - WS), (TILE - WS) to (TILE - SHIFT), (TILE - SHIFT) to TILE)
        val wsr = listOf(0 to (TILE - WS), (TILE - WS) to (TILE - SHIFT), (TILE - SHIFT) to TILE)
        for ((h0, h1) in hs) for ((w0, w1) in wsr) {
            for (i in h0 until h1) for (j in w0 until w1) region[i * TILE + j] = cnt
            cnt++
        }
        // window partition → 每窗口 64 个像素的 region id
        val nWx = TILE / WS
        val mw = IntArray(NUM_WINDOWS * nPx)
        for (wy in 0 until nWx) for (wx in 0 until nWx) {
            val wIdx = wy * nWx + wx
            for (py in 0 until WS) for (px in 0 until WS) {
                mw[wIdx * nPx + py * WS + px] = region[(wy * WS + py) * TILE + (wx * WS + px)]
            }
        }
        // [nW, 64, 64]：id 差 → 0 / -100
        val mask = FloatArray(NUM_WINDOWS * nPx * nPx)
        var k = 0
        for (w in 0 until NUM_WINDOWS) {
            val base = w * nPx
            for (p in 0 until nPx) {
                val rp = mw[base + p]
                for (q in 0 until nPx) {
                    mask[k++] = if (rp == mw[base + q]) 0f else -100f
                }
            }
        }
        val bb = java.nio.ByteBuffer.allocateDirect(mask.size * 4)
        bb.order(java.nio.ByteOrder.nativeOrder())
        val fb = bb.asFloatBuffer()
        fb.put(mask)
        fb.rewind()   // put 会把 position 推到末尾，必须归零，否则 remaining()=0 被 ORT 拒绝
        return fb
    }
}
