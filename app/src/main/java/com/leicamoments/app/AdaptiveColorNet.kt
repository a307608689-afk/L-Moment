package com.leicamoments.app

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import java.nio.FloatBuffer
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * M6.2 deploy path: refs -> aff/knots (ONNX) -> Lab affine + knots -> sRGB.
 *
 * Graph contract (23_export_net.py, style_only checkpoint):
 *   in   refs (1,R,6,128,128)  [ref Lab, N(ref) Lab], L/100, a/b/128
 *   out  aff  (1,12,1,1)       3x4 per-channel affine, [c0 rgb+bias, c1, c2]
 *        knots(1,3,9)          uniformly spaced over L[0,1], a/b[-1,1]
 *
 * The three deploy post-processes from §9.13 are applied here:
 *   protect  continuous sky/skin weight (0..1), cross-faded against the
 *            original pixel with a ±2 Lab clamp. The old hard box put a cliff
 *            at the cloud/building boundary -- a pixel either took the whole
 *            style or none of it -- and the sky broke into blocks.
 *   guard    RGB clipped to 250
 *   smooth   NOT implemented yet (bilateral L); kept off until measured.
 */
object AdaptiveColorNet {

    private const val STAGE1 = "m7_stage1.onnx"
    private const val STAGE2 = "m7_stage2.onnx"
    private const val REF = 128
    private const val REF_SIDE = 256

    // must match ModelColor.cpp / common.protect_weight
    private const val MASK_DIV = 8
    private const val MASK_SIGMA = 2f
    private const val PROTECT_SOFT = 0f

    data class Style(val aff: FloatArray, val knots: FloatArray)

    @Volatile private var env: OrtEnvironment? = null
    @Volatile private var stage1: OrtSession? = null
    @Volatile private var stage2: OrtSession? = null

    fun ensure(ctx: Context): Boolean {
        stage2?.let { return true }
        synchronized(this) {
            stage2?.let { return true }
            return try {
                val e = OrtEnvironment.getEnvironment()
                val bytes1 = ctx.applicationContext.assets.open(STAGE1).use { it.readBytes() }
                val bytes2 = ctx.applicationContext.assets.open(STAGE2).use { it.readBytes() }
                stage1 = e.createSession(bytes1, OrtSession.SessionOptions()
                    .apply { setIntraOpNumThreads(OrtThreads.pick()) })
                stage2 = e.createSession(bytes2, OrtSession.SessionOptions()
                    .apply { setIntraOpNumThreads(OrtThreads.pick()) })
                env = e
                true
            } catch (t: Throwable) {
                DiagLog.w("ModelColor", "ONNX 加载失败：${t.message}")
                false
            }
        }
    }

    /** Per-image fingerprints e_i (r*36) straight from stage1. */
    fun encodeStats(ctx: Context, refs: List<Bitmap>): FloatArray? {
        if (refs.isEmpty() || !ensure(ctx)) return null
        val e = env ?: return null
        val s = stage1 ?: return null
        val r = refs.size
        val buf = FloatArray(r * 6 * REF * REF)
        try {
            fillRefBuf(refs, buf)
            OnnxTensor.createTensor(e, FloatBuffer.wrap(buf),
                longArrayOf(1, r.toLong(), 6, REF.toLong(), REF.toLong())).use { t ->
                s.run(mapOf("refs" to t)).use { res ->
                    val out = FloatArray(r * 36)
                    (res[0] as OnnxTensor).floatBuffer.get(out)
                    return out
                }
            }
        } catch (t: Throwable) {
            DiagLog.w("ModelColor", "encodeStats 失败：${t.message}")
            return null
        }
    }

    private fun fillRefBuf(refs: List<Bitmap>, buf: FloatArray) {
        val side = REF
        refs.forEachIndexed { i, bmp ->
            val patch = refPatch(bmp)
            val lab = labOf(patch)
            val neu = labOf(neutralize(patch))
            var o = i * 6 * side * side
            for (c in 0 until 3) {
                for (k in 0 until side * side) buf[o++] = lab[c * side * side + k]
            }
            for (c in 0 until 3) {
                for (k in 0 until side * side) buf[o++] = neu[c * side * side + k]
            }
        }
    }

