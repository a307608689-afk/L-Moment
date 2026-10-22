package com.leicamoments.app

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

/**
 * 参考图 → [RefSeed]（v2「相对风格拟合」）。
 *
 * 旧版（v1）直接把参考图的通道均值当成白平衡：一张暖调成片会被解释成 4500K 光源，
 * 套到任意图上都会整体变黄——「参考图的色偏」和「光源的色偏」被混为一谈。
 *
 * v2 改法：参考图是成品、没有原片，于是**伪造原片**：
 * 1) 伪输入 `N(I)`：用低饱和/非极亮极暗像素估光源色 + 亮度中位归一到 0.45；
 * 2) 把 `(N(I) → I)` 当逐像素配对，拟合风格模型 `T`。
 * 因为 N 已经把色温/曝光中和掉，拟合出的 T 只剩风格本身（染色方向、饱和增益、影调）。
 * 灰轴不动（光照中性处 T 输出也中性）→ 套到别的图上不会引入全局色偏。
 *
 * 模型（参数可解释，共 139 值）：
 * - V 曲线 64 桶 → 重采样成 33 点（RefSeed.toneCurve）
 * - 色相旋转 24 值（度）
 * - 分色相 × 饱和档 S 增益 72 值
 * - 饱和-亮度耦合 2 值（S' = S·(a + b·(V-0.5))）
 * - 零均值三段分色调 9 值（shadow/mid/high × RGB）
 *
 * 与 [CustomLutBaker.applyThetaV2] 逐行对齐（改动请两边同步）。
 */
object RefAnalyzer {

    /**
     * 统计分辨率长边。
     *
     * 640 时一张 12MP 参考图只用了 0.3% 的像素，分色相桶的中位数全靠几百个样本撑着，
     * 噪点直接变成饱和增益的抖动。
     *
     * 896（约 60 万像素）是"样本够稳 × 内存不炸"的折中点：拟合期间同时存活的
     * 约 22 个 float 数组 ≈ 53 MB，加解码位图与 65³ LUT 输出约 62 MB，
     * 128 MB heap 的低端机也能过；再往上（1024）会到 70 MB+，风险大于收益。
     */
    private const val STAT_DIM = 896

    /**
     * 以下三个常量直接引用烘焙侧定义：拟合产出的 seed 会被 [CustomLutBaker.applyThetaV2]
     * 按这三个尺寸解释，两边必须严格相等，否则是静默错色（数组越界或错位），不写死数字。
     */
    private const val HUE_BINS = CustomLutBaker.HUE_BINS_V2
    private const val SAT_BANDS = CustomLutBaker.SAT_BANDS
    private const val CURVE_POINTS = CustomLutBaker.CURVE_POINTS_V2

    /** 一个色相桶的度数宽度 */
    private const val HUE_DEG = 360f / HUE_BINS

    /**
     * 色相 → 桶号（就近归桶）。
     *
     * 必须是「就近」而不是 `floor(h/Δ)`：`applyThetaV2` 的两个插值节点在 `i·Δ`，
     * 359.9° 距离节点 0° 只有 0.1°、距离节点 352.5° 有 7.4°，插值几乎全取桶 0。
     * 若统计时按 `floor` 把它记进桶 47，两边就对不上（桶 47 的估计被套到 0° 附近）。
     */
    private fun hueBinOf(h: Float): Int {
        val b = ((h / HUE_DEG) + 0.5f).toInt()
        return ((b % HUE_BINS) + HUE_BINS) % HUE_BINS
    }

    private const val V_BINS = 64

    /** hueShare 载荷固定 24×3：只用于分享码与 v1 聚合权重，不随 v2 扩容 */
    private const val SHARE_BINS = 24

    /** 联合回代轮数：顺序拟合出的 θ 作为初值，再用前向残差修正 */
    private const val REFINE_ROUNDS = 3

    /** 反馈阻尼（1 = 直接用估计值替换，容易过冲；0.6 实测两轮收敛） */
    private const val REFINE_DAMP = 0.6f

    /** 收缩先验强度：桶内样本 k 少时，估计值向全局均值收缩（等价经验贝叶斯） */
    private const val SHRINK_K0 = 240f

    /**
     * 联合残差网格的单格样本量收缩强度：k=300 时置信 0.5，k=2000 时约 0.87。
     * 与上面的 [SHRINK_K0] 分开是因为残差格比色相桶稀疏得多（8×48×3 格）。
     */
    private const val RES_SHRINK_K = 300f

    /** 饱和度分段边界，与 PC 台 SAT_EDGES 一致（左闭右开） */
    private const val SAT_EDGE_1 = 0.25f
    private const val SAT_EDGE_2 = 0.65f

    /** 低于该饱和度的像素不参与色相/饱和统计 */
    private const val SAT_EPS = 0.06f

    /** 伪输入估光：权重满值的饱和度上界，与此上界的比值做 1.5 次幂 */
    private const val NEUTRAL_SAT = 0.26f

    private const val LUMA_R = 0.299f
    private const val LUMA_G = 0.587f
    private const val LUMA_B = 0.114f

    // ---- 网图前置门槛 ----
    //
    // 网图（客户发来的成片）的失败模式和用户自己拍的图完全不同：可能是截图带白边、
    // 可能是二次压缩的色块、可能已经叠过滤镜。这些图烘出来的 LUT 会整体偏色，
    // 而用户只会觉得「算法不行」。宁可提前拒绝并说清原因，也不静默产出坏表。

    /** 参考图像素下限：低于此值伪配对样本不足，分色相桶全靠个位数样本 */
    private const val MIN_PIXELS = 120_000

    /**
     * 硬截断（三通道全 ≥250 / 全 ≤5）占比拒绝线。
     *
     * 截断只损失对应亮度段的桶样本（64 桶的两端），中间桶由未截断像素撑起，
     * 曲线另有单调钳制与 smooth1d(2.4)。30% 就硬拒会误杀大量夜景/剪影/浓色成片；
     * 55% 之后影调段才可能真的无样本，此时拒绝比烘出坏表更诚实。
     */
    private const val CLIP_REJECT = 0.55f

    /** 硬截断占比警戒线：与拒绝线保持一档间隔，通过但提示结果可能偏硬 */
    private const val CLIP_WARN = 0.35f

    /** 单一色相占比警戒线：色彩复刻范围会被限制在参考图覆盖的色域内 */
    private const val MONO_WARN = 0.65f

    /** 上下边缘近白像素占比警戒线：疑似白边/水印/截图边框 */
    private const val EDGE_WHITE_WARN = 0.85f

    /** 最近一次 [analyze] 的拒绝原因（UI 直接展示，不再让用户猜为什么失败） */
    @Volatile
    var lastReject: String? = null
        private set

    /** 最近一次 [analyze] 通过但有风险项（成功返回 seed 时才有值） */
    @Volatile
    var lastWarning: String? = null
        private set

    fun analyze(ctx: Context, uri: Uri): RefSeed? {
        val bmp = ImageUtils.decode(ctx, uri, STAT_DIM) ?: return null
        return try {
            analyze(bmp)
        } finally {
            bmp.recycle()
        }
    }

