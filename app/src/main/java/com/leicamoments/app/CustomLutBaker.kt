package com.leicamoments.app

import android.content.Context
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * 自定义色彩烘焙：多张参考图的统计量聚合成 θ，再把 T(x;θ) 打到 33³ 网格上。
 *
 * 与 L-P9 测量式拟合的区别：参考图只有成品、没有原片，得不到「输入→输出」配对，
 * 只能按目标统计量做确定性映射（白平衡 / 亮度曲线 / 分色相饱和度 / 色相微移 / 基础量）。
 * 这不是「复制那张照片」，而是「把你的照片往那组统计量推」。
 *
 * 输出布局与 [com.codex.leicaapp.AlexaLut] 严格一致：
 * `data[i]`，i = 通道平面·dim³ + b·dim² + g·dim + r（r 变化最快），float32 小端。
 * 33³ 约 431 KB / 版本，比 65³（3.3 MB）省 8 倍，插值误差对「统计量映射」这种平滑函数无感。
 */
object CustomLutBaker {

    /** 旧格式（统计量映射）网格边长：33³ = 35937 点，431 KB。 */
    const val DIM_V1 = 33

    /** 新格式（相对风格拟合）网格：65³ = 3.3 MB。色相旋转在绿→青段斜率约 2.4，
     *  33³ 三线性插值会把这个核心表达项糊掉（M4 实测 33³ 误差 0.024 / 65³ 0.012）。 */
    const val DIM_V2 = 65

    /** 兼容旧引用 */
    const val DIM = DIM_V1

    /** RefSeed.base[3] 等于该值表示新格式（旧数据的 base[3] = 黑位抬升 ∈ [0, 0.25]）。 */
    const val FORMAT_V2 = 1f

    /**
     * v2 相对风格拟合总开关。
     *
     * false 时参考图只产出「影调曲线 + 全局饱和比」（不动白平衡、不做色相旋转与分色调），
     * 输出仍走旧格式，可与历史 seed 混合聚合 —— 当 v2 在真机上出现不可预期偏色时的退路。
     * 注意：旧格式的「白平衡 = 参考图色偏」本身就是要修的错误，退路不会再复现它。
     */
    const val CUSTOM_BAKE_V2 = true

    /** v1（统计量映射）色相桶数；v1 seed 的历史尺寸依赖它，不能动。 */
    private const val HUE_BINS = 24
    const val SAT_BANDS = 3
    private const val CURVE_POINTS = 16

    /**
     * v2 色相桶数（24 → 48，7.5° 一档）。
     *
     * 原来是 15° 一档的**分段常数**，色相旋转/饱和增益在 hue 上是阶梯的，65³ 网格上能看出台阶。
     * 扩容到 48 并在桶间做圆周线性插值后，两者的组合基本连续。
     */
    const val HUE_BINS_V2 = 48

    /** v2 曲线控制点数（16 → 33，0.031 一格）。 */
    const val CURVE_POINTS_V2 = 33

    /** v2 饱和档锚点（与 PC 台 SAT_ANCHORS 一致） */
    private val SAT_ANCHORS = floatArrayOf(0.12f, 0.45f, 0.82f)

    // ---- 联合残差网格（方案 A：伪原片直接建表的工程化形态）----
    //
    // 参数模型把 V 曲线 / 色相旋转 / 饱和增益当成互相解耦的函数，表达不了它们之间的
    // 交叉项；参考图里「同一亮度、不同色相」「同一色相、不同饱和」的真实行为差异会被
    // 摊平。这里把「伪输入 → 参考图」的实测残差按 (V 档 × 色相桶 × 饱和档) 联合分箱
    // 留存，烘焙时叠加在参数骨架之上：
    //   · 有足够测量的色彩域 → 残差把骨架推到测量值，等价于直接使用测量值建表
    //   · 没有测量的色彩域   → 残差为 0，退回参数骨架（不回恒等，避免「部分像素改、
    //                          部分不改」的断层；骨架本身带收缩与限幅，不会乱染色）
    // 这就是「覆盖度分档」：置信度随样本量连续变化，不存在硬切档。

    /** 残差网格的亮度档数（V 维；曲线已单独建模，残差在 V 上平滑，8 档足够） */
    const val RES_V_BINS = 8

    /** 残差网格每格存的 float 数：{dV, dH(度), dS(比例), w(0..1 置信度)} */
    const val RES_STRIDE = 4

    /** 残差网格尺寸（与 [RefSeed.meas] 必须一致，旧 seed 空数组即无测量） */
    const val RES_SIZE = RES_V_BINS * HUE_BINS_V2 * SAT_BANDS * RES_STRIDE

    /** 饱和维的桶中心（统计分箱边界 0.25 / 0.65，中心即档内代表值） */
    private val RES_SAT_CENTERS = floatArrayOf(0.125f, 0.45f, 0.825f)

    /** 残差限幅：残差只补模型误差，不允许脱离骨架跑远（防单张网图把整表带偏） */
    const val RES_MAX_DV = 0.05f
    const val RES_MAX_DH = 6f
    const val RES_MAX_DS = 0.25f

    /** 残差零置信阈值：单格样本量收缩后低于该值视为未测量 */
    private const val RES_MIN_CONF = 0.02f

    /** 整张残差表的最低覆盖率：参考图色彩过于单一时整表不可信，直接丢弃回骨架 */
    private const val RES_MIN_COVERAGE = 0.10f