    /** mean/amax/std over R -> the 108-dim r that stage2 consumes. */
    fun aggregate(e: FloatArray, r: Int): FloatArray {
        val d = 36
        val mu = FloatArray(d)
        val amx = FloatArray(d)
        val sd = FloatArray(d)
        for (k in 0 until d) {
            var m = 0f
            var mx = -Float.MAX_VALUE
            for (i in 0 until r) {
                val v = e[i * d + k]
                m += v
                if (v > mx) mx = v
            }
            m /= r
            mu[k] = m
            amx[k] = mx
            var s2 = 0f
            for (i in 0 until r) {
                val dd = e[i * d + k] - m
                s2 += dd * dd
            }
            sd[k] = kotlin.math.sqrt(s2 / r)
        }
        val out = FloatArray(d * 3)
        mu.copyInto(out, 0)
        amx.copyInto(out, d)
        sd.copyInto(out, 2 * d)
        return out
    }

    /** stage2: aggregated r -> aff/knots. */
    fun fuse(ctx: Context, r: FloatArray): Style? {
        if (!ensure(ctx)) return null
        val e = env ?: return null
        val s = stage2 ?: return null
        return try {
            OnnxTensor.createTensor(e, FloatBuffer.wrap(r), longArrayOf(1, r.size.toLong())).use { t ->
                s.run(mapOf("r" to t)).use { res ->
                    val a = FloatArray(12)
                    val k = FloatArray(27)
                    (res[0] as OnnxTensor).floatBuffer.get(a)
                    (res[1] as OnnxTensor).floatBuffer.get(k)
                    Style(a, k)
                }
            }
        } catch (t: Throwable) {
            DiagLog.w("ModelColor", "fuse 失败：${t.message}")
            null
        }
    }

    /** Encode a reference set into aff/knots. Null means fall back to the LUT chain. */
    fun encodeStyle(ctx: Context, refs: List<Bitmap>): Style? {
        val e = encodeStats(ctx, refs) ?: return null
        return fuse(ctx, aggregate(e, refs.size))
    }

    // ------------------------------------------------------------------ mono
    //
    // stage2 是在彩色成片上训练的回归网：黑白参考图属于训练分布外，fuse 只会给出
    // 「近中性」的温和参数——影调能学进去，色度却原样穿透（喂黑白图出彩色图）。
    // 显式补救：参考集均值色度低于阈值时清零 a/b 通路（保留学到的 L 影调曲线）；
    // apply/applyPrepared 对单色风格强制关闭 protect（否则天空/肤色被锚回原彩色，
    // 黑白画面里出现彩色补丁）。

    /** 参考集均值色度阈值（Lab √(a²+b²)，0~181）。彩色照片通常 >12，黑白 JPEG 噪声 <4。 */
    const val MONO_MEAN_CHROMA = 6f

    /** a/b 通路清零 = 纯黑白。native/Kotlin 的 curve 均为替换语义，knots 清零即输出恒 0；
     *  aff 的 a/b 行一并清零，使「单色」性质显式化、不依赖 curve 实现细节。 */
    fun Style.toMono(): Style {
        val a = aff.copyOf()
        val k = knots.copyOf()
        for (i in 4..11) a[i] = 0f
        for (i in 9..26) k[i] = 0f
        return Style(a, k)
    }

    /** a/b 通路全零的单色风格。 */
    fun Style.isMono(): Boolean {
        if (aff.size < 12 || knots.size < 27) return false
        for (i in 4..11) if (abs(aff[i]) > 1e-4f) return false
        for (i in 9..26) if (abs(knots[i]) > 1e-4f) return false
        return true
    }

    /** 参考集平均色度，与 stage1 同口径（128² 中心裁剪后测 Lab）。 */
    fun refsMeanChroma(refs: List<Bitmap>): Float {
        var acc = 0f
        var cnt = 0
        refs.forEach { bmp ->
            try {
                val patch = refPatch(bmp)
                val lab = labOf(patch)
                val n = REF * REF
                for (i in 0 until n) {
                    val a = lab[n + i] * 128f
                    val b = lab[2 * n + i] * 128f
                    acc += sqrt(a * a + b * b)
                    cnt++
                }
                patch.recycle()
            } catch (t: Throwable) {
                DiagLog.w("ModelColor", "refsMeanChroma 跳过一张：${t.message}")
            }
        }
        return if (cnt > 0) acc / cnt else 0f
    }

