package com.leicamoments.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.codex.leicaapp.Dither
import com.codex.leicaapp.LegendaryPostProcessor
import com.codex.leicaapp.MadridLegendary
import com.codex.leicaapp.MadridShader
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.roundToInt

/**
 * 乙档：模型链（M9 / M9+ / M10R / M9D）的**条带化全像素导出**。
 *
 * 整图链（[M9Distill.run]）先把画布 fit 到 4096x3072（12.58MP）再推理，
 * 200MP 输入会丢掉 94% 细节；本算子把「原生分辨率」逐带喂给同一个蒸馏微网：
 *
 *  带（rows x fullW）→ 解码上下各 HALO 的扩展窗口 → 窗口内按 KEEP 宽度分块推理
 *  → 只把带内中心区写回带 → 逐带后处理（dither / 暖调 LUT / M9+ 后推 / M10R 暗角颗粒 / 五滑杆）
 *
 * 与整图链的顺序严格一致（见 LeicaEngine.processM9 / processM9Plus / processM10R）：
 *  M10R : LUT(MadridLegendary) → 蒸馏 → dither → 暖调 LUT → dither → 暗角颗粒 → 滑杆
 *  M9P  : 蒸馏 → dither → 暖调 LUT → dither → 加饱和/对比/暖偏 → dither → 滑杆
 *  M9/M9D: 蒸馏 → dither → 暖调 LUT → dither → 滑杆
 *
 * 关键约束（踩过的坑）：
 *  - 推理窗口每带必须**重新解码**：上/下 halo 若沿用上一带被后处理过的像素，等于把
 *    调色后的图当推理输入（整图链里推理输入永远是原像素）；
 *  - M10R 的 LUT 必须在推理**之前**（整图链就是这样），且 native 就地写要求 mutable；
 *  - 暖调 LUT 的 native time 参数整图链只取一次，逐带调用必须传同一个值
 *    （[LegendaryPostProcessor.applyWarmLutBitmapBinAt]），否则带间可能色调跳变；
 *  - 空间性后处理（暗角/颗粒）必须传全图坐标（fullH + yOffset）；
 *  - 任何一步失败返回 null（strict：宁可报错也不落回落副本）。
 */
object DistillBandOps {

    private const val TAG = "DISTBAND"

    /** 模型链覆盖的风格 id。 */
    private val STYLES = setOf("M9", "M9P", "M10R", "M9D")

    /** 调试开关：关闭暖调逐带修正（仅 debug 批测用）。 */
    @Volatile
    var debugNoToneFix = false

    /** 调试开关：只做蒸馏推理，跳过全部后处理（拆解带间偏差来源用）。 */
    @Volatile
    var debugStyledOnly = false

    /** 调试开关：把拟合出的修正网格转储到外部 files 目录（离线分析网格形态用）。 */
    @Volatile
    var debugDumpGrid = false

    /** 调试开关：带内相位修正的缩放系数（0 = 关闭，仅 debug 批测 A/B 用）。 */
    @Volatile
    var debugPhaseScale = 1f

    /**
     * 规范分块行缓存上限（像素数）。缓存一行 = keep×fullW 个 int；
     * 超限的大图（如 200MP 全像素导出）跳过缓存、按需重算重叠分块行——
     * 推理是确定性的，结果不受影响。
     */
    private const val TILE_CACHE_MAX_PX = 12_000_000L

    fun supports(style: LeicaEngine.Style?): Boolean =
            style != null && STYLES.contains(style.id)

    fun create(app: Context, style: LeicaEngine.Style?,
               levels: IntArray): ImageUtils.StripBandOp? {
        val sid = style?.id ?: return null
        if (!STYLES.contains(sid)) return null
        return when (sid) {
            "M10R" -> DistillOp(
                    app,
                    denoise = 0.6f,
                    ev = -0.95f,
                    warmth = 0.3f,
                    boost = false,
                    m10r = true,
                    warmBin = "leica_filter_param_m10r_snapshot.bin",
                    levels = levels)
            "M9P" -> DistillOp(
                    app,
                    denoise = LegendaryPostProcessor.M9P_DENOISE,
                    ev = LegendaryPostProcessor.M9P_EV,
                    warmth = LegendaryPostProcessor.M9P_WARMTH,
                    boost = true,
                    m10r = false,
                    warmBin = "leica_filter_param_m9_snapshot.bin",
                    levels = levels)
            // M9 / M9D：LeicaEngine.processM9 / processM9D 走 M9Distill.run 的默认参数
            else -> DistillOp(
                    app,
                    denoise = 0.6f,
                    ev = -0.95f,
                    warmth = 0.3f,
                    boost = false,
                    m10r = false,
                    warmBin = "leica_filter_param_m9_snapshot.bin",
                    levels = levels)
        }
    }

