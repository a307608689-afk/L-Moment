package com.leicamoments.app

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.codex.leicaapp.MadridLegendary
import java.io.ByteArrayOutputStream
import java.nio.FloatBuffer

/**
 * M13「Portra」人像胶片：SepLUT（ECCV 2022，官方 FiveK 预训练）打底 + 固定 Portra 400 曲线。
 *
 * 链路：整图缩 256 喂 ONNX（0.9ms/次，模型仅 44KB）→ 1D LUT(3×17) + 3D LUT(17³)
 *   → 全分辨率逐像素：1D 精确查表 → 17³ 三线性 → Portra 矩阵/暖偏/曲线。
 * 全链逐点算子，与 PC 端 portra_harness.py 验证口径一致（max_diff 1e-6）。
 *
 * 失败返回 null，由 LeicaEngine 降级 M9 纯 LUT 档。
 */
object PortraitFilm {

    private const val MODEL_ASSET = "seplut_fivek.onnx"
    private const val INFER_EDGE = 256
    private const val D = 17   // 模型 LUT 顶点数

    // ---- 共享算子常数（三档完全相同，刻意不进 Profile，缩小回归面）----
    private const val SKIN_SAT_LO = 0.05f      // 肤色饱和门限起点
    private const val SKIN_SAT_SPAN = 0.10f    // 肤色饱和门限跨度
    private const val SKIN_LUM_LO = 0.10f      // 肤色亮度门限起点
    private const val SKIN_LUM_SPAN = 0.15f    // 肤色亮度门限跨度
    private const val SKIN_R_DROP = 0.004f     // 肤区 R 微压（防过红）
    private const val SHADOW_SKIN_CUT = 0.8f   // 阴影偏移在肤区的削减强度

    /** 曲线折线控制点的 x 序列（三档共用，0..1 等距） */
    private val KNOTS = floatArrayOf(0f, .25f, .5f, .75f, 1f)

    /** 折线控制点（x 单调递增，范围 0..1）。 */
    class Curve(val xs: FloatArray, val ys: FloatArray)