    fun analyze(bmp: Bitmap): RefSeed? {
        lastReject = null
        lastWarning = null
        val w = bmp.width
        val h = bmp.height
        val n = w * h
        if (n <= 0) {
            lastReject = "参考图无法读取"
            return null
        }
        // 分辨率门槛放在解码/统计之前：样本不足时拟合出的桶全靠个位数像素撑着
        if (n < MIN_PIXELS) {
            lastReject = "参考图分辨率过低（${w}×${h}），换一张更大的成片"
            return null
        }

        val px = IntArray(n)
        bmp.getPixels(px, 0, w, 0, 0, w, h)

        val gate = qualityGate(px, w, h)
        if (gate.reject != null) {
            lastReject = gate.reject
            DiagLog.w("Custom", "参考图不合格：${gate.reject}")
            return null
        }
        lastWarning = gate.warning

        // 参考图（拟合目标）与伪输入分开存：伪输入是原地改动的一批数组
        val sr = FloatArray(n)
        val sg = FloatArray(n)
        val sb = FloatArray(n)
        val tr = FloatArray(n)
        val tg = FloatArray(n)
        val tb = FloatArray(n)
        for (i in 0 until n) {
            val p = px[i]
            val r = ((p shr 16) and 0xFF) / 255f
            val g = ((p shr 8) and 0xFF) / 255f
            val b = (p and 0xFF) / 255f
            sr[i] = r
            sg[i] = g
            sb[i] = b
            tr[i] = r
            tg[i] = g
            tb[i] = b
        }

        val frac = neutralize(sr, sg, sb, n)
        // 中性像素太少 → 光源估计不可信（整幅纯色场景会把主体色当成光照色）。
        // 但 neutralize 的 trust = frac/0.06：frac<0.03 时白平衡增益本就只施加不足一半，
        // 属于天然「弱中和」。因此只在近乎无中性像素时才拒绝，0.5%~3% 放行并提示，
        // 避免把夜景/剪影/浓色风光一刀切掉。
        if (frac < 0.005f) {
            val msg = "参考图色彩过于单一（中性像素仅 ${(frac * 100f).toInt()}%），" +
                "换一张带白墙 / 云 / 灰地面的成片"
            lastReject = msg
            DiagLog.w("Custom", msg)
            return null
        }
        if (frac < 0.03f) {
            val msg = "参考图中性像素仅 ${(frac * 1000f).toInt() / 10f}%，白平衡按弱中和处理，风格可能偏淡。"
            lastWarning = if (lastWarning == null) msg else lastWarning + msg
        }
        // M4：先算出感知哈希挂到 seed 上（去重 / 断点续跑用），算不出也不影响拟合
        val fp = fingerprintOf(px, w, h)
        return fit(sr, sg, sb, tr, tg, tb, n).also { it.fingerprint = fp }
    }

    /**
     * 64 位感知哈希：9×8 网格平均灰度 + 行内梯度比较（dHash 变体）。
     *
     * 只用来回答「这是不是同一张图」：同一文件重编码、不同尺寸导出都稳定；
     * 不做内容相似检索，所以不追求更强的不变性。0 表示无法计算。
     */
    fun fingerprintOf(px: IntArray, w: Int, h: Int): Long {
        if (w <= 0 || h <= 0 || px.size < w * h) return 0L
        val cols = 9
        val rows = 8
        val g = FloatArray(rows * cols)
        for (cy in 0 until rows) {
            val y0 = cy * h / rows
            val y1 = (((cy + 1) * h) / rows).coerceAtLeast(y0 + 1).coerceAtMost(h)
            for (cx in 0 until cols) {
                val x0 = cx * w / cols
                val x1 = (((cx + 1) * w) / cols).coerceAtLeast(x0 + 1).coerceAtMost(w)
                var sum = 0.0
                var cnt = 0
                for (y in y0 until y1) {
                    var idx = y * w + x0
                    for (x in x0 until x1) {
                        val p = px[idx]
                        sum += 0.299 * ((p shr 16) and 0xFF) + 0.587 * ((p shr 8) and 0xFF) + 0.114 * (p and 0xFF)
                        cnt++
                        idx++
                    }
                }
                g[cy * cols + cx] = if (cnt > 0) (sum / cnt).toFloat() else 0f
            }
        }
        var bits = 0L
        for (cy in 0 until rows) {
            for (cx in 0 until cols - 1) {
                if (g[cy * cols + cx] > g[cy * cols + cx + 1]) bits = bits or (1L shl (cy * (cols - 1) + cx))
            }
        }
        return bits
    }

    /** 从已解码位图算哈希（调用方负责 recycle）。 */
    fun fingerprintOf(bmp: Bitmap): Long {
        val w = bmp.width
        val h = bmp.height
        if (w <= 0 || h <= 0) return 0L
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        return fingerprintOf(px, w, h)
    }

    /**
     * 直接从 Uri 算哈希：断点续跑要**在分析之前**判断「这张是不是已经算过」。
     * 小图解码（128px）足够，失败返回 0。
     */
    fun fingerprintOf(ctx: Context, uri: Uri): Long {
        val bmp = try {
            ImageUtils.decode(ctx, uri, 128)
        } catch (t: Throwable) {
            null
        } ?: return 0L
        return try {
            fingerprintOf(bmp)
        } finally {
            bmp.recycle()
        }
    }

    /** 门槛判定结果：reject 非空即拒绝，warning 非空是通过但要提醒的风险项 */
    private class Gate(val reject: String?, val warning: String?)

    /**
     * 网图质量门槛：单次遍历统计硬截断 / 色相集中度 / 上下边缘近白。
     *
     * 只做能明确归因的检查：宁可少拦，也不要拦下正常成片——拦错比放过更伤体验。
     * 这里只看像素统计，不解码 EXIF（截图与二次压缩的成片本来就没有可信 EXIF）。
     */
    private fun qualityGate(px: IntArray, w: Int, h: Int): Gate {
        val n = px.size
        // 近白判定阈值：三通道都 ≥235 才算白边/水印底，避免把高光天空判成白边
        val edgeRows = (h * 0.05f).toInt().coerceAtLeast(1)
        val hueHist = IntArray(HUE_BINS)
        var clip = 0
        var satPixels = 0
        var edgePixels = 0
        var edgeWhite = 0
        var y = 0
        while (y < h) {
            val inEdge = y < edgeRows || y >= h - edgeRows
            val rowBase = y * w
            var x = 0
            while (x < w) {
                val p = px[rowBase + x]
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                // 只统计「真正丢层次」的截断：三通道同时到顶（过曝成白）或同时见底（死黑）。
                // 单通道到顶不算——蓝天 B=255、红花 R=255 都是正常高饱和，算进去会误杀风景照。
                if ((r >= 250 && g >= 250 && b >= 250) || (r <= 5 && g <= 5 && b <= 5)) clip++
                if (inEdge) {
                    edgePixels++
                    if (r >= 235 && g >= 235 && b >= 235) edgeWhite++
                }
                val mx = maxOf(r, g, b)
                val mn = minOf(r, g, b)
                if (mx > 12 && mx < 250 && (mx - mn) / mx.toFloat() > SAT_EPS) {
                    satPixels++
                    val hue: Float = when (mx) {
                        r -> 60f * ((g - b) / (mx - mn).toFloat())
                        g -> 60f * (2f + (b - r) / (mx - mn).toFloat())
                        else -> 60f * (4f + (r - g) / (mx - mn).toFloat())
                    }
                    hueHist[hueBinOf(if (hue < 0f) hue + 360f else hue)]++
                }
                x++
            }
            y++
        }

        val clipRatio = clip.toFloat() / n
        if (clipRatio > CLIP_REJECT) {
            return Gate(
                "参考图有 ${(clipRatio * 100f).toInt()}% 的像素已经过曝或死黑，" +
                    "被裁掉的层次烘不出来，换一张曝光更完整的成片",
                null
            )
        }

        val warn = StringBuilder()
        if (clipRatio > CLIP_WARN) {
            warn.append("参考图有 ${(clipRatio * 100f).toInt()}% 像素过曝/死黑，暗部高光可能偏硬。")
        }
        if (satPixels > n * 0.05f) {
            var peak = 0
            for (v in hueHist) if (v > peak) peak = v
            val share = peak.toFloat() / satPixels
            if (share > MONO_WARN) {
                warn.append(
                    "参考图 ${(share * 100f).toInt()}% 的彩色像素是同一色相，" +
                        "其余颜色的复刻主要靠模型推断。"
                )
            }
        }
        if (edgePixels > 0 && edgeWhite.toFloat() / edgePixels > EDGE_WHITE_WARN) {
            warn.append("参考图上下边缘大片纯白，疑似截图/白边，若效果不对请换原图。")
        }
        return Gate(null, if (warn.isEmpty()) null else warn.toString())
    }

