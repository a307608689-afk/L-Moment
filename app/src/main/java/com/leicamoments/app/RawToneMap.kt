package com.leicamoments.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.pow

/**
 * RAW 线性域亮度映射：只服务「提亮 / 强力提亮」两档，标准档不进入本类。
 *
 * 两条路径，优先走【预览引导分位匹配】，失败回落【自适应软肩曲线】（旧行为）：
 *
 * 1) 预览引导（默认）：DNG 内嵌的厂商预览是「原生观感」的亮度基准。
 *    把我们的线性亮度直方图按分位搬成预览的亮度直方图，得到一条单调曲线，
 *    再按亮度比例缩放 RGB（保色相）。极限大光比场景（逆光/夜景）下，
 *    厂商的多帧合成会同时抬暗部、压高光，任何「全局增益」都追不上；
 *    分位匹配直接对齐亮度分布，暗部与整体亮度都能贴住相册预览。
 *    强度：提亮档 0.55（在旧曲线与原生之间），强力提亮档 1.0（贴死原生）。
 *
 * 2) 回退（无内嵌预览 / 预览解析失败）：旧的自适应软肩曲线
 *    g = clamp(E / pct(Y, Q), 1, 6)，软肩 T0=0.75 / CAP=0.98，BT.709 编码。
 */
object RawToneMap {

    private const val T0 = 0.75f
    private const val CAP = 0.98f
    private const val BINS = 4096
    private const val G_MAX = 6.0f
    private const val LUT_SIZE = 65536

    /** 预览引导：分位采样区间与数量 */
    private const val Q_LO = 0.15f
    private const val Q_HI = 99.85f
    private const val Q_N = 512
    /** 增益上限（暗部极深处防爆） */
    private const val GAIN_MAX = 64f
    /** 预览用于统计的最大像素数（超采样后统计，直方图精度足够） */
    private const val PREVIEW_MAX_PIXELS = 2_000_000

    /** 档位 → (Q 分位, 额外增益)。标准档返回 null，调用方应走原 thr 路径。 */
    private fun paramsOf(level: Int): FloatArray? = when (level) {
        1 -> floatArrayOf(98f, 1.0f)
        2 -> floatArrayOf(97f, 1.1f)
        else -> null
    }

    /** 档位 → 预览引导强度（0 = 完全不用预览） */
    private fun matchStrengthOf(level: Int): Float = when (level) {
        1 -> 0.55f
        2 -> 1.0f
        else -> 0f
    }

    fun isEnabled(level: Int): Boolean = paramsOf(level) != null

    /**
     * 对 [RawDecoder.nativeDecodeLinearSrgb] 的输出套曲线，编码为 ARGB。
     *
     * @param buf direct ByteBuffer：前 8 字节 int w/h，随后 w*h*3 个 u16（本机字节序）
     * @return int[] { w, h, ARGB... }，与 [RawDecoder.nativeDecodeWithThr] 同格式；异常返回 null
     */
    fun render(buf: ByteBuffer, level: Int): IntArray? = render(buf, level, null)

