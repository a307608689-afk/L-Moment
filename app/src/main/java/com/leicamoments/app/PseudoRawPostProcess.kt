package com.leicamoments.app

import android.graphics.Bitmap
import java.util.Random
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * 伪 RAW 输出重建（高位深工作域专属，三个模块一次遍历完成）。
 *
 * 为什么需要：8-bit 源在 16 位域提亮后必然暴露三个缺陷，且都不是「位深不够」能解决的——
 * 1) **量化断层**：源 1 级 = 1/255，提亮 g 倍后空档变成 g/255，直方图出现 comb，视觉上是色带/台阶。
 * 2) **塑料感**：手机 ISP 的降噪把中频抹平，提亮后只剩大片平坦色块。
 * 3) **色边/彩色块**：4:2:0 色度在解码时被双线性上采样，与亮度边缘错位。
 *
 * 本模块对应 [M4]→[M6]→[M3] 三步（顺序固定，见 [apply]），只对「已成片」做重建，
 * **不增加任何信息量**（暗部真实级数、高光余量仍然无法恢复）。
 *
 * 硬约束：
 * - 只在 `rawLin16Level == RawToneMap.PSEUDO_LEVEL` 的伪 RAW 分支调用，绝不进入 RawToneMap 公共路径；
 *   真 RAW 有 16-bit 数据，套这些算子属于画蛇添足。
 * - 预览与导出必须共用本函数与同一组参数，否则会出现「预览一个样、导出另一个样」。
 * - 任何异常返回 -1 并保留原位图，绝不阻断导出。
 *
 * 内存：整图 `IntArray`（12.5MP ≈ 50MB）+ 两张网格（DS=4，各约 16MB），
 * 共用一次 getPixels/setPixels，不为每个模块各分配一份整图数组。
 */
internal object PseudoRawPostProcess {

    private const val TAG = "PSEUDO"

    // ---- 模块开关（A/B 与出问题时单独回退用；默认全开） ----
    const val M4_DETAIL = true
    const val M6_CHROMA = true
    const val M3_DITHER = true

    // ---- M4：引导滤波 detail 重建 ----
    /** 引导网格步长（px/格）。DS=4 时 12.5MP 约 0.79M 格 ≈ 16MB/份 */
    private const val GF_DS = 4
    /** 引导滤波 eps（"0..1 归一化亮度"口径，与 Lm9Post.GF_EPS 一致） */
    private const val GF_EPS = 0.008f
    /** 盒滤波半径 = min(w,h) × 该比例（全分辨率像素），再折算成格 */
    private const val GF_R_FRAC = 0.05f
    /** detail 增益上限：1.45×（再高会变成过度锐化、暗部噪点翻倍） */
    private const val DETAIL_K = 0.45f
    /** 噪声门控：|detail| < T0 不动（平坦区/纯噪点），> T1 全量增益 */
    private const val DETAIL_T0 = 1.2f
    private const val DETAIL_T1 = 6.0f
    /** 暗部保护：提亮后的暗部噪声大，增益从 0.45 缓升到 1.0 */
    private const val DETAIL_DARK_LO = 20f
    private const val DETAIL_DARK_HI = 70f

    // ---- M6：亮度引导色度去振铃 ----
    /** 色度网格步长。DS=4 + R=4 ≈ 36px 等效窗口（与 ChromaClean 的 DS=2/R=9 同量级，内存省一半） */
    private const val CH_DS = 4
    private const val CH_R = 4
    /** 色度细节保留比例：0.55 = 压彩边但保留 55% 色度层次（ChromaClean 同口径） */
    private const val CH_KEEP = 0.55f
    /** 饱和度门控（0..255 级，Cb/Cr 平面值） */
    private const val CH_SAT_T0 = 14f
    private const val CH_SAT_T1 = 45f
    /** 亮度梯度门控（|detail|，0..255 级）：只在真边缘收敛，平坦区不动 */
    private const val CH_GRAD_T0 = 2f
    private const val CH_GRAD_T1 = 10f