    /** 统计侧与查表侧共用的分箱（必须同构，否则残差会错位到别的颜色上） */
    fun resVBin(v: Float): Int =
        (v.coerceIn(0f, 1f) * RES_V_BINS).toInt().coerceIn(0, RES_V_BINS - 1)

    /** 色相桶：与 RefAnalyzer.hueBinOf 同口径「就近归桶」（节点在 i·7.5°，不是桶心） */
    fun resHueBin(h: Float): Int {
        val deg = 360f / HUE_BINS_V2
        val hh = ((h % 360f) + 360f) % 360f
        val b = ((hh / deg) + 0.5f).toInt()
        return ((b % HUE_BINS_V2) + HUE_BINS_V2) % HUE_BINS_V2
    }

    fun resSatBin(s: Float): Int = when {
        s < 0.25f -> 0
        s < 0.65f -> 1
        else -> 2
    }

    fun resIndex(vBin: Int, hBin: Int, sBin: Int): Int =
        ((vBin * HUE_BINS_V2 + hBin) * SAT_BANDS + sBin) * RES_STRIDE

    /** 与 RefAnalyzer 同口径：低于该饱和度视为无彩色，不做色相处理 */
    private const val SAT_EPS = 0.06f

    /** 近中性保护满权阈值：s 从 SAT_EPS 到该值线性渐入完整增益 */
    private const val NEUTRAL_FULL = 0.18f

    /** 亮度曲线缩放比的安全钳制：暗部比值失控会把噪点/暗部拉爆 */
    private const val RATIO_MIN = 0.4f
    private const val RATIO_MAX = 3.0f

    /** 首版黑位抬升的权重（0.25 上限 × 0.5 = 最多 +0.125 的阴影提亮） */
    private const val BLACK_LIFT_GAIN = 0.5f

    /**
     * 聚合后的 θ。
     *
     * v2 = false：旧语义（wbGain 色温 + 曲线比值 + 分色相饱和 + 色相 bin 偏移 + 基础量）。
     * v2 = true ：新语义，字段复用但含义不同——
     *   toneCurve[33]   → V 曲线，y = f(v)（v1 为 16 点，lookup 按数组长度自适应）
     *   satByHue[144]   → 48 色相 × 3 饱和档的 S 增益（不再是"相对全局均值"）
     *   hueShift[48]    → 色相旋转，单位「度」（不再是 bin），桶间圆周插值
     *   colorMatrix[9]  → 三段式分色调，行 = 阴影/中间/高光，列 = RGB 偏移（0..1 尺度）
     *   base[3]         → 1f，格式标记
     *   hueShare[0..1]  → 饱和-亮度耦合 a, b
     */
    class Theta(
        val wbGain: FloatArray,      // 3
        val colorMatrix: FloatArray, // 9
        val toneCurve: FloatArray,   // v1 16 / v2 33
        val satByHue: FloatArray,    // v1 72 / v2 144
        val hueShift: FloatArray,    // v1 24 / v2 48
        val base: FloatArray,        // 4
        val v2: Boolean = false,
        val satLuma: FloatArray = floatArrayOf(1f, 0f),  // v2: S' = S·(a + b·(V-0.5))
        /** 联合残差网格（见 [RES_SIZE]）；空数组 = 无测量，只走参数骨架 */
        val residual: FloatArray = FloatArray(0)
    )

    /**
     * 聚合多张参考图的统计量（等权；色相相关量按像素占比加权）。
     * 返回 null 表示没有可用参考图。
     */
    fun aggregate(seeds: List<RefSeed>): Theta? = aggregateInternal(seeds)