    /**
     * @param previewJpeg DNG 内嵌厂商预览（JPEG 字节）。非空且可解析时走分位匹配，
     *                    否则回落自适应软肩曲线。只取亮度直方图，不需要与 RAW 对齐。
     */
    fun render(buf: ByteBuffer, level: Int, previewJpeg: ByteArray?): IntArray? {
        val p = paramsOf(level) ?: return null
        // native 按本机字节序写入，ByteBuffer 默认大端，必须先切序再读头部与像素
        val bb = buf.order(ByteOrder.nativeOrder())
        val w = bb.getInt(0)
        val h = bb.getInt(4)
        if (w <= 0 || h <= 0) return null
        val total = w * h
        val stride = w * 3
        val sb = bb.asShortBuffer()
        val row = ShortArray(stride)

        // 1) 亮度直方图（BT.709 加权，与标定脚本同源；u16 右移 4 位落 4096 桶）
        val hist = IntArray(BINS)
        var base = 4
        for (y in 0 until h) {
            sb.position(base)
            sb.get(row, 0, stride)
            var i = 0
            while (i < stride) {
                val r = row[i].toInt() and 0xFFFF
                val g = row[i + 1].toInt() and 0xFFFF
                val b = row[i + 2].toInt() and 0xFFFF
                hist[((r * 77 + g * 150 + b * 29) shr 8) shr 4]++
                i += 3
            }
            base += stride
        }

        // 2) 优先：预览引导分位匹配
        val strength = matchStrengthOf(level)
        if (previewJpeg != null && strength > 0f) {
            try {
                val gain = buildPreviewGain(hist, total, previewJpeg, strength)
                if (gain != null) {
                    val out = applyGain(sb, w, h, gain)
                    DiagLog.i("RawTone", "预览引导 level=$level s=$strength ${w}x$h")
                    return out
                }
            } catch (t: Throwable) {
                DiagLog.e("RawTone", "预览引导失败（level=$level），回落软肩曲线", t)
            }
        }

        // 3) 回退：Q 分位 → 归一化增益
        val target = (total.toLong() * p[0].toInt() / 100L).toInt()
        var acc = 0
        var bin = 0
        while (bin < BINS - 1) {
            acc += hist[bin]
            if (acc >= target) break
            bin++
        }
        val white = ((bin + 0.5f) * (65536f / BINS)).coerceAtLeast(1f)
        val gain = (p[1] * 65535f / white).coerceIn(1.0f, G_MAX)

        // 4) 16bit → 8bit 曲线表（软肩 + BT.709 编码，16bit 输入直接索引，末端只量化一次）
        val lut = IntArray(LUT_SIZE)
        val capSpan = CAP - T0
        for (v in 0 until LUT_SIZE) {
            val x = v * gain / 65535f
            val y = if (x <= T0) x else T0 + capSpan * (1f - exp(-(x - T0) / capSpan))
            lut[v] = (bt709Enc(y) * 255f + 0.5f).toInt().coerceIn(0, 255)
        }

        // 5) 逐行查表输出
        val out = IntArray(total + 2)
        out[0] = w
        out[1] = h
        base = 4
        var o = 2
        for (y in 0 until h) {
            sb.position(base)
            sb.get(row, 0, stride)
            var i = 0
            while (i < stride) {
                val r = lut[row[i].toInt() and 0xFFFF]
                val g = lut[row[i + 1].toInt() and 0xFFFF]
                val b = lut[row[i + 2].toInt() and 0xFFFF]
                out[o++] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                i += 3
            }
            base += stride
        }
        return out
    }

    // ------------------------------------------------------------------
    // P2：曲线后线性 16-bit 窗口（五档叠加 / 预览基准降采样）
    // ------------------------------------------------------------------

    // 编码表：65536 用于无五档直编码（与旧版逐字节一致），1024 供 ToneCurve 插值
    private val srgb16: IntArray by lazy {
        IntArray(LUT_SIZE) { v -> (srgbEnc(v / 65535f) * 255f + 0.5f).toInt().coerceIn(0, 255) }
    }
    private val bt70916: IntArray by lazy {
        IntArray(LUT_SIZE) { v -> (bt709Enc(v / 65535f) * 255f + 0.5f).toInt().coerceIn(0, 255) }
    }
    private val bt709_1024: FloatArray by lazy {
        FloatArray(1025) { i -> bt709Enc(i / 1024f) }
    }

    /** 曲线映射后的 native u16 缓冲句柄（bb 由调用方 nativeFreeLinear） */
    class Mapped internal constructor(
        val bb: ByteBuffer, val sb: java.nio.ShortBuffer,
        val w: Int, val h: Int, val usedBt709: Boolean
    )

    /** 降采样后的线性 16-bit 小图（调档预览基准） */
    class Lin16 internal constructor(val data: ShortArray, val w: Int, val h: Int)

    private class Prep(val sb: java.nio.ShortBuffer, val w: Int, val h: Int, val total: Int)

    private fun prepare(buf: ByteBuffer): Prep? {
        val bb = buf.order(ByteOrder.nativeOrder())
        val w = bb.getInt(0)
        val h = bb.getInt(4)
        if (w <= 0 || h <= 0) return null
        return Prep(bb.asShortBuffer(), w, h, w * h)
    }

