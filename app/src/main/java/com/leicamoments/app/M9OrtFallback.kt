package com.leicamoments.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.codex.leicaapp.LegendaryPostProcessor
import com.codex.leicaapp.MadridLegendary
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.zip.CRC32

/**
 * M9/M9+/M10R 全机型 ORT 降级链（OffLineM9 解密权重的 ONNX 移植）。
 *
 * 定位：native styletrans（QNN/HTP）与 QNN CPU 补丁链都不可用的机型上的
 * "真 M9 神经网络"降级档——同一个 StyleTrans 网络（ESRGAN/RRDB 形态，
 * 权重自小米 17U ROM HTP 上下文解出），ORT CPU fp32 推理。
 *
 * 管线复刻 m9/styletrans.py（工作分辨率自适应版）：
 *   竖图转横 → fit 4096x3072 → 中心 pad(镜像)到最小 4:3 画布 → 缩 1024x768 → pad 48
 *   → net(1,3,864,1120)+(1,1,864,1120) → 裁 1024x768 → 上采样到画布尺寸
 *   → ColorFix 近似（σ2 高频搬运：styled + 原图高频）→ 裁回 → 转回竖图
 *   → native 暖调 LUT（Bitmap 直入直出）→ JPEG95 单次编码 + EXIF。
 *
 * 质量要点（PC 对拍 vs 真 ColorFix u8 mean ~1.4，远小于可感知阈）：
 *   - 细节场（orig − blur）必须全分辨率计算——半分辨率会丢 0.25~0.5 cyc/px
 *     最细纹理带（v1.2 曾犯此错导致画质回归）；
 *   - 低通模糊场本身平滑 → 半分辨率计算 + 双线性放大近似无损（高斯 σ2 在
 *     0.25cyc/px 处能量衰减 ~e^-5，半分辨率表示无损），提速 4×；
 *   - styled 中间体以 Bitmap 缓存/传递（不落 JPEG），全链只编码一次 JPEG95。
 *
 * v1.3 优化（2026-10-10）：
 *   a) 工作分辨率自适应：画布 = 包含输入的最小 4:3 尺寸，12.5MP 4:3 逐像素不变；
 *   b) 三模式 styled Bitmap 单槽缓存（CRC32 键控），三连跑只算一遍网络；
 *   c) 后处理瘦身：模糊场半分辨率、合成只取裁剪窗口、无全尺寸中间位图；
 *   d) 暖调 LUT 在 styled 副本上做（applyWarmLutBitmapBin 对 mutable 输入是原地改，
 *      副本化保证单槽缓存只存「网络输出、未暖调」，见 run() 内注释），消灭 styled
 *      JPEG95 + 暖调解码 + 终编 的多代 JPEG 编码。
 *
 * 模型档位：native 链 lux/cct 恒 200/5500 → choose_model 恒选 high，故只带 high。
 * 真机标定（OPPO OPD2515）：session 就绪 0.6s；网络段 ~3s（fp32 地板）。
 * 内存峰值：画布 ≤4096x3072，低内存机型 OOM 时返回 null 自动落纯 LUT，不崩。
 */
object M9OrtFallback {

    private const val TAG = "M9ORT"
    private const val MODEL_ASSET = "m9fb_styletrans_high.onnx"
    private const val W4K = 4096
    private const val H4K = 3072
    private const val NW = 1024
    private const val NH = 768
    private const val PAD = 48
    private const val DETAIL_SIGMA = 2f

    @Volatile private var env: OrtEnvironment? = null
    @Volatile private var session: OrtSession? = null

    // styled Bitmap 单槽缓存（网络输出与暖调参数无关，M9/M9P/M10R 共用）
    @Volatile private var cacheHash: Long = Long.MIN_VALUE
    @Volatile private var cacheStyled: Bitmap? = null

