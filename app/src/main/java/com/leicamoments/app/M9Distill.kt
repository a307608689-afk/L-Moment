package com.leicamoments.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.codex.leicaapp.Dither
import com.codex.leicaapp.LegendaryPostProcessor
import com.codex.leicaapp.MadridLegendary
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.FloatBuffer
import java.util.zip.CRC32
import kotlin.math.roundToInt

/**
 * M9「一体化蒸馏」微网推理链（学生网 = 整条重链的蒸馏结果）。
 *
 * 定位：M9OrtFallback 把 StyleTrans(1024x768) + 真 ColorFix(544 分块) 两段重网络搬到端上
 * （网络段 ~3s）；本链用蒸馏出的 0.64MB 微网（HDRNet-lite 双边网格）**一次前向直接在全分辨率
 * 分块上出图**，取代「小图推理 + 上采样 + 高频搬运」整段。
 *
 * 接线：M9 / M9+ / M10R 三张卡片的降级链「降级 1.25」，插在 ORT 重链（降级 1.5）**之前**；
 * 因此三处的调用形状与 M9OrtFallback.run 一致——暖调 LUT、M9P 色彩后推、M10R 暗角颗粒
 * 都由调用方照旧施加，本对象只负责 styled 段。另有独立卡片 Style.M9D 直接调用它。
 *
 * 学生网输入/输出（与训练口径严格一致，勿改）：
 *   lowres (1,3,n/2,n/2) : 该分块自身 2:1 盒降采样后 /255（训练时 = cv2.resize(tile,256,INTER_AREA)）
 *   full   (1,3,n,n)     : 该分块 /255
 *   out    (1,3,n,n)     : [0,1] 直接是整块结果（网格仿射 + 中频残差都已在网内）
 *   n = 分块输入边长。权重/图与 ckpt 导出逐位一致，仅 lowres 的 H/W 声明为动态
 *   （原资产写死 256x256，只够喂 512 块；halo 口径需要能喂 1216 → 已重导并逐位对拍通过）。
 *
 * 几何协议 v2（2026-10-12 接缝修复，PC 端 23/25 号脚本验证）：
 *   fit 4096x3072 → 按「保留区」KEEP 步进、每块四周各多喂 HALO 像素（越界行列边缘复制）、
 *   **只取中心 KEEP×KEEP** 拼回（末块贴边、后写覆盖）→ 画布域结果 → 缩放回原尺寸 → 竖图转回。
 *   为什么必须裁边：微网的低分分支在每个输入分块的边界是零填充，边界一圈（实测 ≈96px）的
 *   色调场与中频残差都是脏的；旧口径（512 块直接拼）把脏边正好摆在接缝上，在大平滑面
 *   （天空/旗面）表现为可见「格子」（平坦区跨线 z：旧 +7.2~+8.4 → halo96 +0.3~+1.1 = 噪声地板）。
 *   代价：单块面积比 (KEEP+2*HALO)²/KEEP² = 1.42×（4096x3072 共 12 块，PC 0.76s → 1.08s）。
 *   PC 实测：切片输出 vs teacher = 4.03 u8（crop 口径 3.61）；外观漂移（新旧对比）mean 0.4~0.5/255。
 *
 * 失败（OOM/asset 缺失/推理异常）返回 null，调用方继续往下一级降级，不崩。
 */
object M9Distill {

    private const val TAG = "M9DIST"
    private const val MODEL_ASSET = "m9_distill.onnx"

    // 分块口径 v2：保留区 KEEP、四周各喂 HALO（见文件头几何协议 v2）
    private const val KEEP = 1024
    private const val HALO = 96
    private const val MIN_CANVAS = 256          // 画布最小边下限（更小先放大再跑，保证网格有足够格数）
    private const val W4K = 4096
    private const val H4K = 3072
    private const val INV255 = 1f / 255f

    /**
     * 蒸馏链专用降噪缩放（阻断平坦区色块的第一道）。
     *
     * native 暖调的 denoise=0.6 是为 styletrans 输出（自带网噪）配的；学生网输出本身
     * 干净得多（实测 b* 高频 σ 0.07 vs 原图 0.11），0.6 属于过杀——它把平坦区相邻像素
     * 抹成完全相同的常量，后级 8bit 量化后就显出 10~30px 的色块（等高线）。
     * 乘 0.33（0.6→0.2）保留暖调/影调语义，同时让平坦区留下足以掩盖量化台阶的微结构。
     * 只作用于本链；styletrans 原生链与 ORT 重链仍用 0.6（它们的输出本来带噪）。
     */
    private const val DENOISE_SCALE = 0.33f