    /**
     * 就地映射 native 缓冲（预览引导失败回落软肩）。
     * 返回 true = 走了 BT.709 回退路径（编码器须跟随）。
     */
    private fun mapInPlace(m: Prep, level: Int, previewJpeg: ByteArray?): Boolean {
        val p = paramsOf(level) ?: return false
        val hist = histogram(m.sb, m.w, m.h)
        val strength = matchStrengthOf(level)
        if (previewJpeg != null && strength > 0f) {
            try {
                val gain = buildPreviewGain(hist, m.total, previewJpeg, strength)
                if (gain != null) {
                    applyGainInPlace(m.sb, m.w, m.h, gain, false)
                    DiagLog.i("RawTone", "预览引导 level=$level s=$strength ${m.w}x${m.h}")
                    return false
                }
            } catch (t: Throwable) {
                DiagLog.e("RawTone", "预览引导失败（level=$level），回落软肩曲线", t)
            }
        }
        val target = (m.total.toLong() * p[0].toInt() / 100L).toInt()
        var acc = 0
        var bin = 0
        while (bin < BINS - 1) {
            acc += hist[bin]
            if (acc >= target) break
            bin++
        }
        val white = ((bin + 0.5f) * (65536f / BINS)).coerceAtLeast(1f)
        val gain = (p[1] * 65535f / white).coerceIn(1.0f, GAIN_MAX)
        val uniform = FloatArray(BINS).also { it.fill(gain) }
        applyGainInPlace(m.sb, m.w, m.h, uniform, true)
        return true
    }

    /** 管线：准备 → 映射 → 编码。tone 非空且非全 0 时五档作用于线性 16-bit，末端一次量化。 */
    private fun pipeline(
        buf: ByteBuffer, level: Int, previewJpeg: ByteArray?, tone: ToneCurve.Params?
    ): IntArray? {
        val m = prepare(buf) ?: return null
        val usedBt709 = mapInPlace(m, level, previewJpeg)
        return if (tone == null || ToneCurve.isNeutral(tone)) {
            encodeDirect(m.sb, m.w, m.h, if (usedBt709) bt70916 else srgb16)
        } else {
            encodeWithTone(m.sb, m.w, m.h, tone, if (usedBt709) bt709_1024 else null)
        }
    }

    /** 曲线映射 + 五档叠加 → ARGB（{w,h,px...} 格式） */
    fun renderWithTone(
        buf: ByteBuffer, level: Int, previewJpeg: ByteArray?, tone: ToneCurve.Params
    ): IntArray? = pipeline(buf, level, previewJpeg, tone)

    /**
     * 组合入口：曲线映射 → 编码 ARGB → 同时产出降采样 16-bit 基准（一次解码三件事）。
     * [baseMaxDim] <=0 时不产基准（导出重放场景）。native 缓冲由本函数释放。
     */
    fun renderWithBase(
        buf: ByteBuffer, level: Int, previewJpeg: ByteArray?,
        tone: ToneCurve.Params?, baseMaxDim: Int
    ): Pair<IntArray, Lin16?>? {
        val m = prepare(buf) ?: return null
        val usedBt709 = mapInPlace(m, level, previewJpeg)
        val argb = if (tone == null || ToneCurve.isNeutral(tone)) {
            encodeDirect(m.sb, m.w, m.h, if (usedBt709) bt70916 else srgb16)
        } else {
            encodeWithTone(m.sb, m.w, m.h, tone, if (usedBt709) bt709_1024 else null)
        }
        val base = if (baseMaxDim > 0) downsample16(m.sb, m.w, m.h, baseMaxDim) else null
        return argb to base
    }

    /** 曲线映射后的 u16 句柄（供降采样基准；bb 由调用方 free） */
    fun mapLinear16(buf: ByteBuffer, level: Int, previewJpeg: ByteArray?): Mapped? {
        val m = prepare(buf) ?: return null
        val usedBt709 = mapInPlace(m, level, previewJpeg)
        return Mapped(buf, m.sb, m.w, m.h, usedBt709)
    }