    /**
     * 伪输入 N(I)：估光源色并中和，再把亮度中位拉到 0.45。原地修改 sr/sg/sb。
     *
     * @return 中性像素占比（0..1）。占比过低说明整幅图是单色/浓色场景，
     *         光源估计不可信 —— 由调用方决定是否剔除（PC 台剔 <2%）。
     */
    private fun neutralize(r: FloatArray, g: FloatArray, b: FloatArray, n: Int): Float {
        var wSum = 0.0
        var rSum = 0.0
        var gSum = 0.0
        var bSum = 0.0
        for (i in 0 until n) {
            val ri = r[i]
            val gi = g[i]
            val bi = b[i]
            val mx = maxOf(ri, gi, bi)
            val mn = minOf(ri, gi, bi)
            val s = if (mx > 1e-6f) (mx - mn) / mx else 0f
            var w = ((NEUTRAL_SAT - s) / NEUTRAL_SAT).coerceIn(0f, 1f)
            w = w * w * kotlin.math.sqrt(w)          // ^1.5
            w *= ((mx - 0.03f) / 0.10f).coerceIn(0f, 1f)
            w *= ((0.97f - mx) / 0.10f).coerceIn(0f, 1f)
            if (w > 0f) {
                wSum += w.toDouble()
                rSum += (ri * w).toDouble()
                gSum += (gi * w).toDouble()
                bSum += (bi * w).toDouble()
            }
        }

        var frac = (wSum / n).toFloat()
        var gainR = 1f
        var gainG = 1f
        var gainB = 1f
        if (wSum < n * 0.004) {
            // 几乎全是彩色像素：不敢估光源，只做曝光归一
            frac = 0f
        } else {
            val mr = (rSum / wSum).toFloat()
            val mg = (gSum / wSum).toFloat()
            val mb = (bSum / wSum).toFloat()
            val mean = (mr + mg + mb) / 3f
            gainR = (mean / mr.coerceAtLeast(1e-4f)).coerceIn(0.55f, 1.80f)
            gainG = (mean / mg.coerceAtLeast(1e-4f)).coerceIn(0.55f, 1.80f)
            gainB = (mean / mb.coerceAtLeast(1e-4f)).coerceIn(0.55f, 1.80f)
            // 置信度收缩：中性像素越少越不敢动白平衡（否则把单色场景当光照色）
            val trust = (frac / 0.06f).coerceIn(0f, 1f)
            gainR = 1f + (gainR - 1f) * trust
            gainG = 1f + (gainG - 1f) * trust
            gainB = 1f + (gainB - 1f) * trust
        }

        // 亮度中位数在白平衡增益之后统计（V = max 通道，与 PC 台同序）
        val hist = IntArray(256)
        for (i in 0 until n) {
            val rr = (r[i] * gainR).coerceAtMost(1f)
            val gg = (g[i] * gainG).coerceAtMost(1f)
            val bb = (b[i] * gainB).coerceAtMost(1f)
            val mx = maxOf(rr, gg, bb)
            hist[(mx * 255f).toInt().coerceIn(0, 255)]++
        }
        val median = medianOfHist(hist, n)
        val scale = (0.45f / median.coerceAtLeast(1e-3f)).coerceIn(0.5f, 2.0f)
        for (i in 0 until n) {
            r[i] = (r[i] * gainR * scale).coerceIn(0f, 1f)
            g[i] = (g[i] * gainG * scale).coerceIn(0f, 1f)
            b[i] = (b[i] * gainB * scale).coerceIn(0f, 1f)
        }
        return frac
    }

    private fun medianOfHist(hist: IntArray, n: Int): Float {
        val half = n / 2
        var acc = 0
        for (i in 0 until 256) {
            acc += hist[i]
            if (acc >= half) return i / 255f
        }
        return 1f
    }

