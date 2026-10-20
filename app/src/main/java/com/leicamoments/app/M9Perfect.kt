package com.leicamoments.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.codex.leicaapp.LegendaryPostProcessor
import com.codex.leicaapp.MadridLegendary
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 「完美档」两条路径：
 *
 * 1) [runFull]（RAW 完整管线，二期）——OffLineM9 全链：
 *    DNG → 前段（AE 重测光 → TMC/CC/tdl13/gamma152/cv122）→ StyleTrans high → 真 ColorFix
 *    → LeicaFilter（lux×CCT 四角 LUT + CvStyle 暗角）→ JPEG95 + EXIF。
 * 2) [run]（JPEG 后段，一期）——StyleTrans high + 真 ColorFix + 暖调 LUT。
 *
 * 几何协议与网络前后处理逐行对齐 `m9/styletrans.py`：
 *   竖图转横 → fit 4096x3072 → 中心 pad(镜像) 到最小 4:3 画布 → 缩 1024x768 → pad48
 *   → net(rgb 1x3x864x1120 + mask 全 0) → 裁 1024x768 → 放大回画布
 *   → ColorFix 544 分块(step 512, 中心 512 保留, margin 16; 输入 6ch = [styled(3), orig(3)])
 *   → 裁回 → 缩回原尺寸 → 转回竖图。
 */
object M9Perfect {

    private const val TAG = "M9PERFECT"
    private const val MODEL_COLORFIX = "m9fb_colorfix.onnx"

    private const val W4K = 4096
    private const val H4K = 3072
    private const val NW = 1024
    private const val NH = 768
    private const val PAD = 48

    // ColorFix 分块协议（与 m9/styletrans.py 完全一致）
    private const val TILE = 544
    private const val STEP = 512
    private const val MARGIN = 16
    private const val BATCH = 2

    @Volatile private var cfSession: OrtSession? = null

    // ---------------------------------------------------------------- 二期：RAW 完整管线

