package com.leicamoments.app

import android.graphics.Bitmap
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * 复合色调引擎：亮度/曝光/高光/阴影/黑色阶五参数在参数域合成一条 4096 级亮度曲线，
 * 单次遍历输出（替代旧五算子串行位图链）。
 *
 * 对旧链的修复：
 *  - 参数近似正交：亮度=中间调 gamma（保端点）；曝光=线性域 2^EV；高光=软肩混合；
 *    阴影=软趾加性 lift；黑色阶=(1-v)^2 权重的脚点位移（中间调基本不动）。
 *  - 增益严格非负、合成后强制单调——旧版负档怪色从数学上不可能再发生。
 *  - 色相保护：曲线只作用于线性亮度 Y，RGB 按 Y'/Y 等比缩放；通道越界用保亮度
 *    去饱和软限，不再逐通道硬裁（高光不偏青/白）。
 *  - 只量化一次：8bit 进浮点，输出端 TPDF 抖动后取整（提阴影不再出色带）。
 *  - 顺序与旧链一致：亮度→曝光→高光→阴影→黑色阶。
 */
object ToneCurve {

    const val MIN_LEVEL = -10
    const val MAX_LEVEL = 10

    /** 每档行程（与旧算子同量级，装机后口头微调的唯一入口） */
    private const val BR_STEP = 0.035f   // 亮度：中间调 gamma 指数 2^(-br*step)
    private const val EV_STEP = 0.15f    // 曝光：±10 档 = ±1.5EV
    private const val HL_STEP = 0.04f    // 高光：最大混合强度 ±0.4
    private const val SH_STEP = 0.04f    // 阴影：最大混合强度 ±0.4
    private const val BL_STEP = 0.025f   // 黑阶：脚点位移 ±0.25
    private const val CON_STEP = 0.03f   // 对比度：向 S 曲线混合 ±0.3
    private const val SAT_STEP = 0.045f  // 饱和度：向亮度插值增益 ±0.45

    private const val LUT_N = 4096

    /** 七档参数（−10..10）：五档影调 + 饱和度 + 对比度 */
    data class Params(
        val brightness: Int = 0,
        val exposure: Int = 0,
        val highlights: Int = 0,
        val shadows: Int = 0,
        val black: Int = 0,
        val saturation: Int = 0,
        val contrast: Int = 0
    )

    /** 旧五参签名保留（既有调用零改动），饱和/对比缺省 0 */
    fun of(bright: Int, exposure: Int, highlights: Int, shadows: Int, black: Int): Params =
        of(bright, exposure, highlights, shadows, black, 0, 0)

    fun of(
        bright: Int, exposure: Int, highlights: Int, shadows: Int, black: Int,
        saturation: Int, contrast: Int
    ): Params = Params(
        bright.coerceIn(MIN_LEVEL, MAX_LEVEL),
        exposure.coerceIn(MIN_LEVEL, MAX_LEVEL),
        highlights.coerceIn(MIN_LEVEL, MAX_LEVEL),
        shadows.coerceIn(MIN_LEVEL, MAX_LEVEL),
        black.coerceIn(MIN_LEVEL, MAX_LEVEL),
        saturation.coerceIn(MIN_LEVEL, MAX_LEVEL),
        contrast.coerceIn(MIN_LEVEL, MAX_LEVEL)
    )

    // ---- 传递函数表（构建期生成，逐像素只查表） ----
    private val S2L = FloatArray(256)    // 8bit sRGB → 线性
    private val L2S = FloatArray(1025)   // 线性（0..1，1024 格）→ sRGB

    init {
        for (v in 0..255) {
            val d = v / 255.0
            S2L[v] = (if (d <= 0.04045) d / 12.92 else Math.pow((d + 0.055) / 1.055, 2.4)).toFloat()
        }
        for (i in 0..1024) {
            val d = i / 1024.0
            L2S[i] = (if (d <= 0.0031308) d * 12.92 else 1.055 * Math.pow(d, 1.0 / 2.4) - 0.055).toFloat()
        }
    }