    // ---- M3：蓝噪声抖动 + 亮度自适应颗粒 ----
    /** 全局抖动强度（× 提亮倍率 g × 亮度权重） */
    private const val DITHER_STRENGTH = 0.5f
    /** 蓝噪声 tile 边长（2 的幂，便于位运算取模） */
    private const val TILE = 64
    /** 亮度权重：暗部 1.0 → 高光 0.15（高光没有空档） */
    private const val AMP_LO = 150f
    private const val AMP_HI = 240f
    private const val AMP_HIGH_FLOOR = 0.15f

    /**
     * 档位 → 提亮倍率 g（决定 M3 抖动振幅）。
     *
     * 与 [ToneCurve] 同源：曝光在线性域 `2^EV`（EV_STEP=0.15），亮度是中间调 gamma
     * `2^(br*BR_STEP)`，阴影是软趾加性 lift（SH_STEP=0.04，暗部最大约 +0.8）。
     * 只取正方向：压暗不产生「提亮空档」，抖了反而变噪点。
     *
     * @return g ∈ [1, 6]；1 表示无需抖动
     */
    fun estimateGain(bright: Int, exposure: Int, shadows: Int): Float {
        val br = bright.coerceIn(ToneCurve.MIN_LEVEL, ToneCurve.MAX_LEVEL)
        val ev = exposure.coerceIn(ToneCurve.MIN_LEVEL, ToneCurve.MAX_LEVEL)
        val sh = max(0, shadows.coerceIn(ToneCurve.MIN_LEVEL, ToneCurve.MAX_LEVEL))
        val expPart = 2f.pow(ev * 0.15f + br * 0.035f)
        val liftPart = 1f + sh * 0.08f
        return (expPart * liftPart).coerceIn(1f, 6f)
    }