    private class DistillOp(
            private val app: Context,
            private val denoise: Float,
            private val ev: Float,
            private val warmth: Float,
            private val boost: Boolean,
            private val m10r: Boolean,
            private val warmBin: String,
            private val levels: IntArray
    ) : ImageUtils.StripBandOp {

        private var src: ImageUtils.StripSource? = null
        private var sess: OrtSession? = null
        private var env: OrtEnvironment? = null

        /** M10R 需要在推理前套 LUT，窗口必须 mutable 且复用（避免每带三次 30MB 级分配）。 */
        private var window: Bitmap? = null

        /** 整图链只取一次的 native time（逐带必须相同）。 */
        private var warmTime = 0f

        /** 暖调逐带修正：代理图上拟合的分段色调网格（null = 未拟合/放弃，按原样输出）。 */
        private var toneGrid: FloatArray? = null
        /** 网格纵向行数（覆盖整幅高度，见 [fitToneGridGlobal]）。 */
        private var gridGh = 0
        /** 网格对应的整图高度：应用时用全图 y 归一化到 [gridGh]。 */
        private var gridFullH = 0
        /**
         * 带内相位修正表：[(u*3 + k)*levels + lv]，u = 带内相对行（[PHASE_ROWS] 档）。
         *
         * 根因：native 暖调 LUT 自带空间场，坐标按**传入位图自身**的宽高归一化。
         * 整图链拿到 H，逐带链只拿到带的 rows，于是同一条物理行在两条链里落在场里
         * 不同位置，修正量差额是 (x, y_global) 与 (x, y_local) 之差——它在每个带边
         * 都跳一次（对 y_global 而言是锯齿）。
         *
         * 空间网格只能表达 y_global 的**连续**函数，锯齿的直流部分被它当噪声平均掉，
         * 剩下的形状落在带边就成了台阶（200MP 实测：中位 0.06 已达标，但少数带边
         * 达 1.4，且只出现在亮内容区——乘性误差 × 亮度，暗部看不出来）。
         *
         * 解法：在代理图上按 u 折叠统计残差（48 条带共享一条曲线，样本量 ×48），
         * 应用时按带内相对行插值叠加。它与带宽/带高无关，纯几何量。
         */
        private var phaseFix: FloatArray? = null
        /** 真实带几何（带顶/带高，末带可短）：只用于构造代理图的逐带副本。 */
        private var geomTops = IntArray(0)
        private var geomRows = IntArray(0)
        private var gridTried = false

        /** 规范分块网格：keep 边长 + 分块行起点（按整幅高度算，与整图链 tiledInfer 同口径）。 */
        private var gridKeep = 0
        private var gridYs = IntArray(0)

        private var tilePx = IntArray(0)
        private var tmpPx = IntArray(0)
        private var lowPx = IntArray(0)
        private var rowPx = IntArray(0)

        /**
         * 最近一次算完的「规范分块行」推理输出（keep×fullW，未抖动/未暖调）。
         * 带高（native 决定，本项目 1016）与推理分块（keep=1024）不对齐，一个分块行会被
         * 相邻两带共用；缓存下来避免重复推理，同时保证两带用到的行完全同源。
         */
        private var tileOut = IntArray(0)
        private var tileOutTop = Int.MIN_VALUE
        private var fullArr = FloatArray(0)
        private var lowArr = FloatArray(0)
        private var fullBuf: FloatBuffer? = null
        private var lowBuf: FloatBuffer? = null
        private var bandCount = 0

        override fun prepare(reader: ImageUtils.StripSource): Boolean {
            src = reader
            val s = M9Distill.sessionFor(app)
            if (s == null) {
                DiagLog.w(TAG, "ORT session 不可用，放弃条带导出")
                return false
            }
            sess = s
            env = OrtEnvironment.getEnvironment()
            warmTime = (android.os.SystemClock.uptimeMillis() % 60000L).toFloat()
            DiagLog.w(TAG, "prepare ${reader.width}x${reader.height} "
                    + "denoise=$denoise ev=$ev warmth=$warmth boost=$boost m10r=$m10r")
            return true
        }

        override fun apply(band: Bitmap, top: Int, rows: Int,
                           fullW: Int, fullH: Int): Bitmap? {
            val session = sess ?: return null
            if (band.isRecycled || rows <= 0 || fullW <= 0) return null

            // 首带时确定真实带几何并拟合暖调修正（bandRows 由 native 决定，prepare 阶段未知）
            if (!gridTried && top == 0 && !debugStyledOnly) ensureToneCorrection(rows)

            try {
                // 推理严格复刻整图链的规范 KEEP 网格（见 inferBand）：带高与分块不对齐时，
                // 一条带横跨两个规范分块行、相邻两带共用同一分块行，必须取同一次推理的结果，
                // 否则每条带各自按带高切块会得到不同的网络上下文（逐带 gain 偏差 + 带边阶跃）。
                if (!inferBand(session, top, rows, fullW, fullH, band)) return null
                if (debugStyledOnly) {
                    bandCount++
                    return band
                }

                var post: Bitmap = band
                Dither.tpdf(post, DITHER, top, DITHER_PHASE_PRE)
                val warmed = LegendaryPostProcessor.applyWarmLutBitmapBinAt(
                        app, post, warmBin, denoise * DENOISE_SCALE, ev, warmth, warmTime)
                if (warmed == null) {
                    DiagLog.w(TAG, "暖调 LUT 失败")
                    return null
                }
                if (warmed !== post) {
                    post.recycle()
                    post = warmed
                }
                Dither.tpdf(post, DITHER, top, DITHER_PHASE_WARM)
                // 暖调逐带修正：把「只看自己这一带」的暖调结果拉回「整幅暖调」的目标值
                applyToneFix(post, top, rows, fullH)
                if (boost) {
                    boostInPlace(post)
                    Dither.tpdf(post, DITHER, top, DITHER_PHASE_BOOST)
                }
                // 空间性后处理：传全图高度 + 带起点（否则暗角/颗粒在带边界错位）
                if (m10r) MadridShader.apply(post, 0.3f, 1f, fullH, top)
                // 五滑杆最后（与整图导出顺序一致）；LevelStack 会回收输入，band 本就是一次性的
                if (levels.any { it != 0 }) post = LevelStack.apply(post, levels)

                bandCount++
                if (bandCount == 1) DiagLog.w(TAG, "首带完成 rows=$rows keep=$gridKeep")
                return post
            } catch (t: Throwable) {
                DiagLog.e(TAG, "带处理异常 top=$top rows=$rows", t)
                return null
            }
        }

        override fun release() {
            try {
                window?.recycle()
            } catch (_: Throwable) {
            }
            window = null
            tilePx = IntArray(0)
            tmpPx = IntArray(0)
            lowPx = IntArray(0)
            rowPx = IntArray(0)
            fullArr = FloatArray(0)
            lowArr = FloatArray(0)
            fullBuf = null
            lowBuf = null
            tileOut = IntArray(0)
            tileOutTop = Int.MIN_VALUE
            gridKeep = 0
            gridYs = IntArray(0)
            toneGrid = null
            phaseFix = null
            gridGh = 0
            gridFullH = 0
            geomTops = IntArray(0)
            geomRows = IntArray(0)
            src = null
            sess = null
            env = null
            DiagLog.w(TAG, "release，共 $bandCount 带")
        }

        // ---- 窗口 ----

        private fun windowOf(w: Int, h: Int): Bitmap {
            val cur = window
            if (cur != null && !cur.isRecycled && cur.width == w && cur.height == h) return cur
            try {
                cur?.recycle()
            } catch (_: Throwable) {
            }
            val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            window = b
            return b
        }

        // ---- 暖调逐带修正 ----

        /** 代理图跑一次蒸馏网络（只推理、不暖调），失败返回 null。 */
        private fun styleProxy(src: Bitmap): Bitmap? = try {
            val bos = ByteArrayOutputStream()
            if (!src.compress(Bitmap.CompressFormat.JPEG, 95, bos)) {
                null
            } else {
                val out = M9Distill.styleOnly(app, bos.toByteArray())
                if (out != null && (out.width != src.width || out.height != src.height)) {
                    out.recycle()
                    null
                } else {
                    out
                }
            }
        } catch (t: Throwable) {
            DiagLog.e(TAG, "暖调修正：代理推理异常", t)
            null
        }

        /** 代理图上调用 native 暖调（参数与真实带完全同源）。 */
        private fun warmOnProxy(src: Bitmap): Bitmap? =
                LegendaryPostProcessor.applyWarmLutBitmapBinAt(
                        app, src, warmBin, denoise * DENOISE_SCALE, ev, warmth, warmTime)

        /**
         * 拟合「整幅暖调」对「逐带暖调」的修正网格。
         *
         * 整图链对整幅只做一次暖调 LUT，逐带链每带各做一次；native 暖调带统计/空间行为，
         * 逐带结果与整幅结果差出 MAE 十几到二十几，并表现为带边阶跃。这里在 ≤2.5MP
         * 代理图上同时跑「整幅」与「逐带」，拟合「逐带 → 整幅」的逐带 × 空间格 ×
         * 通道分段色调映射，再按带套用到全分辨率结果上。
         *
         * 代理图每条带的宽高比必须与真实带逐一相同（空间项与带自身宽高比相关），
         * 故按「先定宽，再按真实带行数等比定代理带行数」构建。
         */
        private fun ensureToneCorrection(bandRows: Int) {
            if (gridTried) return
            gridTried = true
            val reader = src ?: return
            val w = reader.width
            val h = reader.height
            if (bandRows <= 0 || w <= 0 || h <= 0) return

            val topsL = ArrayList<Int>()
            val rowsL = ArrayList<Int>()
            var tp = 0
            while (tp < h) {
                val r = minOf(bandRows, h - tp)
                topsL.add(tp)
                rowsL.add(r)
                tp += r
            }
            geomTops = topsL.toIntArray()
            geomRows = rowsL.toIntArray()

            val t0 = android.os.SystemClock.uptimeMillis()
            var proxy: Bitmap? = null
            var pWhole: Bitmap? = null
            var pBands: Bitmap? = null
            try {
                val longEdge = kotlin.math.sqrt(PROXY_PX * maxOf(w, h) / minOf(w, h).toDouble())
                        .toInt().coerceIn(64, maxOf(w, h))
                val sampled = reader.sample(longEdge) ?: run {
                    DiagLog.w(TAG, "暖调修正：代理图采样失败，按原样输出")
                    return
                }
                val pw = sampled.width
                val pRows = IntArray(geomRows.size)
                var acc = 0
                for (i in geomRows.indices) {
                    // 四舍五入而不是截断：末条短带只有 24 行，截断会把 10.7 行压成 10 行，
                    // 代理带宽高比与真实带（4096:24）偏差被放大
                    pRows[i] = ((pw.toDouble() * geomRows[i]) / w + 0.5).toInt().coerceAtLeast(2)
                    acc += pRows[i]
                }
                proxy = if (sampled.width == pw && sampled.height == acc) {
                    sampled
                } else {
                    val b = Bitmap.createScaledBitmap(sampled, pw, acc, true)
                    if (b !== sampled) sampled.recycle()
                    b
                }
                val ph = proxy.height
                val pTops = IntArray(pRows.size)
                var t = 0
                for (i in pRows.indices) {
                    pTops[i] = t
                    t += pRows[i]
                }
                DiagLog.w(TAG, "暖调修正代理 ${pw}x$ph pRows=${pRows.joinToString(",")}")

                // M10R 的暖调作用在预 LUT 之后，代理图必须在同一域
                if (m10r && !MadridLegendary.filterBitmap(app, proxy, warmBin)) {
                    DiagLog.w(TAG, "暖调修正：代理图 M10R LUT 失败，按原样输出")
                    return
                }

                // 暖调的实际行为与输入内容的统计/细节相关：必须在「推理产物」上拟合。
                // 用原始图拟合再套到推理结果上会系统性过冲（实测约 1.5~2x），残差反而变成
                // 整带偏亮。代理图面积小，一次 styleOnly 约 0.5~1s；逐带侧用同一整幅推理
                // 结果的裁剪，天然复刻「每带只看自己这一块」的统计差异，不必另跑逐带推理。
                run {
                    val before: Bitmap? = proxy
                    if (before == null) return
                    val inferred = styleProxy(before)
                    if (inferred == null) {
                        DiagLog.w(TAG, "暖调修正：代理推理失败，退回原始图拟合")
                    } else {
                        proxy = inferred
                        before.recycle()
                    }
                }

                val px: Bitmap = proxy ?: return
                val wholeSrc = px.copy(Bitmap.Config.ARGB_8888, true) ?: return
                pWhole = warmOnProxy(wholeSrc)
                if (pWhole == null) {
                    wholeSrc.recycle()
                    DiagLog.w(TAG, "暖调修正：代理整幅暖调失败，按原样输出")
                    return
                }
                if (pWhole !== wholeSrc) wholeSrc.recycle()

                pBands = Bitmap.createBitmap(pw, ph, Bitmap.Config.ARGB_8888)
                val cv = Canvas(pBands)
                for (i in pRows.indices) {
                    val crop = Bitmap.createBitmap(px, 0, pTops[i], pw, pRows[i])
                            .copy(Bitmap.Config.ARGB_8888, true) ?: continue
                    val wr = warmOnProxy(crop)
                    if (wr == null) {
                        crop.recycle()
                        continue
                    }
                    cv.drawBitmap(wr, 0f, pTops[i].toFloat(), null)
                    if (wr !== crop) crop.recycle()
                    wr.recycle()
                }

                // 网格纵向**覆盖整幅**（不是每条带一套）：带边若各带独立按带高归一化，
                // 边界两侧的校正值互不相干，残差恰好落在边界行上
                // （200MP 实测边界行跳变中位 0.196 / 峰 3.0 级，非边界中位 0.020）。
                val ghTotal = (ph / GRID_ROW_ROWS).coerceIn(8, GRID_H_MAX)
                val fitted = fitToneGridGlobal(app, pWhole, pBands, GRID_W, ghTotal, GRID_LEVELS)
                toneGrid = fitted
                gridGh = ghTotal
                gridFullH = h
                // 网格之上再拟一层「带内相位」修正：暖调空间场按传入位图高度归一化，
                // 逐带调用时同一条物理行落在场里不同位置，差额随 u 变化而在带边跳变，
                // 全局网格表达不了（详见 phaseFix 注释）。
                phaseFix = fitPhaseFix(pWhole, pBands, pTops, pRows, fitted,
                        ghTotal, GRID_W, GRID_LEVELS)
                DiagLog.w(TAG, "暖调修正就绪网格=${GRID_W}x$ghTotal"
                        + "x$GRID_LEVELS 代理=${pw}x$ph 带=${pRows.size} "
                        + "耗时=${android.os.SystemClock.uptimeMillis() - t0}ms")
            } catch (t: Throwable) {
                toneGrid = null
                phaseFix = null
                gridGh = 0
                gridFullH = 0
                DiagLog.e(TAG, "暖调修正拟合失败，按原样输出", t)
            } finally {
                try {
                    if (pWhole !== proxy) pWhole?.recycle()
                } catch (_: Throwable) {
                }
                try {
                    pBands?.recycle()
                } catch (_: Throwable) {
                }
                try {
                    proxy?.recycle()
                } catch (_: Throwable) {
                }
            }
        }

        /** 按全图坐标把该带的暖调结果修正到「整幅暖调」口径。 */
        private fun applyToneFix(bmp: Bitmap, top: Int, rows: Int, fullH: Int) {
            if (debugNoToneFix) return
            val grid = toneGrid ?: return
            if (gridGh <= 1 || gridFullH <= 0 || fullH <= 0 || rows <= 0) return
            applyToneGridGlobal(bmp, grid, top, fullH, gridGh, GRID_W, GRID_LEVELS,
                    if (debugPhaseScale > 0f) phaseFix else null, rows)
        }

        /**
         * 全图坐标分段色调网格应用：[yBase] = 该带在整图中的顶行。
         *
         * 旧口径按带内局部 y 归一化（`y / rowsEff`），越界再 clamp 到首/末网格行，于是
         * 同一条物理行在不同带里落到不同网格位置、带边两侧的校正值互不相干，边界必留台阶。
         * 这里改为整图归一化 + 越界**线性外延**（实际只发生在整图首末行）。
         */
        private fun applyToneGridGlobal(bmp: Bitmap, grid: FloatArray, yBase: Int,
                                       fullH: Int, gh: Int, gw: Int, levels: Int,
                                       phase: FloatArray?, bandRows: Int) {
            val w = bmp.width
            val h = bmp.height
            if (rowPx.size < w) rowPx = IntArray(w)
            val row = rowPx
            val lvF = levels
            val ghF = gh.toFloat()
            val gwF = gw.toFloat()
            // 带内相位修正：同一行所有像素共用 u，逐行把相邻两档混成 3*levels 个值，
            // 像素循环里只剩两次查表（复用亮度级那套 l0/l1/tt）
            val pArr: FloatArray = phase ?: FloatArray(0)
            val pRows = if (bandRows > 0) bandRows else h
            val phaseScale = if (debugPhaseScale > 0f) debugPhaseScale else 1f
            val rowFix = FloatArray(3 * levels)
            for (y in 0 until h) {
                if (phase != null) {
                    val uf = y.toFloat() * PHASE_ROWS / pRows
                    val u0 = uf.toInt().coerceIn(0, PHASE_ROWS - 1)
                    val u1 = (u0 + 1).coerceAtMost(PHASE_ROWS - 1)
                    val ut = (uf - u0).coerceIn(0f, 1f)
                    for (k in 0 until 3) {
                        val b0 = (u0 * 3 + k) * levels
                        val b1 = (u1 * 3 + k) * levels
                        val bo = k * levels
                        for (l in 0 until levels) {
                            rowFix[bo + l] = (pArr[b0 + l] * (1 - ut) + pArr[b1 + l] * ut) * phaseScale
                        }
                    }
                }
                bmp.getPixels(row, 0, w, 0, y, w, 1)
                val fy = (yBase + y + 0.5f) / fullH * ghF - 0.5f
                val y0: Int
                val y1: Int
                val wy: Float
                if (fy <= 0f) {
                    y0 = 0; y1 = 1; wy = fy
                } else if (fy >= gh - 1f) {
                    y0 = gh - 2; y1 = gh - 1; wy = fy - y0
                } else {
                    y0 = fy.toInt(); y1 = y0 + 1; wy = fy - y0
                }
                val rowBase0 = y0 * gw * 3
                val rowBase1 = y1 * gw * 3
                for (x in 0 until w) {
                    val fx = (x + 0.5f) / w * gwF - 0.5f
                    val x0: Int
                    val x1: Int
                    val wx: Float
                    if (fx <= 0f) {
                        x0 = 0; x1 = 1; wx = fx
                    } else if (fx >= gw - 1f) {
                        x0 = gw - 2; x1 = gw - 1; wx = fx - x0
                    } else {
                        x0 = fx.toInt(); x1 = x0 + 1; wx = fx - x0
                    }
                    val c00 = rowBase0 + x0 * 3
                    val c01 = rowBase0 + x1 * 3
                    val c10 = rowBase1 + x0 * 3
                    val c11 = rowBase1 + x1 * 3
                    val c = row[x]
                    var out = 0xFF shl 24
                    for (k in 0 until 3) {
                        val v = (c shr (k * 8)) and 0xFF
                        val lf = v * lvF / 256f - 0.5f
                        val l0 = lf.toInt().coerceIn(0, levels - 1)
                        val l1 = (l0 + 1).coerceAtMost(levels - 1)
                        val tt = (lf - l0).coerceIn(0f, 1f)
                        val i00 = (c00 + k) * levels
                        val i01 = (c01 + k) * levels
                        val i10 = (c10 + k) * levels
                        val i11 = (c11 + k) * levels
                        val yl0 = (grid[i00 + l0] * (1 - wx) + grid[i01 + l0] * wx) * (1 - wy) +
                                (grid[i10 + l0] * (1 - wx) + grid[i11 + l0] * wx) * wy
                        val yl1 = (grid[i00 + l1] * (1 - wx) + grid[i01 + l1] * wx) * (1 - wy) +
                                (grid[i10 + l1] * (1 - wx) + grid[i11 + l1] * wx) * wy
                        var ov = yl0 + (yl1 - yl0) * tt
                        if (phase != null) {
                            val bb = k * levels
                            ov += rowFix[bb + l0] * (1 - tt) + rowFix[bb + l1] * tt
                        }
                        // 四舍五入而不是截断：截断在整幅上会累积出约 -0.5 级的系统性压暗
                        out = out or ((ov + 0.5f).toInt().coerceIn(0, 255) shl (k * 8))
                    }
                    row[x] = out
                }
                bmp.setPixels(row, 0, w, 0, y, w, 1)
            }
        }

        // ---- 分块推理 ----

        /**
         * 按整图链（[M9Distill.tiledInfer]）的**规范 KEEP 网格**推理，只把覆盖本带的行写回 [dst]。
         *
         * 分块行网格按整幅高度算（y = 0, 1024, 2048 …），与 native 带高（本项目 1016）无关：
         * 一条带可能横跨两个规范分块行，相邻两条带又可能共用同一个分块行。[tileRowAt] 把
         * 刚算完的分块行缓存下来，保证跨带的行来自**同一次推理**（同源像素、同源上下文）。
         *
         * 旧口径按「带高」切块（keepH = min(KEEP, rows)），1016 行带与 24 行末带拿到的是
         * 与整图链完全不同的网络上下文，实测逐带 signed 偏差 +2.9/+6.2/+2.1/-4.8 且
         * 末带台阶 stepExMax@y=3048 —— 即本函数要消灭的带间差。
         */
        private fun inferBand(session: OrtSession, top: Int, rows: Int,
                              fullW: Int, fullH: Int, dst: Bitmap): Boolean {
            var keep = minOf(KEEP, minOf(fullW, fullH))
            keep -= keep and 1                                    // 偶数对齐（2:1 盒降采样要求）
            if (keep < 16) return false
            gridKeep = keep
            if (gridYs.isEmpty()) gridYs = tileOffsets(fullH, keep)

            val bandEnd = top + rows
            var covered = false
            for (y0 in gridYs) {
                if (y0 + keep <= top) continue
                if (y0 >= bandEnd) break
                val line = tileRowAt(session, y0, keep, fullW, fullH) ?: return false
                val yEnd = minOf(bandEnd, y0 + keep)
                for (y in maxOf(top, y0) until yEnd) {
                    try {
                        dst.setPixels(line, (y - y0) * fullW, fullW, 0, y - top, fullW, 1)
                    } catch (t: Throwable) {
                        DiagLog.e(TAG, "setPixels 失败 y=$y", t)
                        return false
                    }
                }
                covered = true
            }
            return covered
        }

        /**
         * 计算一条规范分块行（[y0, y0+keep) × [0, fullW)）的推理输出并缓存。
         * 只有相邻带会复用（最多跨一个分块行），单槽足够；超大图跳过缓存，
         * 结果仍然正确（推理是确定性的），只是重叠的分块行会被重算一遍。
         */
        private fun tileRowAt(session: OrtSession, y0: Int, keep: Int,
                              fullW: Int, fullH: Int): IntArray? {
            val need = keep.toLong() * fullW
            val cacheable = need <= TILE_CACHE_MAX_PX
            if (cacheable && tileOutTop == y0 && tileOut.size.toLong() >= need) return tileOut
            if (tileOut.size.toLong() < need) tileOut = IntArray(need.toInt())

            val wy0 = maxOf(0, y0 - HALO)
            val wy1 = minOf(fullH, y0 + keep + HALO)
            val winH = wy1 - wy0
            val raw = src?.read(wy0, winH) ?: run {
                DiagLog.w(TAG, "分块行解码失败 y=$wy0 h=$winH")
                return null
            }
            var win: Bitmap = raw
            if (m10r) {
                // LUT 在推理之前；native 就地写回 → 先拷进复用的 mutable 窗口
                val w2 = windowOf(raw.width, winH)
                try {
                    Canvas(w2).drawBitmap(raw, 0f, 0f, null)
                } finally {
                    raw.recycle()
                }
                if (!MadridLegendary.filterBitmap(app, w2, warmBin)) {
                    DiagLog.w(TAG, "M10R LUT 失败")
                    return null
                }
                win = w2
            }
            try {
                // yCenter：窗口第 0 行 = 全图 wy0，readBlockRect 内部再减 HALO 得到块顶
                if (!inferTileRow(session, win, y0 - wy0, keep, fullW, tileOut)) return null
            } finally {
                if (win !== window) win.recycle()
            }
            tileOutTop = if (cacheable) y0 else Int.MIN_VALUE
            return tileOut
        }

        /** 单条规范分块行的横向分块推理（每块 n×n，取中心 keep×keep 写进 [dst]）。 */
        private fun inferTileRow(session: OrtSession, win: Bitmap, yCenter: Int, keep: Int,
                                 fullW: Int, dst: IntArray): Boolean {
            val e = env ?: return false
            val n = keep + 2 * HALO
            val lowN = n / 2
            val plane = n * n
            val lowPlane = lowN * lowN

            ensureBuffers(plane, lowPlane)
            val xs = tileOffsets(fullW, keep)
            for (x0 in xs) {
                readBlockRect(win, x0, yCenter, n, n, tilePx, tmpPx)
                boxDown2Rect(tilePx, n, n, lowPx)
                fillChw(tilePx, plane, fullArr)
                fillChw(lowPx, lowPlane, lowArr)

                val fb = fullBuf ?: return false
                val lb = lowBuf ?: return false
                fb.rewind()
                fb.put(fullArr)
                fb.rewind()
                lb.rewind()
                lb.put(lowArr)
                lb.rewind()

                // 复用缓冲按历史最大尺寸分配，形状变化时容量会偏大；
                // ORT 要求缓冲元素数 == 形状元素数，故用 limit 精确裁剪视图。
                val fullT = OnnxTensor.createTensor(e, exactView(fb, plane * 3),
                        longArrayOf(1, 3, n.toLong(), n.toLong()))
                val lowT = OnnxTensor.createTensor(e, exactView(lb, lowPlane * 3),
                        longArrayOf(1, 3, lowN.toLong(), lowN.toLong()))
                fullT.use { ft ->
                    lowT.use { lt ->
                        session.run(mapOf("lowres" to lt, "full" to ft)).use { res ->
                            val ob = (res[0] as OnnxTensor).floatBuffer
                            val cols = minOf(keep, fullW - x0)
                            for (ty in 0 until keep) {
                                val base = ty * fullW + x0
                                val i = (ty + HALO) * n + HALO
                                val iG = plane + i
                                val iB = plane * 2 + i
                                for (tx in 0 until cols) {
                                    val r = (ob.get(i + tx) * 255f).roundToInt().coerceIn(0, 255)
                                    val g = (ob.get(iG + tx) * 255f).roundToInt().coerceIn(0, 255)
                                    val b = (ob.get(iB + tx) * 255f).roundToInt().coerceIn(0, 255)
                                    dst[base + tx] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                                }
                            }
                        }
                    }
                }
            }
            // 仅单块（fullW <= KEEP）且宽为奇数时 keep = fullW-1，
            // 最后一列用前一列补齐（1px 边缘复制）；多块时各块已覆盖到 fullW
            if (xs.size == 1 && keep < fullW) {
                for (ty in 0 until keep) {
                    val base = ty * fullW
                    val v = dst[base + keep - 1]
                    for (x in keep until fullW) dst[base + x] = v
                }
            }
            return true
        }

        private fun ensureBuffers(plane: Int, lowPlane: Int) {
            if (tilePx.size < plane) tilePx = IntArray(plane)
            if (tmpPx.size < plane) tmpPx = IntArray(plane)
            if (lowPx.size < lowPlane) lowPx = IntArray(lowPlane)
            if (rowPx.size < KEEP) rowPx = IntArray(KEEP)
            if (fullArr.size < plane * 3) fullArr = FloatArray(plane * 3)
            if (lowArr.size < lowPlane * 3) lowArr = FloatArray(lowPlane * 3)
            if (fullBuf == null || fullBuf!!.capacity() < plane * 3) {
                fullBuf = ByteBuffer.allocateDirect(4 * plane * 3)
                        .order(ByteOrder.nativeOrder()).asFloatBuffer()
            }
            if (lowBuf == null || lowBuf!!.capacity() < lowPlane * 3) {
                lowBuf = ByteBuffer.allocateDirect(4 * lowPlane * 3)
                        .order(ByteOrder.nativeOrder()).asFloatBuffer()
            }
        }

        /** 复用缓冲的精确视图（元素数 = need），供 ONNX 建张量。 */
        private fun exactView(buf: FloatBuffer, need: Int): FloatBuffer {
            val v = buf.duplicate()
            v.clear()
            if (v.capacity() > need) v.limit(need)
            return v
        }

        /** 读一块 nw×nh（四周含 halo）：越界行列边缘复制，不能用黑边（低分分支会当内容算出脏边）。 */
        private fun readBlockRect(bmp: Bitmap, x0: Int, y0: Int, nw: Int, nh: Int,
                                  dst: IntArray, tmp: IntArray) {
            val bx = x0 - HALO
            val by = y0 - HALO
            val cx0 = maxOf(0, bx)
            val cy0 = maxOf(0, by)
            val cx1 = minOf(bmp.width, bx + nw)
            val cy1 = minOf(bmp.height, by + nh)
            val cw = cx1 - cx0
            bmp.getPixels(tmp, 0, cw, cx0, cy0, cw, cy1 - cy0)
            for (ry in 0 until nh) {
                val sy = (by + ry).coerceIn(cy0, cy1 - 1)
                val srcRow = (sy - cy0) * cw
                val dstRow = ry * nw
                for (rx in 0 until nw) {
                    val sx = (bx + rx).coerceIn(cx0, cx1 - 1)
                    dst[dstRow + rx] = tmp[srcRow + sx - cx0]
                }
            }
        }

        /** 覆盖 [0,total) 的分块起点：常规 keep 步进，末块贴边、重叠区后写覆盖。 */
        private fun tileOffsets(total: Int, keep: Int): IntArray {
            if (total <= keep) return intArrayOf(0)
            val cnt = (total + keep - 1) / keep
            val arr = IntArray(cnt)
            for (i in 0 until cnt) arr[i] = minOf(i * keep, total - keep)
            return arr
        }

        /** nw×nh → nw/2×nh/2 精确 2:1 盒降采样（= cv2.INTER_AREA 在 2:1 下的定义）。 */
        private fun boxDown2Rect(src: IntArray, nw: Int, nh: Int, dst: IntArray) {
            val lowW = nw / 2
            val lowH = nh / 2
            var d = 0
            for (y in 0 until lowH) {
                val r0 = (2 * y) * nw
                val r1 = r0 + nw
                for (x in 0 until lowW) {
                    val c = 2 * x
                    val p0 = src[r0 + c]
                    val p1 = src[r0 + c + 1]
                    val p2 = src[r1 + c]
                    val p3 = src[r1 + c + 1]
                    val r = (((p0 shr 16) and 0xFF) + ((p1 shr 16) and 0xFF)
                            + ((p2 shr 16) and 0xFF) + ((p3 shr 16) and 0xFF) + 2) shr 2
                    val g = (((p0 shr 8) and 0xFF) + ((p1 shr 8) and 0xFF)
                            + ((p2 shr 8) and 0xFF) + ((p3 shr 8) and 0xFF) + 2) shr 2
                    val b = ((p0 and 0xFF) + (p1 and 0xFF) + (p2 and 0xFF) + (p3 and 0xFF) + 2) shr 2
                    dst[d++] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                }
            }
        }

        /** ARGB int 数组 → CHW float（/255），布局 [c][y][x]。 */
        private fun fillChw(px: IntArray, n: Int, dst: FloatArray) {
            for (i in 0 until n) {
                val p = px[i]
                dst[i] = ((p shr 16) and 0xFF) * INV255
                dst[n + i] = ((p shr 8) and 0xFF) * INV255
                dst[2 * n + i] = (p and 0xFF) * INV255
            }
        }

        // ---- M9+ 后推（与 LeicaEngine.boostSaturationContrast 同参数/同顺序）----

        /**
         * 整图链在 JPEG 上做「饱和度 ↑ → 暖偏 → 对比 ↑」，本处直接在带像素上算。
         * 矩阵没有旋转项（无 alpha 交叉），逐通道线性即可；alpha 恒 255。
         */
        private fun boostInPlace(bmp: Bitmap) {
            val sat = LeicaEngine.M9P_BOOST_SATURATION
            val con = LeicaEngine.M9P_BOOST_CONTRAST
            val warm = LeicaEngine.M9P_BOOST_WARM

            // 饱和度矩阵（Rec.601 亮度权重，与 ColorMatrix.setSaturation 一致）
            val ir = 0.299f
            val ig = 0.587f
            val ib = 0.114f
            var m00 = ir + (1f - ir) * sat
            var m01 = ig - ig * sat
            var m02 = ib - ib * sat
            var m10 = ir - ir * sat
            var m11 = ig + (1f - ig) * sat
            var m12 = ib - ib * sat
            var m20 = ir - ir * sat
            var m21 = ig - ig * sat
            var m22 = ib + (1f - ib) * sat
            // 暖偏：R 增益 +warm，B 增益 -warm（postConcat 右乘 scale）
            m00 *= (1f + warm); m10 *= (1f + warm); m20 *= (1f + warm)
            m02 *= (1f - warm); m12 *= (1f - warm); m22 *= (1f - warm)
            // 对比：右乘 scale
            m00 *= con; m01 *= con; m02 *= con
            m10 *= con; m11 *= con; m12 *= con
            m20 *= con; m21 *= con; m22 *= con

            val w = bmp.width
            val h = bmp.height
            if (rowPx.size < w) rowPx = IntArray(w)
            val px = rowPx
            for (y in 0 until h) {
                bmp.getPixels(px, 0, w, 0, y, w, 1)
                for (i in 0 until w) {
                    val p = px[i]
                    val r = (p shr 16) and 0xFF
                    val g = (p shr 8) and 0xFF
                    val b = p and 0xFF
                    val nr = (m00 * r + m01 * g + m02 * b).roundToInt().coerceIn(0, 255)
                    val ng = (m10 * r + m11 * g + m12 * b).roundToInt().coerceIn(0, 255)
                    val nb = (m20 * r + m21 * g + m22 * b).roundToInt().coerceIn(0, 255)
                    px[i] = (0xFF shl 24) or (nr shl 16) or (ng shl 8) or nb
                }
                bmp.setPixels(px, 0, w, 0, y, w, 1)
            }
        }
    }