    private fun smoothstep(a: Float, b: Float, x: Float): Float {
        val t = ((x - a) / (b - a)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private fun sToL(v: Float): Float =
        if (v <= 0.04045f) v / 12.92f else ((v + 0.055f) / 1.055f).pow(2.4f)

    private fun lToS(v: Float): Float =
        if (v <= 0.0031308f) v * 12.92f else 1.055f * v.pow(1f / 2.4f) - 0.055f

    /**
     * 参数域合成线性亮度→线性亮度曲线（单槽缓存：同参数重复调用直接复用，
     * 预览原图/结果图两次应用与连档重跑免重建；构建 ~1ms 级，synchronized 足够）。
     * 工作域：sRGB 编码值 v∈[0,1]（感知域，窗口阈值视觉稳定），仅曝光一步进线性域。
     * 合成后强制单调（防个别组合的极小非单调导致色带/反转）。
     */
    private var cacheKey: Params? = null
    private var cacheLut: FloatArray? = null

    fun buildLumaLut(p: Params): FloatArray {
        synchronized(this) {
            if (cacheKey == p) cacheLut?.let { return it }
        }
        val lut = FloatArray(LUT_N)
        val br = p.brightness * BR_STEP
        val ev = 2f.pow(p.exposure * EV_STEP)
        val hl = p.highlights * HL_STEP
        val sh = p.shadows * SH_STEP
        val bl = p.black * BL_STEP
        val con = p.contrast * CON_STEP
        for (i in 0 until LUT_N) {
            var v = lToS(i / (LUT_N - 1).toFloat())   // 线性亮度 → 感知域
            // 1) 亮度：中间调 gamma，保 0/1 端点（替代旧版全图均匀乘法）
            if (br != 0f) v = v.pow(2f.pow(-br))
            // 2) 曝光：线性域乘增益（可暂越 1，末端软限回收，不硬裁）
            if (ev != 1f) v = lToS(sToL(v) * ev)
            // 3) 高光：软肩。正档压肩回细节（目标 v^p），负档提肩（目标 1-(1-v)^q）
            if (hl != 0f) {
                val vc = v.coerceIn(0f, 1f)
                val w = smoothstep(0.40f, 0.90f, vc)
                val t = (Math.abs(hl) * w).coerceAtMost(0.85f)
                if (t > 0f) {
                    val a = Math.abs(hl)
                    val tgt = if (hl > 0f) vc.pow(1f + 2f * a) else 1f - (1f - vc).pow(1f + 2f * a)
                    v += t * (tgt - v)
                }
            }
            // 4) 阴影：软趾。正档加性提亮（目标 sqrt(v)），负档压深（目标 v²）
            if (sh != 0f) {
                val vc = v.coerceIn(0f, 1f)
                val w = 1f - smoothstep(0.10f, 0.60f, vc)
                val t = (Math.abs(sh) * w).coerceAtMost(0.85f)
                if (t > 0f) {
                    val tgt = if (sh > 0f) sqrt(vc) else vc * vc
                    v += t * (tgt - v)
                }
            }
            // 5) 黑色阶：脚点位移，(1-v)^2 权重使中间调基本不动（正档压黑，负档抬黑）
            if (bl != 0f) {
                val vc = v.coerceIn(0f, 1f)
                v -= bl * (1f - vc) * (1f - vc)
            }
            // 6) 对比度：向 smoothstep S 曲线混合——绕 0.5 对称、严格保 0/1 端点。
            //    正档向 S 靠（加对比），负档向线性回拉（降对比）；两支均为
            //    「单调函数的正系数线性组合」（和恰为 1）→ 全程单调，末端钳不触发。
            if (con != 0f) {
                val vc = v.coerceIn(0f, 1f)
                val s = vc * vc * (3f - 2f * vc)
                v += con * (s - vc)
            }
            lut[i] = sToL(v)
        }
        for (i in 1 until LUT_N) if (lut[i] < lut[i - 1]) lut[i] = lut[i - 1]
        synchronized(this) { cacheKey = p; cacheLut = lut }
        return lut
    }

    /** 七档全 0 时无需处理 */
    fun isNeutral(p: Params): Boolean =
        p.brightness == 0 && p.exposure == 0 && p.highlights == 0 &&
            p.shadows == 0 && p.black == 0 && p.saturation == 0 && p.contrast == 0

    /**
     * 单次遍历应用：查 4096 级亮度曲线得 Y'，RGB 线性通道按 Y'/Y 等比缩放（色相不变），
     * 通道越界保亮度去饱和软限，TPDF 抖动后一次取整。
     */
    fun applyToBitmap(src: Bitmap, p: Params): Bitmap {
        if (isNeutral(p)) return src
        val lut = buildLumaLut(p)
        val sg = 1f + p.saturation * SAT_STEP
        val w = src.width
        val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        var st = 0x9E3779B9L xor System.nanoTime()   // xorshift 抖动状态
        for (i in px.indices) {
            val p8 = px[i]
            val r = S2L[(p8 shr 16) and 0xFF]
            val g = S2L[(p8 shr 8) and 0xFF]
            val b = S2L[p8 and 0xFF]
            val y = 0.2126f * r + 0.7152f * g + 0.0722f * b
            // 4096 级亮度曲线线性插值
            val fi = y * (LUT_N - 1)
            var idx = fi.toInt()
            if (idx > LUT_N - 2) idx = LUT_N - 2
            if (idx < 0) idx = 0
            val y2 = lut[idx] + (lut[idx + 1] - lut[idx]) * (fi - idx)
            var r2: Float
            var g2: Float
            var b2: Float
            if (y < 1e-5f) {
                // 近黑像素：曲线若抬黑则给等量中性灰，避免除零
                r2 = y2; g2 = y2; b2 = y2
            } else {
                val s = y2 / y
                r2 = r * s; g2 = g * s; b2 = b * s
                // 软限：通道越界时保亮度去饱和（不逐通道硬裁，高光不偏色）
                val m = maxOf(r2, g2, b2)
                if (m > 1f) {
                    val k = 1f - smoothstep(1f, 1.25f, m)
                    r2 = y2 + (r2 - y2) * k
                    g2 = y2 + (g2 - y2) * k
                    b2 = y2 + (b2 - y2) * k
                }
            }
            // 7) 饱和度：向曲线映射后的亮度 y2 插值——正档增益、负档去饱和，色相与
            //    亮度不动；越界沿用同款保亮度软限。近黑分支 r2=y2 灰，插值仍灰零影响。
            if (sg != 1f) {
                r2 = y2 + (r2 - y2) * sg
                g2 = y2 + (g2 - y2) * sg
                b2 = y2 + (b2 - y2) * sg
                val m = maxOf(r2, g2, b2)
                if (m > 1f) {
                    val k = 1f - smoothstep(1f, 1.25f, m)
                    r2 = y2 + (r2 - y2) * k
                    g2 = y2 + (g2 - y2) * k
                    b2 = y2 + (b2 - y2) * k
                }
            }
            // TPDF 抖动：±0.5 LSB 三角噪声，三通道同值（不引入彩噪）
            st = st xor (st shl 13); st = st xor (st ushr 17); st = st xor (st shl 5)
            val u1 = (st and 0xFFFFFFL).toFloat() / 16777216f
            st = st xor (st shl 13); st = st xor (st ushr 17); st = st xor (st shl 5)
            val u2 = (st and 0xFFFFFFL).toFloat() / 16777216f
            val dth = (u1 + u2 - 1f) * 0.5f
            px[i] = (p8 and 0xFF000000.toInt()) or
                (enc8(r2, dth) shl 16) or (enc8(g2, dth) shl 8) or enc8(b2, dth)
        }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(px, 0, w, 0, 0, w, h)
        return out
    }

    /** 线性→8bit：1024 格表插值，抖动加在 8bit 码值上（±0.5 LSB 三角），>1 裁到 255 */
    private fun enc8(x: Float, dither: Float): Int {
        if (x >= 1f) return 255
        if (x <= 0f) return 0
        val fi = x * 1024f
        val idx = fi.toInt()
        val f = L2S[idx] + (L2S[idx + 1] - L2S[idx]) * (fi - idx)
        val v8 = (f * 255f + 0.5f + dither).toInt()
        return if (v8 > 255) 255 else if (v8 < 0) 0 else v8
    }

    /**
     * 16-bit 线性 sRGB 数据直连应用（P2：RAW 高位深窗口）。
     *
     * 输入是 packed 线性 u16（RGB 交错，w*h*3，值为 0..65535 线性）——LibRaw 解码或
     * RawToneMap 增益后的中间数据，未经 8-bit 量化。曲线/色相保护/软限/抖动与 8-bit
     * 版完全同源，只是输入无需 S2L 线性化（本来就在线性域，14-bit RAW 数据一路保真），
     * 且只在最终编码时量化一次——提阴影不再有 8-bit 源量化色带。
     *
     * @return ARGB 像素数组（长度 w*h），alpha=0xFF
     */
    fun applyLinear16ToArgb(
        src: ShortArray, w: Int, h: Int, p: Params, out: IntArray,
        enc1024: FloatArray? = null
    ) {
        val lut = buildLumaLut(p)
        val neutral = isNeutral(p)
        val sg = 1f + p.saturation * SAT_STEP
        var st = 0x9E3779B9L xor System.nanoTime()
        var o = 0
        var i = 0
        val n = w * h
        while (o < n) {
            // u16 线性 → float 线性（1/65535 步长，高于 8-bit 一个数量级的精度）
            val r = (src[i].toInt() and 0xFFFF) * (1f / 65535f)
            val g = (src[i + 1].toInt() and 0xFFFF) * (1f / 65535f)
            val b = (src[i + 2].toInt() and 0xFFFF) * (1f / 65535f)
            i += 3
            val y = 0.2126f * r + 0.7152f * g + 0.0722f * b
            var r2: Float
            var g2: Float
            var b2: Float
            if (neutral) {
                r2 = r; g2 = g; b2 = b
            } else {
                val fi = y * (LUT_N - 1)
                var idx = fi.toInt()
                if (idx > LUT_N - 2) idx = LUT_N - 2
                if (idx < 0) idx = 0
                val y2 = lut[idx] + (lut[idx + 1] - lut[idx]) * (fi - idx)
                if (y < 1e-6f) {
                    r2 = y2; g2 = y2; b2 = y2
                } else {
                    val s = y2 / y
                    r2 = r * s; g2 = g * s; b2 = b * s
                    val m = maxOf(r2, g2, b2)
                    if (m > 1f) {
                        val k = 1f - smoothstep(1f, 1.25f, m)
                        r2 = y2 + (r2 - y2) * k
                        g2 = y2 + (g2 - y2) * k
                        b2 = y2 + (b2 - y2) * k
                    }
                }
            }
            // 7) 饱和度：向当前亮度插值——曲线路径 luma=y2，恒等路径不可能到这
            //    （饱和度≠0 时 isNeutral 必 false）；越界走保亮度软限。与 8-bit 同源。
            if (sg != 1f) {
                val yc = 0.2126f * r2 + 0.7152f * g2 + 0.0722f * b2
                r2 = yc + (r2 - yc) * sg
                g2 = yc + (g2 - yc) * sg
                b2 = yc + (b2 - yc) * sg
                val m = maxOf(r2, g2, b2)
                if (m > 1f) {
                    val k = 1f - smoothstep(1f, 1.25f, m)
                    r2 = yc + (r2 - yc) * k
                    g2 = yc + (g2 - yc) * k
                    b2 = yc + (b2 - yc) * k
                }
            }
            st = st xor (st shl 13); st = st xor (st ushr 17); st = st xor (st shl 5)
            val u1 = (st and 0xFFFFFFL).toFloat() / 16777216f
            st = st xor (st shl 13); st = st xor (st ushr 17); st = st xor (st shl 5)
            val u2 = (st and 0xFFFFFFL).toFloat() / 16777216f
            val dth = (u1 + u2 - 1f) * 0.5f
            out[o++] = (0xFF shl 24) or
                (enc8t(r2, dth, enc1024) shl 16) or (enc8t(g2, dth, enc1024) shl 8) or enc8t(b2, dth, enc1024)
        }
    }

    /** 线性→8bit，编码器可插拔（null = 内置 sRGB 表；RAW 软肩路径传 BT.709 表） */
    private fun enc8t(x: Float, dither: Float, t: FloatArray?): Int {
        if (x >= 1f) return 255
        if (x <= 0f) return 0
        val fi = x * 1024f
        val idx = fi.toInt()
        val f = if (t != null) t[idx] + (t[idx + 1] - t[idx]) * (fi - idx)
        else L2S[idx] + (L2S[idx + 1] - L2S[idx]) * (fi - idx)
        val v8 = (f * 255f + 0.5f + dither).toInt()
        return if (v8 > 255) 255 else if (v8 < 0) 0 else v8
    }
}