    /**
     * 执行重建。管线顺序固定为 M4 → M6 → M3（顺序错了会互相打架：
     * detail 增益会把抖动放大的颗粒当成细节继续放大）。
     *
     * @param bmp      可变的 ARGB_8888 位图（导出期是全尺寸，预览期是 1600px）
     * @param gain     [estimateGain] 的结果：提亮倍率，只影响 M3 振幅
     * @param strength 总强度（0=不处理，1=标称；留作真机调参入口）
     * @return 耗时 ms；未启用/尺寸过小/异常返回 -1（调用方无需处理，原位图保持可用）
     */
    fun apply(bmp: Bitmap, gain: Float, strength: Float = 1f): Long {
        if (bmp.isRecycled || !bmp.isMutable) return -1
        val w = bmp.width
        val h = bmp.height
        if (w < 32 || h < 32) return -1
        val s = strength.coerceIn(0f, 1f)
        if (s <= 0f) return -1

        val useM4 = M4_DETAIL
        // M6 的梯度门控依赖 M4 的 detail；M4 关闭时退化为「仅饱和度门控」
        val useM6 = M6_CHROMA
        // g≈1（没提亮）时抖动只会引入噪点，直接跳过
        val useM3 = M3_DITHER && gain > 1.02f
        if (!useM4 && !useM6 && !useM3) return -1

        val t0 = System.currentTimeMillis()
        return try {
            val px = IntArray(w * h)
            bmp.getPixels(px, 0, w, 0, 0, w, h)

            // ---------- M4 预计算：亮度网格 + 自引导滤波 → base ----------
            var baseGrid: FloatArray? = null
            var sw4 = 0
            var sh4 = 0
            if (useM4) {
                sw4 = (w + GF_DS - 1) / GF_DS
                sh4 = (h + GF_DS - 1) / GF_DS
                val iG = FloatArray(sw4 * sh4)
                val cnt = IntArray(sw4 * sh4)
                for (y in 0 until h) {
                    val gy = y / GF_DS
                    val rowBase = gy * sw4
                    val off = y * w
                    for (x in 0 until w) {
                        val p = px[off + x]
                        val r = (p shr 16) and 0xFF
                        val g = (p shr 8) and 0xFF
                        val b = p and 0xFF
                        // 归一化到 0..1：这样 GF_EPS=0.008 的量纲才与 Lm9Post 一致
                        val lum = (0.299f * r + 0.587f * g + 0.114f * b) * (1f / 255f)
                        val gi = rowBase + x / GF_DS
                        iG[gi] += lum
                        cnt[gi]++
                    }
                }
                // 注意：`FloatArray[i] /= IntArray[i]` 的复合赋值在 Kotlin 里不成立
                // （异类型 div 的复合赋值会报 "No set method providing array access"），
                // 必须显式转 Float 并写成直接赋值。
                for (i in iG.indices) {
                    val n = cnt[i]
                    if (n > 0) {
                        iG[i] = iG[i] / n.toFloat()
                    }
                }

                val rG = max(1, (min(w, h) * GF_R_FRAC / GF_DS).toInt())
                val i2 = FloatArray(sw4 * sh4) { iG[it] * iG[it] }
                val mI = boxBlur(iG, sw4, sh4, rG)
                val varI = boxBlur(i2, sw4, sh4, rG)
                for (i in varI.indices) {
                    val v = varI[i] - mI[i] * mI[i]
                    varI[i] = if (v < 0f) 0f else v
                }
                // a = var / (var + eps)；b = mI - a·mI
                val aG = FloatArray(sw4 * sh4) { varI[it] / (varI[it] + GF_EPS) }
                val bG = FloatArray(sw4 * sh4) { mI[it] * (1f - aG[it]) }
                val aa = boxBlur(aG, sw4, sh4, rG)
                val bb = boxBlur(bG, sw4, sh4, rG)
                // base = a·I + b（网格域），上采样时再乘回 255
                baseGrid = FloatArray(sw4 * sh4) { aa[it] * iG[it] + bb[it] }
            }

            // ---------- M6 预计算：Cb/Cr 低频网格 ----------
            var crL: FloatArray? = null
            var cbL: FloatArray? = null
            var sw6 = 0
            var sh6 = 0
            if (useM6) {
                sw6 = (w + CH_DS - 1) / CH_DS
                sh6 = (h + CH_DS - 1) / CH_DS
                val crS = FloatArray(sw6 * sh6)
                val cbS = FloatArray(sw6 * sh6)
                val cnt = IntArray(sw6 * sh6)
                for (y in 0 until h) {
                    val gy = y / CH_DS
                    val rowBase = gy * sw6
                    val off = y * w
                    for (x in 0 until w) {
                        val p = px[off + x]
                        val r = (p shr 16) and 0xFF
                        val g = (p shr 8) and 0xFF
                        val b = p and 0xFF
                        val lum = 0.299f * r + 0.587f * g + 0.114f * b
                        val gi = rowBase + x / CH_DS
                        crS[gi] += (r - lum) / 1.402f
                        cbS[gi] += (b - lum) / 1.772f
                        cnt[gi]++
                    }
                }
                for (i in crS.indices) {
                    if (cnt[i] > 0) {
                        val n = cnt[i].toFloat()
                        crS[i] /= n
                        cbS[i] /= n
                    }
                }
                // 双程盒滤波 = 三角窗，等效 2·(2R+1)·DS ≈ 36px
                crL = boxBlur(boxBlur(crS, sw6, sh6, CH_R), sw6, sh6, CH_R)
                cbL = boxBlur(boxBlur(cbS, sw6, sh6, CH_R), sw6, sh6, CH_R)
            }

            val tile = if (useM3) noiseTile else null
            val inv4 = 1f / GF_DS
            val inv6 = 1f / CH_DS

            // ---------- 单遍：M4 上采样 → M6 收敛 → M3 抖动 ----------
            for (y in 0 until h) {
                val off = y * w
                var gy40 = 0
                var gy41 = 0
                var wy4 = 0f
                if (useM4) {
                    val fy = (y + 0.5f) * inv4 - 0.5f
                    gy40 = floorInt(fy, sh4 - 1)
                    gy41 = min(gy40 + 1, sh4 - 1)
                    wy4 = max(fy - gy40, 0f)
                }
                var gy60 = 0
                var gy61 = 0
                var wy6 = 0f
                if (useM6) {
                    val fy = (y + 0.5f) * inv6 - 0.5f
                    gy60 = floorInt(fy, sh6 - 1)
                    gy61 = min(gy60 + 1, sh6 - 1)
                    wy6 = max(fy - gy60, 0f)
                }

                for (x in 0 until w) {
                    val p = px[off + x]
                    val a = (p shr 24) and 0xFF
                    var r = ((p shr 16) and 0xFF).toFloat()
                    var g = ((p shr 8) and 0xFF).toFloat()
                    var b = (p and 0xFF).toFloat()
                    var lum = 0.299f * r + 0.587f * g + 0.114f * b
                    var detail = 0f

                    // M4：base/detail 分离 + 噪声门控增益（低对比不放大，防噪点与条纹被抬）
                    if (useM4) {
                        val base = bilerp(baseGrid!!, sw4, gy40, gy41, wy4, x, inv4) * 255f
                        detail = lum - base
                        val ad = abs(detail)
                        val gate = smoothstep(DETAIL_T0, DETAIL_T1, ad)
                        val darkGuard =
                            0.45f + 0.55f * smoothstep(DETAIL_DARK_LO, DETAIL_DARK_HI, lum)
                        val k = DETAIL_K * gate * darkGuard * s
                        val lum2 = base + detail * (1f + k)
                        if (lum > 1e-3f) {
                            val sc = lum2 / lum
                            r *= sc
                            g *= sc
                            b *= sc
                        } else {
                            r = lum2
                            g = lum2
                            b = lum2
                        }
                        lum = lum2
                    }

                    // M6：色度向低频收敛，只在「高饱和 + 真边缘」区生效（防色渗、防新色块）
                    if (useM6) {
                        val yL = 0.299f * r + 0.587f * g + 0.114f * b
                        val cr = (r - yL) / 1.402f
                        val cb = (b - yL) / 1.772f
                        val crLo = bilerp(crL!!, sw6, gy60, gy61, wy6, x, inv6)
                        val cbLo = bilerp(cbL!!, sw6, gy60, gy61, wy6, x, inv6)
                        val satGate = smoothstep(CH_SAT_T0, CH_SAT_T1, max(abs(cr), abs(cb)))
                        // M4 关闭时没有 detail，退化为仅饱和度门控
                        val gradGate = if (useM4) smoothstep(CH_GRAD_T0, CH_GRAD_T1, abs(detail)) else 0f
                        val gate = if (useM4) satGate * gradGate else satGate
                        val keep = 1f - (1f - CH_KEEP) * gate * s
                        val cr2 = crLo + (cr - crLo) * keep
                        val cb2 = cbLo + (cb - cbLo) * keep
                        r = yL + 1.402f * cr2
                        b = yL + 1.772f * cb2
                        g = yL - 0.7141f * cr2 - 0.3441f * cb2
                        lum = yL
                    }

                    // M3：按提亮倍率缩放的蓝噪声抖动（三通道同值 = 亮度颗粒，不产生彩色沙点）
                    if (tile != null) {
                        val n = tile[((y and (TILE - 1)) shl 6) or (x and (TILE - 1))]
                        val wgt = AMP_HIGH_FLOOR +
                            (1f - AMP_HIGH_FLOOR) * (1f - smoothstep(AMP_LO, AMP_HI, lum))
                        val d = n * DITHER_STRENGTH * gain * wgt * s
                        r += d
                        g += d
                        b += d
                    }

                    px[off + x] = (a shl 24) or (clamp255(r) shl 16) or (clamp255(g) shl 8) or clamp255(b)
                }
            }

            bmp.setPixels(px, 0, w, 0, 0, w, h)
            val ms = System.currentTimeMillis() - t0
            DiagLog.i(
                TAG,
                "重建 ${w}x$h g=${String.format(java.util.Locale.US, "%.2f", gain)} " +
                    "M4=$useM4 M6=$useM6 M3=$useM3 耗时 ${ms}ms"
            )
            ms
        } catch (t: Throwable) {
            // 含 OOM：重建是纯增益环节，失败必须保留原图继续导出
            DiagLog.e(TAG, "重建失败（跳过，不影响导出）", t)
            -1
        }
    }