    // ---- 暖调修正网格拟合 ----

    /**
     * 像素坐标 → 网格索引（拟合与应用必须同口径）。
     *
     * 约定：网格格 g 的中心落在归一化位置 (g+0.5)/G，故索引 = floor(u-0.5)；
     * 首末格各向外延伸半格承载边界像素（越界不再另行 clamp）。
     */
    private fun gridIndex(v: Int, size: Int, g: Int): Int {
        if (g <= 1 || size <= 0) return 0
        val f = (v + 0.5f) / size * g - 0.5f
        if (f <= 0f) return 0
        val i = f.toInt()
        return if (i >= g - 1) g - 1 else i
    }

    /**
     * 全图坐标拟合的亮度分段修正表（网格纵向覆盖整幅，不做任何带内归一化）。
     *
     * 索引布局：[(gy*gw + gx)*3 + k] → levels 级目标值。
     * 纵向格子跨带时两带的样本自然混在一格，边界处的校正值因此连续（差分量被摊到
     * 一格宽度 ≈ 3 代理行 ≈ 27 全分辨率行上），不会再在边界行上留下台阶。
     */
    private fun fitToneGridGlobal(ctx: Context, whole: Bitmap, band: Bitmap,
                                  gw: Int, gh: Int, levels: Int): FloatArray {
        val w = whole.width
        val h = whole.height
        val cells = gh * gw * 3
        val cnt = IntArray(cells * levels)
        val sum = FloatArray(cells * levels)
        val gxs = IntArray(w)
        for (x in 0 until w) gxs[x] = gridIndex(x, w, gw) * 3
        val rowW = IntArray(w)
        val rowB = IntArray(w)
        for (py in 0 until h) {
            whole.getPixels(rowW, 0, w, 0, py, w, 1)
            band.getPixels(rowB, 0, w, 0, py, w, 1)
            val base = gridIndex(py, h, gh) * gw * 3
            for (x in 0 until w) {
                val cell = base + gxs[x]
                val cw = rowW[x]
                val cb = rowB[x]
                for (k in 0 until 3) {
                    val vb = (cb shr (k * 8)) and 0xFF
                    val vw = (cw shr (k * 8)) and 0xFF
                    val lv = ((vb * levels) / 256).coerceIn(0, levels - 1)
                    val i = (cell + k) * levels + lv
                    sum[i] += vw.toFloat()
                    cnt[i]++
                }
            }
        }
        val grid = FloatArray(cells * levels)
        val vxs = IntArray(levels)
        val vys = FloatArray(levels)
        for (c3 in 0 until cells) {
            val base = c3 * levels
            // 样本稀疏的格子放宽计数门限，否则整格退化恒等；用格自身总样本量估算
            var cellN = 0
            for (l in 0 until levels) cellN += cnt[base + l]
            val minCnt = maxOf(2, minOf(8, cellN / levels / 2))
            var n = 0
            for (l in 0 until levels) {
                if (cnt[base + l] >= minCnt) {
                    vxs[n] = l
                    vys[n] = sum[base + l] / cnt[base + l]
                    n++
                }
            }
            var j = 0
            for (l in 0 until levels) {
                val ident = (l + 0.5f) * 256f / levels
                var raw = ident
                var dist = 0
                if (n > 0) {
                    while (j + 1 < n && vxs[j + 1] <= l) j++
                    raw = when {
                        vxs[j] == l -> vys[j]
                        j + 1 < n -> {
                            val t = (l - vxs[j]).toFloat() / (vxs[j + 1] - vxs[j])
                            vys[j] + (vys[j + 1] - vys[j]) * t
                        }
                        else -> {
                            // 锚点外侧按首/末段斜率外推：深暗/高光端的「逐带 vs 整幅」
                            // 差异明显大于中段，实测外推比"保持最近锚点"低约 0.4 MAE
                            // （保持锚点会把偏差挪成 -0.3 的系统性偏移、但局部误差变大）
                            val slope = if (n >= 2) {
                                (vys[n - 1] - vys[n - 2]) / (vxs[n - 1] - vxs[n - 2]).toFloat()
                            } else 0f
                            vys[j] + slope * (l - vxs[j])
                        }
                    }
                    dist = when {
                        l < vxs[0] -> vxs[0] - l
                        l > vxs[n - 1] -> l - vxs[n - 1]
                        else -> 0
                    }
                }
                // 锚点外侧的外推按距离衰减，超出窗口完全回落到恒等映射：
                // 稀疏高亮档（高光/镜面）的样本往往只来自某一种内容，硬外推会把
                // 近白像素整段染成该内容的颜色（实测 B 253 → 38 的黄花高光）
                val span = if (n > 0 && l < vxs[0]) TAIL_BLEND_DARK else TAIL_BLEND_BRIGHT
                val w = if (dist <= 0) 1f else (1f - dist / span).coerceAtLeast(0f)
                val d = ((raw - ident) * w).coerceIn(-MAX_DEV, MAX_DEV)
                grid[base + l] = (ident + d).coerceIn(0f, 255f)
            }
        }
        // 沿亮度轴三点平滑，抹掉代理图缩放/噪声带来的表内抖动（就地做，省一份整表内存）
        val tmp = FloatArray(levels)
        for (c3 in 0 until cells) {
            val base = c3 * levels
            for (l in 0 until levels) tmp[l] = grid[base + l]
            for (l in 0 until levels) {
                grid[base + l] = when (l) {
                    0 -> tmp[0]
                    levels - 1 -> tmp[levels - 1]
                    else -> (tmp[l - 1] + 2f * tmp[l] + tmp[l + 1]) / 4f
                }
            }
            // 单调化：暖调是单调映射，表值不许随输入亮度下降。
            // 稀疏档（某级只有 2~3 个样本）偶尔会拟出反向台阶，这里直接压平。
            for (l in 1 until levels) {
                if (grid[base + l] < grid[base + l - 1]) grid[base + l] = grid[base + l - 1]
            }
        }
        if (debugDumpGrid) dumpToneGrid(ctx, grid, cnt, gw, gh, levels, w, h)
        return grid
    }