    private fun aggregateInternal(seeds: List<RefSeed>): Theta? {
        if (seeds.isEmpty()) return null
        // v2 与 v1 的字段语义不同（colorMatrix 是分色调而非 3×3 矩阵、hueShare 前两位是
        // 饱和-亮度耦合系数），混合聚合会算出无意义结果：只要有 v2 seed 就只按 v2 聚合，
        // 旧 seed 视为过期（其 LUT 已落盘可继续用，重新分析参考图才会被替换）。
        val v2 = seeds.filter { it.base.getOrElse(3) { 0f } >= 0.5f }
        if (v2.isNotEmpty()) return aggregateV2(v2)
        val n = seeds.size.toFloat()

        // 白平衡：几何平均（对色偏更稳，单张偏色不会直接主导）
        val wb = FloatArray(3) { ch ->
            var acc = 0.0
            seeds.forEach { acc += kotlin.math.ln(it.wbGain.getOrElse(ch) { 1f }.coerceAtLeast(0.01f).toDouble()) }
            kotlin.math.exp(acc / seeds.size).toFloat()
        }

        // 色彩矩阵：算术平均后重新正交化（S2 恒为单位阵，给后续多图色偏聚合留位）
        val m = FloatArray(9)
        seeds.forEach { s -> for (i in 0 until 9) m[i] += s.colorMatrix.getOrElse(i) { if (i % 4 == 0) 1f else 0f } / n }
        val matrix = orthonormalize(m)

        // 亮度曲线：逐控制点取中位数（抗单张过曝/过暗），再强制单调
        val curve = FloatArray(CURVE_POINTS)
        for (k in 0 until CURVE_POINTS) {
            val vals = seeds.map { it.toneCurve.getOrElse(k) { k / (CURVE_POINTS - 1f) } }.sorted()
            curve[k] = if (vals.size % 2 == 1) vals[vals.size / 2]
            else (vals[vals.size / 2 - 1] + vals[vals.size / 2]) / 2f
        }
        // 参考图不是同一套色彩时（逐点极差大），曲线向恒等回缩 40%：
        // 宁可不改，也别替用户把风格折中成一个谁都不像的中间态。
        if (seeds.size > 1) {
            var spread = 0f
            for (k in 0 until CURVE_POINTS) {
                val vals = seeds.map { it.toneCurve.getOrElse(k) { k / (CURVE_POINTS - 1f) } }
                spread += (vals.maxOrNull() ?: 0f) - (vals.minOrNull() ?: 0f)
            }
            spread /= CURVE_POINTS
            if (spread > 0.18f) {
                for (k in 0 until CURVE_POINTS) {
                    val id = k / (CURVE_POINTS - 1f)
                    curve[k] = id + (curve[k] - id) * 0.6f
                }
            }
        }

        var prev = 0f
        for (k in 0 until CURVE_POINTS) {
            curve[k] = curve[k].coerceIn(prev, 1f)
            prev = curve[k]
        }
        // 分色相饱和增益 / 色相微移：按该格像素占比加权
        val satByHue = FloatArray(HUE_BINS * SAT_BANDS) { 1f }
        val hueShift = FloatArray(HUE_BINS)
        for (hb in 0 until HUE_BINS) {
            var wSum = 0f
            var offAcc = 0f
            for (band in 0 until SAT_BANDS) {
                val i = hb * SAT_BANDS + band
                var ws = 0f
                var gs = 0f
                seeds.forEach { s ->
                    val w = s.hueShare.getOrElse(i) { 0f }
                    if (w > 0f) {
                        ws += w
                        gs += w * s.satByHue.getOrElse(i) { 1f }
                    }
                }
                if (ws > 0f) satByHue[i] = (gs / ws).coerceIn(0.5f, 2.0f)
                wSum += ws
                seeds.forEach { s -> offAcc += s.hueShare.getOrElse(i) { 0f } * s.hueShift.getOrElse(hb) { 0f } }
            }
            hueShift[hb] = if (wSum > 0f) offAcc / wSum else 0f
        }

        // 基础量：算术平均
        val base = FloatArray(4)
        seeds.forEach { s -> for (i in 0 until 4) base[i] += s.base.getOrElse(i) { if (i < 3) 1f else 0f } / n }
        base[0] = base[0].coerceIn(0.6f, 1.5f)
        base[1] = base[1].coerceIn(0.6f, 1.5f)
        base[2] = base[2].coerceIn(0.85f, 1.15f)
        base[3] = base[3].coerceIn(0f, 0.25f)

        return Theta(wb, matrix, curve, satByHue, hueShift, base)
    }

    /**
     * v2 聚合：每个 seed 已经是「纯风格」（伪配对拟合，色温已被中和），
     * 所以等权平均即可，不再需要 hueShare 加权与色彩矩阵正交化。
     */
    private fun aggregateV2(raw: List<RefSeed>): Theta {
        // 旧存档 / 旧分享码里的 seed 是 24×3 桶、16 点曲线（v2 扩容前）。若直接按新下标取值，
        // satByHue[0..143] 的后半段会全部落到默认值 1f —— 饱和信息被静默丢掉一半。
        // 统一在入口重采样到当前常量（尺寸已一致时原样返回，零开销）。
        val seeds = raw.map { it.resampledForV2() }
        val n = seeds.size
        val cp = CURVE_POINTS_V2

        val curve = FloatArray(cp) { k ->
            medianOf(FloatArray(n) { i -> seeds[i].toneCurve[k] })
        }
        if (n > 1) {
            var spread = 0f
            for (k in 0 until cp) {
                val vals = seeds.map { it.toneCurve[k] }
                spread += (vals.maxOrNull() ?: 0f) - (vals.minOrNull() ?: 0f)
            }
            if (spread / cp > 0.22f) {
                for (k in 0 until cp) {
                    val id = k / (cp - 1f)
                    curve[k] = id + (curve[k] - id) * 0.6f
                }
            }
        }
        var prev = 0f
        for (k in 0 until cp) {
            curve[k] = curve[k].coerceIn(prev, 1f)
            prev = curve[k]
        }
        val satByHue = FloatArray(HUE_BINS_V2 * SAT_BANDS) { i ->
            var acc = 0f
            seeds.forEach { acc += it.satByHue[i] }
            (acc / n).coerceIn(0.55f, 2.2f)
        }
        val hueShift = FloatArray(HUE_BINS_V2) { i ->
            var acc = 0f
            seeds.forEach { acc += it.hueShift[i] }
            (acc / n).coerceIn(-14f, 14f)
        }
        val bal = FloatArray(9) { i ->
            var acc = 0f
            seeds.forEach { acc += it.colorMatrix.getOrElse(i) { 0f } }
            (acc / n).coerceIn(-0.06f, 0.06f)
        }
        val sv = floatArrayOf(0f, 0f)
        seeds.forEach {
            sv[0] += it.hueShare.getOrElse(0) { 1f } / n
            sv[1] += it.hueShare.getOrElse(1) { 0f } / n
        }
        sv[0] = sv[0].coerceIn(0.70f, 1.30f)
        sv[1] = sv[1].coerceIn(-0.60f, 0.60f)

        // 联合残差网格：按各 seed 的置信度 w 加权平均（w 已含样本量收缩，
        // 所以单图直接取 w、多图取 max 作为最终置信，不再二次收缩）。
        // 尺寸不符（旧存档 / 旧分享码 / 未测量的 seed）整张跳过，不参与也不影响其它 seed。
        val residual = aggregateResidual(seeds)

        return Theta(
            wbGain = floatArrayOf(1f, 1f, 1f),
            colorMatrix = bal,
            toneCurve = curve,
            satByHue = satByHue,
            hueShift = hueShift,
            base = floatArrayOf(1f, 1f, 1f, FORMAT_V2),
            v2 = true,
            satLuma = sv,
            residual = residual
        )
    }