    /**
     * 伪配对拟合：`(伪输入 → 参考图)` 的逐像素映射。
     *
     * 分桶阈值按像素总数缩放（PC 台用 900px 长边 + 固定阈值，这里统一按比例），
     * 保证 [STAT_DIM] 统计图的桶密度与 PC 台同量级。
     *
     * 流程：顺序独立拟合（曲线 → 色相 → 饱和 → 饱和-亮度 → 分色调）拿到初值，
     * 再交给 [refine] 用前向残差回代，消掉"下游在已偏掉的中间图上继续拟合"的误差。
     */
    private fun fit(
        sr: FloatArray, sg: FloatArray, sb: FloatArray,
        tr: FloatArray, tg: FloatArray, tb: FloatArray,
        n: Int
    ): RefSeed {
        val sh = FloatArray(n)
        val ss = FloatArray(n)
        val sv = FloatArray(n)
        val th = FloatArray(n)
        val ts = FloatArray(n)
        val tv = FloatArray(n)
        for (i in 0 until n) {
            hsvOf(sr[i], sg[i], sb[i])
            sh[i] = hsvBuf[0]
            ss[i] = hsvBuf[1]
            sv[i] = hsvBuf[2]
            hsvOf(tr[i], tg[i], tb[i])
            th[i] = hsvBuf[0]
            ts[i] = hsvBuf[1]
            tv[i] = hsvBuf[2]
        }

        // 桶内样本数下限：只为"完全空桶"兜底；样本偏少不再直接丢弃，交给下面的收缩先验
        val minBin = maxOf(40, n / 2000)
        val minSatBin = maxOf(30, n / 4000)
        val minSvBin = maxOf(80, n / 750)

        // ---------- 1) V 曲线：按伪输入亮度分桶取目标中位数 ----------
        val vCnt = IntArray(V_BINS)
        val hbOf = IntArray(n)
        val sbOf = IntArray(n)
        for (i in 0 until n) {
            val k = (sv[i] * V_BINS).toInt()
            vCnt[if (k >= V_BINS) V_BINS - 1 else k]++
            hbOf[i] = hueBinOf(sh[i])
            sbOf[i] = when {
                ss[i] < SAT_EDGE_1 -> 0
                ss[i] < SAT_EDGE_2 -> 1
                else -> 2
            }
        }
        val vVals = Array(V_BINS) { FloatArray(vCnt[it]) }
        val vPos = IntArray(V_BINS)
        for (i in 0 until n) {
            val k = (sv[i] * V_BINS).toInt()
            val b = if (k >= V_BINS) V_BINS - 1 else k
            vVals[b][vPos[b]++] = tv[i]
        }
        var vc = FloatArray(V_BINS) { k ->
            val id = (k + 0.5f) / V_BINS
            if (vVals[k].size > minBin) {
                // 样本越少越不敢偏离恒等（暗部几个像素的噪声不该定义整条曲线）
                val w = vVals[k].size / (vVals[k].size + SHRINK_K0)
                id + (median(vVals[k]) - id) * w
            } else {
                id
            }
        }
        // sigma 随桶数缩放：64 桶时 1.2 等于没平滑（相邻桶差一个像素就能跳）
        vc = smooth1d(vc, 2.4f)
        var prevV = 0f
        for (k in 0 until V_BINS) {
            vc[k] = vc[k].coerceIn(prevV, 1f)
            prevV = vc[k]
        }
        // 64 桶 → 66 点（0 + 桶中心 + 1），与 PC 台 v_x / v_curve 同构
        val vx = FloatArray(V_BINS + 2)
        val vy = FloatArray(V_BINS + 2)
        vy[0] = 0f
        vy[V_BINS + 1] = 1f
        vx[V_BINS + 1] = 1f
        for (k in 0 until V_BINS) {
            vx[k + 1] = (k + 0.5f) / V_BINS
            vy[k + 1] = vc[k]
        }
        // RefSeed 只存 33 点（applyThetaV2 线性插值），重采样并锁端点
        val curve = FloatArray(CURVE_POINTS) { k ->
            if (k == 0) 0f
            else if (k == CURVE_POINTS - 1) 1f
            else interp(k / (CURVE_POINTS - 1f), vx, vy).coerceIn(0f, 1f)
        }

        // 退路：只留影调与全局饱和比，不碰色相/白平衡（见 CustomLutBaker.CUSTOM_BAKE_V2）
        if (!CustomLutBaker.CUSTOM_BAKE_V2) return conservative(curve, ss, ts, n)

        // ---------- 2) 色相旋转：同一内容的真实旋转，按饱和度加权圆周均值 ----------
        val sinAcc = DoubleArray(HUE_BINS)
        val cosAcc = DoubleArray(HUE_BINS)
        val wAcc = DoubleArray(HUE_BINS)
        val hueCnt = IntArray(HUE_BINS)
        for (i in 0 until n) {
            val hb = hbOf[i]
            hueCnt[hb]++
            val w = ss[i].toDouble()
            if (w <= 1e-4) continue
            val d = (((th[i] - sh[i] + 540f) % 360f) - 180f).toDouble()
            val rad = d * PI / 180.0
            sinAcc[hb] += w * sin(rad)
            cosAcc[hb] += w * cos(rad)
            wAcc[hb] += w
        }
        var hShift = FloatArray(HUE_BINS) { hb ->
            if (wAcc[hb] > 0.0) {
                val raw = (atan2(sinAcc[hb], cosAcc[hb]) * 180.0 / PI).toFloat()
                // 样本越少越向 0 收缩：色相旋转一旦被噪声带偏就是全图偏色
                raw * (hueCnt[hb] / (hueCnt[hb] + SHRINK_K0))
            } else {
                0f
            }
        }
        hShift = smoothCirc(hShift, 2f)
        for (hb in 0 until HUE_BINS) hShift[hb] = hShift[hb].coerceIn(-14f, 14f)

        return finishFit(
            sr, sg, sb, tr, tg, tb, th, ts, tv, sh, ss, sv, n,
            hbOf, sbOf, curve, vx, vy, hShift, minSatBin, minSvBin
        )
    }