    /**
     * 一档完整色彩参数。用 enum 而非 data class：枚举常量天然单例、可直接作缓存键；
     * 且含 FloatArray 字段的 data class 其 equals/hashCode 是引用比较，作 Map key 不可靠。
     *
     * shadowTint 是**逐通道增量**（乘 (1-lum)² 后加到 RGB），而不是「标量 + 两个比例」：
     * 后者三通道符号被绑定在同一个标量上，无法表达「R 抬、B 抬、G 压」的洋红阴影。
     * 青 = (负, 正, 正)；洋红 = (正, 负, 正)；全 0 = 关闭。
     */
    enum class Profile(
        val label: String,
        val sat: Float,              // 全局饱和基调
        val gain: FloatArray,        // 通道增益（白平衡）
        val warmMid: Float,          // 中间调冷暖偏（负 = 转冷）
        val gamma: Float,            // 亮度域 gamma，0 = 关闭
        val shadowTint: FloatArray,  // 阴影偏移增量 (dR, dG, dB)
        val skinSat: Float,          // 肤区饱和保留
        val skinLumLift: Float,      // 肤区提亮
        val skinBLift: Float,        // 肤区 B 微升
        val skinHueC: Float,         // 肤色相中心（度）
        val skinHueHalf: Float,      // 肤色相半宽（度）
        val curveR: Curve,
        val curveG: Curve,
        val curveB: Curve,
        val grain: Float,            // 颗粒密度（0 = 无）
        val vignette: Float          // 暗角强度（0 = 关，1 = 满）
    ) {
        /** M13 冷白皮 v2 —— 已验收基线，数值不得改动 */
        COOL(
            label = "CCD·冷白", sat = 0.88f,
            gain = floatArrayOf(0.980f, 1.000f, 1.026f),
            warmMid = -0.016f, gamma = 0.05f,
            // 等价原式 (0.015, G=0.35, B=1.10)：r-0.015*ws / g+0.00525*ws / b+0.0165*ws
            shadowTint = floatArrayOf(-0.015f, 0.015f * 0.35f, 0.015f * 1.10f),
            skinSat = 0.62f, skinLumLift = 0.05f, skinBLift = 0.012f,
            skinHueC = 32f, skinHueHalf = 18f,
            curveR = Curve(KNOTS, floatArrayOf(0f, .249f, .500f, .756f, 1f)),
            curveG = Curve(KNOTS, floatArrayOf(0f, .250f, .500f, .752f, 1f)),
            curveB = Curve(KNOTS, floatArrayOf(0f, .251f, .501f, .753f, 1f)),
            grain = 0.18f, vignette = 1f      // 原 applyToJpeg(out, 0.18f) 委托的就是 1f
        ),

        /** CCD·粉 —— 粉白通透（初值，P1 用样张迭代） */
        PINK(
            label = "CCD·粉", sat = 0.92f,
            gain = floatArrayOf(1.012f, 0.994f, 1.014f),
            warmMid = 0.010f, gamma = 0.06f,
            shadowTint = floatArrayOf(0.008f, -0.004f, 0.010f),  // 洋红阴影
            skinSat = 0.70f, skinLumLift = 0.06f, skinBLift = 0.016f,
            skinHueC = 32f, skinHueHalf = 18f,
            curveR = Curve(KNOTS, floatArrayOf(0f, .252f, .505f, .760f, 1f)),
            curveG = Curve(KNOTS, floatArrayOf(0f, .250f, .500f, .752f, 1f)),
            curveB = Curve(KNOTS, floatArrayOf(0f, .254f, .508f, .758f, 1f)),
            grain = 0.14f, vignette = 0.35f    // 粉卡要通透，暗角比冷白轻
        ),

        /** CCD·暖 —— 柯达暖黄（初值，P1 用样张迭代） */
        WARM(
            label = "CCD·暖", sat = 0.98f,
            gain = floatArrayOf(1.030f, 1.000f, 0.964f),
            warmMid = 0.022f, gamma = 0.04f,
            shadowTint = floatArrayOf(0f, 0f, 0f),               // 柯达阴影黄褐，不加偏移
            skinSat = 0.80f, skinLumLift = 0.03f, skinBLift = 0f,
            skinHueC = 26f, skinHueHalf = 22f,
            curveR = Curve(KNOTS, floatArrayOf(0f, .250f, .502f, .771f, 1f)),
            curveG = Curve(KNOTS, floatArrayOf(0f, .250f, .500f, .752f, 1f)),
            curveB = Curve(KNOTS, floatArrayOf(0f, .246f, .497f, .750f, 1f)),
            grain = 0.20f, vignette = 0.55f
        )
    }

    private var env: OrtEnvironment? = null
    private var session: OrtSession? = null

    /** 最近一次失败原因（诊断用，成功后清空） */
    var lastError: String? = null
        private set

    /** 某档的预计算结果（与图像无关）：3x3 混合矩阵 / 三通道 256 级曲线 / 513 级 gamma 表 */
    private class Params(
        val matrix: FloatArray,
        val curveR: FloatArray,
        val curveG: FloatArray,
        val curveB: FloatArray,
        val gammaLut: FloatArray
    )

    /** 按档缓存预计算结果（键是 enum 常量，天然单例） */
    private val paramCache = HashMap<Profile, Params>()

    @Synchronized
    private fun obtainSession(context: Context): OrtSession {
        if (session != null) return session!!
        val e = OrtEnvironment.getEnvironment()
        env = e
        val bytes = context.assets.open(MODEL_ASSET).use { it.readBytes() }
        val opt = OrtSession.SessionOptions()
        opt.setIntraOpNumThreads(4)
        session = e.createSession(bytes, opt)
        return session!!
    }

    @Synchronized
    fun release() {
        try { session?.close() } catch (_: Exception) {}
        session = null
    }

    /** 取某档预计算结果（幂等，首次访问时构建）。 */
    @Synchronized
    private fun paramsOf(p: Profile): Params = paramCache.getOrPut(p) { buildParams(p) }