    /** 编码后调用：黑白参考 → 单色风格。返回 (风格, 是否单色)。 */
    fun monoIfGray(refs: List<Bitmap>, s: Style): Pair<Style, Boolean> {
        val mc = refsMeanChroma(refs)
        return if (mc < MONO_MEAN_CHROMA) {
            DiagLog.i("ModelColor", "参考集均值色度 $mc < $MONO_MEAN_CHROMA → 单色风格")
            s.toMono() to true
        } else {
            s to false
        }
    }

    /**
     * Apply aff/knots to the whole bitmap. [protect] and [guard] mirror §9.13;
     * [smooth] is reserved (bilateral L) and currently ignored.
     */
    fun apply(src: Bitmap, style: Style, protect: Boolean = true, guard: Boolean = true,
              smooth: Boolean = false, strength: Float = 1f): Bitmap? {
        val w = src.width
        val h = src.height
        if (w <= 0 || h <= 0) return null
        val n = w * h
        val px = IntArray(n)
        src.getPixels(px, 0, w, 0, 0, w, h)
        // 单色风格必须关保护：protect 会把天空/肤色锚回原彩色，黑白画面里出现彩色补丁
        val prot = protect && !style.isMono()
        if (nativeReady()) {
            try {
                applyNative(px, w, h, style.aff, style.knots, prot, guard,
                    strength.coerceIn(0f, 2f), smooth)
                return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
            } catch (t: Throwable) {
                DiagLog.w("ModelColor", "native apply 失败，回退 Kotlin：${t.message}")
            }
        }
        // 风格输入 = N(x)，与 04_dataset / 05_model / native 同口径。
        // 受保护像素另取原图 Lab 作锚点（见下方 protect 分支）。
        val lab = neutralizedLabOfPixels(px)            // planar: L[0..n), a[n..2n), b[2n..3n)
        // mask 是 0..1 的连续天空/肤色权重，用交叉淡化 lerp(styled, 原图锚定值, m)
        // 而不是 where(m>0) 硬切——淡云/天空渐变处在硬阈值下会出现色块与硬边。
        val mask = if (prot) protectWeight(px, w, h) else null
        val out = IntArray(n)
        val rgb = FloatArray(3)
        val aff = style.aff
        val kn = style.knots
        for (i in 0 until n) {
            val l = lab[i]
            val a = lab[n + i]
            val b = lab[2 * n + i]
            val p = px[i]
            var vl = aff[0] * l + aff[1] * a + aff[2] * b + aff[3]
            var va = aff[4] * l + aff[5] * a + aff[6] * b + aff[7]
            var vb = aff[8] * l + aff[9] * a + aff[10] * b + aff[11]
            vl = curve(vl, kn, 0, 0f, 1f)
            va = curve(va, kn, 9, -1f, 1f)
            vb = curve(vb, kn, 18, -1f, 1f)
            var L = vl * 100f
            var A = va * 128f
            var B = vb * 128f
            val m = if (prot && mask != null) mask[i] else 0f
            // Anchor = raw source pixel, not N(x): strength=0 and mask=1 must
            // both converge to the original photo.
            if (strength != 1f || m > 0f) {
                val rw = rgbToLab(
                    ((p shr 16) and 0xFF) / 255f,
                    ((p shr 8) and 0xFF) / 255f,
                    (p and 0xFF) / 255f)
                val lo = rw[0]
                val la = rw[1]
                val lb = rw[2]
                if (strength != 1f) {
                    L = lo + strength * (L - lo)
                    A = la + strength * (A - la)
                    B = lb + strength * (B - lb)
                }
                if (m > 0f) {
                    L = (1f - m) * L + m * (lo + (L - lo).coerceIn(-PROTECT_SOFT, PROTECT_SOFT))
                    A = (1f - m) * A + m * (la + (A - la).coerceIn(-PROTECT_SOFT, PROTECT_SOFT))
                    B = (1f - m) * B + m * (lb + (B - lb).coerceIn(-PROTECT_SOFT, PROTECT_SOFT))
                }
            }
            labToRgb(L, A, B, rgb)
            var r8 = (rgb[0] * 255f).roundToInt().coerceIn(0, 255)
            var g8 = (rgb[1] * 255f).roundToInt().coerceIn(0, 255)
            var b8 = (rgb[2] * 255f).roundToInt().coerceIn(0, 255)
            if (guard) {
                r8 = min(r8, 250); g8 = min(g8, 250); b8 = min(b8, 250)
            }
            out[i] = (0xFF shl 24) or (r8 shl 16) or (g8 shl 8) or b8
        }
        if (smooth) {
            val lum = FloatArray(n)
            for (i in 0 until n) {
                val p = out[i]
                lum[i] = (0.2126f * ((p shr 16) and 0xFF) + 0.7152f * ((p shr 8) and 0xFF) +
                    0.0722f * (p and 0xFF)) / 255f
            }
            val sm = FloatArray(n)
            for (y in 0 until h) for (x in 0 until w) {
                val c = lum[y * w + x]
                var acc = 0f
                var cnt = 0
                for (dy in -1..1) for (dx in -1..1) {
                    val yy = (y + dy).coerceIn(0, h - 1)
                    val xx = (x + dx).coerceIn(0, w - 1)
                    val v = lum[yy * w + xx]
                    if (abs(v - c) < 0.04f) {
                        acc += v
                        cnt++
                    }
                }
                sm[y * w + x] = if (cnt > 0) acc / cnt else c
            }
            for (i in 0 until n) {
                val ratio = sm[i] / max(lum[i], 1e-4f)
                val p = out[i]
                var r8 = (((p shr 16) and 0xFF) * ratio).roundToInt().coerceIn(0, 255)
                var g8 = (((p shr 8) and 0xFF) * ratio).roundToInt().coerceIn(0, 255)
                var b8 = ((p and 0xFF) * ratio).roundToInt().coerceIn(0, 255)
                if (guard) {
                    r8 = min(r8, 250)
                    g8 = min(g8, 250)
                    b8 = min(b8, 250)
                }
                out[i] = (0xFF shl 24) or (r8 shl 16) or (g8 shl 8) or b8
            }
        }
        return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
    }