    /**
     * 带内相位修正拟合：把「暖调空间场按带高归一化」造成的锯齿量测出来。
     *
     * 做法与空间网格同一套：用代理图逐像素算残差 `整幅暖调 - 网格预测`，只是不再按
     * 带序号分桶（那条路已试过，逐带直流对带边台阶无用），而是按**带内相对行**
     * `u = (row - top) / rows` 折叠。48 条带共享一条 u 曲线，样本量乘以带数，
     * 够细到分辨带边 1~2 行的跳变。
     *
     * 稀疏档处理：样本不足的 u 档沿用前一档（首档缺则留 0，绝不外推），沿 u 三点
     * 平滑（首末档互为邻档，保持周期性）。幅值限幅 [MAX_PHASE_FIX]。
     */
    private fun fitPhaseFix(whole: Bitmap, bandWarm: Bitmap, pTops: IntArray, pRows: IntArray,
                            grid: FloatArray, gh: Int, gw: Int, levels: Int): FloatArray? {
        val w = whole.width
        val h = whole.height
        val nb = pRows.size
        if (nb <= 0 || h <= 0 || grid.isEmpty()) return null
        val uOfRow = IntArray(h)
        java.util.Arrays.fill(uOfRow, -1)
        for (i in 0 until nb) {
            val rows = pRows[i]
            if (rows <= 0) continue
            val y1 = minOf(pTops[i] + rows, h)
            for (y in pTops[i] until y1) {
                uOfRow[y] = ((y - pTops[i]) * PHASE_ROWS / rows).coerceIn(0, PHASE_ROWS - 1)
            }
        }
        val sum = FloatArray(PHASE_ROWS * 3 * levels)
        val cnt = IntArray(PHASE_ROWS * 3 * levels)
        // 预测值必须与 [applyToneGridGlobal] **完全同口径**（4 邻格双线性 × 亮度级插值）。
        // 若这里用最近格，残差里会混进「最近格 vs 双线性」的差，折叠后成假偏置。
        val gx0 = IntArray(w)
        val gx1 = IntArray(w)
        val gwx = FloatArray(w)
        val gwF = gw.toFloat()
        for (x in 0 until w) {
            val fx = (x + 0.5f) / w * gwF - 0.5f
            if (fx <= 0f) {
                gx0[x] = 0; gx1[x] = 1; gwx[x] = fx
            } else if (fx >= gw - 1f) {
                gx0[x] = gw - 2; gx1[x] = gw - 1; gwx[x] = fx - gx0[x]
            } else {
                gx0[x] = fx.toInt(); gx1[x] = gx0[x] + 1; gwx[x] = fx - gx0[x]
            }
        }
        val lvF = levels
        val ghF = gh.toFloat()
        val rowW = IntArray(w)
        val rowB = IntArray(w)
        for (py in 0 until h) {
            val u = uOfRow[py]
            if (u < 0) continue
            whole.getPixels(rowW, 0, w, 0, py, w, 1)
            bandWarm.getPixels(rowB, 0, w, 0, py, w, 1)
            val fy = (py + 0.5f) / h * ghF - 0.5f
            val gy0: Int
            val gy1: Int
            val wy: Float
            if (fy <= 0f) {
                gy0 = 0; gy1 = 1; wy = fy
            } else if (fy >= gh - 1f) {
                gy0 = gh - 2; gy1 = gh - 1; wy = fy - gy0
            } else {
                gy0 = fy.toInt(); gy1 = gy0 + 1; wy = fy - gy0
            }
            val rb0 = gy0 * gw * 3
            val rb1 = gy1 * gw * 3
            val ub = u * 3
            for (x in 0 until w) {
                val cw = rowW[x]
                val cb = rowB[x]
                val xa = gx0[x] * 3
                val xb = gx1[x] * 3
                val wx = gwx[x]
                val i00 = rb0 + xa
                val i01 = rb0 + xb
                val i10 = rb1 + xa
                val i11 = rb1 + xb
                for (k in 0 until 3) {
                    val vb = (cb shr (k * 8)) and 0xFF
                    val vw = (cw shr (k * 8)) and 0xFF
                    val lf = vb * lvF / 256f - 0.5f
                    val l0 = lf.toInt().coerceIn(0, levels - 1)
                    val l1 = (l0 + 1).coerceAtMost(levels - 1)
                    val tt = (lf - l0).coerceIn(0f, 1f)
                    val p00 = (i00 + k) * levels
                    val p01 = (i01 + k) * levels
                    val p10 = (i10 + k) * levels
                    val p11 = (i11 + k) * levels
                    val a0 = (grid[p00 + l0] * (1 - wx) + grid[p01 + l0] * wx) * (1 - wy) +
                            (grid[p10 + l0] * (1 - wx) + grid[p11 + l0] * wx) * wy
                    val a1 = (grid[p00 + l1] * (1 - wx) + grid[p01 + l1] * wx) * (1 - wy) +
                            (grid[p10 + l1] * (1 - wx) + grid[p11 + l1] * wx) * wy
                    val pred = a0 + (a1 - a0) * tt
                    val idx = (ub + k) * levels + l0
                    sum[idx] += vw - pred
                    cnt[idx]++
                }
            }
        }
        val out = FloatArray(PHASE_ROWS * 3 * levels)
        val curve = FloatArray(PHASE_ROWS)
        val tmp = FloatArray(PHASE_ROWS)
        val minCnt = 16
        var peak = 0f
        var rawPeak = 0f
        var curves = 0
        for (k in 0 until 3) {
            for (l in 0 until levels) {
                var firstValid = -1
                for (u in 0 until PHASE_ROWS) {
                    val idx = (u * 3 + k) * levels + l
                    val n = cnt[idx]
                    if (n >= minCnt) {
                        curve[u] = sum[idx] / n
                        if (firstValid < 0) firstValid = u
                    } else {
                        curve[u] = Float.NaN
                    }
                }
                if (firstValid < 0) continue
                // 缺档按「环形前向最近有效档」填充：相位表首末档物理相邻，旧写法从 0 起补，
                // u=0 会被平滑成末档值的 1/4，正好在带边造出一条假台阶。
                var lastValid = 0f
                for (i in 0 until PHASE_ROWS) {
                    val u = (firstValid + i) % PHASE_ROWS
                    if (!curve[u].isNaN()) lastValid = curve[u] else curve[u] = lastValid
                }
                curves++
                for (u in 0 until PHASE_ROWS) tmp[u] = curve[u]
                for (u in 0 until PHASE_ROWS) {
                    // 首末档互为邻档：u=0 与 u=1 在物理上是相邻行（周期表）
                    val v = when (u) {
                        0 -> (tmp[PHASE_ROWS - 1] + 2f * tmp[0] + tmp[1]) / 4f
                        PHASE_ROWS - 1 ->
                            (tmp[PHASE_ROWS - 2] + 2f * tmp[PHASE_ROWS - 1] + tmp[0]) / 4f
                        else -> (tmp[u - 1] + 2f * tmp[u] + tmp[u + 1]) / 4f
                    }
                    val c = v.coerceIn(-MAX_PHASE_FIX, MAX_PHASE_FIX)
                    out[(u * 3 + k) * levels + l] = c
                    if (kotlin.math.abs(c) > peak) peak = kotlin.math.abs(c)
                    if (kotlin.math.abs(v) > rawPeak) rawPeak = kotlin.math.abs(v)
                }
            }
        }
        if (curves == 0) return null
        DiagLog.w(TAG, "带内相位修正 ${curves}条曲线 u=$PHASE_ROWS"
                + " 峰=${"%.2f".format(peak)} 原峰=${"%.2f".format(rawPeak)} 限幅=$MAX_PHASE_FIX")
        return out
    }