    /** 亮度直方图（BT.709 加权，u16 右移 4 位落 4096 桶） */
    private fun histogram(sb: java.nio.ShortBuffer, w: Int, h: Int): IntArray {
        val hist = IntArray(BINS)
        val stride = w * 3
        val row = ShortArray(stride)
        var base = 4
        for (y in 0 until h) {
            sb.position(base)
            sb.get(row, 0, stride)
            var i = 0
            while (i < stride) {
                val r = row[i].toInt() and 0xFFFF
                val g = row[i + 1].toInt() and 0xFFFF
                val b = row[i + 2].toInt() and 0xFFFF
                hist[((r * 77 + g * 150 + b * 29) shr 8) shr 4]++
                i += 3
            }
            base += stride
        }
        return hist
    }

    /** 按桶增益就地缩放 RGB（保色相）；soft=true 叠加自适应软肩（线性域，回退路径专用） */
    private fun applyGainInPlace(
        sb: java.nio.ShortBuffer, w: Int, h: Int, gain: FloatArray, soft: Boolean
    ) {
        val stride = w * 3
        val row = ShortArray(stride)
        var base = 4
        for (y in 0 until h) {
            sb.position(base)
            sb.get(row, 0, stride)
            var i = 0
            while (i < stride) {
                val r = row[i].toInt() and 0xFFFF
                val g = row[i + 1].toInt() and 0xFFFF
                val b = row[i + 2].toInt() and 0xFFFF
                val gn = gain[((r * 77 + g * 150 + b * 29) shr 8) shr 4]
                var rr = r * gn
                var gg = g * gn
                var bbv = b * gn
                if (soft) {
                    rr = softShoulder(rr / 65535f) * 65535f
                    gg = softShoulder(gg / 65535f) * 65535f
                    bbv = softShoulder(bbv / 65535f) * 65535f
                }
                row[i] = clamp16(rr).toShort()
                row[i + 1] = clamp16(gg).toShort()
                row[i + 2] = clamp16(bbv).toShort()
                i += 3
            }
            sb.position(base)
            sb.put(row, 0, stride)
            base += stride
        }
    }

    /** 软肩：x<=T0 线性，之后 exp 压缩趋近 CAP（与旧 LUT 同一数学） */
    private fun softShoulder(x: Float): Float =
        if (x <= T0) x else T0 + (CAP - T0) * (1f - exp(-(x - T0) / (CAP - T0)))

    /** 映射后线性 u16 → ARGB（无五档：65536 表直查，无抖动，与旧版等价） */
    private fun encodeDirect(sb: java.nio.ShortBuffer, w: Int, h: Int, enc: IntArray): IntArray {
        val stride = w * 3
        val row = ShortArray(stride)
        val out = IntArray(w * h + 2)
        out[0] = w
        out[1] = h
        var base = 4
        var o = 2
        for (y in 0 until h) {
            sb.position(base)
            sb.get(row, 0, stride)
            var i = 0
            while (i < stride) {
                val r = enc[row[i].toInt() and 0xFFFF]
                val g = enc[row[i + 1].toInt() and 0xFFFF]
                val b = enc[row[i + 2].toInt() and 0xFFFF]
                out[o++] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                i += 3
            }
            base += stride
        }
        return out
    }

    /** 映射后线性 u16 → ToneCurve 五档（色相保护+软限+TPDF 抖动）→ 一次量化 ARGB */
    private fun encodeWithTone(
        sb: java.nio.ShortBuffer, w: Int, h: Int, tone: ToneCurve.Params, enc1024: FloatArray?
    ): IntArray {
        val n = w * h * 3
        val lin = ShortArray(n)
        sb.position(4)
        sb.get(lin, 0, n)
        val px = IntArray(w * h)
        ToneCurve.applyLinear16ToArgb(lin, w, h, tone, px, enc1024)
        val out = IntArray(w * h + 2)
        out[0] = w
        out[1] = h
        System.arraycopy(px, 0, out, 2, w * h)
        return out
    }