    // ---------------------------------------------------------------- native

    @Volatile private var nativeLoaded: Boolean? = null

    /** 供全像素条带导出决定是否走 native 路径。 */
    fun isNativeAvailable(): Boolean = nativeReady()

    private fun nativeReady(): Boolean {
        nativeLoaded?.let { return it }
        synchronized(this) {
            nativeLoaded?.let { return it }
            val ok = try {
                System.loadLibrary("rawdecoder")
                true
            } catch (t: Throwable) {
                DiagLog.w("ModelColor", "rawdecoder 加载失败：${t.javaClass.simpleName} ${t.message}")
                false
            }
            nativeLoaded = ok
            return ok
        }
    }

    data class Stats(val gr: Float, val gg: Float, val gb: Float,
                     val scale: Float, val offset: Float)

    /** 全像素条带导出用：在低分辨率整图采样上求 neutralize 的 5 个全局统计量。 */
    fun statsOf(src: Bitmap): Stats? {
        val w = src.width
        val h = src.height
        if (w <= 0 || h <= 0 || !nativeReady()) return null
        val n = w * h
        val px = IntArray(n)
        src.getPixels(px, 0, w, 0, 0, w, h)
        return try {
            val a = statsNative(px, w, h)
            if (a == null || a.size < 5) null
            else Stats(a[0], a[1], a[2], a[3], a[4])
        } catch (t: Throwable) {
            DiagLog.w("ModelColor", "native stats 失败：${t.message}")
            null
        }
    }