    /**
     * 完整 OffLineM9 管线（RAW 输入）。
     *
     * @param dng       原始 DNG 字节（线性域取数与 AE 重测光用）
     * @param exifFrom  输出 EXIF 模板（通常是导入时归一后的 JPEG 字节）
     * @return 处理后 JPEG；任何失败返回 null（调用方回落到一期后段或提示失败）
     */
    fun runFull(app: Context, dng: ByteArray, exifFrom: ByteArray,
                onProgress: ((Int) -> Unit)? = null): ByteArray? {
        return try {
            val t0 = System.currentTimeMillis()
            onProgress?.invoke(3)

            val fe = M9Frontend.b2y(app, dng) ?: run {
                DiagLog.w(TAG, "前段不可用，回落一期后段")
                return null
            }
            onProgress?.invoke(38)

            val src = Bitmap.createBitmap(fe.argb, fe.w, fe.h, Bitmap.Config.ARGB_8888)
            val styled = styledFixed(app, src, 38, 60, 92, onProgress) ?: return null

            // LeicaFilter（StyleTrans 已跑过 → snapshot 参数）
            val sw = styled.width
            val sh = styled.height
            val px = IntArray(sw * sh)
            styled.getPixels(px, 0, sw, 0, 0, sw, sh)
            styled.recycle()
            onProgress?.invoke(93)

            val filtered = M9LeicaFilter.applyArgb(app, px, sw, sh,
                fe.luxIndex, fe.cct, fe.zoom, "common", true)
            px.fill(0)
            val outBmp = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888)
            outBmp.setPixels(filtered, 0, sw, 0, 0, sw, sh)

            val bos = ByteArrayOutputStream(exifFrom.size)
            outBmp.compress(Bitmap.CompressFormat.JPEG, 95, bos)
            outBmp.recycle()
            val out = MadridLegendary.copyExif(exifFrom, bos.toByteArray())
            onProgress?.invoke(100)
            DiagLog.w(TAG, "完整管线完成 ${fe.w}x${fe.h} ${out.size}B, " +
                    "${System.currentTimeMillis() - t0}ms")
            out
        } catch (t: Throwable) {
            DiagLog.e(TAG, "完整管线失败", t)
            null
        }
    }

    // ---------------------------------------------------------------- 一期：JPEG 后段

    /**
     * 后段管线（JPEG 输入）：StyleTrans high → 真 ColorFix → 暖调 LUT(LeicaFilter snapshot bin)。
     *
     * @param warmBin 暖调 LUT bin 资产名（M9/M9P 用 m9 snapshot，M10R 用 m10r snapshot）
     * @param onProgress 阶段式进度 0..100
     */
    fun run(
        app: Context,
        jpeg: ByteArray,
        denoise: Float = 0.6f,
        ev: Float = -0.95f,
        warmth: Float = 0.3f,
        warmBin: String = "leica_filter_param_m9_snapshot.bin",
        onProgress: ((Int) -> Unit)? = null,
    ): ByteArray? {
        return try {
            val t0 = System.currentTimeMillis()
            onProgress?.invoke(3)

            val src = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: return null
            val styled = styledFixed(app, src, 10, 50, 94, onProgress) ?: return null

            // ---- 暖调 LUT（Bitmap 直入直出，无 JPEG 中间代）----
            val warmed = LegendaryPostProcessor.applyWarmLutBitmapBin(
                app, styled, warmBin, denoise, ev, warmth)
            val outBmp = warmed ?: styled
            val bos = ByteArrayOutputStream(jpeg.size)
            outBmp.compress(Bitmap.CompressFormat.JPEG, 95, bos)
            if (outBmp !== styled) styled.recycle()
            outBmp.recycle()
            val out = MadridLegendary.copyExif(jpeg, bos.toByteArray())
            onProgress?.invoke(100)

            DiagLog.w(TAG, "完美档完成 ${out.size}B, ${System.currentTimeMillis() - t0}ms")
            out
        } catch (t: Throwable) {
            DiagLog.e(TAG, "完美档失败", t)
            null
        }
    }

    // ---------------------------------------------------------------- 共用：StyleTrans + 真 ColorFix

    /**
     * styled（StyleTrans 1024×768）+ 真 ColorFix 全分辨率。输入任意尺寸 Bitmap（原样，内部摆正），
     * 返回**已摆正、未暖调**的同尺寸 ARGB Bitmap。失败返回 null。
     *
     * @param pNet 网络前进度、pDone 网络后（ColorFix 起点）、pEnd 完成前
     */
    private fun styledFixed(app: Context, src: Bitmap, pNet: Int, pDone: Int, pEnd: Int,
                            onProgress: ((Int) -> Unit)?): Bitmap? {
        val st = M9OrtFallback.styletransSession(app) ?: return null
        val cf = colorfixSession(app) ?: return null
        val env = OrtEnvironment.getEnvironment()

        val portrait = src.height > src.width
        val landscape = if (portrait) M9OrtFallback.rotate(src, -90f) else src
        if (landscape !== src) src.recycle()

        // fit 4096x3072（只缩不放大）→ 最小 4:3 画布 → 中心 pad 镜像
        val fitted = M9OrtFallback.fit(landscape)
        if (fitted !== landscape) landscape.recycle()
        val fw = fitted.width
        val fh = fitted.height
        val canvas = M9OrtFallback.canvasOf(fw, fh)
        val cw = canvas[0]
        val ch = canvas[1]
        val big = M9OrtFallback.padMirror(fitted, cw, ch)
        if (big !== fitted) fitted.recycle()
        val left = (cw - fw) / 2
        val top = (ch - fh) / 2

        // ---- StyleTrans 网络（1024x768 + pad48）----
        val small = Bitmap.createScaledBitmap(big, NW, NH, true)
        val rgbp = M9OrtFallback.padMirror(small, NW + PAD * 2, NH + PAD * 2)
        small.recycle()
        val inW = NW + PAD * 2
        val inH = NH + PAD * 2
        val inPx = IntArray(inW * inH)
        rgbp.getPixels(inPx, 0, inW, 0, 0, inW, inH)
        rgbp.recycle()
        onProgress?.invoke(pNet)

        val plane = inW * inH
        val rgbFb = ByteBuffer.allocateDirect(4 * plane * 3)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()
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
        inPx.fill(0)
        val maskFb = ByteBuffer.allocateDirect(4 * plane)
            .order(ByteOrder.nativeOrder()).asFloatBuffer() // mask 全 0（humanseg 不移植）

        val styledBmp: Bitmap
        OnnxTensor.createTensor(env, rgbFb, longArrayOf(1, 3, inH.toLong(), inW.toLong())).use { tRgb ->
            OnnxTensor.createTensor(env, maskFb, longArrayOf(1, 1, inH.toLong(), inW.toLong())).use { tMask ->
                st.run(mapOf("rgb" to tRgb, "mask" to tMask)).use { res ->
                    val out = res[0] as OnnxTensor
                    val ob = out.floatBuffer
                    val outPx = IntArray(NW * NH)
                    for (y in 0 until NH) {
                        var o = y * NW
                        val iR = (PAD + y) * inW + PAD
                        val iG = plane + (PAD + y) * inW + PAD
                        val iB = plane * 2 + (PAD + y) * inW + PAD
                        for (x in 0 until NW) {
                            val r = (ob.get(iR + x) * 127.5f + 128f).toInt().coerceIn(0, 255)
                            val g = (ob.get(iG + x) * 127.5f + 128f).toInt().coerceIn(0, 255)
                            val b = (ob.get(iB + x) * 127.5f + 128f).toInt().coerceIn(0, 255)
                            outPx[o + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                        }
                    }
                    styledBmp = Bitmap.createBitmap(NW, NH, Bitmap.Config.ARGB_8888)
                    styledBmp.setPixels(outPx, 0, NW, 0, 0, NW, NH)
                }
            }
        }
        onProgress?.invoke(pDone)

        // ---- 放大回画布，取像素数组（Bitmap 与数组不同时驻留，降峰值）----
        val styledUp = Bitmap.createScaledBitmap(styledBmp, cw, ch, true)
        styledBmp.recycle()
        val styledPx = IntArray(cw * ch)
        styledUp.getPixels(styledPx, 0, cw, 0, 0, cw, ch)
        styledUp.recycle()
        val bigPx = IntArray(cw * ch)
        big.getPixels(bigPx, 0, cw, 0, 0, cw, ch)
        big.recycle()

        // ---- 真 ColorFix（544 分块）----
        val fixed = colorfix(env, cf, bigPx, cw, ch, styledPx, pDone, pEnd, onProgress)
        bigPx.fill(0)
        styledPx.fill(0)

        // ---- 裁回 fitted 窗口 → 缩回原尺寸 → 转回竖图 ----
        val outPx = IntArray(fw * fh)
        for (y in 0 until fh) {
            System.arraycopy(fixed, (top + y) * cw + left, outPx, y * fw, fw)
        }
        fixed.fill(0)
        var crop = Bitmap.createBitmap(fw, fh, Bitmap.Config.ARGB_8888)
        crop.setPixels(outPx, 0, fw, 0, 0, fw, fh)
        outPx.fill(0)
        if (fw != landscape.width || fh != landscape.height) {
            val back = Bitmap.createScaledBitmap(crop, landscape.width, landscape.height, true)
            crop.recycle()
            crop = back
        }
        landscape.recycle()

        val finalBmp = if (portrait) M9OrtFallback.rotateMutable(crop, 90f) else crop
        if (finalBmp !== crop) crop.recycle()
        return finalBmp
    }

    /**
     * ColorFix：544 分块 U-Net，输入 6 通道 [styled(3), orig(3)]，值域 [-1,1]，输出同尺寸 [-1,1]；
     * step 512 / 中心 512 保留 / margin 16。与 m9/styletrans.py 一致。
     */
    private fun colorfix(
        env: OrtEnvironment,
        sess: OrtSession,
        origPx: IntArray,
        w: Int,
        h: Int,
        styledPx: IntArray,
        pFrom: Int,
        pTo: Int,
        onProgress: ((Int) -> Unit)?,
    ): IntArray {
        val out = IntArray(w * h)

        var nx = 0
        var ny = 0
        var y = 0
        while (y < h) { ny++; y += STEP }
        var x = 0
        while (x < w) { nx++; x += STEP }
        val count = nx * ny
        val ty = IntArray(count); val tx = IntArray(count)
        val tys = IntArray(count); val txs = IntArray(count)
        val tth = IntArray(count); val ttw = IntArray(count)
        // 中心保留窗口（对应 styletrans.py 的 places）：min(STEP, H-y) / min(STEP, W-x)，供读回阶段使用
        val tkh = IntArray(count); val tkw = IntArray(count)
        var k = 0
        y = 0
        while (y < h) {
            x = 0
            while (x < w) {
                ty[k] = y; tx[k] = x
                tys[k] = Math.max(y - MARGIN, 0)
                txs[k] = Math.max(x - MARGIN, 0)
                tth[k] = Math.min(y + STEP + MARGIN, h) - tys[k]
                ttw[k] = Math.min(x + STEP + MARGIN, w) - txs[k]
                tkh[k] = Math.min(STEP, h - y)
                tkw[k] = Math.min(STEP, w - x)
                k++
                x += STEP
            }
            y += STEP
        }

        val tilePlane = TILE * TILE
        val buf = ByteBuffer.allocateDirect(4 * BATCH * 6 * tilePlane)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()

        var start = 0
        var done = 0
        val span = Math.max(1, pTo - pFrom)
        while (start < count) {
            val n = Math.min(BATCH, count - start)
            for (i in 0 until n) {
                val j = start + i
                val oy = MARGIN - (ty[j] - tys[j])
                val ox = MARGIN - (tx[j] - txs[j])
                val th = tth[j]
                val tw = ttw[j]
                val sy = tys[j]
                val sx = txs[j]
                val base = i * 6 * tilePlane
                for (row in 0 until th) {
                    val srow = (sy + row) * w + sx
                    val drow = base + (oy + row) * TILE + ox
                    for (col in 0 until tw) {
                        val sp = styledPx[srow + col]
                        val op = origPx[srow + col]
                        val d = drow + col
                        buf.put(d, (((sp shr 16) and 0xFF) - 128) / 127.5f)
                        buf.put(d + tilePlane, (((sp shr 8) and 0xFF) - 128) / 127.5f)
                        buf.put(d + 2 * tilePlane, ((sp and 0xFF) - 128) / 127.5f)
                        buf.put(d + 3 * tilePlane, (((op shr 16) and 0xFF) - 128) / 127.5f)
                        buf.put(d + 4 * tilePlane, (((op shr 8) and 0xFF) - 128) / 127.5f)
                        buf.put(d + 5 * tilePlane, ((op and 0xFF) - 128) / 127.5f)
                    }
                }
            }

            OnnxTensor.createTensor(env, buf,
                longArrayOf(n.toLong(), 6, TILE.toLong(), TILE.toLong())).use { tIn ->
                sess.run(mapOf("x" to tIn)).use { res ->
                    val ob = (res[0] as OnnxTensor).floatBuffer
                    for (i in 0 until n) {
                        val j = start + i
                        // 读回尺寸必须用中心保留窗口：tth/ttw 含 16px margin 外扩，
                        // 内部片（tys/txs 已内缩 16）会多读 16 行/列，越过 544x544 平面导致越界
                        val th = tkh[j]
                        val tw = tkw[j]
                        val base = i * 3 * tilePlane
                        for (row in 0 until th) {
                            val drow = (ty[j] + row) * w + tx[j]
                            val srow = base + (MARGIN + row) * TILE + MARGIN
                            for (col in 0 until tw) {
                                val si = srow + col
                                val r = (ob.get(si) * 127.5f + 128f).toInt().coerceIn(0, 255)
                                val g = (ob.get(si + tilePlane) * 127.5f + 128f).toInt().coerceIn(0, 255)
                                val b = (ob.get(si + 2 * tilePlane) * 127.5f + 128f).toInt().coerceIn(0, 255)
                                out[drow + col] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                            }
                        }
                    }
                }
            }
            start += n
            done += n
            onProgress?.invoke(pFrom + done * span / count)
        }
        return out
    }

    private fun colorfixSession(app: Context): OrtSession? {
        cfSession?.let { return it }
        synchronized(this) {
            cfSession?.let { return it }
            val e = OrtEnvironment.getEnvironment()
            val bytes = app.assets.open(MODEL_COLORFIX).use { it.readBytes() }
            val opt = OrtSession.SessionOptions()
            opt.setIntraOpNumThreads(OrtThreads.pick())
            val s = e.createSession(bytes, opt)
            cfSession = s
            DiagLog.w(TAG, "ColorFix session 就绪 (${bytes.size / 1048576}MB, 线程 ${OrtThreads.pick()})")
            return s
        }
    }

    /** 内存紧张时释放 ColorFix 会话（下次使用自动重建）。 */
    fun release() {
        synchronized(this) {
            try { cfSession?.close() } catch (_: Throwable) {}
            cfSession = null
        }
    }
}