    /** 16-bit 线性 box 降采样（线性域平均是正确的降采样）→ 调档预览基准。直接读 native 缓冲。 */
    fun downsample16(sb: java.nio.ShortBuffer, w: Int, h: Int, maxDim: Int): Lin16 {
        val scale = max(w, h).toFloat() / maxDim
        if (scale <= 1f) {
            val all = ShortArray(w * h * 3)
            sb.position(4)
            sb.get(all, 0, all.size)
            return Lin16(all, w, h)
        }
        val nw = (w / scale).toInt().coerceIn(1, w)
        val nh = (h / scale).toInt().coerceIn(1, h)
        val out = ShortArray(nw * nh * 3)
        for (y in 0 until nh) {
            val y0 = (y * h) / nh
            val y1 = ((y + 1) * h) / nh
            for (x in 0 until nw) {
                val x0 = (x * w) / nw
                val x1 = ((x + 1) * w) / nw
                var sr = 0L; var sg = 0L; var sbv = 0L; var n = 0L
                var sy = y0
                while (sy < y1) {
                    var sx = x0
                    var i = 4 + (sy * w + x0) * 3
                    while (sx < x1) {
                        sr += sb.get(i).toInt() and 0xFFFF
                        sg += sb.get(i + 1).toInt() and 0xFFFF
                        sbv += sb.get(i + 2).toInt() and 0xFFFF
                        n++
                        sx++; i += 3
                    }
                    sy++
                }
                val o = (y * nw + x) * 3
                out[o] = (sr / n).toInt().toShort()
                out[o + 1] = (sg / n).toInt().toShort()
                out[o + 2] = (sbv / n).toInt().toShort()
            }
        }
        return Lin16(out, nw, nh)
    }

    // ------------------------------------------------------------------
    // 预览引导分位匹配
    // ------------------------------------------------------------------

    /**
     * 由「RAW 线性亮度分位」与「内嵌预览亮度分位」构造按桶的亮度增益表。
     * 曲线定义在 sRGB 域（与离线标定脚本一致），增益 = 目标线性亮度 / 当前线性亮度。
     *
     * @return 长度 [BINS] 的增益表（索引与直方图同源）；预览不可用时返回 null
     */
    private fun buildPreviewGain(hist: IntArray, total: Int, jpeg: ByteArray, strength: Float): FloatArray? {
        val phist = previewHistogram(jpeg) ?: return null
        val ptotal = phist.sum()
        if (ptotal <= 0) return null

        // RAW 累计分布（桶 → 累积像素数）
        val cum = LongArray(BINS)
        var acc = 0L
        for (i in 0 until BINS) {
            acc += hist[i]
            cum[i] = acc
        }
        val pcum = LongArray(256)
        var pacc = 0L
        for (i in 0 until 256) {
            pacc += phist[i]
            pcum[i] = pacc
        }

        val xi = FloatArray(Q_N)
        val yi = FloatArray(Q_N)
        for (k in 0 until Q_N) {
            val q = Q_LO + (Q_HI - Q_LO) * k / (Q_N - 1)
            val idx = lowerBound(cum, (total * q / 100.0).toLong())
            xi[k] = srgbEnc(((idx * 16 + 8).toFloat()) / 65535f) * 255f
            val pidx = lowerBound(pcum, (ptotal * q / 100.0).toLong())
            yi[k] = pidx + 0.5f
        }

        // 预览黑/白点保护：异常预览（近全黑/近全白）不参与引导
        if (yi[0] > 250f || yi[Q_N - 1] < 8f) {
            DiagLog.w("RawTone", "预览亮度异常（p0.15=${yi[0]} p99.85=${yi[Q_N - 1]}），跳过引导")
            return null
        }

        // 单调化 + 去重（同一 1/16 桶会给出相同 xi）
        var m = 0
        var lastY = -1f
        for (k in 0 until Q_N) {
            val yv = max(yi[k], lastY)
            lastY = yv
            if (m == 0 || xi[k] > xi[m - 1] + 1e-4f) {
                xi[m] = xi[k]
                yi[m] = yv
                m++
            } else {
                yi[m - 1] = yv
            }
        }

        val gain = FloatArray(BINS)
        for (i in 0 until BINS) {
            val yLin = ((i * 16 + 8).toFloat()) / 65535f
            val x = srgbEnc(yLin) * 255f
            var t = interp(x, xi, yi, m)
            if (strength < 1f) t = x + (t - x) * strength
            val yOut = srgbDec(t / 255f)
            gain[i] = (yOut / max(yLin, 1e-6f)).coerceIn(0.02f, GAIN_MAX)
        }
        return gain
    }