    /** 全像素条带导出用：对已解码的 ext 窗口做 neutralize→aff→knots→protect→guard，仅平滑 core 行。 */
    fun applyPrepared(
        px: IntArray, w: Int, extH: Int, style: Style, stats: Stats,
        extTop: Int, globalH: Int, coreStart: Int, coreRows: Int,
        protect: Boolean = true, guard: Boolean = true,
        smooth: Boolean = true, strength: Float = 1f
    ): Boolean {
        if (!nativeReady()) return false
        // 同 apply：单色风格强制关保护（条带导出路径）
        val prot = protect && !style.isMono()
        return try {
            applyPreparedNative(
                px, w, extH, style.aff, style.knots, prot, guard,
                strength.coerceIn(0f, 2f),
                stats.gr, stats.gg, stats.gb, stats.scale, stats.offset,
                extTop, globalH, coreStart, coreRows, smooth
            )
        } catch (t: Throwable) {
            DiagLog.w("ModelColor", "native prepared apply 失败：${t.message}")
            false
        }
    }

    private external fun statsNative(px: IntArray, w: Int, h: Int): FloatArray?

    private external fun applyPreparedNative(
        px: IntArray, w: Int, extH: Int,
        aff: FloatArray, knots: FloatArray,
        protect: Boolean, guard: Boolean, strength: Float,
        gr: Float, gg: Float, gb: Float, scale: Float, offset: Float,
        extTop: Int, globalH: Int, coreStart: Int, coreRows: Int, smooth: Boolean
    ): Boolean

    private external fun applyNative(px: IntArray, w: Int, h: Int,
                                     aff: FloatArray, knots: FloatArray,
                                     protect: Boolean, guard: Boolean,
                                     strength: Float, smooth: Boolean)

    // ---------------------------------------------------------------- color

    private fun curve(v: Float, k: FloatArray, off: Int, lo: Float, hi: Float): Float {
        val pos = ((v - lo) / (hi - lo)).coerceIn(0f, 1f) * 8f
        val i0 = pos.toInt().coerceIn(0, 7)
        val f = pos - i0
        return k[off + i0] + (k[off + i0 + 1] - k[off + i0]) * f
    }

    private fun refPatch(src: Bitmap): Bitmap {
        val shortSide = min(src.width, src.height)
        val s = REF_SIDE.toFloat() / max(shortSide, 1)
        val nw = max(REF_SIDE, (src.width * s).roundToInt())
        val nh = max(REF_SIDE, (src.height * s).roundToInt())
        val scaled = Bitmap.createScaledBitmap(src, nw, nh, true)
        val x = (nw - REF_SIDE) / 2
        val y = (nh - REF_SIDE) / 2
        val crop = Bitmap.createBitmap(scaled, x, y, REF_SIDE, REF_SIDE)
        return Bitmap.createScaledBitmap(crop, REF, REF, true)
    }

    /** Normalized Lab planes, L/100, a/128, b/128, planar 3*side*side. */
    private fun labOf(bmp: Bitmap): FloatArray {
        val n = bmp.width * bmp.height
        val px = IntArray(n)
        bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        return labOfPixels(px)
    }

    private fun labOfPixels(px: IntArray): FloatArray {
        val n = px.size
        val out = FloatArray(n * 3)
        for (i in 0 until n) {
            val p = px[i]
            val r = ((p shr 16) and 0xFF) / 255f
            val g = ((p shr 8) and 0xFF) / 255f
            val b = (p and 0xFF) / 255f
            val lab = rgbToLab(r, g, b)
            out[i] = lab[0] / 100f
            out[n + i] = lab[1] / 128f
            out[2 * n + i] = lab[2] / 128f
        }
        return out
    }

    private fun rgbToLab(r: Float, g: Float, b: Float): FloatArray {
        val rl = srgbToLinear(r)
        val gl = srgbToLinear(g)
        val bl = srgbToLinear(b)
        val x = (0.412453f * rl + 0.357580f * gl + 0.180423f * bl) / 0.950456f
        val y = (0.212671f * rl + 0.715160f * gl + 0.072169f * bl)
        val z = (0.019334f * rl + 0.119193f * gl + 0.950227f * bl) / 1.088754f
        val fx = if (x > 0.008856f) x.toDouble().pow(1.0 / 3.0).toFloat()
                 else 7.787f * x + 16f / 116f
        val fy = if (y > 0.008856f) y.toDouble().pow(1.0 / 3.0).toFloat()
                 else 7.787f * y + 16f / 116f
        val fz = if (z > 0.008856f) z.toDouble().pow(1.0 / 3.0).toFloat()
                 else 7.787f * z + 16f / 116f
        return floatArrayOf(116f * fy - 16f, 500f * (fx - fy), 200f * (fy - fz))
    }