    /** 量化前抖色幅度（LSB）：①打在 LUT 输入侧，让相邻像素落到不同 LUT bin；②打在 8bit 出口。 */
    private const val DITHER_PRE = 1.0f
    private const val DITHER_POST = 1.0f

    // ---- B1 分发保障：时间预算 / 像素预算 / 失败归因 ----

    /** 单张图跑本链的基础时间预算（毫秒）。 */
    private const val BASE_BUDGET_MS = 1500L
    /** 每百万像素追加的预算：实测旗舰 12.58MP ≈ 4.9s（≈390ms/MP），给约 3× 余量。 */
    private const val PER_MP_MS = 1200L
    private const val MIN_BUDGET_MS = 4000L
    /** 封顶：24MP 档需 ~30s、8192 档（50MP，48 块）需 ~62s，留余量取 70s。 */
    private const val MAX_BUDGET_MS = 70000L

    /** 内存估算里除位图之外还要留的量（分块平面缓冲 + ORT 内部 + 编码）。 */
    private const val HEAP_SLACK_BYTES = 32L * 1024 * 1024
    /** 可用堆最多允许用掉的比例（余量留给暖调副本、JPEG 编码与系统抖动）。 */
    private const val HEAP_SAFE_RATIO = 0.7
    /** 单张图的硬上限像素数（>64MP 本链必超预算，直接不跑）。 */
    private const val MAX_PIXELS = 64_000_000L

    /**
     * 本链失败归因。调用方据此决定「还要不要试更重的 ORT 重链」：
     * OOM / TIMEOUT / BUDGET 说明这台机器吃不下这一档，往上试只会更慢或直接崩；
     * OTHER（资产缺失、图结构异常）与链的编排无关，仍按原链继续。
     *
     * 单例状态，只在处理线程串行使用（App 一次只处理一张）。
     */
    enum class Fail { NONE, OOM, TIMEOUT, BUDGET, OTHER }

    @Volatile var lastFail: Fail = Fail.NONE
        private set

    @Volatile private var env: OrtEnvironment? = null
    @Volatile private var session: OrtSession? = null

    // styled 单槽缓存（网络输出与暖调参数无关，M9/M9D 共用同一份输入时可直接复用）
    @Volatile private var cacheHash: Long = Long.MIN_VALUE
    @Volatile private var cacheStyled: Bitmap? = null
    /**
     * 缓存口径：`cacheHash` 只编码 jpeg 内容，必须连同 longEdge 一起校验——
     * 否则同一份 jpeg 换口径（4096 / 5632）会复用错尺寸的 styled 位图。
     * （现有调用方输入字节必然不同，原风险仅为 crc32 碰撞；补校验后彻底消除，
     * 并保证同一图连续 HiRes 导出能正确复用缓存。）
     */
    @Volatile private var cacheLongEdge: Int = W4K