    /**
     * 诊断：把拟合出的修正网格（偏差 + 样本计数）转储到外部 files 目录，
     * 供 PC 端离线分析网格形态（默认关闭，仅批测时用）。
     */
    private fun dumpToneGrid(ctx: Context, grid: FloatArray, cnt: IntArray, gw: Int, gh: Int,
                             levels: Int, w: Int, h: Int) {
        try {
            val dir = ctx.getExternalFilesDir(null) ?: return
            val path = dir.absolutePath + "/grid_dump.bin"
            val f = java.io.File(path)
            val cells = gh * gw * 3
            val total = cells * levels
            java.io.DataOutputStream(java.io.BufferedOutputStream(java.io.FileOutputStream(path))).use { o ->
                o.writeBytes("GRID1")
                o.writeInt(gw)
                o.writeInt(gh)
                o.writeInt(levels)
                o.writeInt(w)
                o.writeInt(h)
                val dev = ByteArray(total)
                val cb = ByteArray(total)
                for (i in 0 until total) {
                    val l = i % levels
                    val ident = (l + 0.5f) * 256f / levels
                    dev[i] = (grid[i] - ident).roundToInt().coerceIn(-127, 127).toByte()
                    cb[i] = cnt[i].coerceAtMost(255).toByte()
                }
                o.write(dev)
                o.write(cb)
            }
            DiagLog.w(TAG, "网格转储 ${f.absolutePath} cells=$cells levels=$levels")
        } catch (t: Throwable) {
            DiagLog.e(TAG, "网格转储失败", t)
        }
    }