    /** v1 格式的保守 seed：影调曲线 + 全局饱和比，其余恒等（见 CUSTOM_BAKE_V2 注释） */
    private fun conservative(
        curve: FloatArray, ss: FloatArray, ts: FloatArray, n: Int
    ): RefSeed {
        val cap = n / 4 + 1
        val buf = FloatArray(cap)
        var cnt = 0
        var i = 0
        while (i < n) {
            if (ss[i] > 0.08f && ts[i] > 0.02f && cnt < cap) {
                buf[cnt++] = (ts[i] / ss[i].coerceAtLeast(1e-6f)).coerceIn(0.4f, 2.6f)
            }
            i += 4
        }
        val g = if (cnt > 0) median(buf.copyOf(cnt)).coerceIn(0.60f, 1.60f) else 1f
        // 退路是 v1 格式：桶数必须留在 24×3，否则旧版读档会按 24 桶索引读到错位数据
        val satByHue = FloatArray(SHARE_BINS * SAT_BANDS) { g }
        return RefSeed(
            addedAt = System.currentTimeMillis(),
            wbGain = floatArrayOf(1f, 1f, 1f),
            toneCurve = resampleCurve(curve, 16),
            colorMatrix = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f),
            satByHue = satByHue,
            hueShift = FloatArray(SHARE_BINS),
            hueShare = FloatArray(SHARE_BINS * SAT_BANDS) { 1f },
            base = floatArrayOf(1f, 1f, 1f, 0f)
        )
    }

    private fun finishFit(
        sr: FloatArray, sg: FloatArray, sb: FloatArray,
        tr: FloatArray, tg: FloatArray, tb: FloatArray,
        th: FloatArray, ts: FloatArray, tv: FloatArray,
        sh: FloatArray, ss: FloatArray, sv: FloatArray,
        n: Int,
        hbOf: IntArray, sbOf: IntArray,
        curve: FloatArray, vx: FloatArray, vy: FloatArray,
        hShift: FloatArray, minSatBin: Int, minSvBin: Int
    ): RefSeed {
        // 3) 分色相 × 饱和档 S 增益
        val sGain = fitSatGain(ss, ts, hbOf, sbOf, minSatBin)

        // 先用已拟合的曲线/色相/饱和（sv 尚未定，等价于 [1,0]）算一遍 pre 图，
        // 供 4) 饱和-亮度耦合 与 5) 分色调残差共用
        val preR = FloatArray(n)
        val preG = FloatArray(n)
        val preB = FloatArray(n)
        val preS = FloatArray(n)
        val preV = FloatArray(n)
        applyPre(sr, sg, sb, n, vx, vy, hShift, sGain, preR, preG, preB, preS, preV)

        // 4) 饱和-亮度耦合：S' = S·(a + b·(V-0.5))
        val svCoef = fitSv(preS, preV, ts, n, minSvBin)

        // 5) 零均值三段分色调（只取色度分量）
        val bal = fitBalance(preR, preG, preB, tr, tg, tb, ss, n)

        // 以上是「顺序独立拟合」的初值；下面用前向残差反复回代（L1 联合优化）。
        val init = CustomLutBaker.Theta(
            wbGain = floatArrayOf(1f, 1f, 1f),
            colorMatrix = bal,
            toneCurve = curve,
            satByHue = sGain,
            hueShift = hShift,
            base = floatArrayOf(1f, 1f, 1f, CustomLutBaker.FORMAT_V2),
            v2 = true,
            satLuma = svCoef
        )
        val tRefine = System.currentTimeMillis()
        val theta = refine(sr, sg, sb, tr, tg, tb, th, ts, tv, n, init)
        DiagLog.i(
            "Custom",
            "联合回代 $REFINE_ROUNDS 轮 ${System.currentTimeMillis() - tRefine}ms（n=$n）"
        )

        // v2 字段语义映射（RefSeed 结构不变，靠 base[3] 标记格式）：
        //   wbGain      → 恒 1（色温已被伪输入中和，不再需要）
        //   colorMatrix → 9 值分色调
        //   hueShare[0..1] → 饱和-亮度耦合 a,b
        val hueShare = FloatArray(SHARE_BINS * SAT_BANDS)
        hueShare[0] = theta.satLuma[0]
        hueShare[1] = theta.satLuma[1]

        // 方案 A：把「参数骨架输出 → 参考图」的残余差值留成联合残差网格（测量证据）。
        val meas = measureResidual(sh, ss, sv, tr, tg, tb, th, ts, tv, n, theta)
        DiagLog.i("Custom", "联合残差 " + measCoverage(meas) + " 格有测量")

        return RefSeed(
            addedAt = System.currentTimeMillis(),
            wbGain = floatArrayOf(1f, 1f, 1f),
            toneCurve = theta.toneCurve,
            colorMatrix = theta.colorMatrix,
            satByHue = theta.satByHue,
            hueShift = theta.hueShift,
            hueShare = hueShare,
            base = floatArrayOf(1f, 1f, 1f, CustomLutBaker.FORMAT_V2),
            meas = meas
        )
    }

    private fun measCoverage(meas: FloatArray): Int {
        var c = 0
        var i = 0
        while (i < meas.size) {
            if (meas[i + 3] > 0f) c++
            i += CustomLutBaker.RES_STRIDE
        }
        return c
    }

    /**
     * 联合残差测量（方案 A「直接建表」的测量证据）。
     *
     * 用最终 θ 做一次全图前向，按 **(输入 V × 输入色相 × 输入饱和)** 联合分箱统计
     * 「输出 → 目标」的残差：ΔV、ΔH(度)、ΔS(比例)。
     *
     * 参数模型把影调/色相/饱和当成三个解耦的函数，表达不了交叉项（比如同一亮度下
     * 暖色与冷色的饱和增益方向相反）；残差网格补的就是这部分。分箱与
     * [CustomLutBaker.resVBin] / [resHueBin] / [resSatBin] 严格同构，查表侧三线性
     * 插值到同一组桶中心，因此**有测量的色彩域烘焙结果等价于直接用测量值建表**；
     * 没有测量的色彩域残差为 0，退回参数骨架（覆盖度分档是连续的，不硬切）。
     *
     * 置信度 w = k/(k+[RES_SHRINK_K])：只测到几十个像素的格子几乎不生效，
     * 这是防「参考图里一小块偶然色彩把整片色域带偏」的关键。
     */
    private fun measureResidual(
        sh: FloatArray, ss: FloatArray, sv: FloatArray,
        tr: FloatArray, tg: FloatArray, tb: FloatArray,
        th: FloatArray, ts: FloatArray, tv: FloatArray,
        n: Int,
        theta: CustomLutBaker.Theta
    ): FloatArray {
        val fr = tr.copyOf()
        val fg = tg.copyOf()
        val fb = tb.copyOf()
        // 用参考图当输出缓冲只是复用内存，下面立刻被前向结果覆盖
        CustomLutBaker.applyV2Batch(fr, fg, fb, theta, n)

        val stride = CustomLutBaker.RES_STRIDE
        val cells = CustomLutBaker.RES_SIZE / stride
        val accV = FloatArray(cells)
        val accH = FloatArray(cells)
        val accS = FloatArray(cells)
        val cnt = IntArray(cells)

        for (i in 0 until n) {
            val s0 = ss[i]
            // 灰像素的色相/饱和是噪声，只由骨架的亮度曲线负责，不进残差网格
            if (s0 <= SAT_EPS) continue
            val cell = (CustomLutBaker.resVBin(sv[i]) * HUE_BINS +
                CustomLutBaker.resHueBin(sh[i])) * SAT_BANDS +
                CustomLutBaker.resSatBin(s0)

            hsvOf(fr[i], fg[i], fb[i])
            val oh = hsvBuf[0]
            val os = hsvBuf[1]
            val ov = hsvBuf[2]

            var dh = (th[i] - oh) % 360f
            if (dh > 180f) dh -= 360f
            if (dh < -180f) dh += 360f

            accV[cell] += tv[i] - ov
            accH[cell] += dh
            accS[cell] += if (ts[i] > 0.02f && os > 0.02f) ts[i] / os - 1f else 0f
            cnt[cell]++
        }

        val out = FloatArray(CustomLutBaker.RES_SIZE)
        for (c in 0 until cells) {
            val k = cnt[c]
            if (k <= 0) continue
            val base = c * stride
            out[base] = (accV[c] / k).coerceIn(-CustomLutBaker.RES_MAX_DV, CustomLutBaker.RES_MAX_DV)
            out[base + 1] = (accH[c] / k).coerceIn(-CustomLutBaker.RES_MAX_DH, CustomLutBaker.RES_MAX_DH)
            out[base + 2] = (accS[c] / k).coerceIn(-CustomLutBaker.RES_MAX_DS, CustomLutBaker.RES_MAX_DS)
            out[base + 3] = k / (k + RES_SHRINK_K)
        }
        return out
    }

    /**
     * L1 联合回代：把顺序拟合的结果当初值，用**前向输出的残差**反复修正 θ。
     *
     * 原实现的每一步都是独立拟合：V 曲线定死后，色相/饱和/分色调都在"已经偏掉的中间图"
     * 上继续拟合，误差不会回代到上游，最终谁也补不回谁的错（典型症状是暗部曲线与
     * 阴影分色调互相打架）。
     *
     * 这里每轮做一次全图前向（与烘焙同一个 [CustomLutBaker.applyV2Batch]），
     * 再按**输出**分桶取残差中位数，带 [REFINE_DAMP] 阻尼加到当前参数上。
     * 这是反馈式修正（不动点迭代），不是重拟合，所以不会把初值带跑，也不需要雅可比。
     */
    private fun refine(
        sr: FloatArray, sg: FloatArray, sb: FloatArray,
        tr: FloatArray, tg: FloatArray, tb: FloatArray,
        th: FloatArray, ts: FloatArray, tv: FloatArray,
        n: Int,
        init: CustomLutBaker.Theta
    ): CustomLutBaker.Theta {
        var theta = init
        repeat(REFINE_ROUNDS) {
            theta = refineOnce(sr, sg, sb, tr, tg, tb, th, ts, tv, n, theta)
        }
        return theta
    }

    /** 一轮回代：前向 → 残差分桶 → 阻尼修正。 */
    private fun refineOnce(
        sr: FloatArray, sg: FloatArray, sb: FloatArray,
        tr: FloatArray, tg: FloatArray, tb: FloatArray,
        th: FloatArray, ts: FloatArray, tv: FloatArray,
        n: Int,
        theta: CustomLutBaker.Theta
    ): CustomLutBaker.Theta {
        val fr = sr.copyOf()
        val fg = sg.copyOf()
        val fb = sb.copyOf()
        CustomLutBaker.applyV2Batch(fr, fg, fb, theta, n)

        val cp = CURVE_POINTS
        val cCnt = IntArray(cp)
        val cSum = FloatArray(cp)

        val hAcc = FloatArray(HUE_BINS)
        val hW = FloatArray(HUE_BINS)

        val sAcc = FloatArray(HUE_BINS * SAT_BANDS)
        val sCnt = IntArray(HUE_BINS * SAT_BANDS)

        val balSum = FloatArray(9)
        val balW = FloatArray(3)

        for (i in 0 until n) {
            // 输入侧：曲线/色相/饱和的自变量都是它
            hsvOf(sr[i], sg[i], sb[i])
            val v0 = hsvBuf[2]
            val s0 = hsvBuf[1]

            // 输出侧：残差要按"输出落在哪"分桶（反馈控制，比按输入分桶稳）
            hsvOf(fr[i], fg[i], fb[i])
            val h1 = hsvBuf[0]
            val s1 = hsvBuf[1]
            val v1 = hsvBuf[2]

            // 1) 曲线：按输入 V 落点累加「目标 V − 当前输出 V」
            val k = (v0 * (cp - 1)).toInt().coerceIn(0, cp - 1)
            cSum[k] += tv[i] - v1
            cCnt[k]++

            if (s0 > 0.08f) {
                val hb = hueBinOf(h1)
                // 2) 色相：最短弧残差，按饱和度加权（灰像素的色相没有意义）
                var d = th[i] - h1
                if (d > 180f) d -= 360f
                if (d < -180f) d += 360f
                hAcc[hb] += d * s0
                hW[hb] += s0

                // 3) 饱和：按输出饱和档累加目标/当前的比值
                if (ts[i] > 0.02f) {
                    val band = when {
                        s1 < 0.33f -> 0
                        s1 < 0.66f -> 1
                        else -> 2
                    }
                    val b = hb * SAT_BANDS + band
                    sAcc[b] += ts[i] / s1.coerceAtLeast(1e-4f)
                    sCnt[b]++
                }
            }

            // 4) 分色调：残差只取色度分量，按输出亮度三段加权
            val dr = tr[i] - fr[i]
            val dg = tg[i] - fg[i]
            val db = tb[i] - fb[i]
            val dl = LUMA_R * dr + LUMA_G * dg + LUMA_B * db
            val lum = LUMA_R * fr[i] + LUMA_G * fg[i] + LUMA_B * fb[i]
            val w0 = ((0.40f - lum) / 0.40f).coerceIn(0f, 1f)
            val w2 = ((lum - 0.60f) / 0.40f).coerceIn(0f, 1f)
            val w1 = (1f - w0 - w2).coerceIn(0f, 1f)
            balSum[0] += (dr - dl) * w0
            balSum[1] += (dg - dl) * w0
            balSum[2] += (db - dl) * w0
            balSum[3] += (dr - dl) * w1
            balSum[4] += (dg - dl) * w1
            balSum[5] += (db - dl) * w1
            balSum[6] += (dr - dl) * w2
            balSum[7] += (dg - dl) * w2
            balSum[8] += (db - dl) * w2
            balW[0] += w0
            balW[1] += w1
            balW[2] += w2
        }

        // ---- 曲线：加残差、锁端点、强制单调 ----
        val curve = theta.toneCurve.copyOf()
        for (k in 0 until cp) {
            if (cCnt[k] > 0) curve[k] += REFINE_DAMP * (cSum[k] / cCnt[k])
        }
        curve[0] = 0f
        curve[cp - 1] = 1f
        var prev = 0f
        for (k in 0 until cp) {
            curve[k] = curve[k].coerceIn(prev, 1f)
            prev = curve[k]
        }

        // ---- 色相：加残差、平滑、限幅 ----
        var shift = theta.hueShift
        val dShift = FloatArray(HUE_BINS) { hb ->
            if (hW[hb] > 1e-3f) REFINE_DAMP * (hAcc[hb] / hW[hb]) else 0f
        }
        val smShift = smoothCirc(
            FloatArray(HUE_BINS) { hb -> shift[hb] + dShift[hb] }, 1.5f
        )
        shift = FloatArray(HUE_BINS) { hb -> smShift[hb].coerceIn(-14f, 14f) }

        // ---- 饱和：乘性修正、向 1 收缩、限幅 ----
        val gain = theta.satByHue.copyOf()
        for (b in gain.indices) {
            if (sCnt[b] <= 0) continue
            val ratio = (sAcc[b] / sCnt[b]).coerceIn(0.5f, 2.0f)
            val w = sCnt[b] / (sCnt[b] + SHRINK_K0)
            gain[b] = (gain[b] * (1f + REFINE_DAMP * (ratio - 1f) * w)).coerceIn(0.55f, 2.2f)
        }

        // ---- 分色调：加残差、限幅、再拉回零均值 ----
        val bal = theta.colorMatrix.copyOf()
        for (k in 0 until 3) {
            if (balW[k] < 1f) continue
            for (ch in 0 until 3) {
                bal[k * 3 + ch] = (bal[k * 3 + ch] + REFINE_DAMP * balSum[k * 3 + ch] / balW[k])
                    .coerceIn(-0.06f, 0.06f)
            }
        }
        for (ch in 0 until 3) {
            var acc = 0f
            var sw = 0f
            for (k in 0 until 3) {
                val w = maxOf(balW[k], 1f)
                acc += bal[k * 3 + ch] * w
                sw += w
            }
            val mean = if (sw > 0f) acc / sw else 0f
            for (k in 0 until 3) bal[k * 3 + ch] -= mean
        }

        return CustomLutBaker.Theta(
            wbGain = theta.wbGain,
            colorMatrix = bal,
            toneCurve = curve,
            satByHue = gain,
            hueShift = shift,
            base = theta.base,
            v2 = true,
            satLuma = theta.satLuma
        )
    }

    /**
     * 分色相 × 饱和档 S 增益：桶内 `S_tgt / S_src` 的中位数。
     * 缺样本桶按列（同一饱和档）线性插值，再做圆周平滑与档间斜率限幅。
     */
    private fun fitSatGain(
        ss: FloatArray, ts: FloatArray,
        hbOf: IntArray, sbOf: IntArray, minBin: Int
    ): FloatArray {
        val bins = HUE_BINS * SAT_BANDS
        val cnt = IntArray(bins)
        for (i in ss.indices) {
            if (ss[i] <= 0.08f) continue
            cnt[hbOf[i] * SAT_BANDS + sbOf[i]]++
        }
        val vals = Array(bins) { FloatArray(cnt[it]) }
        val pos = IntArray(bins)
        for (i in ss.indices) {
            if (ss[i] <= 0.08f) continue
            val b = hbOf[i] * SAT_BANDS + sbOf[i]
            vals[b][pos[b]++] = ts[i] / ss[i].coerceAtLeast(1e-6f)
        }
        val gain = FloatArray(bins)
        for (b in 0 until bins) {
            var g = Float.NaN
            if (vals[b].size > minBin) {
                val m = median(vals[b])
                if (m > 0.4f && m < 2.6f) {
                    // 收缩到 1：几百个样本的桶不该和上万样本的桶说话一样大声
                    val k = vals[b].size.toFloat()
                    g = 1f + (m - 1f) * (k / (k + SHRINK_K0))
                }
            }
            gain[b] = g
        }

        for (band in 0 until SAT_BANDS) {
            var valid = 0
            for (hb in 0 until HUE_BINS) if (!gain[hb * SAT_BANDS + band].isNaN()) valid++
            if (valid == 0) {
                for (hb in 0 until HUE_BINS) gain[hb * SAT_BANDS + band] = 1f
                continue
            }
            for (hb in 0 until HUE_BINS) {
                val idx = hb * SAT_BANDS + band
                if (!gain[idx].isNaN()) continue
                var l = hb - 1
                while (l >= 0 && gain[l * SAT_BANDS + band].isNaN()) l--
                var r = hb + 1
                while (r < HUE_BINS && gain[r * SAT_BANDS + band].isNaN()) r++
                gain[idx] = when {
                    l < 0 -> gain[r * SAT_BANDS + band]
                    r >= HUE_BINS -> gain[l * SAT_BANDS + band]
                    else -> {
                        val vl = gain[l * SAT_BANDS + band]
                        val vr = gain[r * SAT_BANDS + band]
                        vl + (vr - vl) * (hb - l).toFloat() / (r - l).toFloat()
                    }
                }
            }
        }

        val out = FloatArray(bins)
        val col = FloatArray(HUE_BINS)
        for (band in 0 until SAT_BANDS) {
            for (hb in 0 until HUE_BINS) col[hb] = gain[hb * SAT_BANDS + band]
            val sm = smoothCirc(col, 1.5f)
            for (hb in 0 until HUE_BINS) {
                out[hb * SAT_BANDS + band] = sm[hb].coerceIn(0.55f, 2.2f)
            }
        }
        // 档间斜率限幅（3 轮，与 PC 台 _clamp_slope 一致）
        repeat(3) {
            for (hb in 0 until HUE_BINS) {
                val i0 = hb * SAT_BANDS
                val d01 = (out[i0 + 1] - out[i0]).coerceIn(-0.45f, 0.45f)
                val d12 = (out[i0 + 2] - out[i0 + 1]).coerceIn(-0.45f, 0.45f)
                out[i0 + 1] = out[i0] + d01
                out[i0 + 2] = out[i0 + 1] + d12
            }
        }
        return out
    }

    /**
     * 用「已拟合的曲线/色相/饱和」作用于伪输入，得到中间图 pre。
     * 与 [CustomLutBaker.applyThetaV2] 的第 1~3 步严格同序（sv 此时未定，等价 [1,0]）。
     */
    private fun applyPre(
        sr: FloatArray, sg: FloatArray, sb: FloatArray, n: Int,
        vx: FloatArray, vy: FloatArray,
        hShift: FloatArray, sGain: FloatArray,
        outR: FloatArray, outG: FloatArray, outB: FloatArray,
        outS: FloatArray, outV: FloatArray
    ) {
        for (i in 0 until n) {
            hsvOf(sr[i], sg[i], sb[i])
            val h = hsvBuf[0]
            val s = hsvBuf[1]
            val v = hsvBuf[2]
            val v2 = interp(v, vx, vy).coerceIn(0f, 1f)
            val hb = hueBinOf(h)
            val prot = ((s - 0.05f) / 0.18f).coerceIn(0f, 1f)
            val g = gainAt(sGain, hb, s)
            val s2 = (s * (1f + (g - 1f) * prot)).coerceIn(0f, 1f)
            val hueProt = ((s - 0.06f) / 0.24f).coerceIn(0f, 1f)
            val h2 = ((h + hShift[hb] * hueProt) % 360f + 360f) % 360f
            outS[i] = s2
            outV[i] = v2
            hsvToRgb(h2, s2, v2)
            outR[i] = hsvBuf[0]
            outG[i] = hsvBuf[1]
            outB[i] = hsvBuf[2]
        }
    }

    /** 饱和-亮度耦合：分 V 四档的整体饱和比，一阶线性回归（S' = S·(a + b·(V-0.5))） */
    private fun fitSv(
        preS: FloatArray, preV: FloatArray, ts: FloatArray,
        n: Int, minBin: Int
    ): FloatArray {
        val cnt = IntArray(4)
        for (i in 0 until n) {
            if (preS[i] <= 0.10f || ts[i] <= 0.02f || preV[i] >= 1f) continue
            cnt[bandOf(preV[i])]++
        }
        val vals = Array(4) { FloatArray(cnt[it]) }
        val pos = IntArray(4)
        for (i in 0 until n) {
            if (preS[i] <= 0.10f || ts[i] <= 0.02f || preV[i] >= 1f) continue
            val k = bandOf(preV[i])
            vals[k][pos[k]++] = ts[i] / preS[i].coerceAtLeast(1e-6f)
        }
        val med = FloatArray(4) { k ->
            if (vals[k].size > minBin) median(vals[k]) else Float.NaN
        }

        var m = 0
        var sx = 0.0
        var sy = 0.0
        var sxx = 0.0
        var sxy = 0.0
        for (k in 0 until 4) {
            if (med[k].isNaN()) continue
            val x = ((k + 0.5f) / 4f - 0.5f).toDouble()
            val y = med[k].toDouble()
            m++
            sx += x
            sy += y
            sxx += x * x
            sxy += x * y
        }
        var a = 1f
        var b = 0f
        if (m >= 2) {
            val den = m * sxx - sx * sx
            if (kotlin.math.abs(den) > 1e-9) {
                b = ((m * sxy - sx * sy) / den).toFloat()
                a = ((sy - b * sx) / m).toFloat()
            } else {
                a = (sy / m).toFloat()
                b = 0f
            }
        }
        return floatArrayOf(a.coerceIn(0.70f, 1.30f), b.coerceIn(-0.60f, 0.60f))
    }

    private fun bandOf(v: Float): Int {
        val k = (v * 4f).toInt()
        return if (k >= 4) 3 else if (k < 0) 0 else k
    }

    /**
     * 零均值三段分色调：残差只取色度分量（亮度差归 V 曲线，不让它冒充染色）。
     *
     * 不做逐点「灰进灰出」——那会砍掉阴影青/高光橙的表达力；改为让三段偏移的
     * 加权均值为零：允许按亮度分段染色，禁止整体偏一个色（灰轴不动）。
     */
    private fun fitBalance(
        preR: FloatArray, preG: FloatArray, preB: FloatArray,
        tr: FloatArray, tg: FloatArray, tb: FloatArray,
        srcS: FloatArray, n: Int
    ): FloatArray {
        val rr = FloatArray(n)
        val rg = FloatArray(n)
        val rb = FloatArray(n)
        for (i in 0 until n) {
            val dr = tr[i] - preR[i]
            val dg = tg[i] - preG[i]
            val db = tb[i] - preB[i]
            val dl = LUMA_R * dr + LUMA_G * dg + LUMA_B * db
            rr[i] = dr - dl
            rg[i] = dg - dl
            rb[i] = db - dl
        }

        val bal = FloatArray(9)
        val wsum = DoubleArray(3)
        var grayR = 0.0
        var grayG = 0.0
        var grayB = 0.0
        var grayCnt = 0
        for (i in 0 until n) {
            val lum = LUMA_R * preR[i] + LUMA_G * preG[i] + LUMA_B * preB[i]
            val w0 = ((0.40f - lum) / 0.40f).coerceIn(0f, 1f)
            val w2 = ((lum - 0.60f) / 0.40f).coerceIn(0f, 1f)
            val w1 = (1f - w0 - w2).coerceIn(0f, 1f)
            bal[0] += rr[i] * w0
            bal[1] += rg[i] * w0
            bal[2] += rb[i] * w0
            bal[3] += rr[i] * w1
            bal[4] += rg[i] * w1
            bal[5] += rb[i] * w1
            bal[6] += rr[i] * w2
            bal[7] += rg[i] * w2
            bal[8] += rb[i] * w2
            wsum[0] += w0.toDouble()
            wsum[1] += w1.toDouble()
            wsum[2] += w2.toDouble()
            if (srcS[i] < 0.10f) {
                grayR += rr[i].toDouble()
                grayG += rg[i].toDouble()
                grayB += rb[i].toDouble()
                grayCnt++
            }
        }
        val den = maxOf(200.0, n / 300.0)
        for (k in 0 until 3) {
            if (wsum[k] > den) {
                bal[k * 3] = (bal[k * 3] / wsum[k]).toFloat()
                bal[k * 3 + 1] = (bal[k * 3 + 1] / wsum[k]).toFloat()
                bal[k * 3 + 2] = (bal[k * 3 + 2] / wsum[k]).toFloat()
            } else {
                bal[k * 3] = 0f
                bal[k * 3 + 1] = 0f
                bal[k * 3 + 2] = 0f
            }
        }

        // 全局色偏：优先用近中性像素残差（灰像素残差就是纯色温），否则用分段权重平均
        val offR: Float
        val offG: Float
        val offB: Float
        if (grayCnt > maxOf(400, n / 150)) {
            offR = (grayR / grayCnt).toFloat()
            offG = (grayG / grayCnt).toFloat()
            offB = (grayB / grayCnt).toFloat()
        } else {
            var sR = 0.0
            var sG = 0.0
            var sB = 0.0
            var sw = 0.0
            for (k in 0 until 3) {
                val w = maxOf(wsum[k], 1.0)
                sR += bal[k * 3] * w
                sG += bal[k * 3 + 1] * w
                sB += bal[k * 3 + 2] * w
                sw += w
            }
            offR = (sR / sw).toFloat()
            offG = (sG / sw).toFloat()
            offB = (sB / sw).toFloat()
        }
        for (k in 0 until 3) {
            bal[k * 3] = (bal[k * 3] - offR).coerceIn(-0.06f, 0.06f)
            bal[k * 3 + 1] = (bal[k * 3 + 1] - offG).coerceIn(-0.06f, 0.06f)
            bal[k * 3 + 2] = (bal[k * 3 + 2] - offB).coerceIn(-0.06f, 0.06f)
        }
        // 限幅后再校一次零均值
        for (ch in 0 until 3) {
            var acc = 0.0
            var sw = 0.0
            for (k in 0 until 3) {
                val w = maxOf(wsum[k], 1.0)
                acc += bal[k * 3 + ch] * w
                sw += w
            }
            val mean = if (sw > 0.0) (acc / sw).toFloat() else 0f
            for (k in 0 until 3) bal[k * 3 + ch] -= mean
        }
        return bal
    }

    // ---------------- 基础工具 ----------------

    /** HSV 三元组复用缓冲，避免每点分配对象（64 万点 × 多遍） */
    private val hsvBuf = FloatArray(3)

    private fun hsvOf(r: Float, g: Float, b: Float) {
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
        hsvBuf[0] = h
        hsvBuf[1] = if (mx > 1e-6f) d / mx else 0f
        hsvBuf[2] = mx
    }

    private fun hsvToRgb(h: Float, s: Float, v: Float) {
        val hh = (((h % 360f) + 360f) % 360f) / 60f
        val i = hh.toInt()
        val f = hh - i
        val p = v * (1f - s)
        val q = v * (1f - f * s)
        val t = v * (1f - (1f - f) * s)
        when (i) {
            0 -> {
                hsvBuf[0] = v
                hsvBuf[1] = t
                hsvBuf[2] = p
            }
            1 -> {
                hsvBuf[0] = q
                hsvBuf[1] = v
                hsvBuf[2] = p
            }
            2 -> {
                hsvBuf[0] = p
                hsvBuf[1] = v
                hsvBuf[2] = t
            }
            3 -> {
                hsvBuf[0] = p
                hsvBuf[1] = q
                hsvBuf[2] = v
            }
            4 -> {
                hsvBuf[0] = t
                hsvBuf[1] = p
                hsvBuf[2] = v
            }
            else -> {
                hsvBuf[0] = v
                hsvBuf[1] = p
                hsvBuf[2] = q
            }
        }
    }

    /** 单调折线插值（xs 升序） */
    private fun interp(x: Float, xs: FloatArray, ys: FloatArray): Float {
        val last = xs.size - 1
        if (x <= xs[0]) return ys[0]
        if (x >= xs[last]) return ys[last]
        var lo = 0
        var hi = last
        while (hi - lo > 1) {
            val mid = (lo + hi) ushr 1
            if (xs[mid] <= x) lo = mid else hi = mid
        }
        val span = xs[hi] - xs[lo]
        if (span <= 1e-6f) return ys[lo]
        val f = (x - xs[lo]) / span
        return ys[lo] + (ys[hi] - ys[lo]) * f
    }

    /** 三段饱和锚点线性插值（0.12 / 0.45 / 0.82，与 applyThetaV2 一致） */
    private fun gainAt(satByHue: FloatArray, hb: Int, s: Float): Float {
        val i = hb * SAT_BANDS
        val a0 = satByHue[i]
        val a1 = satByHue[i + 1]
        val a2 = satByHue[i + 2]
        return when {
            s <= 0.12f -> a0
            s <= 0.45f -> a0 + (s - 0.12f) * (a1 - a0) / 0.33f
            s <= 0.82f -> a1 + (s - 0.45f) * (a2 - a1) / 0.37f
            else -> a2
        }
    }

    /** 镜像边界一维高斯平滑（与 numpy 对称填充一致） */
    private fun smooth1d(x: FloatArray, sigma: Float): FloatArray {
        val k = (sigma * 3).toInt() or 1
        val ker = gaussKernel(k * 2 + 1, sigma)
        val n = x.size
        val out = FloatArray(n)
        for (i in 0 until n) {
            var acc = 0f
            for (j in ker.indices) {
                var idx = i + j - k
                if (idx < 0) idx = -idx - 1
                if (idx >= n) idx = 2 * n - idx - 1
                acc += ker[j] * x[idx.coerceIn(0, n - 1)]
            }
            out[i] = acc
        }
        return out
    }

    /** 环绕一维高斯平滑（色相环） */
    private fun smoothCirc(x: FloatArray, sigma: Float): FloatArray {
        val k = (sigma * 3).toInt() or 1
        val ker = gaussKernel(k * 2 + 1, sigma)
        val n = x.size
        val out = FloatArray(n)
        for (i in 0 until n) {
            var acc = 0f
            for (j in ker.indices) {
                var idx = i + j - k
                idx = ((idx % n) + n) % n
                acc += ker[j] * x[idx]
            }
            out[i] = acc
        }
        return out
    }

    private fun gaussKernel(size: Int, sigma: Float): FloatArray {
        val k = FloatArray(size)
        val c = (size - 1) / 2f
        var sum = 0f
        for (i in 0 until size) {
            val d = i - c
            val w = exp(-(d * d) / (2f * sigma * sigma))
            k[i] = w
            sum += w
        }
        for (i in 0 until size) k[i] /= sum
        return k
    }

    private fun median(a: FloatArray): Float {
        if (a.isEmpty()) return Float.NaN
        a.sort()
        val m = a.size / 2
        return if (a.size % 2 == 1) a[m] else (a[m - 1] + a[m]) * 0.5f
    }

    private fun emptySeed(): RefSeed {
        val curve = FloatArray(CURVE_POINTS) { it / (CURVE_POINTS - 1f) }
        curve[0] = 0f
        curve[CURVE_POINTS - 1] = 1f
        val hueShare = FloatArray(SHARE_BINS * SAT_BANDS)
        hueShare[0] = 1f
        hueShare[1] = 0f
        return RefSeed(
            addedAt = System.currentTimeMillis(),
            wbGain = floatArrayOf(1f, 1f, 1f),
            toneCurve = curve,
            colorMatrix = FloatArray(9),
            satByHue = FloatArray(HUE_BINS * SAT_BANDS) { 1f },
            hueShift = FloatArray(HUE_BINS),
            hueShare = hueShare,
            base = floatArrayOf(1f, 1f, 1f, CustomLutBaker.FORMAT_V2)
        )
    }
}