    /**
     * @param warmBin 暖调 LUT bin 资产名（蒸馏网只取代 styled 段，暖调语义与 M9 一致保留）
     * @param longEdge 画布长边上限：默认 4096（旧口径，所有既有调用不变）。
     *        传 [ImageUtils.HIRES_M9_LONG] 走「高分辨率工作链」（B 方案，2026-10-21）：
     *        M9 系放弃全像素条带链后，用整图链在 24MP 级画布上出图——暖调全图一次调用，
     *        结构上不存在带边收敛/条带台阶；失败（预算/超时/native 异常）由调用方回落工作版。
     * @return 处理后 JPEG 字节；任何失败返回 null
     */
    fun run(
        app: Context,
        jpeg: ByteArray,
        denoise: Float = 0.6f,
        ev: Float = -0.95f,
        warmth: Float = 0.3f,
        warmBin: String = "leica_filter_param_m9_snapshot.bin",
        longEdge: Int = W4K,
    ): ByteArray? {
        lastFail = Fail.NONE
        return try {
            val t0 = System.currentTimeMillis()
            val styled = styledCached(app, jpeg, crc32(jpeg), longEdge) ?: return null

            // 暖调必须在副本上做：applyWarmLutBitmapBin 对 mutable 输入是「原地改并返回同一对象」，
            // 而 styled 在正常路径就是 mutable —— 直接喂它再 recycle，会把单槽缓存里那张位图废掉
            // （下次命中即 "Can't compress a recycled bitmap"），同时缓存会变成「已暖调」，
            // 命中后会再暖一次（双重暖调偏色）。改后缓存语义固定为「网络输出，未暖调」。
            val work = styled.copy(Bitmap.Config.ARGB_8888, true) ?: return null
            // 抖色①（LUT 输入侧）：相邻像素随机落到不同 LUT bin，bin 边界不再连成等值线
            Dither.tpdf(work, DITHER_PRE)
            val warmed = LegendaryPostProcessor.applyWarmLutBitmapBin(
                app, work, warmBin, denoise * DENOISE_SCALE, ev, warmth)
            val outBmp = warmed ?: work
            // 抖色②（8bit 出口）：native 输出已是 8bit，台阶在平坦区固化成常量块，这里最后打散
            Dither.tpdf(outBmp, DITHER_POST)
            // P3：出口走 4:4:4（Bitmap.compress 是 4:2:0，暗部色块/边缘彩边来源）
            val encoded = JpegEnc.encode(outBmp)
            if (outBmp !== work) work.recycle()
            outBmp.recycle()
            val out = MadridLegendary.copyExif(jpeg, encoded)
            DiagLog.w(TAG, "蒸馏链完成 ${out.size}B, ${System.currentTimeMillis() - t0}ms")
            out
        } catch (t: Throwable) {
            markFail(t)
            DiagLog.e(TAG, "蒸馏链失败($lastFail)，回落上一级", t)
            null
        }
    }

    /**
     * 只跑蒸馏网络本体（不做暖调 / dither / 后处理），返回**未暖调**的 styled 位图。
     *
     * 供条带链在低分代理图上「同域」拟合暖调的逐带修正：
     * 暖调的实际行为与输入内容的统计/细节有关，在原始图上拟合再套到推理产物上会过冲，
     * 必须在推理产物上拟合。**不走 [styledCached] 单槽缓存**，避免把真实图的 styled 结果挤掉。
     */
    fun styleOnly(app: Context, jpeg: ByteArray): Bitmap? = styledInternal(app, jpeg)

    private fun styledCached(app: Context, jpeg: ByteArray, hash: Long,
                             longEdge: Int = W4K): Bitmap? {
        cacheStyled?.let { cached ->
            if (cacheHash == hash && cacheLongEdge == longEdge) {
                DiagLog.w(TAG, "styled 缓存命中 (${cached.width}x${cached.height})")
                return cached
            }
        }
        synchronized(this) {
            cacheStyled?.let { cached ->
                if (cacheHash == hash && cacheLongEdge == longEdge) {
                    DiagLog.w(TAG, "styled 缓存命中 (${cached.width}x${cached.height})")
                    return cached
                }
            }
            val s = styledInternal(app, jpeg, longEdge) ?: return null
            cacheHash = hash
            cacheLongEdge = longEdge
            cacheStyled = s
            return s
        }
    }

    /** 微网推理 + 几何还原，产出未加暖调的 styled Bitmap（mutable，供暖调就地写回）。 */
    private fun styledInternal(app: Context, jpeg: ByteArray,
                               longEdge: Int = W4K): Bitmap? {
        // 像素预算：解码前只读 JPEG 头判尺寸，吃不下就直接退场（别先 OOM 再退——
        // OOM 之前必然先经历一轮 GC 风暴，用户看到的是 2~3s 卡顿之后才降级）。
        // 高分辨率口径（longEdge > 4K）单独放宽到 0.85：24MP 峰值≈317MB，
        // 0.7 的余量在 512MB 堆上会误拒；失败仍被 run() 的 catch 兜住。
        val ratio = if (longEdge > W4K) 0.85f else HEAP_SAFE_RATIO.toFloat()
        if (!canAfford(jpeg, ratio, longEdge)) {
            lastFail = Fail.BUDGET
            DiagLog.w(TAG, "像素/内存预算不足（${longEdge}px 口径），跳过蒸馏链直落下一级")
            return null
        }
        val t0 = System.currentTimeMillis()
        val sess = obtainSession(app) ?: return null
        val e = env!!

        val src = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: return null
        val portrait = src.height > src.width
        val landscape = if (portrait) rotate(src, -90f) else src
        if (landscape !== src) src.recycle()

        val fitted = fit(landscape, longEdge)
        if (fitted !== landscape) landscape.recycle()
        val fw = fitted.width
        val fh = fitted.height

        // 画布过小（短边 < MIN_CANVAS，微网网格格数不够）→ 等比放大，跑完再缩回
        var work = fitted
        if (minOf(fw, fh) < MIN_CANVAS) {
            val s = MIN_CANVAS.toDouble() / minOf(fw, fh)
            work = Bitmap.createScaledBitmap(fitted, Math.ceil(fw * s).toInt(),
                Math.ceil(fh * s).toInt(), true)
            fitted.recycle()
        }
        val ww = work.width
        val wh = work.height

        val outPx = tiledInfer(sess, e, work, t0 + budgetMs(ww.toLong() * wh))
        work.recycle()
        if (outPx == null) return null

        var result = Bitmap.createBitmap(ww, wh, Bitmap.Config.ARGB_8888)
        result.setPixels(outPx, 0, ww, 0, 0, ww, wh)

        if (ww != fw || wh != fh) {
            val back = Bitmap.createScaledBitmap(result, fw, fh, true)
            result.recycle()
            result = back
        }
        if (fw != landscape.width || fh != landscape.height) {
            val back = Bitmap.createScaledBitmap(result, landscape.width, landscape.height, true)
            result.recycle()
            result = back
        }
        landscape.recycle()

        val finalBmp = if (portrait) rotateMutable(result, 90f) else result
        if (finalBmp !== result) result.recycle()
        DiagLog.w(TAG, "styled 就绪 ${finalBmp.width}x${finalBmp.height}, "
                + "${System.currentTimeMillis() - t0}ms")
        return finalBmp
    }