    private fun labToRgb(L: Float, A: Float, B: Float, out: FloatArray) {
        val fy = (L + 16f) / 116f
        val fx = fy + A / 500f
        val fz = fy - B / 200f
        val x = finv(fx) * 0.950456f
        val y = finv(fy)
        val z = finv(fz) * 1.088754f
        val rl = (3.240479f * x - 1.537150f * y - 0.498535f * z).coerceIn(0f, 1f)
        val gl = (-0.969256f * x + 1.875992f * y + 0.041556f * z).coerceIn(0f, 1f)
        val bl = (0.055648f * x - 0.204043f * y + 1.057311f * z).coerceIn(0f, 1f)
        out[0] = linearToSrgb(rl)
        out[1] = linearToSrgb(gl)
        out[2] = linearToSrgb(bl)
    }

    private fun finv(t: Float): Float =
        if (t > 0.206893f) t * t * t else (t - 16f / 116f) / 7.787f

    private fun srgbToLinear(v: Float): Float =
        if (v <= 0.04045f) v / 12.92f else ((v + 0.055f) / 1.055f).pow(2.4f)

    private fun linearToSrgb(v: Float): Float =
        if (v <= 0.0031308f) v * 12.92f else 1.055f * v.pow(1f / 2.4f) - 0.055f

    // -------------------------------------------------------- neutralization

    /** Gray-world WB + 0.5/99.5 percentile exposure, mirroring common.neutralize. */
    private fun neutralize(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        val n = w * h
        val px = IntArray(n)
        src.getPixels(px, 0, w, 0, 0, w, h)
        return Bitmap.createBitmap(neutralizePixels(px), w, h, Bitmap.Config.ARGB_8888)
    }

    private fun neutralizePixels(px: IntArray): IntArray {
        val n = px.size
        val lin = FloatArray(n * 3)
        var mr = 0f; var mg = 0f; var mb = 0f
        for (i in 0 until n) {
            val p = px[i]
            val r = srgbToLinear(((p shr 16) and 0xFF) / 255f)
            val g = srgbToLinear(((p shr 8) and 0xFF) / 255f)
            val b = srgbToLinear((p and 0xFF) / 255f)
            lin[i * 3] = r; lin[i * 3 + 1] = g; lin[i * 3 + 2] = b
            mr += r; mg += g; mb += b
        }
        mr = mr / n + 1e-6f; mg = mg / n + 1e-6f; mb = mb / n + 1e-6f
        val mean = (mr + mg + mb) / 3f
        val gr = mean / mr; val gg = mean / mg; val gb = mean / mb
        val m = ((n + 15) / 16).coerceAtLeast(1)
        val ys = FloatArray(m)
        for (k in 0 until m) {
            val i = k * 16
            val r = lin[i * 3] * gr.coerceIn(0.5f, 2f)
            val g = lin[i * 3 + 1] * gg.coerceIn(0.5f, 2f)
            val b = lin[i * 3 + 2] * gb.coerceIn(0.5f, 2f)
            ys[k] = 0.2126f * r + 0.7152f * g + 0.0722f * b
        }
        ys.sort()
        val lo = ys[(ys.size * 0.005f).toInt().coerceIn(0, ys.size - 1)]
        val hi = ys[(ys.size * 0.995f).toInt().coerceIn(0, ys.size - 1)]
        val span = max(hi - lo, 1e-4f)
        val scale = (0.98f - 0.005f) / span
        val s = scale.coerceIn(0.25f, 4f)
        val out = IntArray(n)
        for (i in 0 until n) {
            val r = (lin[i * 3] * gr.coerceIn(0.5f, 2f) * s + (0.005f - lo * s)).coerceIn(0f, 1f)
            val g = (lin[i * 3 + 1] * gg.coerceIn(0.5f, 2f) * s + (0.005f - lo * s)).coerceIn(0f, 1f)
            val b = (lin[i * 3 + 2] * gb.coerceIn(0.5f, 2f) * s + (0.005f - lo * s)).coerceIn(0f, 1f)
            out[i] = (0xFF shl 24) or
                (linearToSrgb(r) * 255f).roundToInt().coerceIn(0, 255).shl(16) or
                (linearToSrgb(g) * 255f).roundToInt().coerceIn(0, 255).shl(8) or
                (linearToSrgb(b) * 255f).roundToInt().coerceIn(0, 255)
        }
        return out
    }