    private fun buildParams(p: Profile): Params {
        // 混合矩阵：out = L + s*(x - L)，L=luma 投影；再乘对角增益；行归一保中性灰
        val lum = floatArrayOf(0.299f, 0.587f, 0.114f)
        val m = FloatArray(9)
        for (r in 0..2) for (c in 0..2) {
            m[r * 3 + c] = p.sat * (if (r == c) 1f else 0f) + (1f - p.sat) * lum[c]
        }
        for (r in 0..2) for (c in 0..2) m[r * 3 + c] *= p.gain[r]
        for (r in 0..2) {
            var s = 0f
            for (c in 0..2) s += m[r * 3 + c]
            for (c in 0..2) m[r * 3 + c] /= s
        }
        val gl = FloatArray(513)
        if (p.gamma > 0f) {
            for (i in 0..512) {
                gl[i] = Math.pow(i / 512.0, p.gamma.toDouble()).toFloat()
            }
        }
        return Params(m, buildCurve(p.curveR), buildCurve(p.curveG), buildCurve(p.curveB), gl)
    }

    /** 控制点线性插值采样成 256 级表（与 np.interp 折线一致）。 */
    private fun buildCurve(c: Curve): FloatArray {
        val xs = c.xs
        val ys = c.ys
        val t = FloatArray(256)
        var seg = 0
        for (i in 0..255) {
            val x = i / 255f
            while (seg < xs.size - 2 && x > xs[seg + 1]) seg++
            val x0 = xs[seg]; val x1 = xs[seg + 1]
            val y0 = ys[seg]; val y1 = ys[seg + 1]
            val f = if (x1 > x0) ((x - x0) / (x1 - x0)).coerceIn(0f, 1f) else 0f
            t[i] = y0 + (y1 - y0) * f
        }
        return t
    }

    /**
     * 对 JPEG 应用指定档的 CCD 色彩。任何失败返回 null（调用方降级）。
     * 默认档 COOL 即 M13，调用点无需改动。
     * 输入 JPEG 按原字节解码（不摆正 EXIF 方向），输出重编码后由 copyExif 保留原 EXIF。
     */
    fun process(context: Context, jpeg: ByteArray, profile: Profile = Profile.COOL): ByteArray? {
        return try {
            lastError = null
            val prm = paramsOf(profile)
            val opts = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inMutable = true   // 必须可写：后面 setPixels 回写像素
            }
            val src = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, opts) ?: return null

            // 1. 整图缩 256 喂模型（与官方 test 口径一致：backbone 内部还会插值到 256）
            val small = Bitmap.createScaledBitmap(src, INFER_EDGE, INFER_EDGE, true)
            val sp = IntArray(INFER_EDGE * INFER_EDGE)
            small.getPixels(sp, 0, INFER_EDGE, 0, 0, INFER_EDGE, INFER_EDGE)
            val input = FloatArray(3 * INFER_EDGE * INFER_EDGE)
            val n = INFER_EDGE * INFER_EDGE
            for (i in 0 until n) {
                val p = sp[i]
                input[i] = ((p shr 16) and 0xFF) / 255f          // R
                input[n + i] = ((p shr 8) and 0xFF) / 255f       // G
                input[2 * n + i] = (p and 0xFF) / 255f           // B
            }
            if (small !== src) small.recycle()

            val e = env ?: OrtEnvironment.getEnvironment()
            val tensor = OnnxTensor.createTensor(
                e, FloatBuffer.wrap(input),
                longArrayOf(1, 3, INFER_EDGE.toLong(), INFER_EDGE.toLong()))
            val out = obtainSession(context).run(mapOf("img" to tensor))
            val lut1d = (out[0].value as Array<FloatArray>)      // (3,17)
            val lut3dFlat = out[1].value as FloatArray           // 3*17³
            out.close()
            tensor.close()
            val lut3d = Array(3) { c ->
                Array(D) { b -> Array(D) { g -> FloatArray(D) { r ->
                    lut3dFlat[c * D * D * D + b * D * D + g * D + r]  // 轴序 (B,G,R)
                } } }
            }

            // 2. 预计算 1D 精确表（17 点 → 256 级）
            val t1d = Array(3) { c -> buildTable17(lut1d[c]) }