    /**
     * @param warmBin 暖调 LUT bin 资产名（M9/M9P 用 m9 snapshot，M10R 用 m10r snapshot）
     * @return 处理后 JPEG 字节；任何失败返回 null（调用方继续走纯 LUT 降级）
     */
    fun run(
        app: Context,
        jpeg: ByteArray,
        denoise: Float = 0.6f,
        ev: Float = -0.95f,
        warmth: Float = 0.3f,
        warmBin: String = "leica_filter_param_m9_snapshot.bin",
    ): ByteArray? {
        return try {
            val t0 = System.currentTimeMillis()
            val hash = crc32(jpeg)
            val styledBmp = styledCached(app, jpeg, hash) ?: return null

            // 暖调 LUT 补齐 M9 语义（Bitmap 直入直出，无 JPEG 中间代）。
            // 必须在副本上做：applyWarmLutBitmapBin 对 mutable 输入原地改并返回同一对象，
            // 直接喂缓存里的 styledBmp 会让随后的 recycle 废掉缓存位图（下次命中即
            // "Can't compress a recycled bitmap"），并让缓存变成「已暖调」→ 命中后双重暖调。
            val work = styledBmp.copy(Bitmap.Config.ARGB_8888, true) ?: return null
            val warmed = LegendaryPostProcessor.applyWarmLutBitmapBin(
                app, work, warmBin, denoise, ev, warmth)

            val bos = ByteArrayOutputStream(jpeg.size)
            val outBmp = warmed ?: work
            outBmp.compress(Bitmap.CompressFormat.JPEG, 95, bos)
            if (outBmp !== work) work.recycle()
            outBmp.recycle()
            val out = MadridLegendary.copyExif(jpeg, bos.toByteArray())

            DiagLog.w(TAG, "降级链完成 ${out.size}B, ${(System.currentTimeMillis() - t0)}ms")
            out
        } catch (t: Throwable) {
            DiagLog.e(TAG, "降级链失败，回落纯 LUT", t)
            null
        }
    }

    /** styled Bitmap 缓存：命中直接返回（不 recycle）；未命中同步计算后入槽。 */
    private fun styledCached(app: Context, jpeg: ByteArray, hash: Long): Bitmap? {
        if (cacheHash == hash) {
            cacheStyled?.let {
                DiagLog.w(TAG, "styled 缓存命中 (${it.width}x${it.height})")
                return it
            }
        }
        synchronized(this) {
            if (cacheHash == hash) {
                cacheStyled?.let {
                    DiagLog.w(TAG, "styled 缓存命中 (${it.width}x${it.height})")
                    return it
                }
            }
            val s = styledInternal(app, jpeg) ?: return null
            cacheHash = hash
            cacheStyled = s
            return s
        }
    }