    /**
     * Lab of N(x) in the same planar layout as labOfPixels(). Mirrors
     * common.neutralize_lab(); kept separate from labOfPixels() so the raw
     * source stays available as the protection anchor.
     */
    private fun neutralizedLabOfPixels(px: IntArray): FloatArray {
        val n = px.size
        val lin = FloatArray(n * 3)
        var mr = 0f; var mg = 0f; var mb = 0f
        for (i in 0 until n) {
            val p = px[i]
            val r = srgbToLinear(((p shr 16) and 0xFF) / 255f)
            val g = srgbToLinear(((p shr 8) and 0xFF) / 255f)
            val b = srgbToLinear((p and 0xFF) / 255f)
            lin[i * 3] = r; lin[i * 3 + 1] = g; lin[i * 3 + 2] = b
            mr += r; mg += g; mb += b
        }
        mr = mr / n + 1e-6f; mg = mg / n + 1e-6f; mb = mb / n + 1e-6f
        val mean = (mr + mg + mb) / 3f
        val gr = (mean / mr).coerceIn(0.5f, 2f)
        val gg = (mean / mg).coerceIn(0.5f, 2f)
        val gb = (mean / mb).coerceIn(0.5f, 2f)
        val m = ((n + 15) / 16).coerceAtLeast(1)
        val ys = FloatArray(m)
        for (k in 0 until m) {
            val i = k * 16
            ys[k] = 0.2126f * lin[i * 3] * gr +
                0.7152f * lin[i * 3 + 1] * gg +
                0.0722f * lin[i * 3 + 2] * gb
        }
        ys.sort()
        val lo = ys[(ys.size * 0.005f).toInt().coerceIn(0, ys.size - 1)]
        val hi = ys[(ys.size * 0.995f).toInt().coerceIn(0, ys.size - 1)]
        val span = max(hi - lo, 1e-4f)
        val s = ((0.98f - 0.005f) / span).coerceIn(0.25f, 4f)
        val off = 0.005f - lo * s
        val out = FloatArray(n * 3)
        for (i in 0 until n) {
            val r = (lin[i * 3] * gr * s + off).coerceIn(0f, 1f)
            val g = (lin[i * 3 + 1] * gg * s + off).coerceIn(0f, 1f)
            val b = (lin[i * 3 + 2] * gb * s + off).coerceIn(0f, 1f)
            val lab = rgbToLab(linearToSrgb(r), linearToSrgb(g), linearToSrgb(b))
            out[i] = lab[0] / 100f
            out[n + i] = lab[1] / 128f
            out[2 * n + i] = lab[2] / 128f
        }
        return out
    }