            // 3. 全分辨率逐像素
            val w = src.width
            val h = src.height
            val pixels = IntArray(w * h)
            src.getPixels(pixels, 0, w, 0, 0, w, h)
            val m = prm.matrix
            val cR = prm.curveR; val cG = prm.curveG; val cB = prm.curveB
            val gl = prm.gammaLut
            // 逐像素内只读局部变量（避免每次迭代读 object 字段）
            val useGamma = profile.gamma > 0f
            val warmMid = profile.warmMid
            val skinSat = profile.skinSat
            val skinLumLift = profile.skinLumLift
            val skinBLift = profile.skinBLift
            val skinHueC = profile.skinHueC
            val skinHueHalf = profile.skinHueHalf
            val sR = profile.shadowTint[0]; val sG = profile.shadowTint[1]; val sB = profile.shadowTint[2]
            val d1 = D - 1
            for (i in pixels.indices) {
                val p = pixels[i]
                // --- SepLUT 1D（精确查表）---
                var r = t1d[0][(p shr 16) and 0xFF]
                var g = t1d[1][(p shr 8) and 0xFF]
                var b = t1d[2][p and 0xFF]
                // --- SepLUT 3D 三线性：x(R)→轴2, y(G)→轴1, z(B)→轴0 ---
                val fx = r * d1; val fy = g * d1; val fz = b * d1
                var x0 = fx.toInt(); if (x0 > d1 - 1) x0 = d1 - 1; if (x0 < 0) x0 = 0
                var y0 = fy.toInt(); if (y0 > d1 - 1) y0 = d1 - 1; if (y0 < 0) y0 = 0
                var z0 = fz.toInt(); if (z0 > d1 - 1) z0 = d1 - 1; if (z0 < 0) z0 = 0
                val dx = fx - x0; val dy = fy - y0; val dz = fz - z0
                val x1 = if (dx > 0f) x0 + 1 else x0
                val y1 = if (dy > 0f) y0 + 1 else y0
                val z1 = if (dz > 0f) z0 + 1 else z0
                val w000 = (1 - dx) * (1 - dy) * (1 - dz)
                val w100 = dx * (1 - dy) * (1 - dz)
                val w010 = (1 - dx) * dy * (1 - dz)
                val w110 = dx * dy * (1 - dz)
                val w001 = (1 - dx) * (1 - dy) * dz
                val w101 = dx * (1 - dy) * dz
                val w011 = (1 - dx) * dy * dz
                val w111 = dx * dy * dz
                var nr = 0f; var ng = 0f; var nb = 0f
                for (c in 0..2) {
                    val plane = lut3d[c]
                    val v = plane[z0][y0][x0] * w000 + plane[z0][y0][x1] * w100 +
                            plane[z0][y1][x0] * w010 + plane[z0][y1][x1] * w110 +
                            plane[z1][y0][x0] * w001 + plane[z1][y0][x1] * w101 +
                            plane[z1][y1][x0] * w011 + plane[z1][y1][x1] * w111
                    when (c) { 0 -> nr = v; 1 -> ng = v; else -> nb = v }
                }
                r = nr; g = ng; b = nb
                // --- Portra 矩阵 ---
                val mr = m[0] * r + m[1] * g + m[2] * b
                val mg = m[3] * r + m[4] * g + m[5] * b
                val mb = m[6] * r + m[7] * g + m[8] * b
                r = mr; g = mg; b = mb
                // --- 中间调冷暖偏（负值 = 转冷） ---
                val lum2 = 0.299f * r + 0.587f * g + 0.114f * b
                val wt = 4f * lum2 * (1f - lum2)
                r = (r + warmMid * wt).coerceIn(0f, 1f)
                b = (b - warmMid * wt).coerceIn(0f, 1f)
                // --- 肤色定向：暖色相区去饱和+提亮+B 微升（冷白奶肤） ---
                var skinW = 0f
                run {
                    val mx = maxOf(r, g, b)
                    val mn = minOf(r, g, b)
                    val delta = mx - mn
                    var wgt = 0f
                    if (delta > 1e-5f) {
                        // 色相（度，0=红经黄）
                        val hue = when {
                            mx == r -> { val hh = 60f * (g - b) / delta; if (hh < 0f) hh + 360f else hh }
                            mx == g -> 60f * (b - r) / delta + 120f
                            else -> 60f * (r - g) / delta + 240f
                        }
                        var dh = Math.abs(hue - skinHueC)
                        if (dh > 180f) dh = 360f - dh
                        var hw = 1f - dh / skinHueHalf
                        if (hw > 0f) {
                            hw = hw * hw * (3f - 2f * hw)                 // smoothstep
                            val sat = delta / maxOf(mx, 1e-6f)
                            val sw = ((sat - SKIN_SAT_LO) / SKIN_SAT_SPAN).coerceIn(0f, 1f)
                            val lw = ((mx - SKIN_LUM_LO) / SKIN_LUM_SPAN).coerceIn(0f, 1f)
                            wgt = hw * sw * lw
                        }
                    }
                    if (wgt > 0f) {
                        val keep = 1f - wgt * (1f - skinSat)
                        val ls = 0.299f * r + 0.587f * g + 0.114f * b
                        r = (ls + (r - ls) * keep).coerceIn(0f, 1f)
                        g = (ls + (g - ls) * keep).coerceIn(0f, 1f)
                        b = (ls + (b - ls) * keep).coerceIn(0f, 1f)
                        val lift = 1f + skinLumLift * wgt
                        r = (r * lift - SKIN_R_DROP * wgt).coerceIn(0f, 1f)
                        g = (g * lift).coerceIn(0f, 1f)
                        b = (b * lift + skinBLift * wgt).coerceIn(0f, 1f)
                        skinW = wgt
                    }
                }
                // --- 亮度域 gamma：out_lum = lum^(1+g)，压中间调保黑白点（查表线性插值） ---
                if (useGamma) {
                    val li = (lum2 * 512f)
                    val gi = li.toInt().coerceIn(0, 511)
                    val gf = (li - gi).coerceIn(0f, 1f)
                    val factor = gl[gi] + (gl[gi + 1] - gl[gi]) * gf
                    r = (r * factor).coerceIn(0f, 1f)
                    g = (g * factor).coerceIn(0f, 1f)
                    b = (b * factor).coerceIn(0f, 1f)
                }
                // --- 阴影偏移：越暗越强；肤区内自动减弱防脸灰；三通道各自独立符号 ---
                if (sR != 0f || sG != 0f || sB != 0f) {
                    val l3 = 0.299f * r + 0.587f * g + 0.114f * b
                    val ws = (1f - l3) * (1f - l3) * (1f - skinW * SHADOW_SKIN_CUT)
                    r = (r + sR * ws).coerceIn(0f, 1f)
                    g = (g + sG * ws).coerceIn(0f, 1f)
                    b = (b + sB * ws).coerceIn(0f, 1f)
                }
                // --- 曲线 ---
                r = cR[(r * 255f + 0.5f).toInt().coerceIn(0, 255)]
                g = cG[(g * 255f + 0.5f).toInt().coerceIn(0, 255)]
                b = cB[(b * 255f + 0.5f).toInt().coerceIn(0, 255)]
                // --- 写回 ---
                val ir = (r * 255f + 0.5f).toInt().coerceIn(0, 255)
                val ig = (g * 255f + 0.5f).toInt().coerceIn(0, 255)
                val ib = (b * 255f + 0.5f).toInt().coerceIn(0, 255)
                pixels[i] = ib or (ig shl 8) or (ir shl 16) or -0x1000000
            }
            src.setPixels(pixels, 0, w, 0, 0, w, h)

            val bos = ByteArrayOutputStream(jpeg.size)
            src.compress(Bitmap.CompressFormat.JPEG, 96, bos)
            src.recycle()
            MadridLegendary.copyExif(jpeg, bos.toByteArray())
        } catch (t: Throwable) {
            lastError = "${t.javaClass.simpleName}: ${t.message}"
            android.util.Log.w("PortraitFilm", "M13 failed", t)
            null
        }
    }

    private fun buildTable17(points: FloatArray): FloatArray {
        val t = FloatArray(256)
        for (i in 0..255) {
            val x = i / 255f * (D - 1)
            var i0 = x.toInt(); if (i0 > D - 2) i0 = D - 2; if (i0 < 0) i0 = 0
            val f = (x - i0).coerceIn(0f, 1f)
            t[i] = points[i0] + (points[i0 + 1] - points[i0]) * f
        }
        return t
    }
}