    // ------------------------------------------------------------------
    // 工具：可分离滑动盒滤波（O(1)/px，边界钳位，与 Lm9Post.boxBlur 同口径）
    // ------------------------------------------------------------------

    private fun boxBlur(src: FloatArray, w: Int, h: Int, r: Int): FloatArray {
        if (r <= 0) return src.copyOf()
        val tmp = FloatArray(src.size)
        val out = FloatArray(src.size)
        val win = 2 * r + 1
        val inv = 1f / win
        // 行
        for (y in 0 until h) {
            val row = y * w
            var sum = 0f
            for (i in -r..r) sum += src[row + i.coerceIn(0, w - 1)]
            for (x in 0 until w) {
                tmp[row + x] = sum * inv
                sum += src[row + (x + r + 1).coerceIn(0, w - 1)] -
                    src[row + (x - r).coerceIn(0, w - 1)]
            }
        }
        // 列
        for (x in 0 until w) {
            var sum = 0f
            for (i in -r..r) sum += tmp[i.coerceIn(0, h - 1) * w + x]
            for (y in 0 until h) {
                out[y * w + x] = sum * inv
                sum += tmp[(y + r + 1).coerceIn(0, h - 1) * w + x] -
                    tmp[(y - r).coerceIn(0, h - 1) * w + x]
            }
        }
        return out
    }