    /** 内嵌预览 → 256 桶亮度直方图（0.299/0.587/0.114，与 JPEG 灰度同源） */
    private fun previewHistogram(jpeg: ByteArray): IntArray? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while ((bounds.outWidth / sample).toLong() * (bounds.outHeight / sample) > PREVIEW_MAX_PIXELS) {
            sample = sample shl 1
        }
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, opts) ?: return null
        val hist = IntArray(256)
        try {
            val w = bmp.width
            val h = bmp.height
            if (w <= 0 || h <= 0) return null
            val px = IntArray(w)
            for (y in 0 until h) {
                bmp.getPixels(px, 0, w, 0, y, w, 1)
                for (i in 0 until w) {
                    val c = px[i]
                    val r = (c shr 16) and 0xFF
                    val g = (c shr 8) and 0xFF
                    val b = c and 0xFF
                    hist[(r * 77 + g * 150 + b * 29) shr 8]++
                }
            }
        } finally {
            bmp.recycle()
        }
        return hist
    }

    /** 按桶增益逐像素缩放 RGB（保色相），再统一编码 */
    private fun applyGain(sb: java.nio.ShortBuffer, w: Int, h: Int, gain: FloatArray): IntArray {
        val stride = w * 3
        val row = ShortArray(stride)
        val out = IntArray(w * h + 2)
        out[0] = w
        out[1] = h
        val enc = IntArray(LUT_SIZE)
        for (v in 0 until LUT_SIZE) {
            enc[v] = (srgbEnc(v / 65535f) * 255f + 0.5f).toInt().coerceIn(0, 255)
        }
        var base = 4
        var o = 2
        for (y in 0 until h) {
            sb.position(base)
            sb.get(row, 0, stride)
            var i = 0
            while (i < stride) {
                val r = row[i].toInt() and 0xFFFF
                val g = row[i + 1].toInt() and 0xFFFF
                val b = row[i + 2].toInt() and 0xFFFF
                val gn = gain[((r * 77 + g * 150 + b * 29) shr 8) shr 4]
                val rr = clamp16(r * gn)
                val gg = clamp16(g * gn)
                val bbv = clamp16(b * gn)
                out[o++] = (0xFF shl 24) or (enc[rr] shl 16) or (enc[gg] shl 8) or enc[bbv]
                i += 3
            }
            base += stride
        }
        return out
    }

    private fun clamp16(v: Float): Int {
        val i = (v + 0.5f).toInt()
        return if (i > 65535) 65535 else if (i < 0) 0 else i
    }

    /** 累计分布的 lower_bound：第一个 cum >= target 的下标 */
    private fun lowerBound(cum: LongArray, target: Long): Int {
        var lo = 0
        var hi = cum.size - 1
        while (lo < hi) {
            val mid = (lo + hi) shr 1
            if (cum[mid] < target) lo = mid + 1 else hi = mid
        }
        return lo
    }

    /** 分段线性插值；n 为有效点数，端点外取端点值 */
    private fun interp(x: Float, xs: FloatArray, ys: FloatArray, n: Int): Float {
        if (n <= 0) return x
        if (x <= xs[0]) return ys[0]
        if (x >= xs[n - 1]) return ys[n - 1]
        var lo = 0
        var hi = n - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) shr 1
            if (xs[mid] <= x) lo = mid else hi = mid
        }
        val dx = xs[hi] - xs[lo]
        if (dx <= 1e-6f) return ys[lo]
        val t = (x - xs[lo]) / dx
        return ys[lo] + (ys[hi] - ys[lo]) * t
    }

    private fun bt709Enc(y: Float): Float {
        val c = if (y <= 0f) 0f else y
        return if (c <= 0.018f) 4.5f * c else 1.099f * c.pow(0.45f) - 0.099f
    }

    /** sRGB 编码（与离线标定脚本一致） */
    private fun srgbEnc(x: Float): Float {
        val c = if (x <= 0f) 0f else if (x >= 1f) 1f else x
        return if (c <= 0.0031308f) c * 12.92f else 1.055f * c.pow(1f / 2.4f) - 0.055f
    }

    /** sRGB 解码 */
    private fun srgbDec(x: Float): Float {
        val c = if (x <= 0f) 0f else if (x >= 1f) 1f else x
        return if (c <= 0.04045f) c / 12.92f else ((c + 0.055f) / 1.055f).pow(2.4f)
    }
}