    // ---- 与 M9Distill 完全同口径的常量 ----

    private const val KEEP = 1024
    private const val HALO = 96
    private const val INV255 = 1f / 255f
    private const val DENOISE_SCALE = 0.33f
    private const val DITHER = 1.0f

    // ---- 暖调逐带修正（代理图拟合）----
    /** 代理图像素数上限（拟合专用的低分副本）。 */
    private const val PROXY_PX = 2_500_000.0
    /**
     * 修正网格：空间 48x48 × 每通道 64 级分段。
     *
     * 粒度扫描结论（BatchTest oracle，在已修正输出上再拟一套网格的上限诊断）：
     * 24x24/32 级 → 48x48/64 级 使残差 2.25→1.69（样张 1）、1.78→1.54（样张 2）；
     * 48x48/32 与 24x24/64 各只拿到一半收益，说明**空间与分段精度都要**。
     * 空间项是主因：native 暖调含按图像自身宽高比归一化的空间增益场（flat 探针：
     * 4096x768 的水平边缘衰减 64、4096x3072 为 104），逐带结果的场与整幅不同，
     * 需要更细的网格才能表达。
     */
    private const val GRID_W = 48
    /**
     * 纵向网格：整幅高度切 [GRID_ROW_ROWS] 个代理行一格（代理行 ≈ 全分辨率 9 行，
     * 故一格 ≈ 27 全分辨率行），总行数落在 [8, GRID_H_MAX]。
     *
     * 旧实现是「每条带 48 行封顶、按带高归一化」：200MP 一条 256 行带只摊到 9 格，
     * 且带边的格子退化成恒等映射，残差全部堆在边界行上。改成整幅连续网格后，
     * 单元格数总量与旧实现相当（≈450 行 × 48 列），内存不涨。
     */
    private const val GRID_ROW_ROWS = 3
    private const val GRID_H_MAX = 512
    private const val GRID_LEVELS = 64
    /**
     * 锚点外侧外推的衰减窗口（级数）：超出窗口的档位直接回落到恒等映射。
     *
     * 高光端必须短——近裁剪区的样本只来自少数内容（镜面/灯具），逐带与整幅的差
     * 在代理图上被放大后硬外推，实测会把整片近白像素染色（B 253 → 38）。
     * 深暗端本身偏差幅度小（映射到 0 附近天然无害），窗口给长些保留外推收益。
     */
    private const val TAIL_BLEND_BRIGHT = 4f
    private const val TAIL_BLEND_DARK = 10f
    /** 单级修正上限：暖调逐带与整幅的真实差异远小于此值，超出必为稀疏档噪声。 */
    private const val MAX_DEV = 64f
    /**
     * 带内相位修正的 u 分档数。
     *
     * 折叠形状（各带共享一条 u 曲线）实测：折叠后 edge step 仅 0.015、带间形状相关
     * 0.74~0.98，说明残差主成分是**周期形状**而不是逐带直流（逐带 DC 方案已证无效）。
     * 200MP 在测样张是 12288 高 / 48 条 256 行带，代理带宽约 1826、代理带高约 29 行，
     * 24 档 ≈ 每档 1.2 代理行（≈10 全分辨率行），够表达「带首偏暗 / 带尾偏亮」的缓变；
     * 带边 1~2 行的硬跳变靠首末档之差近似（再细分只会把代理采样噪声放大成条纹）。
     */
    private const val PHASE_ROWS = 24
    /**
     * 带内相位修正幅值上限（级）。实测带内残差峰 1.4 级、带边台阶 ~1.4，
     * 超过 8 级几乎必然是代理图上内容极端（近白/近黑）的档位拟过头。
     */
    private const val MAX_PHASE_FIX = 8f
    /**
     * 抖动表相位序号：同一条链的各带必须传同一个值（噪声场才跨带连续），
     * 不同后处理段传不同值（两次量化各自独立）。
     */
    private const val DITHER_PHASE_PRE = 0
    private const val DITHER_PHASE_WARM = 1
    private const val DITHER_PHASE_BOOST = 2
}