    /**
     * 分块逐块推理并拼回整幅（返回 ARGB 像素数组，失败返回 null）。
     *
     * v2：每块保留区 keep×keep（keep = min(KEEP, 画布短边) 向下取偶），四周各多喂 HALO 像素
     * （越界行列边缘复制），**只取中心 keep×keep 写回** —— 把每块自身的边缘脏带裁掉。
     */
    private fun tiledInfer(sess: OrtSession, e: OrtEnvironment, bmp: Bitmap,
                           deadlineMs: Long): IntArray? {
        val w = bmp.width
        val h = bmp.height
        val outPx = IntArray(w * h)
        var keep = minOf(KEEP, minOf(w, h))
        keep -= keep and 1                                   // 偶数对齐（2:1 盒降采样要求）
        if (keep < 16) return null
        val n = keep + 2 * HALO
        val low = n / 2
        val xs = tileOffsets(w, keep)
        val ys = tileOffsets(h, keep)
        val plane = n * n
        val lowPlane = low * low

        val fullBuf = ByteBuffer.allocateDirect(4 * plane * 3)   // 3 平面 float
            .order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer()
        val lowBuf = ByteBuffer.allocateDirect(4 * lowPlane * 3)
            .order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer()
        val tilePx = IntArray(plane)
        val tmpPx = IntArray(plane)
        val lowPx = IntArray(lowPlane)

        val total = xs.size * ys.size
        var done = 0
        try {
            for (y0 in ys) {
                for (x0 in xs) {
                    // 时间预算：只在块边界检查（单块约 0.4s，粒度足够）。超时即弃掉已算结果
                    // 往下一级降级——sess.run 无法安全中断，强杀线程会泄漏原生内存。
                    if (System.currentTimeMillis() > deadlineMs) {
                        lastFail = Fail.TIMEOUT
                        DiagLog.w(TAG, "时间预算耗尽，中止分块推理 ($done/$total)")
                        return null
                    }
                    done++
                    readBlock(bmp, x0, y0, n, tilePx, tmpPx)
                    boxDown2(tilePx, n, lowPx)
                    fillPlanesCHW(tilePx, plane, fullBuf)
                    fillPlanesCHW(lowPx, lowPlane, lowBuf)

                    val fullT = OnnxTensor.createTensor(
                        e, fullBuf, longArrayOf(1, 3, n.toLong(), n.toLong()))
                    val lowT = OnnxTensor.createTensor(
                        e, lowBuf, longArrayOf(1, 3, low.toLong(), low.toLong()))
                    fullT.use { ft ->
                        lowT.use { lt ->
                            sess.run(mapOf("lowres" to lt, "full" to ft)).use { res ->
                                val ob = (res[0] as OnnxTensor).floatBuffer
                                for (ty in 0 until keep) {
                                    val y = y0 + ty
                                    if (y >= h) break
                                    var o = y * w + x0
                                    val i = (ty + HALO) * n + HALO        // 中心区起点
                                    val iG = plane + i
                                    val iB = plane * 2 + i
                                    for (tx in 0 until keep) {
                                        if (x0 + tx >= w) break
                                        val r = (ob.get(i + tx) * 255f).roundToInt().coerceIn(0, 255)
                                        val g = (ob.get(iG + tx) * 255f).roundToInt().coerceIn(0, 255)
                                        val b = (ob.get(iB + tx) * 255f).roundToInt().coerceIn(0, 255)
                                        outPx[o++] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            markFail(t)
            DiagLog.e(TAG, "分块推理失败($lastFail)", t)
            return null
        }
        return outPx
    }

    /**
     * 读一块 n×n（含四周 halo）：越界行列用**边缘复制**补齐。
     * 不能用黑边——低分分支会把黑边当真内容算进色调场，画出暗角式脏边。
     * dst/tmp 由调用方复用（tmp 为 clamped 子矩形的中转）。
     */
    private fun readBlock(bmp: Bitmap, x0: Int, y0: Int, n: Int,
                          dst: IntArray, tmp: IntArray) {
        val bx = x0 - HALO
        val by = y0 - HALO
        val cx0 = maxOf(0, bx)
        val cy0 = maxOf(0, by)
        val cx1 = minOf(bmp.width, bx + n)
        val cy1 = minOf(bmp.height, by + n)
        val cw = cx1 - cx0
        bmp.getPixels(tmp, 0, cw, cx0, cy0, cw, cy1 - cy0)
        for (ry in 0 until n) {
            val sy = (by + ry).coerceIn(cy0, cy1 - 1)        // 行越界 → 复制最近行
            val srcRow = (sy - cy0) * cw
            val dstRow = ry * n
            for (rx in 0 until n) {
                val sx = (bx + rx).coerceIn(cx0, cx1 - 1)    // 列越界 → 复制最近列
                dst[dstRow + rx] = tmp[srcRow + sx - cx0]
            }
        }
    }

    /** 覆盖 [0,total) 的分块起点：常规 keep 步进，末块贴边（与前一/几块重叠，重叠区后写覆盖）。 */
    private fun tileOffsets(total: Int, keep: Int): IntArray {
        if (total <= keep) return intArrayOf(0)
        val cnt = (total + keep - 1) / keep
        val arr = IntArray(cnt)
        for (i in 0 until cnt) arr[i] = minOf(i * keep, total - keep)
        return arr
    }

    /** n→n/2 精确 2:1 盒降采样（= cv2.INTER_AREA 在 2:1 下的定义，与训练低分输入一致）；n 为偶数。 */
    private fun boxDown2(src: IntArray, n: Int, dst: IntArray) {
        val low = n / 2
        var d = 0
        for (y in 0 until low) {
            val r0 = (2 * y) * n
            val r1 = r0 + n
            for (x in 0 until low) {
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

    /** ARGB int 数组 → CHW float 平面（/255），写入复用缓冲并在末尾 rewind。 */
    private fun fillPlanesCHW(px: IntArray, n: Int, fb: FloatBuffer) {
        fb.rewind()
        for (c in 0 until 3) {
            val sh = 16 - c * 8
            for (i in 0 until n) {
                fb.put(((px[i] shr sh) and 0xFF) * INV255)
            }
        }
        fb.rewind()
    }

    // ---- ORT session ----

    /**
     * 供条带算子（[DistillBandOps]）复用同一个 ORT 会话：
     * 模型 651KB、加载一次即缓存，避免全像素导出再建一个 session 抢占线程池。
     */
    internal fun sessionFor(app: Context): OrtSession? = obtainSession(app)

    private fun obtainSession(app: Context): OrtSession? {
        session?.let { return it }
        synchronized(this) {
            session?.let { return it }
            return try {
                val e = OrtEnvironment.getEnvironment()
                val bytes = app.assets.open(MODEL_ASSET).use { it.readBytes() }
                val opt = OrtSession.SessionOptions()
                opt.setIntraOpNumThreads(OrtThreads.pick())
                val s = e.createSession(bytes, opt)
                env = e
                session = s
                DiagLog.w(TAG, "ORT session 就绪 (${bytes.size / 1024}KB, 线程 ${OrtThreads.pick()})")
                s
            } catch (t: Throwable) {
                DiagLog.e(TAG, "session 创建失败", t)
                null
            }
        }
    }

    fun release() {
        synchronized(this) {
            try { session?.close() } catch (_: Throwable) {}
            session = null
        }
    }

    // ---- 辅助 ----

    /**
     * 按像素数给时间预算：12.58MP ≈ 16.6s（旗舰实测 4.9s，余量留给低端机）；
     * 高分辨率口径（24MP ≈ 30s）需要更长的上限，故封顶 40s。超时即中止并降级，不会崩。
     */
    private fun budgetMs(pixels: Long): Long {
        val byArea = BASE_BUDGET_MS + (pixels / 1_000_000L) * PER_MP_MS
        return byArea.coerceIn(MIN_BUDGET_MS, MAX_BUDGET_MS)
    }

    /**
     * 解码前的像素/内存预算判断（只读 JPEG 头，不解码像素）。
     *
     * 估算口径：峰值约 3 张同尺寸 ARGB 位图（原图 / 竖转或 fitted 副本 / 输出拼回）
     * + 一块 IntArray 结果 + 固定余量。上限取可用堆的 70%——蒸馏之后还要做暖调副本
     * 与 JPEG 编码，这里必须留余量，否则会把「省一次 GC」换成「真 OOM」。
     */
    private fun canAfford(jpeg: ByteArray, safeRatio: Float = HEAP_SAFE_RATIO.toFloat(),
                          longEdge: Int = W4K): Boolean {
        val opts = BitmapFactory.Options()
        opts.inJustDecodeBounds = true
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, opts)
        val w = opts.outWidth.toLong()
        val h = opts.outHeight.toLong()
        if (w <= 0 || h <= 0) return true          // 尺寸读不出，交给正常解码路径报错
        val px = w * h
        if (px > MAX_PIXELS) return false
        // 估算口径：默认按「3 份同尺寸位图」保守估（旧口径，12.5MP 路径不变）。
        // 高分辨率档（longEdge > 4K）改按 1.5 份估：Android 8+ 的 Bitmap 像素在 native 堆，
        // 不计入 Java 堆上限，真正的 Java 堆峰值是 tiledInfer 的 outPx（1 份）+ 临时数组。
        // 若仍按 3 份估，8192 档会被算出 636MB 而误拒（512MB 堆上永远不可达）。
        val need = if (longEdge > W4K) {
            px * 4L * 3L / 2 + HEAP_SLACK_BYTES
        } else {
            px * 4L * 3L + HEAP_SLACK_BYTES
        }
        val rt = Runtime.getRuntime()
        val avail = rt.maxMemory() - (rt.totalMemory() - rt.freeMemory())
        return need <= (avail * safeRatio).toLong()
    }

    /** 失败归因：OOM 类（含 allocate 失败）单独标记，其余记为 OTHER。 */
    private fun markFail(t: Throwable) {
        lastFail = when {
            t is OutOfMemoryError -> Fail.OOM
            t.message?.contains("allocat", ignoreCase = true) == true -> Fail.OOM
            else -> Fail.OTHER
        }
    }

    private fun crc32(b: ByteArray): Long {
        val c = CRC32()
        c.update(b)
        return c.value
    }

    private fun rotate(b: Bitmap, deg: Float): Bitmap {
        val m = Matrix()
        m.setRotate(deg)
        return Bitmap.createBitmap(b, 0, 0, b.width, b.height, m, true)
    }

    /** 旋转并保证结果 mutable（createBitmap matrix 版返回 immutable，暖调 LUT 需就地写）。 */
    private fun rotateMutable(b: Bitmap, deg: Float): Bitmap {
        val rotated = rotate(b, deg)
        if (rotated.isMutable) return rotated
        val out = Bitmap.createBitmap(rotated.width, rotated.height, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(rotated, 0f, 0f, null)
        rotated.recycle()
        return out
    }

    /**
     * 画布等比收敛：默认 4096×3072（旧口径，既有调用零影响）；
     * 传 [longEdge] > 4096 时按「长边 = longEdge、短边 = 4:3 推算」收敛
     * （高分辨率工作链用，见 [run] 的 longEdge 参数）。
     */
    private fun fit(b: Bitmap, longEdge: Int = W4K): Bitmap {
        val shortEdge = longEdge * 3 / 4
        val w = b.width
        val h = b.height
        if (w <= longEdge && h <= shortEdge) return b
        val s = minOf(longEdge.toFloat() / w, shortEdge.toFloat() / h)
        return Bitmap.createScaledBitmap(b, (w * s).toInt(), (h * s).toInt(), true)
    }
}