    /** 网络推理 + 高频搬运，产出未加暖调的 styled Bitmap（mutable，供暖调就地写回）。 */
    private fun styledInternal(app: Context, jpeg: ByteArray): Bitmap? {
        val t0 = System.currentTimeMillis()
        val sess = styletransSession(app) ?: return null
        val e = env!!

        val src = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: return null
        val portrait = src.height > src.width
        val landscape = if (portrait) rotate(src, -90f) else src
        if (landscape !== src) src.recycle()

        // fit 4096x3072（只缩不放大）
        val fitted = fit(landscape)
        if (fitted !== landscape) landscape.recycle()
        val fw = fitted.width
        val fh = fitted.height

        // 工作画布 = 包含 fitted 的最小 4:3 尺寸（12.5MP 4:3 输入 = 4096x3072，逐像素同旧版）
        val canvas = canvasOf(fw, fh)
        val cw = canvas[0]
        val ch = canvas[1]

        // 中心 pad 镜像到画布（REFLECT_101 语义 = Android TileMode.MIRROR）
        val big = padMirror(fitted, cw, ch)
        if (fitted !== big) fitted.recycle()

        // 网络输入：small 1024x768 → pad48 → 1120x864
        val small = Bitmap.createScaledBitmap(big, NW, NH, true)
        val rgbp = padMirror(small, NW + PAD * 2, NH + PAD * 2)
        small.recycle()

        val inW = NW + PAD * 2   // 1120
        val inH = NH + PAD * 2   // 864
        val inPx = IntArray(inW * inH)
        rgbp.getPixels(inPx, 0, inW, 0, 0, inW, inH)
        rgbp.recycle()

        val plane = inW * inH
        val rgbFb = ByteBuffer.allocateDirect(4 * plane * 3)
            .order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer()
        val rowBuf = FloatArray(plane)
        for (c in 0 until 3) {
            var p = 0
            for (y in 0 until inH) {
                val row = y * inW
                for (x in 0 until inW) {
                    val px = inPx[row + x]
                    rowBuf[p++] = (((px shr (16 - c * 8)) and 0xFF) - 128) / 127.5f
                }
            }
            rgbFb.put(rowBuf)
        }
        rgbFb.rewind()
        val maskFb = ByteBuffer.allocateDirect(4 * plane)
            .order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer() // mask 全 0（humanseg 不移植）

        val rgbT = OnnxTensor.createTensor(e, rgbFb, longArrayOf(1, 3, inH.toLong(), inW.toLong()))
        val maskT = OnnxTensor.createTensor(e, maskFb, longArrayOf(1, 1, inH.toLong(), inW.toLong()))
        val styledBitmap: Bitmap
        rgbT.use { tRgb ->
            maskT.use { tMask ->
                sess.run(mapOf("rgb" to tRgb, "mask" to tMask)).use { res ->
                    val out = res[0] as OnnxTensor
                    val ob = out.floatBuffer
                    // [-1,1] → u8，裁掉 pad
                    val outPx = IntArray(NW * NH)
                    for (y in 0 until NH) {
                        var o = y * NW
                        var iR = (PAD + y) * inW + PAD           // 平面 0 R 的行起点
                        var iG = plane + (PAD + y) * inW + PAD   // 平面 1 G
                        var iB = plane * 2 + (PAD + y) * inW + PAD
                        for (x in 0 until NW) {
                            val r = (ob.get(iR + x) * 127.5f + 128f).toInt().coerceIn(0, 255)
                            val g = (ob.get(iG + x) * 127.5f + 128f).toInt().coerceIn(0, 255)
                            val b = (ob.get(iB + x) * 127.5f + 128f).toInt().coerceIn(0, 255)
                            outPx[o + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                        }
                    }
                    styledBitmap = Bitmap.createBitmap(NW, NH, Bitmap.Config.ARGB_8888)
                    styledBitmap.setPixels(outPx, 0, NW, 0, 0, NW, NH)
                }
            }
        }

        // ---- ColorFix 近似（质量修正版）----
        // 细节场 = orig − blur(orig)，必须全分辨率（半分辨率会丢最细纹理带）；
        // blur 场平滑 → 半分辨率计算 + 双线性放大近似无损，盒滤波计算量降 4×。
        val styledUp = Bitmap.createScaledBitmap(styledBitmap, cw, ch, true)
        styledBitmap.recycle()

        // 1) 半分辨率低通场：blurHalf = blur(big/2, σ/2)
        val hw = Math.max(1, cw / 2)
        val hh = Math.max(1, ch / 2)
        val bigHalf = Bitmap.createScaledBitmap(big, hw, hh, true)
        val halfPx = IntArray(hw * hh)
        bigHalf.getPixels(halfPx, 0, hw, 0, 0, hw, hh)
        bigHalf.recycle()
        val blurHalf = gaussianBlurARGB(halfPx, hw, hh, DETAIL_SIGMA / 2f)
        halfPx.fill(0)
        val blurHalfBmp = Bitmap.createBitmap(hw, hh, Bitmap.Config.ARGB_8888)
        blurHalfBmp.setPixels(blurHalf, 0, hw, 0, 0, hw, hh)
        blurHalf.fill(0)

        // 2) 模糊场放大回画布（平滑场，近似无损）
        val blurUp = Bitmap.createScaledBitmap(blurHalfBmp, cw, ch, true)
        blurHalfBmp.recycle()

        // 3) 全分辨率细节合成，只取裁剪窗口：styled + (orig − blur)
        val left = (cw - fw) / 2
        val top = (ch - fh) / 2
        val n = fw * fh
        val bigPx = IntArray(n)
        big.getPixels(bigPx, 0, fw, left, top, fw, fh)
        big.recycle()
        val blurPx = IntArray(n)
        blurUp.getPixels(blurPx, 0, fw, left, top, fw, fh)
        blurUp.recycle()
        val styPx = IntArray(n)
        styledUp.getPixels(styPx, 0, fw, left, top, fw, fh)
        styledUp.recycle()

        val outPx = IntArray(n)
        for (i in 0 until n) {
            val s = styPx[i]
            val b0 = bigPx[i]
            val bl = blurPx[i]
            val bch = (s and 0xFF) + (b0 and 0xFF) - (bl and 0xFF)
            val g = ((s shr 8) and 0xFF) + ((b0 shr 8) and 0xFF) - ((bl shr 8) and 0xFF)
            val r = ((s shr 16) and 0xFF) + ((b0 shr 16) and 0xFF) - ((bl shr 16) and 0xFF)
            outPx[i] = (0xFF shl 24) or
                    (r.coerceIn(0, 255) shl 16) or
                    (g.coerceIn(0, 255) shl 8) or
                    bch.coerceIn(0, 255)
        }
        bigPx.fill(0); blurPx.fill(0); styPx.fill(0)

        var crop = Bitmap.createBitmap(fw, fh, Bitmap.Config.ARGB_8888)
        crop.setPixels(outPx, 0, fw, 0, 0, fw, fh)
        outPx.fill(0)
        if (fw != landscape.width || fh != landscape.height) {
            val back = Bitmap.createScaledBitmap(crop, landscape.width, landscape.height, true)
            crop.recycle()
            crop = back
        }
        landscape.recycle()

        // 转回竖图（确保 mutable，供暖调 LUT 就地写回）
        val finalBmp = if (portrait) rotateMutable(crop, 90f) else crop
        if (finalBmp !== crop) crop.recycle()

        DiagLog.w(TAG, "styled 就绪 ${finalBmp.width}x${finalBmp.height}, ${(System.currentTimeMillis() - t0)}ms")
        return finalBmp
    }

    // ---- ORT session ----

    /**
     * StyleTrans 会话（降级链与 M9Perfect 完美档共用同一份，避免同模型两份权重内存）。
     * 降级链内部调用点同为本方法，行为零变化。
     */
    internal fun styletransSession(app: Context): OrtSession? {
        session?.let { return it }
        synchronized(this) {
            session?.let { return it }
            val e = OrtEnvironment.getEnvironment()
            val bytes = app.assets.open(MODEL_ASSET).use { it.readBytes() }
            val opt = OrtSession.SessionOptions()
            opt.setIntraOpNumThreads(OrtThreads.pick())
            val s = e.createSession(bytes, opt)
            env = e
            session = s
            DiagLog.w(TAG, "ORT session 就绪 (${bytes.size / 1048576}MB, 线程 ${OrtThreads.pick()})")
            return s
        }
    }

    fun release() {
        synchronized(this) {
            try { session?.close() } catch (_: Throwable) {}
            session = null
        }
    }

    // ---- 辅助 ----

    private fun crc32(b: ByteArray): Long {
        val c = CRC32()
        c.update(b)
        return c.value
    }

    /** 包含 fw×fh 的最小 4:3 画布（≤4096x3072）。宽高比一致时 = 原尺寸，路径与旧版逐像素一致。 */
    internal fun canvasOf(fw: Int, fh: Int): IntArray {
        return if (fw * 3L >= fh * 4L) {
            // 偏宽：宽即画布宽，高向上补到 3:4
            val w = fw.coerceAtMost(W4K)
            val h = Math.max(fh, Math.round(w * 3f / 4f)).coerceAtMost(H4K)
            intArrayOf(w, h)
        } else {
            // 偏高：高即画布高，宽向上补到 4:3
            val h = fh.coerceAtMost(H4K)
            val w = Math.max(fw, Math.round(h * 4f / 3f)).coerceAtMost(W4K)
            intArrayOf(w, h)
        }
    }

    // 以下几何工具与 M9Perfect（完美档）共用：internal 可见性，逻辑与降级链逐行一致。
    internal fun rotate(b: Bitmap, deg: Float): Bitmap {
        val m = Matrix()
        m.setRotate(deg)
        return Bitmap.createBitmap(b, 0, 0, b.width, b.height, m, true)
    }

    /** 旋转并保证结果 mutable（Bitmap.createBitmap matrix 版返回 immutable）。 */
    internal fun rotateMutable(b: Bitmap, deg: Float): Bitmap {
        val rotated = rotate(b, deg)
        if (rotated.isMutable) return rotated
        val out = Bitmap.createBitmap(rotated.width, rotated.height, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(rotated, 0f, 0f, null)
        rotated.recycle()
        return out
    }

    internal fun fit(b: Bitmap): Bitmap {
        val w = b.width
        val h = b.height
        if (w <= W4K && h <= H4K) return b
        val s = minOf(W4K.toFloat() / w, H4K.toFloat() / h)
        return Bitmap.createScaledBitmap(b, (w * s).toInt(), (h * s).toInt(), true)
    }

    /** 中心 pad 到目标尺寸，边缘镜像填充（TileMode.MIRROR ≈ cv2 REFLECT_101）。 */
    internal fun padMirror(b: Bitmap, w: Int, h: Int): Bitmap {
        if (b.width == w && b.height == h) return b
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val p = Paint()
        val sh = BitmapShader(b, Shader.TileMode.MIRROR, Shader.TileMode.MIRROR)
        // 平铺原点平移，使一份完整拷贝居中（否则贴左上角，裁回错位）
        val m = Matrix()
        m.setTranslate(((w - b.width) / 2).toFloat(), ((h - b.height) / 2).toFloat())
        sh.setLocalMatrix(m)
        p.shader = sh
        c.drawPaint(p)
        return out
    }

    // ---- 高斯模糊（3 段盒滤波近似，可分离，ARGB 整数域）----

    private fun gaussianBlurARGB(src: IntArray, w: Int, h: Int, sigma: Float): IntArray {
        val boxes = boxesForGauss(sigma, 3)
        var a = src.copyOf()
        var b = IntArray(src.size)
        for (r in boxes) {
            if (r < 1) continue
            boxBlurH(a, b, w, h, r)
            boxBlurV(b, a, w, h, r)
        }
        b.fill(0)
        return a
    }

    private fun boxesForGauss(sigma: Float, n: Int): IntArray {
        val wIdeal = Math.sqrt((12.0 * sigma * sigma / n) + 1.0)
        var wl = Math.floor(wIdeal).toInt()
        if (wl % 2 == 0) wl--
        val wu = wl + 2
        val mIdeal = (12.0 * sigma * sigma - n * wl * wl - 4.0 * n * wl - 3.0 * n) / (-4.0 * wl - 4.0)
        val m = Math.round(mIdeal).toInt()
        val sizes = IntArray(n)
        for (i in 0 until n) sizes[i] = if (i < m) wl else wu
        return sizes
    }

    private fun boxBlurH(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int) {
        val win = r * 2 + 1
        for (y in 0 until h) {
            var row = y * w
            var sr = 0; var sg = 0; var sb = 0
            for (i in -r..r) {
                val x = i.coerceIn(0, w - 1)
                val px = src[row + x]
                sr += (px shr 16) and 0xFF; sg += (px shr 8) and 0xFF; sb += px and 0xFF
            }
            for (x in 0 until w) {
                val c = ((0xFF shl 24) or ((sr / win) shl 16) or ((sg / win) shl 8) or (sb / win))
                dst[row + x] = c
                val addX = (x + r + 1).coerceAtMost(w - 1)
                val subX = (x - r).coerceAtLeast(0)
                if (addX != subX) {
                    val pa = src[row + addX]
                    val ps = src[row + subX]
                    sr += ((pa shr 16) and 0xFF) - ((ps shr 16) and 0xFF)
                    sg += ((pa shr 8) and 0xFF) - ((ps shr 8) and 0xFF)
                    sb += (pa and 0xFF) - (ps and 0xFF)
                }
            }
        }
    }

    private fun boxBlurV(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int) {
        val win = r * 2 + 1
        for (x in 0 until w) {
            var sr = 0; var sg = 0; var sb = 0
            for (i in -r..r) {
                val y = i.coerceIn(0, h - 1)
                val px = src[y * w + x]
                sr += (px shr 16) and 0xFF; sg += (px shr 8) and 0xFF; sb += px and 0xFF
            }
            for (y in 0 until h) {
                dst[y * w + x] = (0xFF shl 24) or ((sr / win) shl 16) or ((sg / win) shl 8) or (sb / win)
                val addY = (y + r + 1).coerceAtMost(h - 1)
                val subY = (y - r).coerceAtLeast(0)
                if (addY != subY) {
                    val pa = src[addY * w + x]
                    val ps = src[subY * w + x]
                    sr += ((pa shr 16) and 0xFF) - ((ps shr 16) and 0xFF)
                    sg += ((pa shr 8) and 0xFF) - ((ps shr 8) and 0xFF)
                    sb += (pa and 0xFF) - (ps and 0xFF)
                }
            }
        }
    }
}