    /** 网格双线性取场：(y+0.5)·inv-0.5 的取样口径与 Lm9Post / ChromaClean 一致 */
    private fun bilerp(
        g: FloatArray, sw: Int,
        y0: Int, y1: Int, wy: Float,
        x: Int, invDs: Float
    ): Float {
        val fx = (x + 0.5f) * invDs - 0.5f
        val gx0 = floorInt(fx, sw - 1)
        val gx1 = min(gx0 + 1, sw - 1)
        val wx = max(fx - gx0, 0f)
        val r0 = y0 * sw
        val r1 = y1 * sw
        return (1f - wx) * (1f - wy) * g[r0 + gx0] + wx * (1f - wy) * g[r0 + gx1] +
            (1f - wx) * wy * g[r1 + gx0] + wx * wy * g[r1 + gx1]
    }

    private fun floorInt(v: Float, max: Int): Int {
        val i = floor(v.toDouble()).toInt()
        return if (i < 0) 0 else min(i, max)
    }

    private fun clamp255(v: Float): Int {
        val i = (v + 0.5f).toInt()
        return if (i < 0) 0 else if (i > 255) 255 else i
    }

    private fun smoothstep(a: Float, b: Float, x: Float): Float {
        if (b <= a) return if (x >= b) 1f else 0f
        val t = ((x - a) / (b - a)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    // ------------------------------------------------------------------
    // 蓝噪声 tile（固定种子：预览与导出用同一份，统计一致）
    // ------------------------------------------------------------------

    /**
     * 白噪声去掉 5×5 环绕低频 → 频谱向高频倾斜，视觉上接近蓝噪声。
     *
     * 为什么不用真正的 void-and-cluster：那是 O(N²) 级迭代，启动时算 4096 点会带来
     * 明显首帧卡顿，而这里的抖动只需「不像沙点」即可；若真机觉得颗粒偏白，再升级算法。
     * 固定种子保证每次启动、预览与导出都得到同一张 tile。
     */
    private val noiseTile: FloatArray by lazy { buildNoiseTile(TILE) }

    private fun buildNoiseTile(n: Int): FloatArray {
        val rnd = Random(0x5EED2026L)
        val raw = FloatArray(n * n) { rnd.nextFloat() * 2f - 1f }
        val r = 2
        val low = FloatArray(n * n)
        val area = (2 * r + 1) * (2 * r + 1)
        for (y in 0 until n) {
            for (x in 0 until n) {
                var s = 0f
                for (dy in -r..r) {
                    val yy = ((y + dy) % n + n) % n
                    for (dx in -r..r) {
                        val xx = ((x + dx) % n + n) % n
                        s += raw[yy * n + xx]
                    }
                }
                low[y * n + x] = s / area
            }
        }
        var sum2 = 0.0
        for (i in raw.indices) {
            raw[i] -= low[i]
            sum2 += raw[i].toDouble() * raw[i]
        }
        val rms = sqrt(sum2 / raw.size).toFloat().coerceAtLeast(1e-6f)
        // 归一到 RMS≈0.6（平均幅度约半级），极值夹到 ±1
        val k = 0.6f / rms
        for (i in raw.indices) raw[i] = (raw[i] * k).coerceIn(-1f, 1f)
        return raw
    }
}