    /**
     * 多图残差聚合：逐格按 w 加权平均原始残差，置信取各图 w 的最大值。
     *
     * 覆盖率不足（参考图色彩过于单一）时整表清零——宁可退回参数骨架，也不要让
     * 少量覆盖格子在三线性插值里把未覆盖区域一起带偏。
     */
    private fun aggregateResidual(seeds: List<RefSeed>): FloatArray {
        val size = RES_SIZE
        val measured = seeds.filter { it.meas.size == size }
        if (measured.isEmpty()) return FloatArray(0)
        val out = FloatArray(size)
        val cells = size / RES_STRIDE
        var covered = 0
        for (ci in 0 until cells) {
            val base = ci * RES_STRIDE
            var wSum = 0f
            var accV = 0f
            var accH = 0f
            var accS = 0f
            var wMax = 0f
            for (s in measured) {
                val w = s.meas[base + 3]
                if (w <= 0f) continue
                accV += w * s.meas[base]
                accH += w * s.meas[base + 1]
                accS += w * s.meas[base + 2]
                wSum += w
                if (w > wMax) wMax = w
            }
            if (wSum <= 1e-4f) continue
            out[base] = (accV / wSum).coerceIn(-RES_MAX_DV, RES_MAX_DV)
            out[base + 1] = (accH / wSum).coerceIn(-RES_MAX_DH, RES_MAX_DH)
            out[base + 2] = (accS / wSum).coerceIn(-RES_MAX_DS, RES_MAX_DS)
            out[base + 3] = wMax
            if (wMax >= RES_MIN_CONF) covered++
        }
        if (covered.toFloat() / cells < RES_MIN_COVERAGE) return FloatArray(0)
        return out
    }

    /**
     * 烘焙并落盘到 filesDir/luts/[fileName]。
     * 先写临时文件再改名，避免中途被杀留下半个 LUT（AlexaLut 只尝试加载一次）。
     */
    fun bake(ctx: Context, seeds: List<RefSeed>, fileName: String): Boolean {
        val bytes = bakeBytes(seeds) ?: return false
        return saveBytes(ctx, bytes, fileName)
    }

    /** 落盘任意 LUT 字节（烘焙结果、强度混合结果都走这里）。 */
    fun saveBytes(ctx: Context, bytes: ByteArray, fileName: String): Boolean {
        return try {
            val dir = com.codex.leicaapp.AlexaLut.customDir(ctx)
            val target = File(dir, fileName)
            val tmp = File(dir, "$fileName.tmp")
            tmp.writeBytes(bytes)
            if (target.exists() && !target.delete()) return false
            if (!tmp.renameTo(target)) {
                // 个别文件系统不允许改名覆盖，退化为复制 + 删除
                target.writeBytes(bytes)
                tmp.delete()
            }
            true
        } catch (t: Throwable) {
            DiagLog.w("Custom", "LUT 落盘失败 $fileName: ${t.message}")
            false
        }
    }

    /** 读取 filesDir/luts/ 下的 LUT 字节（强度混合需要新旧两份）。 */
    fun readBytes(ctx: Context, fileName: String): ByteArray? {
        return try {
            val f = File(com.codex.leicaapp.AlexaLut.customDir(ctx), fileName)
            if (f.isFile) f.readBytes() else null
        } catch (t: Throwable) {
            null
        }
    }

    /**
     * 两个同网格 LUT 的 bin 层线性混合：`bin = (1-s)·a + s·b`。
     *
     * 三线性插值对 bin 值是线性的，所以这与「像素域按强度混合两个 LUT 的输出」完全等价。
     * 尺寸不同（理论上不会发生，版本全是 33³）时退回按强度取其一，避免产生错位数据。
     */
    fun blendBytes(a: ByteArray, b: ByteArray, strength: Float): ByteArray {
        val t = strength.coerceIn(0f, 1f)
        if (a.isEmpty() || a.size != b.size || a.size % 4 != 0) {
            return if (t >= 0.5f) b else a
        }
        val fa = ByteBuffer.wrap(a).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        val fb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        val out = ByteArray(a.size)
        val fo = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        val inv = 1f - t
        val n = fa.capacity()
        for (i in 0 until n) {
            fo.put(i, fa.get(i) * inv + fb.get(i) * t)
        }
        return out
    }

    /** 烘焙成 LUT 字节（不落盘）。 */
    fun bakeBytes(seeds: List<RefSeed>): ByteArray? {
        val theta = aggregateInternal(seeds) ?: return null
        return renderBytes(theta)
    }

    /**
     * 只做聚合、把 θ 存成快照（约 19 KB），**不生成 LUT 表**。
     *
     * M5 起版本与 LUT 文件解耦：历史版本的 LUT 会被回收，需要时按快照重烘。
     * 存 θ 而不是「池前缀」的原因——θ 是烘焙的全部输入，按它重烘的结果与当初
     * 逐字节一致；而池会被后续加图/淘汰重排，按前缀重聚合会悄悄变成另一个风格。
     */
    fun snapshotBytes(seeds: List<RefSeed>): ByteArray? {
        val theta = try {
            aggregateInternal(seeds)
        } catch (t: Throwable) {
            DiagLog.w("Custom", "聚合失败: ${t.message}")
            null
        } ?: return null
        return try {
            encodeTheta(theta)
        } catch (t: Throwable) {
            DiagLog.w("Custom", "θ 快照编码失败: ${t.message}")
            null
        }
    }