    /**
     * Continuous 0..1 sky/skin weight (1 = protect). Scored on a 1/[MASK_DIV]
     * grid -- a full-res score is 1/64 as much work for the same picture, and
     * the blur radius there (~16px at full res) is what hides the boundary --
     * then blurred and bilinearly upsampled on demand.
     */
    private fun protectWeight(px: IntArray, w: Int, h: Int): FloatArray {
        val div = MASK_DIV
        val sw = max(1, (w + div - 1) / div)
        val sh = max(1, (h + div - 1) / div)
        val small = FloatArray(sw * sh)
        for (sy in 0 until sh) {
            val y0 = sy * div
            val y1 = min(h, y0 + div)
            for (sx in 0 until sw) {
                val x0 = sx * div
                val x1 = min(w, x0 + div)
                var sr = 0
                var sg = 0
                var sb = 0
                var cnt = 0
                for (y in y0 until y1) {
                    var idx = y * w + x0
                    for (x in x0 until x1) {
                        val p = px[idx++]
                        sr += (p shr 16) and 0xFF
                        sg += (p shr 8) and 0xFF
                        sb += p and 0xFF
                        cnt++
                    }
                }
                if (cnt <= 0) continue
                // INTER_AREA averages in 8-bit space first, then converts
                val lab = rgbToLab(((sr + cnt / 2) / cnt) / 255f,
                    ((sg + cnt / 2) / cnt) / 255f, ((sb + cnt / 2) / cnt) / 255f)
                small[sy * sw + sx] = protectScore(lab[0], lab[1], lab[2])
            }
        }
        // separable Gaussian, sigma=MASK_SIGMA, BORDER_REFLECT_101 (cv2 default)
        val rad = ceil(3f * MASK_SIGMA).toInt()
        val kern = FloatArray(2 * rad + 1)
        var ksum = 0f
        for (i in -rad..rad) {
            val wgt = exp(-(i * i) / (2f * MASK_SIGMA * MASK_SIGMA))
            kern[i + rad] = wgt
            ksum += wgt
        }
        for (i in kern.indices) kern[i] /= ksum
        val tmp = FloatArray(sw * sh)
        for (y in 0 until sh) for (x in 0 until sw) {
            var acc = 0f
            for (i in -rad..rad) acc += kern[i + rad] * small[y * sw + reflect101(x + i, sw)]
            tmp[y * sw + x] = acc
        }
        for (y in 0 until sh) for (x in 0 until sw) {
            var acc = 0f
            for (i in -rad..rad) acc += kern[i + rad] * tmp[reflect101(y + i, sh) * sw + x]
            small[y * sw + x] = acc
        }
        val out = FloatArray(w * h)
        val scaleX = sw.toFloat() / w
        val scaleY = sh.toFloat() / h
        for (y in 0 until h) {
            val spy = (y + 0.5f) * scaleY - 0.5f
            var gy0 = floor(spy).toInt()
            val fy = spy - gy0
            val gy1 = (gy0 + 1).coerceIn(0, sh - 1)
            gy0 = gy0.coerceIn(0, sh - 1)
            val r0 = gy0 * sw
            val r1 = gy1 * sw
            for (x in 0 until w) {
                val spx = (x + 0.5f) * scaleX - 0.5f
                var gx0 = floor(spx).toInt()
                val fx = spx - gx0
                val gx1 = (gx0 + 1).coerceIn(0, sw - 1)
                gx0 = gx0.coerceIn(0, sw - 1)
                out[y * w + x] =
                    (small[r0 + gx0] * (1f - fx) + small[r0 + gx1] * fx) * (1f - fy) +
                        (small[r1 + gx0] * (1f - fx) + small[r1 + gx1] * fx) * fy
            }
        }
        return out
    }

    /**
     * Two sky models -- thin cloud is bright but nearly neutral, a clear sky is
     * darker and clearly blue -- plus a soft skin box. The chroma and |a| caps
     * keep blue objects (signs, cars, sculptures) out.
     */
    private fun protectScore(L: Float, a: Float, b: Float): Float {
        val chroma = kotlin.math.sqrt(a * a + b * b)
        val white = smoothstep(66f, 79f, L) * smoothstep(25f, 12f, chroma) *
            smoothstep(-1f, -4f, b) * (1f - smoothstep(4f, 9f, abs(a)))
        val blue = smoothstep(42f, 60f, L) * smoothstep(42f, 25f, chroma) *
            smoothstep(-2f, -7f, b) * (1f - smoothstep(12f, 20f, abs(a)))
        val skin = smoothstep(26f, 36f, L) * smoothstep(92f, 80f, L) *
            smoothstep(5f, 11f, a) * smoothstep(38f, 30f, a) *
            smoothstep(4f, 10f, b) * smoothstep(40f, 32f, b)
        return max(max(white, blue), skin)
    }

    private fun smoothstep(e0: Float, e1: Float, x: Float): Float {
        val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private fun reflect101(i: Int, size: Int): Int {
        if (size <= 1) return 0
        var v = i
        while (v < 0 || v >= size) {
            if (v < 0) v = -v
            if (v >= size) v = 2 * (size - 1) - v
        }
        return v
    }
}
