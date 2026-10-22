package com.leicamoments.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
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

    /**
     * 调试开关：完全跳过 native 暖调 LUT（含其逐带修正与托底），仅 debug 批测用。
     *
     * 用于隔离「暖调逐带调用」是否为带边界台阶的成因：暖调是链上唯一同时带统计与空间
     * 行为的算子，其空间增益场按传入 Bitmap 的宽高归一化，逐带调用时每条带都被当作
     * 独立图像处理。关掉它若台阶消失，即可确认病灶。
     */
    @Volatile
    var debugNoWarm = false

    /** 调试开关：关闭暖调逐带修正（仅 debug 批测用）。 */
    @Volatile
    var debugNoToneFix = false

    /**
     * 暖调重叠窗口行数：>0 时暖调（含前置抖动/托底/逐带修正）在「上下各扩该行数」的
     * 窗口上执行，只取中间本带。0 = 旧口径（暖调逐带独立）。
     *
     * 暖调是链上唯一按「整个输入 Bitmap 的宽高」归一化的算子：逐带调用时每条带都被当成
     * 一张独立图像，空间增益场在带边被迫收敛、带内被拉伸，形成 256 行周期的横向条纹
     * （200MP 纯灰探针：带尾抬亮 ~0.6 级、接缝暗线 ~0.85 级、真实照片平滑区折叠幅度
     * 中位 3.8 级）。真机 warmover 探针实测接缝凹陷随扩窗单调收敛：
     * E=0/-1.10、32/-0.57、64/-0.27、128/-0.08（B 通道级）。
     *
     * 默认 64（2026-10-21 真机对拍恢复）：warmOverlap=0 曾被误判「无可见收益」回退，
     * 真机实测直接打脸——条带边界暗线（用户所见「画面中间那条暗线」）在 E=0 时
     * y=6144 残差 -2.24 级、全图条带边界均值 -0.90；E=64 时分别为 -0.83 / -0.24
     * （N4 修复版 FlatHfClean 生效后 E=0 也仅 -2.00 / -0.67，仍显著劣于 64）。
     * 即 E=0→64 的收益就是这条用户全程可见的暗线，+22% 耗时是必要代价。
     * 留档——E=64：200MP 纯灰探针带边台阶 0.67→0.12 级（-82%）、带内折叠 1.32→0.32 级、
     * 真实照片平滑区 2.50→0.72 级，导出耗时 +22%（98s→120s）、heap +4MB。
     * E=128 还能再降到 0.05/0.50，但要 +70% 耗时（167s），边际收益不足以抵消。
     */
    @Volatile
    var warmOverlap = 64

    /** 调试开关：只做蒸馏推理，跳过全部后处理（拆解带间偏差来源用）。 */
    @Volatile
    var debugStyledOnly = false

    /**
     * 出口细噪治理总开关（默认开）。
     *
     * 200MP 条带链把平滑区细噪/中频云纹放大到原图 ~2 倍（native 暖调局部增强 +
     * 双次 TPDF 抖动，厂商 .so 不可改参）——预览链因降采样推理+缩屏显示不可见，
     * 100% 查看导出图显形为「水彩纹/条纹」。治理 = LevelStack 之后做平坦区高频衰减
     * （[FlatHfClean]，边缘门控保护）+ 出口 0.6 LSB TPDF 抖动（重建被衰减抹掉的
     * 防色块噪声地板）。关掉即完全回到旧口径（真机 A/B 用）。
     */
    @Volatile
    var grainClean = true

    /** 调试开关：把拟合出的修正网格转储到外部 files 目录（离线分析网格形态用）。 */
    @Volatile
    var debugDumpGrid = false

    /** 调试开关：逐带打印覆盖行数 / 分块行调用次数 / 推理前后带均值（定位带边接缝用）。 */
    @Volatile
    var debugBandStat = false

    /**
     * 规范分块行缓存上限（像素数）。缓存一行 = keep×fullW 个 int。
     *
     * 上限必须 **大于等于** 单条分块行的像素数（keep × fullW）：200MP（16320 宽）一条
     * 1024 行分块即 16.7M 像素，原 12M 上限会让所有带都判 cacheable=false，
     * 于是同一分块行被覆盖它的 4 条带各重算一次（实测 calls=1 hits=0），纯亏 3 倍推理时间。
     * 放开后缓存槽复用，200MP 导出从 ~243s 降到 ~80s。
     * 内存上并不额外增加——[tileOut] 无论是否可缓存都按需分配到该尺寸。
     */
    private const val TILE_CACHE_MAX_PX = 20_000_000L

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

        /** 暖调重叠窗口复用位图（尺寸 = fullW × (rows + 2E)，200MP 时约 33MB）。 */
        private var extWin: Bitmap? = null
        /** 上一带最后若干行的 styled（推理产物、未暖调）像素，供本带上扩使用。 */
        private var tailPx = IntArray(0)
        private var tailRows = 0
        private var tailTop = Int.MIN_VALUE

        private var fullArr = FloatArray(0)
        private var lowArr = FloatArray(0)
        private var fullBuf: FloatBuffer? = null
        private var lowBuf: FloatBuffer? = null
        private var bandCount = 0

        /** 逐带诊断计数（仅 [debugBandStat] 打开时维护）。 */
        private var covRows = 0
        private var tileCalls = 0
        private var tileHits = 0
        private var prevTail = 0f
        private var prevPreTail = 0f
        private var hasPrev = false

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
            if (!gridTried && top == 0 && !debugStyledOnly && !debugNoWarm) ensureToneCorrection(rows)

            val stat = debugBandStat
            val preRows = if (stat) FloatArray(rows) else null
            if (preRows != null) rowMeans(band, fullW, rows, preRows)
            if (top == 0) hasPrev = false
            tileCalls = 0
            tileHits = 0
            try {
                // 推理严格复刻整图链的规范 KEEP 网格（见 inferBand）：带高与分块不对齐时，
                // 一条带横跨两个规范分块行、相邻两带共用同一分块行，必须取同一次推理的结果，
                // 否则每条带各自按带高切块会得到不同的网络上下文（逐带 gain 偏差 + 带边阶跃）。
                if (!inferBand(session, top, rows, fullW, fullH, band)) return null
                if (stat && preRows != null) {
                    val postRows = FloatArray(rows)
                    rowMeans(band, fullW, rows, postRows)
                    val k = minOf(4, rows)
                    var head = 0f
                    var tail = 0f
                    var preHead = 0f
                    var preTail = 0f
                    for (i in 0 until k) {
                        head += postRows[i] - preRows[i]
                        tail += postRows[rows - k + i] - preRows[rows - k + i]
                        preHead += preRows[i]
                        preTail += preRows[rows - k + i]
                    }
                    head /= k
                    tail /= k
                    preHead /= k
                    preTail /= k
                    val seam = if (hasPrev) head - prevTail else 0f
                    val pseam = if (hasPrev) preHead - prevPreTail else 0f
                    DiagLog.w(TAG, "BSTAT top=$top cov=$covRows calls=$tileCalls "
                            + "hits=$tileHits seam=" + "%.2f".format(seam)
                            + " pseam=" + "%.2f".format(pseam))
                    prevTail = tail
                    prevPreTail = preTail
                    hasPrev = true
                }
                if (debugStyledOnly) {
                    bandCount++
                    return band
                }

                var post: Bitmap = band
                if (debugNoWarm) {
                    Dither.tpdf(post, DITHER, top, DITHER_PHASE_PRE)
                } else {
                    val ov = warmOverlap
                    val ext = if (ov > 0)
                        buildWarmWindow(session, band, top, rows, fullW, fullH, ov) else null
                    val srcW = ext?.first ?: band
                    val yBase = ext?.second ?: top
                    val hWin = ext?.third ?: rows
                    // 上扩行必须来自「本带之前的 styled」，必须在暖调把窗口写脏之前缓存
                    if (ext != null) rememberTail(srcW, top, rows, yBase, fullW, ov)
                    val p2 = warmStage(srcW, yBase, hWin, fullH)
                    if (p2 == null) {
                        DiagLog.w(TAG, "暖调 LUT 失败")
                        return null
                    }
                    if (ext != null) {
                        // 只取扩展窗口的中间本带，带边那 E 行（暖调的空间场在此收敛）被丢弃
                        try {
                            Canvas(band).drawBitmap(p2, 0f, (yBase - top).toFloat(), null)
                        } catch (t: Throwable) {
                            DiagLog.e(TAG, "扩窗暖调回写失败 top=$top", t)
                            return null
                        }
                        if (p2 !== srcW) p2.recycle()
                        post = band
                    } else {
                        if (p2 !== band) band.recycle()
                        post = p2
                    }
                }
                if (boost) {
                    boostInPlace(post)
                    Dither.tpdf(post, DITHER, top, DITHER_PHASE_BOOST)
                }
                // 空间性后处理：传全图高度 + 带起点（否则暗角/颗粒在带边界错位）
                if (m10r) MadridShader.apply(post, 0.3f, 1f, fullH, top)
                // 出口色度护栏：暖调 LUT + 逐带修正 + 饱和后推会把低值通道（典型是暖黄主体的蓝）
                // 削顶到 0，造成色阶塌陷/色块。这里只把已经跌到下限以下的通道按色差比例拉回。
                val guardHits = ChromaGuard.knee(post)
                if (bandCount == 0) {
                    DiagLog.w(TAG, "色度护栏 首带命中=$guardHits/${post.width}x${post.height}")
                }
                // 五滑杆最后（与整图导出顺序一致）；LevelStack 会回收输入，band 本就是一次性的
                if (levels.any { it != 0 }) post = LevelStack.apply(post, levels)

                // 出口细噪治理：平坦区高频衰减（边缘门控）+ 0.6 LSB 出口抖动。
                // 前者把平滑区被链上放大的细噪/云纹压回原图水平，后者重建防量化色块的
                // 噪声地板（衰减会把 warm 段两次抖动的效果在平坦区抹掉）。见 FlatHfClean。
                if (grainClean) {
                    val ms = FlatHfClean.apply(post, top, src)
                    val msC = ChromaClean.apply(post)
                    if (bandCount == 0) DiagLog.w(
                        TAG,
                        "平坦区高频衰减 首带 ${ms}ms ${FlatHfClean.lastStat} " +
                            "(T0=5.0 正确门控；meanWgt<0.2 = 门控过弱，blend<50% = 活动场异常)；" +
                            "色度清理 ${msC}ms"
                    )
                    Dither.tpdf(post, DITHER_OUT, top, DITHER_PHASE_OUT)
                }

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
            try {
                extWin?.recycle()
            } catch (_: Throwable) {
            }
            extWin = null
            tailPx = IntArray(0)
            tailRows = 0
            tailTop = Int.MIN_VALUE
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
            gridGh = 0
            gridFullH = 0
            geomTops = IntArray(0)
            geomRows = IntArray(0)
            src = null
            sess = null
            env = null
            FlatHfClean.reset()   // 清跨带 A 场递推状态，避免下次导出用上次的衔接值
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

        // ---- 暖调重叠窗口 ----

        /**
         * 组装「本带 + 上下各扩 ov 行」的 styled（推理产物、未暖调）窗口。
         *
         * 返回 (窗口位图, 窗口顶行在整图中的 y, 窗口行数)；不需要扩窗时返回 null（走旧口径）。
         *
         * 带外行的来源：上侧用上一带缓存的 styled 尾部（[tailPx]），下侧直接用分块行缓存
         * （[tileRowAt]）——需要时会把下一条规范分块行提前算出来，而它本来就要在下一带
         * 被算，总量不变，只是把成本前移了一带。
         */
        private fun buildWarmWindow(session: OrtSession, band: Bitmap, top: Int, rows: Int,
                                    fullW: Int, fullH: Int, ov: Int): Triple<Bitmap, Int, Int>? {
            val extTop = maxOf(0, top - ov)
            val extEnd = minOf(fullH, top + rows + ov)
            val extRows = extEnd - extTop
            if (extRows <= rows) return null
            val w = extWinOf(fullW, extRows) ?: return null
            var ok = true
            if (extTop < top) {
                val up = top - extTop
                if (tailRows >= up && tailTop + tailRows == top) {
                    try {
                        w.setPixels(tailPx, 0, fullW, 0, 0, fullW, up)
                    } catch (t: Throwable) {
                        DiagLog.e(TAG, "上扩行写入失败 top=$top", t)
                        ok = false
                    }
                } else {
                    ok = copyStyledRows(session, extTop, top, fullW, fullH, w, 0)
                }
            }
            if (ok) {
                try {
                    Canvas(w).drawBitmap(band, 0f, (top - extTop).toFloat(), null)
                } catch (t: Throwable) {
                    DiagLog.e(TAG, "本带写入扩窗失败 top=$top", t)
                    return null
                }
                if (extEnd > top + rows) {
                    ok = copyStyledRows(session, top + rows, extEnd, fullW, fullH,
                            w, rows + (top - extTop))
                }
            }
            return if (ok) Triple(w, extTop, extRows) else null
        }

        /** 把 [y0,y1) 的 styled 行从规范分块行缓存拷进 [dst] 的第 [dstRow] 行起。 */
        private fun copyStyledRows(session: OrtSession, y0: Int, y1: Int, fullW: Int, fullH: Int,
                                   dst: Bitmap, dstRow: Int): Boolean {
            if (y1 <= y0) return true
            if (gridKeep <= 0 || gridYs.isEmpty()) return false
            var any = false
            for (g in gridYs) {
                if (g + gridKeep <= y0) continue
                if (g >= y1) break
                val line = tileRowAt(session, g, gridKeep, fullW, fullH) ?: return false
                val s = maxOf(y0, g)
                val e = minOf(y1, g + gridKeep)
                try {
                    for (y in s until e) {
                        dst.setPixels(line, (y - g) * fullW, fullW, 0, dstRow + (y - y0), fullW, 1)
                    }
                } catch (t: Throwable) {
                    DiagLog.e(TAG, "扩窗行拷贝失败 [$s,$e) dstRow=$dstRow", t)
                    return false
                }
                any = true
            }
            return any
        }

        /** 缓存本带尾部 [ov] 行 styled 像素（下一带的上扩输入），必须在暖调之前调用。 */
        private fun rememberTail(win: Bitmap, top: Int, rows: Int, winTop: Int,
                                 fullW: Int, ov: Int) {
            if (ov <= 0 || rows <= 0) return
            val n = minOf(ov, rows)
            val start = top + rows - n
            val off = start - winTop
            if (off < 0 || off + n > win.height) return
            if (tailPx.size < n * fullW) tailPx = IntArray(n * fullW)
            try {
                win.getPixels(tailPx, 0, fullW, 0, off, fullW, n)
                tailRows = n
                tailTop = start
            } catch (t: Throwable) {
                DiagLog.e(TAG, "尾部 styled 缓存失败 top=$top", t)
                tailRows = 0
                tailTop = Int.MIN_VALUE
            }
        }

        private fun extWinOf(w: Int, h: Int): Bitmap? {
            val cur = extWin
            if (cur != null && !cur.isRecycled && cur.width == w && cur.height == h) return cur
            try {
                cur?.recycle()
            } catch (_: Throwable) {
            }
            extWin = null
            return try {
                Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { extWin = it }
            } catch (t: Throwable) {
                DiagLog.e(TAG, "扩窗缓冲分配失败 ${w}x$h", t)
                null
            }
        }

        /**
         * 暖调阶段（前置抖动 → 暖调 LUT → 抖动 → 托底 → 逐带修正）。
         * 返回的 Bitmap 可能就地改写 [src]，也可能是新对象。
         */
        private fun warmStage(src: Bitmap, yBase: Int, hWin: Int, fullH: Int): Bitmap? {
            Dither.tpdf(src, DITHER, yBase, DITHER_PHASE_PRE)
            var p = src
            val warmed = LegendaryPostProcessor.applyWarmLutBitmapBinAt(
                    app, p, warmBin, denoise * DENOISE_SCALE, ev, warmth, warmTime)
                    ?: return null
            if (warmed !== p) p = warmed
            Dither.tpdf(p, DITHER, yBase, DITHER_PHASE_WARM)
            // 托底：暖调把暖黄主体的蓝压到 0~3，先拉到色域下限之上，让逐带修正与
            // 后续饱和/暖偏后推都在「还有层次」的域里工作（拟合侧用同一口径，见下方代理）。
            ChromaGuard.knee(p)
            // 暖调逐带修正：把「只看自己这一带」的暖调结果拉回「整幅暖调」的目标值
            applyToneFix(p, yBase, hWin, fullH)
            return p
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

                // 与实际链同口径：真实带在「上下各扩 warmOverlap 行」的窗口上做暖调，
                // 代理侧按同一比例扩 eProxy 行、只取中间本带，否则拟合出的修正量会对着
                // 旧口径（逐带独立暖调）的偏差过修。
                val eProxy = if (warmOverlap > 0)
                    ((warmOverlap.toDouble() * ph) / h + 0.5).toInt() else 0
                pBands = Bitmap.createBitmap(pw, ph, Bitmap.Config.ARGB_8888)
                val cv = Canvas(pBands)
                for (i in pRows.indices) {
                    val y0 = pTops[i]
                    val y1 = y0 + pRows[i]
                    val e0 = maxOf(0, y0 - eProxy)
                    val e1 = minOf(ph, y1 + eProxy)
                    val crop = Bitmap.createBitmap(px, 0, e0, pw, e1 - e0)
                            .copy(Bitmap.Config.ARGB_8888, true) ?: continue
                    val wr = warmOnProxy(crop)
                    if (wr == null) {
                        crop.recycle()
                        continue
                    }
                    cv.drawBitmap(wr, Rect(0, y0 - e0, pw, y1 - e0), Rect(0, y0, pw, y1), null)
                    if (wr !== crop) wr.recycle()
                    crop.recycle()
                }

                // 网格纵向**覆盖整幅**（不是每条带一套）：带边若各带独立按带高归一化，
                // 边界两侧的校正值互不相干，残差恰好落在边界行上
                // （200MP 实测边界行跳变中位 0.196 / 峰 3.0 级，非边界中位 0.020）。
                //
                // 曾试「带周期整数格」（每带 10 格、带缝恒落格边界）：纯灰探针反而变差
                // （whole p2p 1.240 → 2.324，接缝台阶 −1.115 级）——带首/带末两格只被
                // 单侧样本锚定，格值偏 ±0.5~1.7 级，跨带就形成真实台阶。故维持整幅口径。
                // 拟合前先跑同一出口软膝：暖调把低值通道压到 0~3 时，「整幅暖调」这个目标值
                // 本身就在削顶区，直接拟合会把带内结果对齐到一片没有层次的 0 上。
                // 让目标与带内输入/结果同域（都已托底），修正才是在有色阶的域里做。
                pWhole?.let { ChromaGuard.knee(it) }
                pBands?.let { ChromaGuard.knee(it) }
                val ghTotal = (ph / GRID_ROW_ROWS).coerceIn(8, GRID_H_MAX)
                val fitted = fitToneGridGlobal(app, pWhole, pBands, GRID_W, ghTotal, GRID_LEVELS)
                toneGrid = fitted
                gridGh = ghTotal
                gridFullH = h
                DiagLog.w(TAG, "暖调修正就绪网格=${GRID_W}x$ghTotal"
                        + "x$GRID_LEVELS 代理=${pw}x$ph 带=${pRows.size} "
                        + "耗时=${android.os.SystemClock.uptimeMillis() - t0}ms")
            } catch (t: Throwable) {
                toneGrid = null
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
            applyToneGridGlobal(bmp, grid, top, fullH, gridGh, GRID_W, GRID_LEVELS)
        }

        /**
         * 全图坐标分段色调网格应用：[yBase] = 该带在整图中的顶行。
         *
         * 旧口径按带内局部 y 归一化（`y / rowsEff`），越界再 clamp 到首/末网格行，于是
         * 同一条物理行在不同带里落到不同网格位置、带边两侧的校正值互不相干，边界必留台阶。
         * 这里改为整图归一化 + 越界**线性外延**（实际只发生在整图首末行）。
         */
        private fun applyToneGridGlobal(bmp: Bitmap, grid: FloatArray, yBase: Int,
                                       fullH: Int, gh: Int, gw: Int, levels: Int) {
            val w = bmp.width
            val h = bmp.height
            if (rowPx.size < w) rowPx = IntArray(w)
            val row = rowPx
            val lvF = levels
            val ghF = gh.toFloat()
            val gwF = gw.toFloat()
            for (y in 0 until h) {
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
        /** 诊断采样：固定 8 列 × 逐行亮度均值（0..255），用于在残差域比对带边界是否跳变。 */
        private fun rowMeans(b: Bitmap, fullW: Int, rows: Int, out: FloatArray) {
            val xs = IntArray(8) { (fullW.toLong() * (it + 1) / 9L).toInt() }
            for (y in 0 until rows) {
                var s = 0L
                for (x in xs) {
                    val p = b.getPixel(x, y)
                    s += ((p shr 16 and 0xFF) * 299 + (p shr 8 and 0xFF) * 587
                            + (p and 0xFF) * 114) / 1000
                }
                out[y] = s / 8f
            }
        }

        private fun inferBand(session: OrtSession, top: Int, rows: Int,
                              fullW: Int, fullH: Int, dst: Bitmap): Boolean {
            var keep = minOf(KEEP, minOf(fullW, fullH))
            keep -= keep and 1                                    // 偶数对齐（2:1 盒降采样要求）
            if (keep < 16) return false
            gridKeep = keep
            if (gridYs.isEmpty()) gridYs = tileOffsets(fullH, keep)

            val bandEnd = top + rows
            var covered = false
            covRows = 0
            for (y0 in gridYs) {
                if (y0 + keep <= top) continue
                if (y0 >= bandEnd) break
                val line = tileRowAt(session, y0, keep, fullW, fullH) ?: return false
                val yEnd = minOf(bandEnd, y0 + keep)
                covRows += yEnd - maxOf(top, y0)
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
            tileCalls++
            if (cacheable && tileOutTop == y0 && tileOut.size.toLong() >= need) {
                tileHits++
                return tileOut
            }
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

            // Rec.601 亮度权重（与 ColorMatrix.setSaturation 一致）
            val ir = 0.299f
            val ig = 0.587f
            val ib = 0.114f
            val warmUp = 1f + warm
            val warmDown = 1f - warm

            val w = bmp.width
            val h = bmp.height
            if (rowPx.size < w) rowPx = IntArray(w)
            val px = rowPx
            for (y in 0 until h) {
                bmp.getPixels(px, 0, w, 0, y, w, 1)
                for (i in 0 until w) {
                    val p = px[i]
                    val r0 = (p shr 16) and 0xFF
                    val g0 = (p shr 8) and 0xFF
                    val b0 = p and 0xFF
                    // 暖偏作用在输入通道（与旧矩阵右乘 scale 等价）
                    val r = r0 * warmUp
                    val b = b0 * warmDown
                    val lum = ir * r + ig * g0 + ib * b
                    // 饱和限幅：最小通道越低越让出增益，避免把已偏低的通道推穿 0（削顶）
                    val satEff = ChromaGuard.satGain(minOf(r0, g0, b0).toFloat(), sat)
                    val nr = (lum + (r - lum) * satEff) * con
                    val ng = (lum + (g0 - lum) * satEff) * con
                    val nb = (lum + (b - lum) * satEff) * con
                    px[i] = (0xFF shl 24) or
                            (nr.roundToInt().coerceIn(0, 255) shl 16) or
                            (ng.roundToInt().coerceIn(0, 255) shl 8) or
                            nb.roundToInt().coerceIn(0, 255)
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
        val gys = IntArray(h)
        for (py in 0 until h) gys[py] = gridIndex(py, h, gh)
        val rowW = IntArray(w)
        val rowB = IntArray(w)
        for (py in 0 until h) {
            whole.getPixels(rowW, 0, w, 0, py, w, 1)
            band.getPixels(rowB, 0, w, 0, py, w, 1)
            val base = gys[py] * gw * 3
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
                // 最暗档下压限幅：暖调后被打到 0~3 的低值通道（典型是暖黄主体的蓝）若还允许
                // -64，整片会被钉死在 0 上（色阶全塌），且逐格修正量不同会让差异显形成块。
                val maxDown = if (ident < ChromaGuard.TONE_LOW_GUARD) ChromaGuard.TONE_LOW_DEV else MAX_DEV
                val d = ((raw - ident) * w).coerceIn(-maxDown, MAX_DEV)
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

    /** 出口抖动幅度（LSB）：细噪衰减后重建防色块噪声地板，幅度小于链内两次 1.0 */
    private const val DITHER_OUT = 0.5f

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
     * 三代口径的教训：①「每带 48 格封顶、带边格子恒等」→ 残差全堆在边界行；
     * ②整幅连续网格（464 格，200MP 纯灰残差 1.240 / 边界台阶 0.037 级）；
     * ③带周期整数格（每带 10 格）→ 带首/带末两格只被单侧样本锚定，偏 ±0.5~1.7 级，
     * 跨带反而出现 1.1 级真台阶（残差 2.324）。故保留②。
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
     * 抖动表相位序号：同一条链的各带必须传同一个值（噪声场才跨带连续），
     * 不同后处理段传不同值（两次量化各自独立）。
     */
    private const val DITHER_PHASE_PRE = 0
    private const val DITHER_PHASE_WARM = 1
    private const val DITHER_PHASE_BOOST = 2
    private const val DITHER_PHASE_OUT = 3
}