    /** 按 θ 快照重烘落盘（快照损坏/版本不符返回 false）。 */
    fun bakeFromSnapshotTo(ctx: Context, snapshot: ByteArray, fileName: String): Boolean {
        val theta = decodeTheta(snapshot) ?: return false
        return saveBytes(ctx, renderBytes(theta), fileName)
    }

    /** θ → 65³（或 33³）LUT 字节。 */
    private fun renderBytes(theta: Theta): ByteArray {
        val dim = if (theta.v2) DIM_V2 else DIM_V1
        val shift = dim * dim * dim
        val data = FloatArray(shift * 3)
        val inv = 1f / (dim - 1)
        val rgb = FloatArray(3)

        for (bi in 0 until dim) {
            val b = bi * inv
            for (gi in 0 until dim) {
                val g = gi * inv
                for (ri in 0 until dim) {
                    rgb[0] = ri * inv
                    rgb[1] = g
                    rgb[2] = b
                    applyTheta(rgb, theta)
                    val i = bi * dim * dim + gi * dim + ri
                    data[i] = rgb[0]
                    data[shift + i] = rgb[1]
                    data[shift * 2 + i] = rgb[2]
                }
            }
        }

        smooth(data, dim)
        // 空腔/端点保真：网格 8 个角必须仍是纯黑/纯白，平滑会把边界往里带
        restoreCorners(data, dim)

        val buf = ByteBuffer.allocate(data.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        data.forEach { buf.putFloat(it) }
        return buf.array()
    }

    // ---- θ 快照编解码（M5 懒烘）----

    /** 快照魔数（"CLS1"）；改聚合语义时换值，旧快照会被安全拒绝。 */
    private const val SNAP_MAGIC = 0x434C5331

    /** 单数组长度上限：防坏文件让 decode 申请超大内存（最大数组是 4608）。 */
    private const val SNAP_MAX_LEN = 65536

    private fun encodeTheta(t: Theta): ByteArray {
        val arrays = arrayOf(
            t.wbGain, t.colorMatrix, t.toneCurve, t.satByHue,
            t.hueShift, t.base, t.satLuma, t.residual
        )
        var size = 8
        arrays.forEach { size += 4 + it.size * 4 }
        val buf = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(SNAP_MAGIC)
        buf.putInt(if (t.v2) 1 else 0)
        arrays.forEach { a ->
            buf.putInt(a.size)
            a.forEach { buf.putFloat(it) }
        }
        return buf.array()
    }

    private fun decodeTheta(bytes: ByteArray): Theta? {
        if (bytes.size < 8) return null
        return try {
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            if (buf.int != SNAP_MAGIC) return null
            val flags = buf.int
            val arrays = Array(8) {
                val len = buf.int
                if (len < 0 || len > SNAP_MAX_LEN || buf.remaining() < len * 4) return null
                FloatArray(len) { buf.float }
            }
            if (arrays[0].size != 3 || arrays[1].size != 9 ||
                arrays[5].size != 4 || arrays[6].size != 2
            ) return null
            Theta(
                wbGain = arrays[0],
                colorMatrix = arrays[1],
                toneCurve = arrays[2],
                satByHue = arrays[3],
                hueShift = arrays[4],
                base = arrays[5],
                v2 = (flags and 1) != 0,
                satLuma = arrays[6],
                residual = arrays[7]
            )
        } catch (t: Throwable) {
            null
        }
    }

    /**
     * 批量应用 v2 变换（原地改 r/g/b），供 [RefAnalyzer] 的迭代回代使用。
     *
     * 直接复用 [applyThetaV2]，不在拟合侧另写一份变换 —— 拟合用哪套公式，
     * 烘焙就必须用哪套，否则"拟合误差最小"和"成片最像"是两件事。
     */
    fun applyV2Batch(r: FloatArray, g: FloatArray, b: FloatArray, t: Theta, n: Int) {
        val c = FloatArray(3)
        for (i in 0 until n) {
            c[0] = r[i]
            c[1] = g[i]
            c[2] = b[i]
            applyThetaV2(c, t)
            r[i] = c[0]
            g[i] = c[1]
            b[i] = c[2]
        }
    }

    // ---- T(x;θ) ----

    private fun applyTheta(c: FloatArray, t: Theta) {
        if (t.v2) {
            applyThetaV2(c, t)
            return
        }
        var r = c[0] * t.wbGain[0]
        var g = c[1] * t.wbGain[1]
        var b = c[2] * t.wbGain[2]

        // 色彩矩阵
        val m = t.colorMatrix
        val mr = m[0] * r + m[1] * g + m[2] * b
        val mg = m[3] * r + m[4] * g + m[5] * b
        val mb = m[6] * r + m[7] * g + m[8] * b
        r = mr.coerceIn(0f, 1f)
        g = mg.coerceIn(0f, 1f)
        b = mb.coerceIn(0f, 1f)

        // 亮度曲线（只动亮度、保留色相/饱和比例的比值法）
        val y = 0.299f * r + 0.587f * g + 0.114f * b
        val ty = evalCurve(t.toneCurve, y)
        if (y > 1e-4f) {
            val ratio = (ty / y).coerceIn(RATIO_MIN, RATIO_MAX)
            r *= ratio
            g *= ratio
            b *= ratio
        }

        // 分色相饱和增益 + 色相微移
        rgbToHsv(r, g, b)
        var h = hsvBuffer[0]
        var s = hsvBuffer[1]
        var v = hsvBuffer[2]
        // 近中性保护：SAT_EPS→NEUTRAL_FULL 之间线性渐入，低饱和像素（云、雾、灰墙）
        // 只吃到部分增益/色移，避免被染紫染青（L-P9 淡云紫漂的同类问题）。
        val neutral = ((s - SAT_EPS) / (NEUTRAL_FULL - SAT_EPS)).coerceIn(0f, 1f)
        if (neutral > 0f) {
            val hb = (h / 15f).toInt().coerceIn(0, HUE_BINS - 1)
            val band = when {
                s < 0.33f -> 0
                s < 0.66f -> 1
                else -> 2
            }
            s *= 1f + (t.satByHue[hb * SAT_BANDS + band] - 1f) * neutral
            h += t.hueShift[hb] * 15f * neutral   // hueShift 单位 = 1 个 bin = 15°
            if (h < 0f) h += 360f
            if (h >= 360f) h -= 360f
        }

        // 基础量：全局饱和 / 对比度 / gamma / 黑位抬升
        s *= t.base[0]
        v = (v - 0.5f) * t.base[1] + 0.5f
        v = v.coerceIn(0f, 1f).pow(t.base[2])
        v += t.base[3] * (1f - v) * (1f - v) * BLACK_LIFT_GAIN
        v = v.coerceIn(0f, 1f)

        hsvToRgb(h, s.coerceIn(0f, 1f), v)
        c[0] = hsvBuffer[0].coerceIn(0f, 1f)
        c[1] = hsvBuffer[1].coerceIn(0f, 1f)
        c[2] = hsvBuffer[2].coerceIn(0f, 1f)
    }

    /**
     * v2 变换：V 曲线 → 分色相饱和 → 色相旋转 → 饱和-亮度耦合 → 零均值分色调。
     * 与 PC 台 core.py 的 StyleModel.apply 逐行对齐（改动请两边同步）。
     */
    private fun applyThetaV2(c: FloatArray, t: Theta) {
        rgbToHsv(c[0].coerceIn(0f, 1f), c[1].coerceIn(0f, 1f), c[2].coerceIn(0f, 1f))
        var h = hsvBuffer[0]
        var s = hsvBuffer[1]
        var v = hsvBuffer[2]
        val s0 = s
        val h0 = h
        val v0 = v

        // 1) V 曲线：只动明度，保留色相/饱和
        v = evalCurve(t.toneCurve, v).coerceIn(0f, 1f)

        // 2)+3) 分色相 × 饱和档 S 增益 / 色相旋转。
        // 色相维度做**圆周线性插值**：原来按 15° 分档取常数，旋转量在档边界处跳变，
        // 会在 65³ 网格上留台阶（渐变扫过时显形）。插值后与曲线一样连续。
        val hp = h / 360f * HUE_BINS_V2
        val hi = hp.toInt().coerceIn(0, HUE_BINS_V2 - 1)
        val hj = (hi + 1) % HUE_BINS_V2
        val hf = (hp - hi).coerceIn(0f, 1f)

        val g = lerp(gainAt(t.satByHue, hi, s0), gainAt(t.satByHue, hj, s0), hf)
        val prot = ((s0 - 0.05f) / 0.18f).coerceIn(0f, 1f)
        s = (s0 * (1f + (g - 1f) * prot)).coerceIn(0f, 1f)

        // 色相旋转：单位「度」，低饱和不转（防淡云/灰墙染紫）
        val hueProt = ((s0 - 0.06f) / 0.24f).coerceIn(0f, 1f)
        val shift = lerp(t.hueShift[hi], t.hueShift[hj], hf)
        h = ((h + shift * hueProt) % 360f + 360f) % 360f

        // 4) 饱和-亮度耦合：S' = S·(a + b·(V-0.5))
        s = (s * (t.satLuma[0] + t.satLuma[1] * (v - 0.5f))).coerceIn(0f, 1f)

        hsvToRgb(h, s, v)
        var r = hsvBuffer[0]
        var gg = hsvBuffer[1]
        var bb = hsvBuffer[2]

        // 5) 零均值分色调（三段按亮度权重叠加）
        val lum = 0.299f * r + 0.587f * gg + 0.114f * bb
        val w0 = ((0.40f - lum) / 0.40f).coerceIn(0f, 1f)
        val w2 = ((lum - 0.60f) / 0.40f).coerceIn(0f, 1f)
        val w1 = (1f - w0 - w2).coerceIn(0f, 1f)
        val m = t.colorMatrix
        r += w0 * m[0] + w1 * m[3] + w2 * m[6]
        gg += w0 * m[1] + w1 * m[4] + w2 * m[7]
        bb += w0 * m[2] + w1 * m[5] + w2 * m[8]

        r = r.coerceIn(0f, 1f)
        gg = gg.coerceIn(0f, 1f)
        bb = bb.coerceIn(0f, 1f)

        // 6) 联合残差修正（方案 A）：把「骨架输出 → 参考图」的实测残差补回去
        if (t.residual.isEmpty()) {
            c[0] = r
            c[1] = gg
            c[2] = bb
        } else {
            applyResidual(r, gg, bb, v0, h0, s0, t.residual, c)
        }
    }

    /**
     * 联合残差修正：在最终 HSV 上叠加实测残差（方案 A 的「直接建表」）。
     *
     * 放在流水线最后是刻意的——残差本身就是「参数骨架整链输出 → 参考图」的差值，
     * 若在中间步骤施加会被后续算子二次变换，做不到「覆盖区等价于测量值」。
     */
    private fun applyResidual(
        r0: Float, g0: Float, b0: Float,
        v0: Float, h0: Float, s0: Float,
        res: FloatArray, out: FloatArray
    ) {
        val dv = residueAt(res, v0, h0, s0, 0)
        val dh = residueAt(res, v0, h0, s0, 1)
        val ds = residueAt(res, v0, h0, s0, 2)
        if (dv == 0f && dh == 0f && ds == 0f) {
            out[0] = r0
            out[1] = g0
            out[2] = b0
            return
        }
        rgbToHsv(r0, g0, b0)
        var h = hsvBuffer[0]
        var s = hsvBuffer[1]
        var v = hsvBuffer[2] + dv
        // 低饱和处色相/饱和不可靠（灰像素的 hue 是噪声），与色相旋转同一套渐入保护
        val reliable = ((s - 0.06f) / 0.24f).coerceIn(0f, 1f)
        if (reliable > 0f) {
            h += dh * reliable
            h = ((h % 360f) + 360f) % 360f
            if (s > 1e-4f) s *= 1f + ds * reliable
        }
        hsvToRgb(h, s.coerceIn(0f, 1f), v.coerceIn(0f, 1f))
        out[0] = hsvBuffer[0]
        out[1] = hsvBuffer[1]
        out[2] = hsvBuffer[2]
    }

    /**
     * 在联合残差网格上做三线性插值（V / 色相 / 饱和）。
     *
     * 三个维度的桶中心与统计侧同构：[resVBin] → (i+0.5)/RES_V_BINS、
     * [resHueBin] → (i+0.5)·360/HUE_BINS_V2、[RES_SAT_CENTERS]。
     * 桶中心处插值结果恰好等于该桶的统计值，桶间线性过渡。
     */
    private fun residueAt(res: FloatArray, v0: Float, h0: Float, s0: Float, ch: Int): Float {
        val pv = (v0.coerceIn(0f, 1f) * RES_V_BINS - 0.5f)
            .coerceIn(0f, (RES_V_BINS - 1).toFloat())
        val vi = pv.toInt()
        val vf = pv - vi
        val vj = if (vi + 1 < RES_V_BINS) vi + 1 else vi

        val nh = HUE_BINS_V2
        val rawH = (((h0 % 360f) + 360f) % 360f) / (360f / nh)
        val hl = kotlin.math.floor(rawH).toInt()
        val hf = rawH - hl
        val hi = ((hl % nh) + nh) % nh
        val hj = (hi + 1) % nh

        val c0 = RES_SAT_CENTERS[0]
        val c1 = RES_SAT_CENTERS[1]
        val c2 = RES_SAT_CENTERS[2]
        val s = s0.coerceIn(0f, 1f)
        val si: Int
        val sj: Int
        val sf: Float
        when {
            s <= c0 -> { si = 0; sj = 0; sf = 0f }
            s >= c2 -> { si = 2; sj = 2; sf = 0f }
            s < c1 -> { si = 0; sj = 1; sf = (s - c0) / (c1 - c0) }
            else -> { si = 1; sj = 2; sf = (s - c1) / (c2 - c1) }
        }

        val w00 = (1f - vf) * (1f - hf)
        val w01 = (1f - vf) * hf
        val w10 = vf * (1f - hf)
        val w11 = vf * hf
        val low = res[resIndex(vi, hi, si) + ch] * w00 +
            res[resIndex(vi, hj, si) + ch] * w01 +
            res[resIndex(vj, hi, si) + ch] * w10 +
            res[resIndex(vj, hj, si) + ch] * w11
        if (sf == 0f) return low
        val high = res[resIndex(vi, hi, sj) + ch] * w00 +
            res[resIndex(vi, hj, sj) + ch] * w01 +
            res[resIndex(vj, hi, sj) + ch] * w10 +
            res[resIndex(vj, hj, sj) + ch] * w11
        return low + (high - low) * sf
    }

    /** 三段饱和锚点线性插值（锚点 0.12 / 0.45 / 0.82，与 PC 台一致）。 */
    private fun gainAt(satByHue: FloatArray, hb: Int, s: Float): Float {
        val i = hb * SAT_BANDS
        val a0 = satByHue[i]
        val a1 = satByHue[i + 1]
        val a2 = satByHue[i + 2]
        val x0 = SAT_ANCHORS[0]
        val x1 = SAT_ANCHORS[1]
        val x2 = SAT_ANCHORS[2]
        return when {
            s <= x0 -> a0
            s <= x1 -> a0 + (s - x0) * (a1 - a0) / (x1 - x0)
            s <= x2 -> a1 + (s - x1) * (a2 - a1) / (x2 - x1)
            else -> a2
        }
    }

    /** HSV 三元组复用缓冲，避免每点分配对象（33³ × 3 次/点） */
    private val hsvBuffer = FloatArray(3)

    private fun rgbToHsv(r: Float, g: Float, b: Float) {
        val mx = maxOf(r, g, b)
        val mn = minOf(r, g, b)
        val d = mx - mn
        var h = 0f
        if (d > 1e-6f) {
            h = when (mx) {
                r -> ((g - b) / d % 6f + 6f) % 6f
                g -> (b - r) / d + 2f
                else -> (r - g) / d + 4f
            } * 60f
        }
        hsvBuffer[0] = h
        hsvBuffer[1] = if (mx <= 1e-6f) 0f else d / mx
        hsvBuffer[2] = mx
    }

    private fun hsvToRgb(h: Float, s: Float, v: Float) {
        if (s <= 1e-6f) {
            hsvBuffer[0] = v
            hsvBuffer[1] = v
            hsvBuffer[2] = v
            return
        }
        val c = v * s
        val hp = h / 60f
        val x = c * (1f - kotlin.math.abs(hp % 2f - 1f))
        val m = v - c
        var r = 0f
        var g = 0f
        var b = 0f
        when (hp.toInt().coerceIn(0, 5)) {
            0 -> { r = c; g = x }
            1 -> { r = x; g = c }
            2 -> { g = c; b = x }
            3 -> { g = x; b = c }
            4 -> { r = x; b = c }
            else -> { r = c; b = x }
        }
        hsvBuffer[0] = r + m
        hsvBuffer[1] = g + m
        hsvBuffer[2] = b + m
    }

    /** 分段线性查表（点数由数组长度决定：v1 16 点 / v2 33 点）；输入先钳到 [0,1] */
    private fun evalCurve(curve: FloatArray, x: Float): Float {
        val last = curve.size - 1
        if (last < 1) return x.coerceIn(0f, 1f)
        val pos = x.coerceIn(0f, 1f) * last
        val i = pos.toInt().coerceIn(0, last - 1)
        val f = pos - i
        return (curve[i] * (1f - f) + curve[i + 1] * f).coerceIn(0f, 1f)
    }

    private fun lerp(a: Float, b: Float, f: Float): Float = a + (b - a) * f

    private fun medianOf(vals: FloatArray): Float {
        if (vals.isEmpty()) return Float.NaN
        vals.sort()
        val m = vals.size / 2
        return if (vals.size % 2 == 1) vals[m] else (vals[m - 1] + vals[m]) * 0.5f
    }

    // ---- LUT 平滑 ----

    /**
     * 三轴各一次一维高斯（sigma=0.9，半径 2），等价于 3D 高斯但只做 3 次一维卷积。
     * 目的与 L-P9/L-M9 的 LUT 平滑一致：填掉网格稀疏区的不连续，
     * 否则渐变颜色扫过噪声格会显形为多边形硬边（散景伪影）。
     */
    private fun smooth(data: FloatArray, dim: Int) {
        // exp(-k²/2σ²) 归一化（σ=0.9）
        val kernel = floatArrayOf(0.0473f, 0.2551f, 0.3952f, 0.2551f, 0.0473f)
        val shift = dim * dim * dim
        val tmp = FloatArray(data.size)
        val strides = intArrayOf(1, dim, dim * dim)
        for (stride in strides) {
            for (plane in 0..2) {
                val off = plane * shift
                for (bi in 0 until dim) {
                    for (gi in 0 until dim) {
                        for (ri in 0 until dim) {
                            val coord = when (stride) {
                                1 -> ri
                                dim -> gi
                                else -> bi
                            }
                            val base = off + bi * dim * dim + gi * dim + ri
                            var acc = 0f
                            for (k in -2..2) {
                                val c = (coord + k).coerceIn(0, dim - 1)
                                acc += kernel[k + 2] * data[base + (c - coord) * stride]
                            }
                            tmp[base] = acc
                        }
                    }
                }
            }
            System.arraycopy(tmp, 0, data, 0, data.size)
        }
    }

    private fun restoreCorners(data: FloatArray, dim: Int) {
        val shift = dim * dim * dim
        val last = dim - 1
        val corners = IntArray(8)
        corners[0] = 0                                   // 0,0,0
        corners[1] = last                                // r 角
        corners[2] = last * dim                          // g 角
        corners[3] = last * dim * dim                    // b 角
        corners[4] = last + last * dim
        corners[5] = last + last * dim * dim
        corners[6] = last * dim + last * dim * dim
        corners[7] = last + last * dim + last * dim * dim
        for (i in corners) {
            val r = if (i % dim == last) 1f else 0f
            val g = if ((i / dim) % dim == last) 1f else 0f
            val b = if ((i / (dim * dim)) == last) 1f else 0f
            data[i] = r
            data[shift + i] = g
            data[shift * 2 + i] = b
        }
    }

    /** 一组互相近似正交的基向量（Gram-Schmidt）；数值退化时回落单位阵。 */
    private fun orthonormalize(m: FloatArray): FloatArray {
        val out = FloatArray(9)
        for (row in 0 until 3) {
            var v = floatArrayOf(m[row * 3], m[row * 3 + 1], m[row * 3 + 2])
            for (prev in 0 until row) {
                val dot = v[0] * out[prev * 3] + v[1] * out[prev * 3 + 1] + v[2] * out[prev * 3 + 2]
                v = floatArrayOf(
                    v[0] - dot * out[prev * 3],
                    v[1] - dot * out[prev * 3 + 1],
                    v[2] - dot * out[prev * 3 + 2]
                )
            }
            val len = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
            if (len < 1e-3f) {
                // 退化（多图色偏完全共线）：该行回单位阵，避免矩阵不可逆
                return floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
            }
            out[row * 3] = v[0] / len
            out[row * 3 + 1] = v[1] / len
            out[row * 3 + 2] = v[2] / len
        }
        return out
    }
}
