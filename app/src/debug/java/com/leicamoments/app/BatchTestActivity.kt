package com.leicamoments.app

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.os.Build
import android.os.Bundle
import android.util.Log
import com.codex.leicaapp.LeicaMark
import com.codex.leicaapp.LegendaryPostProcessor
import java.io.File

/**
 * ADB 批量测试入口（仅测试构建使用）：
 *
 * adb shell am start -n com.leicamoments.app/.BatchTestActivity \
 *   --es style LM9,LP9 --es indir /sdcard/LeicaTestIn --es outdir /sdcard/LeicaTestOut
 *
 * 流程：读 indir 内全部 jpg/dng → 逐张跑 LeicaEngine.process → 写 outdir/<stem>_<style>.jpg
 * 完成后写 _batch_done.txt（含逐张结果与耗时）。进度可用 logcat -s BatchTest 跟踪。
 * 权限：debug 包经 `adb shell appops set com.leicamoments.app MANAGE_EXTERNAL_STORAGE allow` 授权。
 */
class BatchTestActivity : Activity() {

    private val tag = "BatchTest"

    /**
     * 解码亮度阈值（LibRaw auto_bright_thr）。默认 0.01 = 旧行为，
     * 用于 A/B 验证「RAW 解码亮度」档位是否真的生效（不给参数时测量基线不变）。
     */
    private var thr = 0.01f

    /**
     * 解码档位：-1 = 只用 [thr]（旧行为）；0/1/2 = 与 MainActivity.decodeRaw 同分支，
     * 即 1/2 走「线性解码 + 自适应软肩曲线」。用于新曲线的真机 A/B 复现。
     */
    private var level = -1

    /** 条带高度请求值（实际值由 native 流式编码器决定，见报告 band=rows x count） */
    private var bandRowsReq = 512

    @SuppressLint("SdCardPath")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        thr = intent.getFloatExtra("thr", 0.01f)
        level = intent.getIntExtra("level", -1)
        bandRowsReq = intent.getIntExtra("bandrows", 512)
        val rawOnly = intent.getBooleanExtra("rawonly", false)
        // 导出 16bit 线性 sRGB（w,h 头 + u16 RGB），供 PC 端做曲线/局部色调原型搜索
        val dumpLin = intent.getBooleanExtra("dumplin", false)
        val styles = (intent.getStringExtra("style") ?: "LM9,LP9").split(",").map { it.trim() }.filter { it.isNotEmpty() }
        val indir = intent.getStringExtra("indir") ?: "/sdcard/LeicaTestIn"
        val outdir = intent.getStringExtra("outdir") ?: "/sdcard/LeicaTestOut"
        val limit = intent.getIntExtra("limit", 0)
        // P3 A/B：强制回落到旧的 Bitmap.compress（4:2:0），用于同包内同像素对比编码口径
        JpegEnc.forceLegacy420 = intent.getBooleanExtra("legacy", false)
        // P3 A/B：质量覆盖（0 = 用各出口默认值），用于真机扫 volume/quality 曲线
        JpegEnc.qualityOverride = intent.getIntExtra("q", 0)
        // C1 验收入口：条带全像素直通（可用 --es mode fullres 触发）
        val mode = intent.getStringExtra("mode") ?: ""
        Log.i(tag, "启动批量测试 mode=$mode styles=$styles indir=$indir outdir=$outdir limit=$limit thr=$thr level=$level rawonly=$rawOnly dumplin=$dumpLin")

        if (mode == "fullres") {
            Thread { runFullResSuite(File(indir), File(outdir).apply { mkdirs() }, limit) }.start()
            return
        }

        // 乙档验收：模型链条带导出 vs 整图链参考（同尺寸下应≈一致，且无带边界接缝）
        if (mode == "band") {
            DistillBandOps.debugNoToneFix = intent.getBooleanExtra("nofix", false)
            DistillBandOps.debugNoWarm = intent.getBooleanExtra("nowarm", false)
            DistillBandOps.debugStyledOnly = intent.getBooleanExtra("styledonly", false)
            DistillBandOps.debugDumpGrid = intent.getBooleanExtra("griddump", false)
            DistillBandOps.debugBandStat = intent.getBooleanExtra("bstat", false)
            // 暖调重叠窗口行数（0 = 旧口径）：A/B 用 wov=0/64/128 跑同一张 200MP
            DistillBandOps.warmOverlap =
                    intent.getIntExtra("wov", DistillBandOps.warmOverlap)
            Log.i(tag, "band A/B: nofix=${DistillBandOps.debugNoToneFix} "
                    + "nowarm=${DistillBandOps.debugNoWarm} "
                    + "styledonly=${DistillBandOps.debugStyledOnly} "
                    + "bstat=${DistillBandOps.debugBandStat} "
                    + "wov=${DistillBandOps.warmOverlap}")
            Thread { runBandSuite(File(indir), File(outdir).apply { mkdirs() }, limit, styles) }.start()
            return
        }

        // 暖调 LUT 逐带敏感性隔离：同一张图「整幅套一次」vs「逐带各套一次」
        if (mode == "warm") {
            Thread { runWarmSuite(File(indir), File(outdir).apply { mkdirs() }) }.start()
            return
        }

        // 代理图修正场验证：逐带暖调 + 代理图（整幅 vs 逐带）增益网格 → 应逼近整幅结果
        if (mode == "warm2") {
            Thread { runWarmFixSuite(File(indir), File(outdir).apply { mkdirs() }) }.start()
            return
        }

        // 暖调段重叠窗口验证：逐带暖调时上下各扩 E 行、只保留中间带，看带边台阶是否消失
        if (mode == "warmover") {
            Thread { runWarmOverlapSuite(File(indir), File(outdir).apply { mkdirs() }) }.start()
            return
        }

        // 纯色探针：判定 native 暖调是否含暗角（空间）与尺寸/统计依赖
        if (mode == "flat") {
            Thread { runFlatSuite(File(outdir).apply { mkdirs() }) }.start()
            return
        }

        // P5/C3：增益图重标定验收（旧增益 vs 重标定，量化 HDR 还原误差）
        if (mode == "c3") {
            Thread { runC3Suite(File(indir), File(outdir).apply { mkdirs() }, styles, limit) }.start()
            return
        }

        // V2 探针：RAW 高光余量（highlight × adjust_maximum_thr 矩阵）
        if (mode == "linprobe") {
            val dump = intent.getBooleanExtra("dump", false)
            Thread { runLinProbeSuite(File(indir), File(outdir).apply { mkdirs() }, limit, dump) }.start()
            return
        }

        // B0 接缝基线：恒等算子应「零接缝」，故意错位算子必须被 meas 出接缝（判据灵敏度自检）
        if (mode == "seam") {
            Thread { runSeamSuite(File(indir), File(outdir).apply { mkdirs() }, limit) }.start()
            return
        }

        Thread {
            val summary = StringBuilder()
            try {
                val inDir = File(indir)
                val files = inDir.listFiles { f ->
                    f.isFile && f.extension.lowercase() in setOf("jpg", "jpeg", "dng")
                }?.sortedBy { it.name } ?: emptyList()
                val use = if (limit > 0) files.take(limit) else files
                Log.i(tag, "输入文件 ${use.size}/${files.size}")
                summary.append("styles=$styles in=${use.size}\n")

                val outDir = File(outdir).apply { mkdirs() }
                var ok = 0
                var fail = 0
                use.forEachIndexed { idx, f ->
                    val t0 = System.currentTimeMillis()
                    try {
                        val bytes = f.readBytes()
                        val stem = f.nameWithoutExtension
                        if (dumpLin && f.extension.lowercase() == "dng") {
                            val buf = RawDecoder.nativeDecodeLinearSrgb(bytes)
                                ?: throw IllegalStateException("线性解码失败")
                            try {
                                File(outDir, "${stem}_lin16.bin").outputStream().use { os ->
                                    buf.rewind()
                                    val tmp = ByteArray(buf.remaining())
                                    buf.get(tmp)
                                    os.write(tmp)
                                }
                            } finally {
                                RawDecoder.nativeFreeLinear(buf)
                            }
                            ok++
                            Log.i(tag, "BATCH ${idx + 1}/${use.size} ${f.name} DUMP-LIN OK ${System.currentTimeMillis() - t0}ms")
                            summary.append("OK-LIN ${f.name} ${System.currentTimeMillis() - t0}ms\n")
                            return@forEachIndexed
                        }
                        val work = if (f.extension.lowercase() == "dng") {
                            rawWorkJpeg(bytes) ?: throw IllegalStateException("RAW 解码失败")
                        } else {
                            ImageUtils.normalizeExifOrientation(bytes)
                        }
                        if (rawOnly) {
                            // 只写解码结果（不套滤镜），用于量化核对 p50/削顶
                            File(outDir, "${stem}_raw_l${level}.jpg").writeBytes(work)
                        } else {
                            for (st in styles) {
                                val style = LeicaEngine.Style.fromId(st)
                                val out = LeicaEngine.process(this, work, style, null)
                                File(outDir, "${stem}_${style.id}.jpg").writeBytes(out)
                            }
                        }
                        ok++
                        Log.i(tag, "BATCH ${idx + 1}/${use.size} ${f.name} OK ${System.currentTimeMillis() - t0}ms")
                        summary.append("OK ${f.name} ${System.currentTimeMillis() - t0}ms\n")
                    } catch (t: Throwable) {
                        fail++
                        Log.e(tag, "BATCH ${idx + 1}/${use.size} ${f.name} FAIL", t)
                        summary.append("FAIL ${f.name} ${t.message}\n")
                    }
                }
                summary.append("done ok=$ok fail=$fail\n")
                Log.i(tag, "批量完成 ok=$ok fail=$fail")
            } catch (t: Throwable) {
                Log.e(tag, "批量异常", t)
                summary.append("FATAL ${t.message}\n")
            }
            try {
                File(outdir, "_batch_done.txt").writeText(summary.toString())
            } catch (t: Throwable) {
                Log.e(tag, "写 summary 失败", t)
            }
            finish()
        }.start()
    }

    /** 与 MainActivity.decodeRaw 的工作字节逻辑一致：LibRaw 解码 → 全尺寸 JPEG95。 */
    private fun rawWorkJpeg(bytes: ByteArray): ByteArray? {
        val decoded = if (RawToneMap.isEnabled(level)) {
            val buf = RawDecoder.nativeDecodeLinearSrgb(bytes) ?: return null
            val toned = try {
                RawToneMap.render(buf, level, RawDecoder.nativeExtractPreview(bytes))
            } finally {
                RawDecoder.nativeFreeLinear(buf)
            }
            toned ?: RawDecoder.nativeDecodeWithThr(bytes, thr)
        } else {
            RawDecoder.nativeDecodeWithThr(bytes, if (level == 0) 0.01f else thr)
        } ?: return null
        if (decoded.size < 3) return null
        val w = decoded[0]
        val h = decoded[1]
        val full = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        full.setPixels(decoded, 2, w, 0, 0, w, h)
        val work = ImageUtils.toJpeg(full, 95)
        full.recycle()
        return work
    }

    // ==================== C1：条带全像素直通验收（--es mode fullres） ====================

    /**
     * 对 indir 下每个 JPEG 依次跑（**与 MainActivity.exportFullRes 同为文件级链路**）：
     *  1) ImageUtils.encodeStripFromFile（条带解码 + native 拼接编码，源不进堆）；
     *  2) ExifTransplant.copyInPlace + LeicaMark.markFile + HdrGainMap.embedFile（头改写 + 流搬运）；
     *  3) 采样 1/8 解码，量接缝指标（边界行梯度 vs 原图同位置）与堆占用。
     * 结果写 _fullres_report.txt。
     */
    private fun runFullResSuite(indir: File, outdir: File, limit: Int) {
        val summary = StringBuilder()
        try {
            val files = indir.listFiles { f ->
                f.isFile && f.name.lowercase().let { it.endsWith(".jpg") || it.endsWith(".jpeg") }
            }?.sortedBy { it.name } ?: emptyList()
            summary.append("mode=fullres files=${files.size} limit=$limit\n")
            var idx = 0
            for (f in files) {
                if (limit > 0 && idx >= limit) break
                idx++
                summary.append(runOneFullRes(f, outdir))
            }
        } catch (t: Throwable) {
            Log.e(tag, "fullres 批量异常", t)
            summary.append("FATAL ${t.message}\n")
        }
        runCatching { File(outdir, "_fullres_report.txt").writeText(summary.toString()) }
        finish()
    }

    private fun runOneFullRes(f: File, outdir: File): String {
        val sb = StringBuilder()
        val t0 = System.currentTimeMillis()
        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.absolutePath, bounds)
            val dims = intArrayOf(bounds.outWidth, bounds.outHeight)
            val ori = runCatching { androidx.exifinterface.media.ExifInterface(f.absolutePath) }
                .map { it.getAttributeInt(androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION, 1) }
                .getOrDefault(1)
            val supports = ImageUtils.supportsFullResDirect(dims[0], dims[1], ori)
            sb.append("== ${f.name} ${dims[0]}x${dims[1]} ori=$ori src=${f.length()}B ")
                .append("heap=${ImageUtils.heapUsageLabel()} supportsDirect=$supports\n")

            val strip = File(outdir, "${f.nameWithoutExtension}_strip.jpg")
            val encT0 = System.currentTimeMillis()
            val res = ImageUtils.encodeStripFromFile(f, strip, 95, 512)
            val encMs = System.currentTimeMillis() - encT0
            sb.append("  encode ok=${res.ok} fallback=${res.fallbackCopy} ")
                .append("${res.width}x${res.height} band=${res.bandRows}x${res.bandCount} ")
                .append("${res.outBytes}B ${encMs}ms heap=${ImageUtils.heapUsageLabel()}\n")
            if (!res.ok && !res.fallbackCopy) return sb.append("  SKIP encode failed\n").toString()

            val ob = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(strip.absolutePath, ob)
            sb.append("  decode ${ob.outWidth}x${ob.outHeight}\n")

            // 尾部（文件级）：EXIF 原地回填 → 徽章头部改写 → 增益图回嵌
            val head = ImageUtils.readFileHeader(f)
            val exifOk = head != null && ExifTransplant.copyInPlace(this, head, strip, "1")
            val badgeOk = LeicaMark.markFile(strip)
            sb.append("  exif=$exifOk mark=$badgeOk ${strip.length()}B heap=${ImageUtils.heapUsageLabel()}\n")
            // 增益图提取仍需整幅源字节（仅测大文件时跳过，避免污染内存基线）
            val hdr = if (f.length() <= 40L * 1024 * 1024) HdrGainMap.extract(f.readBytes()) else null
            val hdrRes = if (hdr != null) HdrGainMap.embedFile(strip, hdr) else HdrGainMap.truncateToPrimaryFile(strip)
            sb.append("  hdr src=${hdr != null} ok=${hdrRes.ok} ${hdrRes.brief} ")
                .append("${strip.length()}B heap=${ImageUtils.heapUsageLabel()}\n")
            val dst = File(outdir, "${f.nameWithoutExtension}_fullres.jpg")
            strip.copyTo(dst, overwrite = true)
            sb.append("  saved ${dst.name} ${dst.length()}B\n")

            // 接缝：1/8 采样解码，比较边界行与原图同位置的行梯度
            val seam = measureSeam(f, dst, res.bandRows)
            sb.append("  seam $seam\n")
            sb.append("  total ${System.currentTimeMillis() - t0}ms heap=${ImageUtils.heapUsageLabel()}\n")
            runCatching { strip.delete() }
        } catch (t: Throwable) {
            Log.e(tag, "fullres 单张失败 ${f.name}", t)
            sb.append("  FAIL ${t.javaClass.simpleName}: ${t.message} ")
                .append("total ${System.currentTimeMillis() - t0}ms\n")
        }
        return sb.toString()
    }

    // ==================== P5/C3：增益图重标定验收（--es mode c3） ====================

    /**
     * 对 indir 下的 UltraHDR JPEG × 每个风格产出四组对照：
     *  1) `<stem>_<style>_sdr.jpg`  ：套色后的 SDR 主图（无增益图，供看 SDR 画面）；
     *  2) `<stem>_<style>_c1.jpg`   ：新 SDR + **旧**增益图（C1 直通语义，错误基线）；
     *  3) `<stem>_<style>_c3.jpg`   ：新 SDR + 重标定增益图（C3，`rebakeJpeg` 字节链）；
     *  4) `<stem>_<style>_c3file.jpg`：同上但走 `rebakeFiles`（文件级，与全像素直通同函数）。
     *
     * 量化：以「旧 SDR + 旧增益」重建的 HDR 亮度为基准，分别算 C1/C3 的 EV 误差与 8bit 码值误差；
     * C3 的误差显著低于 C1 即说明重标定生效。结果写 `_c3_report.txt`。
     */
    private fun runC3Suite(indir: File, outdir: File, styles: List<String>, limit: Int) {
        val sb = StringBuilder()
        try {
            val files = indir.listFiles { f ->
                f.isFile && f.name.lowercase().let { it.endsWith(".jpg") || it.endsWith(".jpeg") }
            }?.sortedBy { it.name } ?: emptyList()
            sb.append("mode=c3 files=${files.size} limit=$limit styles=$styles ")
                .append("rebakeEnabled=${HdrGainMap.REBAKE_ENABLED}\n")
            sb.append("heap=${ImageUtils.heapUsageLabel()}\n")
            var idx = 0
            var hit = 0
            for (f in files) {
                if (limit > 0 && idx >= limit) break
                idx++
                val block = runOneC3(f, outdir, styles)
                if (block.contains(" verify=true ")) hit++
                sb.append(block)
            }
            sb.append("done hit=$hit/${minOf(idx, files.size)}\n")
        } catch (t: Throwable) {
            Log.e(tag, "c3 suite 失败", t)
            sb.append("FAIL ${t.javaClass.simpleName}: ${t.message}\n")
        }
        File(outdir, "_c3_report.txt").writeText(sb.toString())
        Log.i(tag, "c3 done -> ${outdir.absolutePath}/_c3_report.txt")
        finish()
    }

    // ==================== V2 探针：RAW 高光余量（--es mode linprobe） ====================

    /**
     * (highlight, adjust_maximum_thr) 矩阵。
     *
     * `amt=0.75` 是 LibRaw 默认（按直方图自动拉白点）；`1.0` 关闭自动调整 → 使用相机白点，
     * **只有这一维才可能让超过白点的原始数据留下来**。历史 §5.1 的「Blend ≡ Clip」是在
     * 默认 amt 下测的，本探针第一次把 amt 作为自变量。
     */
    private val linProbeMatrix = listOf(
        Triple(0, 0.75f, "A_clip_auto"),
        Triple(0, 1.0f, "B_clip_fixed"),
        Triple(2, 0.75f, "C_blend_auto"),
        Triple(2, 1.0f, "D_blend_fixed"),
        Triple(5, 1.0f, "E_recon5_fixed"),
        Triple(6, 1.0f, "F_recon6_fixed")
    )

    /**
     * RAW 高光余量探针：判定 LibRaw 是否还留有「超过相机白点」的原始高光，
     * 决定 V2b（RAW 真 HDR 的超白点还原）是否值得做。
     *
     * 每组输出：几何、亮度最大值、p99/p99.9/p99.99、顶格占比、高光末端 6 段分布。
     * `--ez dump true` 时额外把线性数据落盘（8 字节 int w/h + u16 RGB，本机字节序），
     * 供 PC 端 `m9_fb_work/linprobe_stats.py` 做空间分析；默认只出报告
     * （200MP × 6 组约 1.8GB，不默认写盘）。
     *
     * adb:
     *   am start -n com.leicamoments.app/.BatchTestActivity --es mode linprobe \
     *     --es indir /sdcard/LeicaTestIn --es outdir /sdcard/LeicaTestOut
     */
    private fun runLinProbeSuite(indir: File, outdir: File, limit: Int, dump: Boolean) {
        val sb = StringBuilder()
        try {
            val files = indir.listFiles { f ->
                f.isFile && f.extension.lowercase() == "dng"
            }?.sortedBy { it.name } ?: emptyList()
            val use = if (limit > 0) files.take(limit) else files
            sb.append("mode=linprobe files=${use.size}/${files.size} dump=$dump\n")
            sb.append("matrix: ").append(linProbeMatrix.joinToString { it.third }).append('\n')
            use.forEachIndexed { idx, f ->
                sb.append("\n== ${f.name} ${f.length()}B\n")
                val bytes = runCatching { f.readBytes() }.getOrNull()
                if (bytes == null) {
                    sb.append("  READ_FAIL\n")
                    return@forEachIndexed
                }
                for ((hl, amt, name) in linProbeMatrix) {
                    val t0 = System.currentTimeMillis()
                    val buf = try {
                        RawDecoder.nativeDecodeLinearSrgbEx(bytes, hl, amt)
                    } catch (t: Throwable) {
                        Log.e(tag, "linprobe $name 解码异常", t)
                        null
                    }
                    if (buf == null) {
                        sb.append("  $name hl=$hl amt=$amt DECODE_FAIL ${System.currentTimeMillis() - t0}ms\n")
                        continue
                    }
                    try {
                        if (dump) {
                            val dst = File(outdir, "${f.nameWithoutExtension}_${name}.lin")
                            runCatching {
                                // 分块搬运：200MP 线性约 300MB，不能整块进 Java 堆
                                dst.outputStream().use { os ->
                                    buf.rewind()
                                    val chunk = ByteArray(1 shl 20)
                                    while (buf.remaining() > 0) {
                                        val n = minOf(buf.remaining(), chunk.size)
                                        buf.get(chunk, 0, n)
                                        os.write(chunk, 0, n)
                                    }
                                }
                            }.onFailure { sb.append("  $name DUMP_FAIL ${it.message}\n") }
                        }
                        sb.append("  $name hl=$hl amt=$amt ")
                            .append(probeStats(buf))
                            .append(" ${System.currentTimeMillis() - t0}ms\n")
                    } finally {
                        RawDecoder.nativeFreeLinear(buf)
                    }
                }
                Log.i(tag, "linprobe ${idx + 1}/${use.size} ${f.name} done")
            }
        } catch (t: Throwable) {
            Log.e(tag, "linprobe suite 失败", t)
            sb.append("FATAL ${t.javaClass.simpleName}: ${t.message}\n")
        }
        runCatching { File(outdir, "_linprobe_report.txt").writeText(sb.toString()) }
        runCatching { File(outdir, "_linprobe_done.txt").writeText("done\n") }
        Log.i(tag, "linprobe done -> ${outdir.absolutePath}/_linprobe_report.txt")
        finish()
    }

    /**
     * 线性缓冲统计：BT.709 亮度 65536 桶直方图，按像素数自适应步进采样
     * （200MP 也秒级）。指标语义：
     *
     * - `max` / `p9999`：线性值上界（归一化到 65535），若 amt=1.0 组明显低于基线，
     *   说明白点没被拉满、超白点有机会保留；
     * - `capped`：亮度 ≥ 65500 的像素占比 —— 直接反映「被顶到上界」的比例，
     *   这是 clip 的直接证据；
     * - `tail`：64000→65535 分 6 段的万分比分布，尖峰=被削平，平缓=有动态余量。
     */
    private fun probeStats(buf: java.nio.ByteBuffer): String {
        val bb = buf.order(java.nio.ByteOrder.nativeOrder())
        val w = bb.getInt(0)
        val h = bb.getInt(4)
        if (w <= 0 || h <= 0) return "BAD_DIM ${w}x$h"
        val shorts = bb.asShortBuffer()
        val count = w * h
        val step = if (count > 12_000_000) 8 else if (count > 3_000_000) 4 else 1
        val hist = IntArray(65536)
        var n = 0L
        var i = 4
        val end = 4 + count * 3
        val inc = 3 * step
        while (i + 2 < end) {
            val r = shorts.get(i).toInt() and 0xFFFF
            val g = shorts.get(i + 1).toInt() and 0xFFFF
            val b = shorts.get(i + 2).toInt() and 0xFFFF
            hist[(r * 77 + g * 150 + b * 29) shr 8]++
            n++
            i += inc
        }
        if (n == 0L) return "EMPTY"
        fun q(p: Double): Int {
            val target = (p * n).toLong().coerceAtLeast(1L)
            var acc = 0L
            for (k in hist.indices) {
                acc += hist[k]
                if (acc >= target) return k
            }
            return 65535
        }
        var capped = 0L
        for (k in 65500..65535) capped += hist[k]
        val tail = IntArray(6)
        for (k in 0 until 6) {
            val from = 64000 + k * 256
            var s = 0L
            for (x in from until from + 256) if (x < 65536) s += hist[x]
            tail[k] = (s * 10000 / n).toInt()
        }
        return "w=$w h=$h step=$step max=${q(1.0)} " +
                "p99=${q(0.99)} p999=${q(0.999)} p9999=${q(0.9999)} " +
                "capped=" + String.format(java.util.Locale.US, "%.3f", capped * 100.0 / n) + "% " +
                "tail=" + tail.joinToString(",")
    }

    private fun runOneC3(f: File, outdir: File, styles: List<String>): String {
        val sb = StringBuilder()
        val src = f.readBytes()
        val hdr = HdrGainMap.extract(src)
        val stem = f.nameWithoutExtension
        sb.append("== $stem ${src.size}B hdr=${hdr != null}")
        if (hdr == null) return sb.append(" SKIP 无增益图\n").toString()
        val gb = BitmapFactory.decodeByteArray(hdr.gainmapJpeg, 0, hdr.gainmapJpeg.size)
        sb.append(" gain=${hdr.gainmapJpeg.size}B ${gb?.width}x${gb?.height}").append('\n')
        gb?.recycle()
        sb.append("   meta[min=${hdr.minLog2} max=${hdr.maxLog2} gamma=${hdr.gamma} ")
            .append("offSdr=${hdr.offsetSdr} offHdr=${hdr.offsetHdr} ")
            .append("capMin=${hdr.capMinLog2} capMax=${hdr.capMaxLog2}]\n")

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(src, 0, src.size, bounds)
        val srcW = bounds.outWidth
        val srcH = bounds.outHeight

        val work = ImageUtils.normalizeExifOrientation(src)
        for (st in styles) {
            // fromId 会把未知 id 回落成 M9，验收必须精确匹配
            val style = LeicaEngine.Style.values().firstOrNull { it.id.equals(st, ignoreCase = true) }
            if (style == null) {
                sb.append("  SKIP style=$st\n")
                continue
            }
            val sdr = LeicaEngine.process(this, work, style, null)
            val c1 = HdrGainMap.embed(sdr, hdr)

            val t0 = System.currentTimeMillis()
            val rb = HdrGainMap.rebakeJpeg(src, sdr, hdr)
            val rbMs = System.currentTimeMillis() - t0

            val tmpSdr = File(outdir, ".tmp_${stem}_${style.id}_sdr.jpg")
            tmpSdr.writeBytes(sdr)
            val t1 = System.currentTimeMillis()
            val rbFile = if (rb != null) HdrGainMap.rebakeFiles(f, tmpSdr, hdr, srcW, srcH) else null
            val fileMs = System.currentTimeMillis() - t1
            tmpSdr.delete()

            val c3 = if (rb != null) HdrGainMap.embed(sdr, rb) else c1
            val c3f = if (rbFile != null) HdrGainMap.embed(sdr, rbFile) else sdr
            File(outdir, "${stem}_${style.id}_sdr.jpg").writeBytes(sdr)
            File(outdir, "${stem}_${style.id}_c1.jpg").writeBytes(c1)
            File(outdir, "${stem}_${style.id}_c3.jpg").writeBytes(c3)
            File(outdir, "${stem}_${style.id}_c3file.jpg").writeBytes(c3f)

            val v3 = HdrGainMap.verify(c3)
            sb.append("  ${style.id} sdr=${sdr.size}B c1=${c1.size}B c3=${c3.size}B ")
                .append("rebake=${if (rb != null) "${rb.gainmapJpeg.size}B/${rbMs}ms" else "NULL"} ")
                .append("file=${if (rbFile != null) "${rbFile.gainmapJpeg.size}B/${fileMs}ms" else "NULL"} ")
                .append("verify=${v3.ok} ${v3.brief}\n")
            if (!v3.ok) sb.append("    verifyDetail=${v3.detail}\n")
            // 平台侧定性：ImageDecoder 是否把导出图识别为 UltraHDR（有 gainmap 即成功）
            sb.append("    platform(").append(style.id).append(")=")
                .append(platformGainmapInfo(File(outdir, "${stem}_${style.id}_c3.jpg")))
                .append(" | c1=")
                .append(platformGainmapInfo(File(outdir, "${stem}_${style.id}_c1.jpg")))
                .append('\n')

            sb.append("    C1(旧增益)  ").append(c3ErrText(measureC3(hdr, src, sdr, hdr))).append('\n')
            sb.append("    C3(重标定)  ").append(c3ErrText(measureC3(hdr, src, sdr, rb))).append('\n')
            sb.append("    C3F(文件级) ").append(c3ErrText(measureC3(hdr, src, sdr, rbFile))).append('\n')
        }
        return sb.toString()
    }

    private fun c3ErrText(e: C3Err?): String = e?.toString() ?: "n/a"

    /**
     * 平台侧定性：ImageDecoder 解码后 Bitmap.gainmap 非空 = 系统认这张图为 UltraHDR。
     * （自写 Container 若是元素式而非属性式，某些解码器可能找不到增益图，此检查可直接暴露）
     */
    private fun platformGainmapInfo(f: File): String {
        return try {
            val src = ImageDecoder.createSource(f)
            val bmp = ImageDecoder.decodeBitmap(src) { decoder, _, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
            var out = "no-gainmap"
            if (Build.VERSION.SDK_INT >= 34) {
                val gm = bmp.gainmap
                out = if (gm == null) {
                    "NULL"
                } else {
                    val gc = gm.gainmapContents
                    val ratio = gm.ratioMax?.joinToString(",") {
                        String.format(java.util.Locale.US, "%.2f", it)
                    } ?: "?"
                    "${gc?.width}x${gc?.height} ratioMax=$ratio"
                }
                }
            bmp.recycle()
            out
        } catch (t: Throwable) {
            "ERR ${t.javaClass.simpleName}:${t.message}"
        }
    }

    private class C3Err(
        val evMae: Double,
        val evP99: Double,
        val evMax: Double,
        val codeMae: Double,
        val clip: Double,
        val n: Int,
        val skipped: Int
    ) {
        override fun toString(): String = String.format(
            java.util.Locale.US,
            "EVmae=%.4f EVp99=%.4f EVmax=%.4f codeMAE=%.2f >0.5EV=%.2f%% n=%d skip=%d",
            evMae, evP99, evMax, codeMae, clip * 100.0, n, skipped
        )
    }

    /** sRGB 编码值 → 线性光（256 级查表）；与 HdrGainMap 内部口径一致 */
    private val c3S2L = FloatArray(256) { i ->
        val c = i / 255.0
        (if (c <= 0.04045) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)).toFloat()
    }

    private fun c3Luma(p: Int): Double =
        (0.299 * c3S2L[(p shr 16) and 0xFF] +
            0.587 * c3S2L[(p shr 8) and 0xFF] +
            0.114 * c3S2L[p and 0xFF])

    private fun c3Encode(l: Double): Double {
        val c = l.coerceIn(0.0, 1.0)
        return if (c <= 0.0031308) c * 12.92 else 1.055 * Math.pow(c, 1.0 / 2.4) - 0.055
    }

    private fun decodeForMeasure(jpeg: ByteArray, tw: Int, th: Int): Bitmap? {
        val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, b)
        if (b.outWidth <= 0 || b.outHeight <= 0) return null
        var s = 1
        while (s < 8 && b.outWidth / (s * 2) >= tw && b.outHeight / (s * 2) >= th) s *= 2
        val bmp = BitmapFactory.decodeByteArray(
            jpeg, 0, jpeg.size, BitmapFactory.Options().apply { inSampleSize = s }
        ) ?: return null
        if (bmp.width == tw && bmp.height == th) return bmp
        val scaled = Bitmap.createScaledBitmap(bmp, tw, th, true)
        if (scaled !== bmp) bmp.recycle()
        return scaled
    }

    /**
     * 以「旧 SDR + 旧增益」重建的 HDR 亮度为基准，算 newSdr × newGain 的重建误差。
     * newGain 传旧 hdr 时即 C1 基线（分母换了增益没换）。
     */
    private fun measureC3(
        old: HdrGainMap.HdrData,
        oldSdr: ByteArray,
        newSdr: ByteArray,
        newGain: HdrGainMap.HdrData?
    ): C3Err? {
        var gOld: Bitmap? = null
        var gNew: Bitmap? = null
        var oBmp: Bitmap? = null
        var nBmp: Bitmap? = null
        try {
            gOld = BitmapFactory.decodeByteArray(old.gainmapJpeg, 0, old.gainmapJpeg.size) ?: return null
            gNew = newGain?.let { BitmapFactory.decodeByteArray(it.gainmapJpeg, 0, it.gainmapJpeg.size) }
                ?: return null
            val gw = gOld.width
            val gh = gOld.height
            if (gNew.width != gw || gNew.height != gh) return null
            oBmp = decodeForMeasure(oldSdr, gw, gh) ?: return null
            nBmp = decodeForMeasure(newSdr, gw, gh) ?: return null

            val offSdr = old.offsetSdr.toDoubleOrNull() ?: 0.0
            val offHdr = old.offsetHdr.toDoubleOrNull() ?: 0.0
            val minL2 = old.minLog2.toDoubleOrNull() ?: 0.0
            val maxL2 = old.maxLog2.toDoubleOrNull() ?: 1.0
            val gamma = (old.gamma.toDoubleOrNull() ?: 1.0).takeIf { it > 1e-6 } ?: 1.0
            val minN = newGain.minLog2.toDoubleOrNull() ?: minL2
            val maxN = newGain.maxLog2.toDoubleOrNull() ?: maxL2
            val gammaN = (newGain.gamma.toDoubleOrNull() ?: gamma).takeIf { it > 1e-6 } ?: 1.0
            val span = (maxL2 - minL2).takeIf { Math.abs(it) > 1e-6 } ?: 1.0
            val spanN = (maxN - minN).takeIf { Math.abs(it) > 1e-6 } ?: 1.0
            val invG = 1.0 / gamma
            val invGN = 1.0 / gammaN

            val gr = IntArray(gw)
            val gn = IntArray(gw)
            val orow = IntArray(gw)
            val nrow = IntArray(gw)
            val hist = IntArray(4001) // 0..4 EV，步长 0.001
            var sumEv = 0.0
            var sumCode = 0.0
            var cnt = 0
            var skipped = 0
            var over = 0
            var maxEv = 0.0

            for (y in 0 until gh) {
                gOld.getPixels(gr, 0, gw, 0, y, gw, 1)
                gNew.getPixels(gn, 0, gw, 0, y, gw, 1)
                oBmp.getPixels(orow, 0, gw, 0, y, gw, 1)
                nBmp.getPixels(nrow, 0, gw, 0, y, gw, 1)
                for (x in 0 until gw) {
                    val p1 = ((gr[x] shr 16) and 0xFF) / 255.0
                    val p2 = ((gn[x] shr 16) and 0xFF) / 255.0
                    val q1 = if (gamma == 1.0) p1 else Math.pow(p1, invG)
                    val q2 = if (gammaN == 1.0) p2 else Math.pow(p2, invGN)
                    val ref = (c3Luma(orow[x]) + offSdr) * Math.pow(2.0, minL2 + span * q1) - offHdr
                    val v = (c3Luma(nrow[x]) + offSdr) * Math.pow(2.0, minN + spanN * q2) - offHdr
                    if (ref <= 1e-4) {
                        skipped++
                        continue
                    }
                    val num = v + offHdr
                    val den = ref + offHdr
                    val ev = if (num <= 1e-6 || den <= 1e-6) 8.0 else Math.abs(Math.log(num / den) / LN2)
                    sumEv += ev
                    sumCode += Math.abs(c3Encode(ref) - c3Encode(v)) * 255.0
                    if (ev > 0.5) over++
                    if (ev > maxEv) maxEv = ev
                    val bin = (ev * 1000.0).toInt().coerceIn(0, 4000)
                    hist[bin]++
                    cnt++
                }
            }
            if (cnt <= 0) return null
            val target = (cnt * 0.99).toInt().coerceAtLeast(1)
            var acc = 0
            var p99 = 4.0
            for (i in hist.indices) {
                acc += hist[i]
                if (acc >= target) {
                    p99 = i / 1000.0
                    break
                }
            }
            return C3Err(sumEv / cnt, p99, maxEv, sumCode / cnt, over.toDouble() / cnt, cnt, skipped)
        } catch (t: Throwable) {
            Log.w(tag, "measureC3 失败", t)
            return null
        } finally {
            gOld?.recycle()
            gNew?.recycle()
            oBmp?.recycle()
            nBmp?.recycle()
        }
    }

    private companion object {
        private const val LN2 = 0.6931471805599453
    }

    // ==================== B0：条带基础设施接缝基线（--es mode seam） ====================

    /**
     * 恒等算子（shift=0）走完 encodeStripFromFile：输出应≈原图（仅 q95 重编码），
     * 若 seamMae 明显高于 globalMae → 条带读写/拼装本身有接缝，与滤镜无关。
     * shift=1 故意把带内像素整体错位一行 → seamMae 必须暴涨，否则说明判据不灵敏。
     * 带高取 320（200MP 实际值）与 1280（大带）。
     */
    private fun runSeamSuite(indir: File, outdir: File, limit: Int) {
        val sb = StringBuilder()
        try {
            val files = indir.listFiles { f ->
                f.isFile && f.name.lowercase().let { it.endsWith(".jpg") || it.endsWith(".jpeg") }
            }?.sortedBy { it.name } ?: emptyList()
            sb.append("mode=seam files=${files.size} limit=$limit\n")
            val f = files.firstOrNull()
            if (f == null) {
                sb.append("NO INPUT\n")
            } else {
                sb.append("input=${f.name} heap=${ImageUtils.heapUsageLabel()}\n")
                for (bandRows in intArrayOf(320, 1280)) {
                    for (brk in booleanArrayOf(false, true)) {
                        val tagName = if (brk) "brk" else "id"
                        val out = File(outdir, "seam_${f.nameWithoutExtension}_b${bandRows}_$tagName.jpg")
                        val t0 = System.currentTimeMillis()
                        val res = ImageUtils.encodeStripFromFile(
                                f, out, 95, bandRows, op = IdentityOp(brk), strict = true)
                        val ms = System.currentTimeMillis() - t0
                        if (!res.ok) {
                            sb.append("  b=$bandRows $tagName FAILED ${res.width}x${res.height}\n")
                            continue
                        }
                        sb.append("  b=$bandRows $tagName ${res.bandRows}x${res.bandCount} ")
                            .append("${res.outBytes}B ${ms}ms\n")
                        sb.append("    ${compareMae(f, out, res.bandRows)}\n")
                    }
                }
                sb.append("heapEnd=${ImageUtils.heapUsageLabel()}\n")
            }
        } catch (t: Throwable) {
            Log.e(tag, "seam 基线异常", t)
            sb.append("FATAL ${t.message}\n")
        }
        runCatching { File(outdir, "_seam_report.txt").writeText(sb.toString()) }
        finish()
    }

    /**
     * 恒等 / 故意破坏带边界算子（只破坏非首带的第 0 行，用于验证接缝判据灵敏度）。
     * 破坏只影响 1 行，若 seamMae 不显著高于 globalMae 就说明判据抓不到接缝。
     */
    private class IdentityOp(private val breakBoundary: Boolean) : ImageUtils.StripBandOp {
        override fun prepare(reader: ImageUtils.StripSource): Boolean = true

        override fun apply(band: Bitmap, top: Int, rows: Int, fullW: Int, fullH: Int): Bitmap? {
            if (!breakBoundary || top == 0) return band
            val row = IntArray(fullW)
            band.getPixels(row, 0, fullW, 0, 0, fullW, 1)
            val out = IntArray(fullW)
            val off = fullW / 3
            for (x in 0 until fullW) out[x] = row[(x + off) % fullW]
            band.setPixels(out, 0, fullW, 0, 0, fullW, 1)
            return band
        }
    }

    // ==================== 乙档：模型链条带导出验收（--es mode band） ====================

    /**
     * 对 indir 下每个 JPEG × 每个模型链风格：
     *  1) 整图链（LeicaEngine.process）作为参考（仅 ≤12.5MP 有意义：整图链本身会 fit 到 4096x3072）；
     *  2) 条带链（DistillBandOps + encodeStripFromFile，与 exportFullRes 同链路）实测；
     *  3) 量化 globalMae / 带边界 MAE —— 接缝判据：seamMae ≈ globalMae（×1.0 附近）即无接缝；
     * 结果写 _band_report.txt。
     *
     * 200MP 大图只跑条带链（整图链会把整幅解码进堆，等价性也不成立）。
     */
    private fun runBandSuite(indir: File, outdir: File, limit: Int, styles: List<String>) {
        val sb = StringBuilder()
        try {
            val files = indir.listFiles { f ->
                f.isFile && f.name.lowercase().let { it.endsWith(".jpg") || it.endsWith(".jpeg") }
            }?.sortedBy { it.name } ?: emptyList()
            sb.append("mode=band wov=${DistillBandOps.warmOverlap} "
                    + "files=${files.size} limit=$limit styles=$styles\n")
            var idx = 0
            for (f in files) {
                if (limit > 0 && idx >= limit) break
                idx++
                for (st in styles) {
                    // fromId 对未知 id 会回落到 M9，验收必须精确匹配 styles 参数
                    val style = LeicaEngine.Style.values()
                        .firstOrNull { it.id.equals(st, ignoreCase = true) }
                    if (style == null || !DistillBandOps.supports(style)) {
                        sb.append("SKIP ${f.name} $st\n")
                        continue
                    }
                    sb.append(runOneBand(f, outdir, style))
                }
            }
        } catch (t: Throwable) {
            Log.e(tag, "band 批量异常", t)
            sb.append("FATAL ${t.message}\n")
        }
        runCatching { File(outdir, "_band_report.txt").writeText(sb.toString()) }
        finish()
    }

    /**
     * 纯色探针：对纯灰图套暖调 LUT，观察输出是否空间均匀（有无暗角）、是否随位图尺寸/灰度变化。
     * 用于判定 native 暖调与「内容统计 / 几何」的依赖关系。
     */
    private fun runFlatSuite(outdir: File) {
        val sb = StringBuilder()
        val rf = File(outdir, "_flat_report.txt")
        fun flush() = runCatching { rf.writeText(sb.toString()) }
        try {
            fun probe(w: Int, h: Int, gray: Int, label: String) {
                val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                val px = IntArray(w)
                val c = (0xFF shl 24) or (gray shl 16) or (gray shl 8) or gray
                java.util.Arrays.fill(px, c)
                for (y in 0 until h) b.setPixels(px, 0, w, 0, y, w, 1)
                val r = LegendaryPostProcessor.applyWarmLutBitmapBinAt(
                    this, b, WARM_BIN, WARM_DN, WARM_EV, WARM_WA, 12345f)
                if (r == null) {
                    sb.append("  $label ${w}x$h gray=$gray FAILED\n")
                    flush()
                    return
                }
                val row = IntArray(w)
                val rowMean = FloatArray(h)
                for (y in 0 until h) {
                    r.getPixels(row, 0, w, 0, y, w, 1)
                    var s = 0L
                    for (x in 0 until w) s += (row[x] shr 8) and 0xFF
                    rowMean[y] = (s.toFloat() / w)
                }
                r.getPixels(row, 0, w, 0, h / 2, w, 1)
                fun gAt(x: Int) = (row[x] shr 8) and 0xFF
                // 归一化纵向剖面（9 点）：判定场形只依赖宽高比，还是也随绝对像素尺寸变化
                val pts = intArrayOf(0, h / 8, h / 4, 3 * h / 8, h / 2,
                    5 * h / 8, 3 * h / 4, 7 * h / 8, h - 1)
                val prof = pts.joinToString(",") { "%.1f".format(rowMean[it]) }
                sb.append(
                    "  $label ${w}x$h gray=$gray ar=${"%.2f".format(w.toFloat() / h)}" +
                        " outRow0=${"%.1f".format(rowMean[0])}" +
                        " rowMid=${"%.1f".format(rowMean[h / 2])}" +
                        " rowLast=${"%.1f".format(rowMean[h - 1])}" +
                        " midRowCC=${gAt(w / 2)} midRowEdge=${gAt(0)}/${gAt(w - 1)}" +
                        " prof=$prof\n"
                )
                b.recycle()
                r.recycle()
                flush()
            }
            sb.append("mode=flat heap=${ImageUtils.heapUsageLabel()}\n")
            flush()
            probe(1024, 768, 128, "small")
            probe(4096, 3072, 128, "full")
            // A 组：固定宽 4096，只改高度 → 宽高比依赖
            probe(4096, 768, 128, "ar5")
            probe(4096, 256, 128, "ar16")
            probe(4096, 128, 128, "ar32")
            probe(4096, 64, 128, "ar64")
            // B 组：固定宽高比 63.75:1，只改绝对尺寸 → 绝对尺寸依赖（真实带 16320x256 vs 代理带 1889x30）
            probe(16320, 256, 128, "ar64-real")
            probe(8160, 128, 128, "ar64-half")
            probe(4080, 64, 128, "ar64-quarter")
            probe(2040, 32, 128, "ar64-eighth")
            probe(1889, 30, 128, "ar64-proxy")
            // 灰度依赖（保留既有探针）
            probe(4096, 768, 40, "dark")
            probe(4096, 768, 200, "bright")
        } catch (t: Throwable) {
            Log.e(tag, "flat 探针失败", t)
            sb.append("FATAL ${t.message}\n")
        }
        flush()
        finish()
    }

    private val WARM_BIN = "leica_filter_param_m9_snapshot.bin"
    private val WARM_DN = 0.6f * 0.33f
    private val WARM_EV = -0.95f
    private val WARM_WA = 0.3f

    private fun warmOn(src: Bitmap): Bitmap? = LegendaryPostProcessor.applyWarmLutBitmapBinAt(
        this, src.copy(Bitmap.Config.ARGB_8888, true), WARM_BIN, WARM_DN, WARM_EV, WARM_WA, 12345f)

    private fun warmBands(src: Bitmap, bandRows: Int): Bitmap {
        val w = src.width
        val h = src.height
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val cv = android.graphics.Canvas(out)
        var top = 0
        while (top < h) {
            val rows = minOf(bandRows, h - top)
            val crop = Bitmap.createBitmap(src, 0, top, w, rows).copy(Bitmap.Config.ARGB_8888, true)
            val wr = warmOn(crop)
            if (wr != null) cv.drawBitmap(wr, 0f, top.toFloat(), null)
            top += rows
        }
        return out
    }

    /** 按显式带顶/带高（可为不等高，如末带较短）逐带暖调后拼回整幅。 */
    private fun warmBandsAt(src: Bitmap, tops: IntArray, rowsArr: IntArray): Bitmap {
        val w = src.width
        val h = src.height
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val cv = android.graphics.Canvas(out)
        for (i in tops.indices) {
            val crop = Bitmap.createBitmap(src, 0, tops[i], w, rowsArr[i]).copy(Bitmap.Config.ARGB_8888, true)
            val wr = warmOn(crop)
            if (wr != null) cv.drawBitmap(wr, 0f, tops[i].toFloat(), null)
        }
        return out
    }

    /** 由代理图（整幅暖调 vs 逐带暖调）拟合 [gw]x[gh] 的逐通道仿射修正：whole ≈ a*band + b。 */
    private fun fitAffineGrid(whole: Bitmap, band: Bitmap, gw: Int, gh: Int): FloatArray {
        val w = whole.width
        val h = whole.height
        val rowW = IntArray(w)
        val rowB = IntArray(w)
        val sx = DoubleArray(gw * gh * 3)
        val sy = DoubleArray(gw * gh * 3)
        val sxx = DoubleArray(gw * gh * 3)
        val sxy = DoubleArray(gw * gh * 3)
        val cnt = IntArray(gw * gh * 3)
        for (py in 0 until h) {
            whole.getPixels(rowW, 0, w, 0, py, w, 1)
            band.getPixels(rowB, 0, w, 0, py, w, 1)
            val gy = ((py.toLong() * gh) / h).toInt().coerceIn(0, gh - 1)
            for (x in 0 until w) {
                val gx = ((x.toLong() * gw) / w).toInt().coerceIn(0, gw - 1)
                val cell = gy * gw + gx
                val cw = rowW[x]
                val cb = rowB[x]
                for (k in 0 until 3) {
                    val vw = (cw shr (k * 8)) and 0xFF
                    val vb = (cb shr (k * 8)) and 0xFF
                    if (vw >= 8 && vb >= 8) {
                        val i = cell * 3 + k
                        sx[i] += vb.toDouble()
                        sy[i] += vw.toDouble()
                        sxx[i] += (vb * vb).toDouble()
                        sxy[i] += (vb * vw).toDouble()
                        cnt[i]++
                    }
                }
            }
        }
        val raw = FloatArray(gw * gh * 6)
        for (cell in 0 until gw * gh) {
            for (k in 0 until 3) {
                val i = cell * 3 + k
                val n = cnt[i]
                var a = 1.0
                var b = 0.0
                if (n >= 24) {
                    val den = n * sxx[i] - sx[i] * sx[i]
                    if (den > 1e-6) {
                        a = (n * sxy[i] - sx[i] * sy[i]) / den
                        b = (sy[i] - a * sx[i]) / n
                        if (a !in 0.5..2.0 || b !in -96.0..96.0) {
                            a = 1.0
                            b = 0.0
                        }
                    }
                }
                raw[cell * 3 + k] = a.toFloat()
                raw[(gw * gh * 3) + cell * 3 + k] = b.toFloat()
            }
        }
        // 两轮 3x3 平滑，抹掉代理图缩放/噪声带来的网格抖动（a 平面与 b 平面分别平滑）
        val aBase = gw * gh * 3
        var cur = raw
        repeat(2) {
            val nxt = FloatArray(raw.size)
            for (pa in 0 until 2) {
                val base = pa * aBase
                for (gy in 0 until gh) for (gx in 0 until gw) for (k in 0 until 3) {
                    var s = 0f
                    var c = 0
                    for (dy in -1..1) for (dx in -1..1) {
                        val yy = gy + dy
                        val xx = gx + dx
                        if (yy in 0 until gh && xx in 0 until gw) {
                            s += cur[base + (yy * gw + xx) * 3 + k]
                            c++
                        }
                    }
                    nxt[base + (gy * gw + gx) * 3 + k] = s / c
                }
            }
            cur = nxt
        }
        return cur
    }

    private fun applyAffineGrid(bmp: Bitmap, grid: FloatArray, gw: Int, gh: Int) {
        val w = bmp.width
        val h = bmp.height
        val row = IntArray(w)
        val aBase = gw * gh * 3
        for (y in 0 until h) {
            bmp.getPixels(row, 0, w, 0, y, w, 1)
            val fy = ((y + 0.5f) / h * gh - 0.5f).coerceIn(0f, (gh - 1).toFloat())
            val y0 = fy.toInt().coerceAtMost(gh - 1)
            val y1 = (y0 + 1).coerceAtMost(gh - 1)
            val wy = fy - y0
            for (x in 0 until w) {
                val fx = ((x + 0.5f) / w * gw - 0.5f).coerceIn(0f, (gw - 1).toFloat())
                val x0 = fx.toInt().coerceAtMost(gw - 1)
                val x1 = (x0 + 1).coerceAtMost(gw - 1)
                val wx = fx - x0
                val c00 = y0 * gw + x0
                val c01 = y0 * gw + x1
                val c10 = y1 * gw + x0
                val c11 = y1 * gw + x1
                val c = row[x]
                var out = 0xFF shl 24
                for (k in 0 until 3) {
                    val a = (grid[c00 * 3 + k] * (1 - wx) + grid[c01 * 3 + k] * wx) * (1 - wy) +
                        (grid[c10 * 3 + k] * (1 - wx) + grid[c11 * 3 + k] * wx) * wy
                    val b = (grid[aBase + c00 * 3 + k] * (1 - wx) + grid[aBase + c01 * 3 + k] * wx) * (1 - wy) +
                        (grid[aBase + c10 * 3 + k] * (1 - wx) + grid[aBase + c11 * 3 + k] * wx) * wy
                    val v = ((c shr (k * 8)) and 0xFF) * a + b
                    out = out or (v.toInt().coerceIn(0, 255) shl (k * 8))
                }
                row[x] = out
            }
            bmp.setPixels(row, 0, w, 0, y, w, 1)
        }
    }

    /**
     * 逐格亮度分段修正表：每个网格格、每通道建立 band 亮度级 → whole 目标值 的 32 级查找表。
     * 比仿射强，可表达「暗角强度随亮度非线性变化」。
     */
    private fun fitToneGrid(whole: Bitmap, band: Bitmap, gw: Int, gh: Int, levels: Int): FloatArray {
        val w = whole.width
        val h = whole.height
        val cells = gw * gh * 3
        val cnt = IntArray(cells * levels)
        val sum = DoubleArray(cells * levels)
        val rowW = IntArray(w)
        val rowB = IntArray(w)
        for (py in 0 until h) {
            whole.getPixels(rowW, 0, w, 0, py, w, 1)
            band.getPixels(rowB, 0, w, 0, py, w, 1)
            val gy = ((py.toLong() * gh) / h).toInt().coerceIn(0, gh - 1)
            for (x in 0 until w) {
                val gx = ((x.toLong() * gw) / w).toInt().coerceIn(0, gw - 1)
                val cell = (gy * gw + gx) * 3
                val cw = rowW[x]
                val cb = rowB[x]
                for (k in 0 until 3) {
                    val vb = (cb shr (k * 8)) and 0xFF
                    val vw = (cw shr (k * 8)) and 0xFF
                    val lv = ((vb * levels) / 256).coerceIn(0, levels - 1)
                    val i = (cell + k) * levels + lv
                    sum[i] += vw.toDouble()
                    cnt[i]++
                }
            }
        }
        val grid = FloatArray(cells * levels)
        val vxs = IntArray(levels)
        val vys = FloatArray(levels)
        for (c3 in 0 until cells) {
            val base = c3 * levels
            var n = 0
            for (l in 0 until levels) {
                if (cnt[base + l] >= 8) {
                    vxs[n] = l
                    vys[n] = (sum[base + l] / cnt[base + l]).toFloat()
                    n++
                }
            }
            if (n == 0) {
                for (l in 0 until levels) grid[base + l] = (l + 0.5f) * 256f / levels
                continue
            }
            var j = 0
            for (l in 0 until levels) {
                while (j + 1 < n && vxs[j + 1] <= l) j++
                grid[base + l] = when {
                    vxs[j] == l -> vys[j]
                    j + 1 < n -> {
                        val t = (l - vxs[j]).toFloat() / (vxs[j + 1] - vxs[j])
                        vys[j] + (vys[j + 1] - vys[j]) * t
                    }
                    else -> vys[j]
                }
            }
        }
        // 沿亮度轴 1-2-1 平滑一次（避免分箱台阶）
        val sm = FloatArray(grid.size)
        for (c3 in 0 until cells) {
            val base = c3 * levels
            for (l in 0 until levels) {
                sm[base + l] = when (l) {
                    0 -> grid[base]
                    levels - 1 -> grid[base + levels - 1]
                    else -> (grid[base + l - 1] + 2f * grid[base + l] + grid[base + l + 1]) / 4f
                }
            }
        }
        return sm
    }

    private fun applyToneGrid(bmp: Bitmap, grid: FloatArray, gw: Int, gh: Int, levels: Int) {
        val w = bmp.width
        val h = bmp.height
        val row = IntArray(w)
        val lvF = levels
        for (y in 0 until h) {
            bmp.getPixels(row, 0, w, 0, y, w, 1)
            val fy = ((y + 0.5f) / h * gh - 0.5f).coerceIn(0f, (gh - 1).toFloat())
            val y0 = fy.toInt().coerceAtMost(gh - 1)
            val y1 = (y0 + 1).coerceAtMost(gh - 1)
            val wy = fy - y0
            for (x in 0 until w) {
                val fx = ((x + 0.5f) / w * gw - 0.5f).coerceIn(0f, (gw - 1).toFloat())
                val x0 = fx.toInt().coerceAtMost(gw - 1)
                val x1 = (x0 + 1).coerceAtMost(gw - 1)
                val wx = fx - x0
                val c00 = (y0 * gw + x0) * 3
                val c01 = (y0 * gw + x1) * 3
                val c10 = (y1 * gw + x0) * 3
                val c11 = (y1 * gw + x1) * 3
                val c = row[x]
                var out = 0xFF shl 24
                for (k in 0 until 3) {
                    val v = (c shr (k * 8)) and 0xFF
                    val lf = v * lvF / 256f - 0.5f
                    val l0 = lf.toInt().coerceIn(0, levels - 1)
                    val l1 = (l0 + 1).coerceAtMost(levels - 1)
                    val t = (lf - l0).coerceIn(0f, 1f)
                    val i00 = (c00 + k) * levels
                    val i01 = (c01 + k) * levels
                    val i10 = (c10 + k) * levels
                    val i11 = (c11 + k) * levels
                    val yl0 = (grid[i00 + l0] * (1 - wx) + grid[i01 + l0] * wx) * (1 - wy) +
                        (grid[i10 + l0] * (1 - wx) + grid[i11 + l0] * wx) * wy
                    val yl1 = (grid[i00 + l1] * (1 - wx) + grid[i01 + l1] * wx) * (1 - wy) +
                        (grid[i10 + l1] * (1 - wx) + grid[i11 + l1] * wx) * wy
                    val ov = yl0 + (yl1 - yl0) * t
                    out = out or ((ov + 0.5f).toInt().coerceIn(0, 255) shl (k * 8))
                }
                row[x] = out
            }
            bmp.setPixels(row, 0, w, 0, y, w, 1)
        }
    }

    /** 每条带的纵向网格行数：最多 gh，且保证每格约 3 行样本（与主链 DistillBandOps 同口径）。 */
    private fun bandGridRows(rowsArr: IntArray, gh: Int): IntArray =
        IntArray(rowsArr.size) { i -> minOf(gh, maxOf(2, rowsArr[i] / 3)) }

    private fun gridOffsets(ghArr: IntArray, gw: Int): IntArray {
        val off = IntArray(ghArr.size + 1)
        for (i in ghArr.indices) off[i + 1] = off[i] + ghArr[i] * gw * 3
        return off
    }

    /**
     * 逐带独立拟合的亮度分段修正表（关键：带间不插值，边界阶跃可被完整表达）。
     * 索引布局：offs[band] + [(gy*gw + gx)*3 + k] → levels 级目标值。
     * 带内纵向按该带实际行数归一化，因此代理图与全分辨率的最后一条短带也一一对应。
     */
    private fun fitToneGridPerBand(
        whole: Bitmap, band: Bitmap,
        tops: IntArray, rowsArr: IntArray, ghArr: IntArray, gw: Int, levels: Int
    ): FloatArray {
        val w = whole.width
        val h = whole.height
        val bands = tops.size
        val offs = gridOffsets(ghArr, gw)
        val cells = offs[bands]
        val cnt = IntArray(cells * levels)
        val sum = DoubleArray(cells * levels)
        val rowW = IntArray(w)
        val rowB = IntArray(w)
        for (py in 0 until h) {
            whole.getPixels(rowW, 0, w, 0, py, w, 1)
            band.getPixels(rowB, 0, w, 0, py, w, 1)
            var bi = 0
            while (bi + 1 < bands && py >= tops[bi + 1]) bi++
            val yIn = py - tops[bi]
            val gh = ghArr[bi]
            val rowsEff = rowsArr[bi].coerceAtLeast(1)
            val gy = ((yIn.toLong() * gh) / rowsEff).toInt().coerceIn(0, gh - 1)
            val bandBase = offs[bi]
            for (x in 0 until w) {
                val gx = ((x.toLong() * gw) / w).toInt().coerceIn(0, gw - 1)
                val cell = bandBase + (gy * gw + gx) * 3
                val cw = rowW[x]
                val cb = rowB[x]
                for (k in 0 until 3) {
                    val vb = (cb shr (k * 8)) and 0xFF
                    val vw = (cw shr (k * 8)) and 0xFF
                    val lv = ((vb * levels) / 256).coerceIn(0, levels - 1)
                    val i = (cell + k) * levels + lv
                    sum[i] += vw.toDouble()
                    cnt[i]++
                }
            }
        }
        val grid = FloatArray(cells * levels)
        val vxs = IntArray(levels)
        val vys = FloatArray(levels)
        for (c3 in 0 until cells) {
            val base = c3 * levels
            var cellN = 0
            for (l in 0 until levels) cellN += cnt[base + l]
            val minCnt = maxOf(2, minOf(8, cellN / levels / 2))
            var n = 0
            for (l in 0 until levels) {
                if (cnt[base + l] >= minCnt) {
                    vxs[n] = l
                    vys[n] = (sum[base + l] / cnt[base + l]).toFloat()
                    n++
                }
            }
            var j = 0
            for (l in 0 until levels) {
                if (n == 0) {
                    grid[base + l] = (l + 0.5f) * 256f / levels
                } else {
                    while (j + 1 < n && vxs[j + 1] <= l) j++
                    grid[base + l] = when {
                        vxs[j] == l -> vys[j]
                        j + 1 < n -> {
                            val t = (l - vxs[j]).toFloat() / (vxs[j + 1] - vxs[j])
                            vys[j] + (vys[j + 1] - vys[j]) * t
                        }
                        else -> vys[j]
                    }
                }
            }
        }
        val sm = FloatArray(grid.size)
        for (c3 in 0 until cells) {
            val base = c3 * levels
            for (l in 0 until levels) {
                sm[base + l] = when (l) {
                    0 -> grid[base]
                    levels - 1 -> grid[base + levels - 1]
                    else -> (grid[base + l - 1] + 2f * grid[base + l] + grid[base + l + 1]) / 4f
                }
            }
        }
        return sm
    }

    private fun applyToneGridPerBand(
        bmp: Bitmap, grid: FloatArray,
        tops: IntArray, rowsArr: IntArray, ghArr: IntArray, gw: Int, levels: Int
    ) {
        val w = bmp.width
        val h = bmp.height
        val bands = tops.size
        val offs = gridOffsets(ghArr, gw)
        val row = IntArray(w)
        val lvF = levels
        for (y in 0 until h) {
            bmp.getPixels(row, 0, w, 0, y, w, 1)
            var bi = 0
            while (bi + 1 < bands && y >= tops[bi + 1]) bi++
            val yIn = y - tops[bi]
            val gh = ghArr[bi]
            val rowsEff = rowsArr[bi].coerceAtLeast(1)
            val fy = ((yIn + 0.5f) / rowsEff * gh - 0.5f).coerceIn(0f, (gh - 1).toFloat())
            val y0 = fy.toInt().coerceAtMost(gh - 1)
            val y1 = (y0 + 1).coerceAtMost(gh - 1)
            val wy = fy - y0
            val rowBase0 = offs[bi] + y0 * gw * 3
            val rowBase1 = offs[bi] + y1 * gw * 3
            for (x in 0 until w) {
                val fx = ((x + 0.5f) / w * gw - 0.5f).coerceIn(0f, (gw - 1).toFloat())
                val x0 = fx.toInt().coerceAtMost(gw - 1)
                val x1 = (x0 + 1).coerceAtMost(gw - 1)
                val wx = fx - x0
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
                    val t = (lf - l0).coerceIn(0f, 1f)
                    val i00 = (c00 + k) * levels
                    val i01 = (c01 + k) * levels
                    val i10 = (c10 + k) * levels
                    val i11 = (c11 + k) * levels
                    val yl0 = (grid[i00 + l0] * (1 - wx) + grid[i01 + l0] * wx) * (1 - wy) +
                        (grid[i10 + l0] * (1 - wx) + grid[i11 + l0] * wx) * wy
                    val yl1 = (grid[i00 + l1] * (1 - wx) + grid[i01 + l1] * wx) * (1 - wy) +
                        (grid[i10 + l1] * (1 - wx) + grid[i11 + l1] * wx) * wy
                    val ov = yl0 + (yl1 - yl0) * t
                    out = out or ((ov + 0.5f).toInt().coerceIn(0, 255) shl (k * 8))
                }
                row[x] = out
            }
            bmp.setPixels(row, 0, w, 0, y, w, 1)
        }
    }

    /**
     * 验证「逐带暖调 + 代理图增益修正」：代理图上同时跑整幅/逐带暖调，拟合逐通道增益网格，
     * 再套到全分辨率逐带结果上，与整幅暖调比较。
     */
    private fun runWarmFixSuite(indir: File, outdir: File) {
        val sb = StringBuilder()
        try {
            val f = indir.listFiles { x ->
                x.isFile && x.name.lowercase().let { it.endsWith(".jpg") || it.endsWith(".jpeg") }
            }?.sortedBy { it.name }?.firstOrNull()
            if (f == null) {
                sb.append("no jpg\n")
            } else {
                val bmp = BitmapFactory.decodeFile(f.absolutePath)
                    ?: throw IllegalStateException("decode failed")
                val src = bmp.copy(Bitmap.Config.ARGB_8888, true)
                bmp.recycle()
                val w = src.width
                val h = src.height
                val bandRows = 768
                sb.append("mode=warm2 ${f.name} ${w}x$h band=$bandRows heap=${ImageUtils.heapUsageLabel()}\n")

                val a = warmOn(src) ?: throw IllegalStateException("whole warm failed")
                val af = File(outdir, "warmfix_whole.jpg")
                java.io.FileOutputStream(af).use { a.compress(Bitmap.CompressFormat.JPEG, 95, it) }
                a.recycle()

                val b = warmBands(src, bandRows)
                val bf0 = File(outdir, "warmfix_raw.jpg")
                java.io.FileOutputStream(bf0).use { b.compress(Bitmap.CompressFormat.JPEG, 95, it) }
                sb.append("  cmp_raw ${compareMae(af, bf0, bandRows)}\n")

                // 真实逐带几何（末带可能更短，暗角强度由「该带自身宽高比」决定）
                val hTopsL = ArrayList<Int>()
                val hRowsL = ArrayList<Int>()
                var tp = 0
                while (tp < h) {
                    val r = minOf(bandRows, h - tp)
                    hTopsL.add(tp); hRowsL.add(r); tp += r
                }
                val hTops = hTopsL.toIntArray()
                val hRows = hRowsL.toIntArray()

                // 代理图：先定宽，再让每条代理带的行数 = 宽度 × 真实带行数 / 图宽，
                // 使代理带与真实带的宽高比逐一相同（上一版按整图等比缩小，末带/非整除时会变形）
                val pw = kotlin.math.sqrt(2_500_000.0 * w / h).toInt().coerceAtLeast(16)
                val pTops = IntArray(hRows.size)
                val pRows = IntArray(hRows.size)
                var acc = 0
                for (i in hRows.indices) {
                    pTops[i] = acc
                    pRows[i] = ((pw.toDouble() * hRows[i]) / w).toInt().coerceAtLeast(2)
                    acc += pRows[i]
                }
                val ph = acc
                sb.append("  proxy ${pw}x$ph pRows=${pRows.joinToString(",")}\n")
                val proxy = Bitmap.createScaledBitmap(src, pw, ph, true)
                val pWhole = warmOn(proxy) ?: throw IllegalStateException("proxy whole failed")
                val pBands = warmBandsAt(proxy, pTops, pRows)
                val gw = 24
                val gh = 24
                val levels = 32
                val ghArr = bandGridRows(pRows, gh)
                sb.append("  proxyGh=${ghArr.joinToString(",")}\n")
                val toneBand = fitToneGridPerBand(pWhole, pBands, pTops, pRows, ghArr, gw, levels)
                // 代理图自检：修正后与整幅目标的残差（不含 JPEG/缩放误差，纯模型精度）
                run {
                    val pChk = pBands.copy(Bitmap.Config.ARGB_8888, true)
                    applyToneGridPerBand(pChk, toneBand, pTops, pRows, ghArr, gw, levels)
                    val pr = IntArray(pw)
                    val pc = IntArray(pw)
                    var sd = 0.0
                    var sn = 0L
                    var bd = 0.0
                    var bn = 0
                    for (py in 0 until ph) {
                        pWhole.getPixels(pr, 0, pw, 0, py, pw, 1)
                        pChk.getPixels(pc, 0, pw, 0, py, pw, 1)
                        var rs = 0.0
                        for (x in 0 until pw) {
                            val p = pr[x]
                            val q = pc[x]
                            val lp = ((p shr 16 and 0xFF) * 299 + (p shr 8 and 0xFF) * 587 + (p and 0xFF) * 114) / 1000
                            val lq = ((q shr 16 and 0xFF) * 299 + (q shr 8 and 0xFF) * 587 + (q and 0xFF) * 114) / 1000
                            rs += kotlin.math.abs(lp - lq)
                        }
                        sd += rs
                        sn += pw
                        if (py > 0 && pTops.any { it == py }) {
                            bd += rs / pw
                            bn++
                        }
                    }
                    sb.append("  proxyMae=%.3f proxyBoundaryMae=%.3f\n".format(sd / sn, if (bn > 0) bd / bn else 0.0))
                    pChk.recycle()
                }
                proxy.recycle()
                pWhole.recycle()
                pBands.recycle()

                b.recycle()

                val b2 = warmBandsAt(src, hTops, hRows)
                applyToneGridPerBand(b2, toneBand, hTops, hRows, ghArr, gw, levels)
                val bf2 = File(outdir, "warmfix_perband.jpg")
                java.io.FileOutputStream(bf2).use { b2.compress(Bitmap.CompressFormat.JPEG, 95, it) }
                sb.append("  cmp_perband ${compareMae(af, bf2, bandRows)}\n")

                // 分辨率敏感性探针：把代理宽度放大后再拟合一次，看「传递到全分辨率的残余偏差」
                // 是否随之收敛。收敛明显 → 提高 PROXY_PX 值得；几乎不变 → 残余是模型表达上限。
                for (f in doubleArrayOf(1.6)) {
                    val pw2 = (pw * f).toInt().coerceAtMost(w)
                    if (pw2 <= pw) continue
                    val pTops2 = IntArray(hRows.size)
                    val pRows2 = IntArray(hRows.size)
                    var acc2 = 0
                    for (i in hRows.indices) {
                        pTops2[i] = acc2
                        pRows2[i] = ((pw2.toDouble() * hRows[i]) / w).toInt().coerceAtLeast(2)
                        acc2 += pRows2[i]
                    }
                    val proxy2 = Bitmap.createScaledBitmap(src, pw2, acc2, true)
                    val pWhole2 = warmOn(proxy2)
                    val pBands2 = warmBandsAt(proxy2, pTops2, pRows2)
                    val gh2 = bandGridRows(pRows2, gh)
                    val g2 = if (pWhole2 != null)
                        fitToneGridPerBand(pWhole2, pBands2, pTops2, pRows2, gh2, gw, levels)
                    else FloatArray(0)
                    val tag2 = "  scale${"%.2f".format(f)}"
                    sb.append("$tag2 proxy ${pw2}x$acc2 pRows=${pRows2.joinToString(",")}"
                            + " gh=${gh2.joinToString(",")}\n")
                    val pChk2 = pBands2.copy(Bitmap.Config.ARGB_8888, true)
                    applyToneGridPerBand(pChk2, g2, pTops2, pRows2, gh2, gw, levels)
                    val r2 = IntArray(pw2)
                    val c2 = IntArray(pw2)
                    var sd2 = 0.0
                    var sn2 = 0L
                    for (py in 0 until acc2) {
                        pWhole2?.getPixels(r2, 0, pw2, 0, py, pw2, 1)
                        pChk2.getPixels(c2, 0, pw2, 0, py, pw2, 1)
                        for (x in 0 until pw2) {
                            val p = r2[x]
                            val q = c2[x]
                            val lp = ((p shr 16 and 0xFF) * 299 + (p shr 8 and 0xFF) * 587
                                    + (p and 0xFF) * 114) / 1000
                            val lq = ((q shr 16 and 0xFF) * 299 + (q shr 8 and 0xFF) * 587
                                    + (q and 0xFF) * 114) / 1000
                            sd2 += kotlin.math.abs(lp - lq)
                            sn2++
                        }
                    }
                    sb.append("$tag2 proxyMae=%.3f\n".format(sd2 / sn2))
                    pChk2.recycle()
                    val b3 = warmBandsAt(src, hTops, hRows)
                    applyToneGridPerBand(b3, g2, hTops, hRows, gh2, gw, levels)
                    val bf3 = File(outdir, "warmfix_scale.jpg")
                    java.io.FileOutputStream(bf3).use { b3.compress(Bitmap.CompressFormat.JPEG, 95, it) }
                    sb.append("$tag2 cmp ${compareMae(af, bf3, bandRows)}\n")
                    b3.recycle()
                    proxy2.recycle()
                    pWhole2?.recycle()
                    pBands2.recycle()
                }

                src.recycle()
                b2.recycle()
            }
        } catch (t: Throwable) {
            Log.e(tag, "warm2 失败", t)
            sb.append("FATAL ${t.message}\n")
        }
        runCatching { File(outdir, "_warm2_report.txt").writeText(sb.toString()) }
        finish()
    }

    /**
     * 暖调 LUT 的「整幅一次」vs「逐带各一次」对比，用于判定 native 暖调是否依赖整图统计。
     * 若两者差出一个大 MAE → 条带链的根本偏差就来自逐带暖调（与推理分块无关）。
     */
    private fun runWarmSuite(indir: File, outdir: File) {
        val sb = StringBuilder()
        try {
            val f = indir.listFiles { x ->
                x.isFile && x.name.lowercase().let { it.endsWith(".jpg") || it.endsWith(".jpeg") }
            }?.sortedBy { it.name }?.firstOrNull()
            if (f == null) {
                sb.append("no jpg\n")
            } else {
                val bmp = BitmapFactory.decodeFile(f.absolutePath)
                    ?: throw IllegalStateException("decode failed")
                val src = bmp.copy(Bitmap.Config.ARGB_8888, true)
                bmp.recycle()
                val w = src.width
                val h = src.height
                sb.append("mode=warm ${f.name} ${w}x$h heap=${ImageUtils.heapUsageLabel()}\n")
                val bin = "leica_filter_param_m9_snapshot.bin"
                val dn = 0.6f * 0.33f
                val ev = -0.95f
                val wa = 0.3f
                val t = 12345f

                val a = LegendaryPostProcessor.applyWarmLutBitmapBinAt(
                    this, src.copy(Bitmap.Config.ARGB_8888, true), bin, dn, ev, wa, t)
                val af = File(outdir, "warm_whole.jpg")
                if (a == null) {
                    sb.append("  whole warm failed\n")
                } else {
                    java.io.FileOutputStream(af).use { a.compress(Bitmap.CompressFormat.JPEG, 95, it) }
                }

                val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                val cv = android.graphics.Canvas(out)
                val step = minOf(768, h)
                var top = 0
                while (top < h) {
                    val rows = minOf(step, h - top)
                    val crop = Bitmap.createBitmap(src, 0, top, w, rows)
                        .copy(Bitmap.Config.ARGB_8888, true)
                    val wr = LegendaryPostProcessor.applyWarmLutBitmapBinAt(
                        this, crop, bin, dn, ev, wa, t)
                    if (wr == null) sb.append("  band warm failed @$top\n")
                    else cv.drawBitmap(wr, 0f, top.toFloat(), null)
                    top += rows
                }
                val bf = File(outdir, "warm_band.jpg")
                java.io.FileOutputStream(bf).use { out.compress(Bitmap.CompressFormat.JPEG, 95, it) }
                if (af.exists()) sb.append("  cmp ${compareMae(af, bf, step)}\n")
                src.recycle()
                out.recycle()
            }
        } catch (t: Throwable) {
            Log.e(tag, "warm 隔离失败", t)
            sb.append("FATAL ${t.message}\n")
        }
        runCatching { File(outdir, "_warm_report.txt").writeText(sb.toString()) }
        finish()
    }

    /**
     * 暖调段「重叠窗口」验证：逐带（256 行）暖调时把窗口上下各扩 E 行、只保留中间 256 行，
     * 看带边台阶 / 带内边缘塌陷是否消失。台阶就是「每 256 行一条水平线」的直接来源。
     * 判据：stepB（带边台阶）与 dipB（带边塌陷）随 E 增大而趋 0，stepI 为全图逐行抖动基线。
     */
    private fun runWarmOverlapSuite(indir: File, outdir: File) {
        val sb = StringBuilder()
        val rf = File(outdir, "_warmover_report.txt")
        fun flush() = runCatching { rf.writeText(sb.toString()) }
        try {
            val f = indir.listFiles { x ->
                x.isFile && x.name.lowercase().let { it.endsWith(".jpg") || it.endsWith(".jpeg") }
            }?.sortedBy { it.name }?.firstOrNull()
            if (f == null) {
                sb.append("no jpg\n")
            } else {
                val src = BitmapFactory.decodeFile(f.absolutePath)
                    ?: throw IllegalStateException("decode failed")
                val w = src.width
                val h = src.height
                sb.append("mode=warmover ${f.name} ${w}x$h band=256 heap=${ImageUtils.heapUsageLabel()}\n")
                flush()

                val ref = LegendaryPostProcessor.applyWarmLutBitmapBinAt(
                    this, src.copy(Bitmap.Config.ARGB_8888, true),
                    WARM_BIN, WARM_DN, WARM_EV, WARM_WA, 12345f)
                    ?: throw IllegalStateException("whole warm failed")
                val ra = IntArray(w)
                val rb = IntArray(w)

                for (e in intArrayOf(0, 32, 64, 128)) {
                    val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    val cv = android.graphics.Canvas(out)
                    var top = 0
                    while (top < h) {
                        val rows = minOf(256, h - top)
                        val wt = maxOf(0, top - e)
                        val wb = minOf(h, top + rows + e)
                        val crop = Bitmap.createBitmap(src, 0, wt, w, wb - wt)
                            .copy(Bitmap.Config.ARGB_8888, true)
                        val wr = LegendaryPostProcessor.applyWarmLutBitmapBinAt(
                            this, crop, WARM_BIN, WARM_DN, WARM_EV, WARM_WA, 12345f)
                        if (wr == null) {
                            sb.append("  band warm failed @$top\n")
                        } else {
                            cv.drawBitmap(
                                wr,
                                android.graphics.Rect(0, top - wt, w, top - wt + rows),
                                android.graphics.Rect(0, top, w, top + rows), null)
                            wr.recycle()
                        }
                        crop.recycle()
                        top += rows
                    }

                    val d = FloatArray(h)
                    var mae = 0.0
                    for (y in 0 until h) {
                        out.getPixels(rb, 0, w, 0, y, w, 1)
                        ref.getPixels(ra, 0, w, 0, y, w, 1)
                        var s = 0L
                        for (x in 0 until w) {
                            val g = (rb[x] shr 8) and 0xFF
                            val r = (ra[x] shr 8) and 0xFF
                            s += (g - r)
                            mae += Math.abs(g - r)
                        }
                        d[y] = s.toFloat() / w
                    }
                    mae /= (w.toDouble() * h)

                    var stepSum = 0.0
                    var dipSum = 0.0
                    var nb = 0
                    var y0 = 256
                    while (y0 < h) {
                        stepSum += d[y0] - d[y0 - 1]
                        var loc = 0.0
                        var c = 0
                        for (k in -12 until 12) {
                            val yy = y0 + k
                            if (yy in 0 until h) {
                                loc += d[yy]
                                c++
                            }
                        }
                        loc /= c
                        dipSum += (d[y0] + d[y0 - 1]) / 2.0 - loc
                        nb++
                        y0 += 256
                    }
                    var stepI = 0.0
                    for (y in 1 until h) stepI += Math.abs(d[y] - d[y - 1])
                    stepI /= (h - 1)

                    sb.append("  E=%d globalMae=%.2f stepB=%+.2f dipB=%+.2f stepI=%.3f\n"
                        .format(e, mae, stepSum / nb, dipSum / nb, stepI))
                    flush()
                    if (e == 0 || e == 64) {
                        runCatching {
                            java.io.FileOutputStream(File(outdir, "warmover_e$e.jpg"))
                                .use { out.compress(Bitmap.CompressFormat.JPEG, 95, it) }
                        }
                    }
                    out.recycle()
                }
                ref.recycle()
                src.recycle()
            }
        } catch (t: Throwable) {
            Log.e(tag, "warmover 失败", t)
            sb.append("FATAL ${t.message}\n")
        }
        flush()
        finish()
    }

    private fun runOneBand(f: File, outdir: File, style: LeicaEngine.Style): String {
        val sb = StringBuilder()
        val stem = f.nameWithoutExtension
        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.absolutePath, bounds)
            val w = bounds.outWidth
            val h = bounds.outHeight
            val mp = w.toLong() * h / 1_000_000.0
            sb.append("== %s %dx%d (%.1fMP) style=%s heap=%s\n"
                .format(stem, w, h, mp, style.id, ImageUtils.heapUsageLabel()))

            // 等价性参考：只有 M9D 的整图链「就是」M9Distill.run（无前后额外步骤），
            // 才能与条带链严格对拍；其它风格的整图链走原生引擎/额外后推，MAE 不可比。
            var refMs = -1L
            var refFile: File? = null
            // 整图链自身可重复性：第二次 run 复用 styled 缓存，只差暖调 time 与抖色随机 → 测后处理噪声地板
            var ref2File: File? = null
            if (style.id == "M9D" && w.toLong() * h <= 12_600_000L) {
                // 必须与条带链同像素网格：条带链按文件原始像素逐带处理（EXIF 方向靠回填保留），
                // 这里再 normalizeExifOrientation 会变成竖版 3072x4096，与横版条带图不可比。
                val work = f.readBytes()
                if (DistillBandOps.debugStyledOnly) {
                    // 只比蒸馏推理这一段：条带链关掉全部后处理，参考图 = 整图链的 styled 输出
                    val sbmp = M9Distill.styleOnly(this, work)
                    if (sbmp != null) {
                        refFile = File(outdir, "${stem}_${style.id}_ref.jpg").apply {
                            java.io.FileOutputStream(this).use {
                                sbmp.compress(Bitmap.CompressFormat.JPEG, 95, it)
                            }
                        }
                        sbmp.recycle()
                    }
                    sb.append("  styledOnly compare\n")
                } else {
                val t1 = System.currentTimeMillis()
                val ref = M9Distill.run(this, work)
                refMs = System.currentTimeMillis() - t1
                if (ref != null) {
                    refFile = File(outdir, "${stem}_${style.id}_ref.jpg").apply { writeBytes(ref) }
                    val ref2 = M9Distill.run(this, work)
                    if (ref2 != null) {
                        ref2File = File(outdir, "${stem}_${style.id}_ref2.jpg").apply { writeBytes(ref2) }
                    }
                }
                }
            }

            val op = DistillBandOps.create(this, style, IntArray(5))
            if (op == null) return sb.append("  op create failed\n").toString()
            val strip = File(outdir, "${stem}_${style.id}_band.jpg")
            val t2 = System.currentTimeMillis()
            val res = ImageUtils.encodeStripFromFile(f, strip, 95, bandRowsReq, op = op, strict = true)
            val bandMs = System.currentTimeMillis() - t2
            sb.append("  band ok=${res.ok} ${res.width}x${res.height} ")
                .append("band=${res.bandRows}x${res.bandCount} ${res.outBytes}B ")
                .append("${bandMs}ms heap=${ImageUtils.heapUsageLabel()}\n")
            if (!res.ok) return sb.append("  band FAILED (strict)\n").toString()
            sb.append("  wholeChainMs=$refMs\n")
            if (refFile != null) sb.append("  cmp ${compareMae(refFile, strip, res.bandRows)}\n")
            if (refFile != null && ref2File != null) {
                sb.append("  cmpRefRef ${compareMae(refFile, ref2File, res.bandRows)}\n")
            }
            // 上限诊断：在「已被比较的两张图」上直接拟合同样的网格（oracle），
            // 用来区分「修正模型表达力不足」与「代理图迁移损失」
            if (refFile != null) {
                sb.append("  oracle ${oracleToneFix(refFile, strip, res.bandRows)}\n")
            }
        } catch (t: Throwable) {
            Log.e(tag, "band 单张失败 ${f.name} ${style.id}", t)
            sb.append("  FAIL ${t.javaClass.simpleName}: ${t.message}\n")
        }
        return sb.toString()
    }

    /** 参考图 vs 条带图的亮度 MAE（1/2 采样）；带边界行单独统计，比值≈1 表示无接缝。 */
    private fun compareMae(refFile: File, bandFile: File, bandRows: Int): String {
        fun load(f: File): Bitmap? = runCatching {
            BitmapFactory.decodeFile(f.absolutePath, BitmapFactory.Options().apply {
                inSampleSize = 2
            })
        }.getOrNull()

        val a = load(refFile) ?: return "ref decode failed"
        val b = load(bandFile) ?: return "band decode failed"
        try {
            val w = minOf(a.width, b.width)
            val h = minOf(a.height, b.height)
            if (w <= 0 || h <= 0) return "bad size"
            val ra = IntArray(w)
            val rb = IntArray(w)
            var sum = 0.0
            var n = 0L
            var maxRow = 0.0
            var maxRowY = -1
            var seamSum = 0.0
            var seamN = 0
            // 分带误差拆分：带内「有符号均值」大 → 仍在整带色调偏差；均值小而 MAE 大 → 只剩细节差异
            val bands = ((h + maxOf(1, bandRows / 2) - 1) / maxOf(1, bandRows / 2))
            val bSigned = DoubleArray(bands)
            val bAbs = DoubleArray(bands)
            val bN = LongArray(bands)
            // 输出图自身的行梯度（接缝=输出在带边界多出来的阶跃，需与参考图同位置梯度相减）
            val dRowA = DoubleArray(h)
            val dRowB = DoubleArray(h)
            var prevA: IntArray? = null
            var prevB: IntArray? = null
            val step = maxOf(1, bandRows / 2)   // 1/2 采样后的带高
            for (y in 0 until h) {
                a.getPixels(ra, 0, w, 0, y, w, 1)
                b.getPixels(rb, 0, w, 0, y, w, 1)
                var rowSum = 0.0
                var rowSigned = 0.0
                var gradA = 0.0
                var gradB = 0.0
                val lumA = IntArray(w)
                val lumB = IntArray(w)
                for (x in 0 until w) {
                    val p = ra[x]
                    val q = rb[x]
                    val lp = ((p shr 16 and 0xFF) * 299 + (p shr 8 and 0xFF) * 587 + (p and 0xFF) * 114) / 1000
                    val lq = ((q shr 16 and 0xFF) * 299 + (q shr 8 and 0xFF) * 587 + (q and 0xFF) * 114) / 1000
                    lumA[x] = lp
                    lumB[x] = lq
                    rowSum += kotlin.math.abs(lp - lq).toDouble()
                    rowSigned += (lq - lp).toDouble()
                    val pa = prevA
                    if (pa != null) gradA += kotlin.math.abs(lp - pa[x]).toDouble()
                    val pb = prevB
                    if (pb != null) gradB += kotlin.math.abs(lq - pb[x]).toDouble()
                }
                dRowA[y] = if (prevA != null) gradA / w else 0.0
                dRowB[y] = if (prevB != null) gradB / w else 0.0
                prevA = lumA
                prevB = lumB
                val rowMae = rowSum / w
                val biIdx = (y / step).coerceAtMost(bands - 1)
                bSigned[biIdx] += rowSigned
                bAbs[biIdx] += rowSum
                bN[biIdx] += w.toLong()
                sum += rowSum
                n += w
                if (rowMae > maxRow) {
                    maxRow = rowMae
                    maxRowY = y
                }
                if (y > 0 && y % step == 0) {
                    seamSum += rowMae
                    seamN++
                }
            }
            val global = if (n > 0) sum / n else 0.0
            val seam = if (seamN > 0) seamSum / seamN else 0.0
            val ratio = if (global > 0.01) seam / global else 1.0
            // 带边界「多出来的阶跃」：输出梯度 - 参考图同位置梯度（>1.0 肉眼可辨）
            var excessMax = 0.0
            var excessMaxY = -1
            var excessSum = 0.0
            var excessN = 0
            for (y in 1 until h) {
                if (y % step != 0) continue
                val ex = dRowB[y] - dRowA[y]
                excessSum += ex
                excessN++
                if (ex > excessMax) {
                    excessMax = ex
                    excessMaxY = y
                }
            }
            val excessMean = if (excessN > 0) excessSum / excessN else 0.0
            val perBand = (0 until bands).joinToString(" ") { i ->
                val cnt = if (bN[i] > 0) bN[i].toDouble() else 1.0
                "%d:%+.2f/%.2f".format(i, bSigned[i] / cnt, bAbs[i] / cnt)
            }
            return ("globalMae=%.3f seamMae=%.3f (x%.2f) maxRowMae=%.3f@y=%d stepExMean=%.2f stepExMax=%.2f@y=%d"
                + "\n  perBand signed/mae %s")
                .format(global, seam, ratio, maxRow, maxRowY, excessMean, excessMax, excessMaxY * 2, perBand)
        } finally {
            a.recycle()
            b.recycle()
        }
    }

    /** 同尺寸两张图的有符号/绝对亮度误差（每 2 像素采样，逐带拆分）。 */
    private fun signedMae(a: Bitmap, b: Bitmap, tops: IntArray): String {
        val w = minOf(a.width, b.width)
        val h = minOf(a.height, b.height)
        val bands = tops.size
        val sgn = DoubleArray(bands)
        val abs = DoubleArray(bands)
        val cnt = LongArray(bands)
        val ra = IntArray(w)
        val rb = IntArray(w)
        fun lum(c: Int) = ((c shr 16 and 0xFF) * 299 + (c shr 8 and 0xFF) * 587 + (c and 0xFF) * 114) / 1000
        var bi = 0
        for (y in 0 until h) {
            while (bi + 1 < bands && y >= tops[bi + 1]) bi++
            a.getPixels(ra, 0, w, 0, y, w, 1)
            b.getPixels(rb, 0, w, 0, y, w, 1)
            var x = 0
            while (x < w) {
                val lp = lum(ra[x])
                val lq = lum(rb[x])
                sgn[bi] += (lq - lp).toDouble()
                abs[bi] += kotlin.math.abs(lq - lp).toDouble()
                cnt[bi]++
                x += 2
            }
        }
        var gs = 0.0
        var ga = 0.0
        var gn = 0L
        for (i in 0 until bands) {
            gs += sgn[i]
            ga += abs[i]
            gn += cnt[i]
        }
        val per = (0 until bands).joinToString(" ") { i ->
            val c = if (cnt[i] > 0) cnt[i].toDouble() else 1.0
            "%d:%+.2f/%.2f".format(i, sgn[i] / c, abs[i] / c)
        }
        return "mae=%.2f signed=%+.2f %s".format(ga / gn, gs / gn, per)
    }

    /**
     * 上限诊断（oracle）：直接在「参考图」与「条带输出」之间拟合同一套逐带色调网格，
     * 用来区分「网格表达力不足」与「代理图迁移损失」。
     * 若 oracle 也降不下来 → 残差是细节级差异，色调网格治不了。
     */
    private fun oracleToneFix(refFile: File, bandFile: File, bandRows: Int): String {
        fun load(f: File): Bitmap? = runCatching {
            BitmapFactory.decodeFile(f.absolutePath, BitmapFactory.Options().apply {
                inSampleSize = 2
                inPreferredConfig = Bitmap.Config.ARGB_8888
            })
        }.getOrNull()

        val a = load(refFile) ?: return "ref decode failed"
        val b = load(bandFile) ?: return "band decode failed"
        if (a.width != b.width || a.height != b.height) {
            val res = "size mismatch ${a.width}x${a.height} vs ${b.width}x${b.height}"
            a.recycle()
            b.recycle()
            return res
        }
        try {
            val step = maxOf(2, bandRows / 2)
            val bands = (b.height + step - 1) / step
            val tops = IntArray(bands) { it * step }
            val rows = IntArray(bands) { i -> minOf(step, b.height - tops[i]) }
            val pre = signedMae(a, b, tops)
            val sbO = StringBuilder("\n    pre  $pre")
            // 网格粒度/精度扫描：判据 = 提高空间分辨率或分段精度后 post MAE 是否还下降。
            // 若多档都收敛在同一水平 → 残差已到模型/噪声地板，再加大网格无收益。
            for (cfg in arrayOf(
                intArrayOf(24, 24, 32),
                intArrayOf(48, 48, 32),
                intArrayOf(24, 24, 64),
                intArrayOf(48, 48, 64),
                intArrayOf(12, 12, 32)
            )) {
                val gw = cfg[0]
                val gh = cfg[1]
                val levels = cfg[2]
                val ghArr = bandGridRows(rows, gh)
                val grid = fitToneGridPerBand(a, b, tops, rows, ghArr, gw, levels)
                val b2 = b.copy(Bitmap.Config.ARGB_8888, true) ?: return "copy failed"
                applyToneGridPerBand(b2, grid, tops, rows, ghArr, gw, levels)
                val post = signedMae(a, b2, tops)
                b2.recycle()
                sbO.append("\n    post g${gw}x$gh lv$levels $post")
            }
            return sbO.toString()
        } catch (t: Throwable) {
            return "oracle failed ${t.javaClass.simpleName}"
        } finally {
            a.recycle()
            b.recycle()
        }
    }

    /**
     * 接缝量化：把原图与条带输出都按 1/8 采样解码，逐行算亮度平均梯度；
     * 边界行（bandRows/8 的整数倍）与原图同位置梯度之差就是接缝强度。
     */
    private fun measureSeam(origFile: File, outFile: File, bandRows: Int): String {
        fun decodeSampled(f: File): Bitmap? {
            val opts = BitmapFactory.Options().apply {
                inSampleSize = 8
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            return runCatching { BitmapFactory.decodeFile(f.absolutePath, opts) }.getOrNull()
        }

        val a = decodeSampled(origFile) ?: return "orig decode failed"
        val b = decodeSampled(outFile) ?: return "out decode failed"
        try {
            val w = minOf(a.width, b.width)
            val h = minOf(a.height, b.height)
            val step = maxOf(1, bandRows / 8)
            fun rowDiff(bmp: Bitmap, y: Int): Double {
                val row = IntArray(w)
                bmp.getPixels(row, 0, w, 0, y, w, 1)
                var sum = 0.0
                for (x in 1 until w) {
                    val p = row[x]; val q = row[x - 1]
                    val lp = ((p shr 16 and 0xFF) * 299 + (p shr 8 and 0xFF) * 587 + (p and 0xFF) * 114) / 1000
                    val lq = ((q shr 16 and 0xFF) * 299 + (q shr 8 and 0xFF) * 587 + (q and 0xFF) * 114) / 1000
                    sum += kotlin.math.abs(lp - lq).toDouble()
                }
                return sum / (w - 1)
            }
            // 直接比逐像素差（同尺寸同采样，量化误差远小于接缝量级）
            fun maeAt(y0: Int, rows: Int): Double {
                val ra = IntArray(w * rows)
                val rb = IntArray(w * rows)
                a.getPixels(ra, 0, w, 0, y0, w, rows)
                b.getPixels(rb, 0, w, 0, y0, w, rows)
                var sum = 0.0
                var n = 0
                for (i in ra.indices) {
                    val p = ra[i]; val q = rb[i]
                    val lp = ((p shr 16 and 0xFF) * 299 + (p shr 8 and 0xFF) * 587 + (p and 0xFF) * 114) / 1000
                    val lq = ((q shr 16 and 0xFF) * 299 + (q shr 8 and 0xFF) * 587 + (q and 0xFF) * 114) / 1000
                    sum += kotlin.math.abs(lp - lq).toDouble()
                    n++
                }
                return sum / n
            }

            var maxSeam = 0.0
            var seamSum = 0.0
            var seams = 0
            var worstY = -1
            var y = step
            while (y < h - 1) {
                val dOut = rowDiff(b, y)
                val dOrig = rowDiff(a, y)
                val d = kotlin.math.abs(dOut - dOrig)
                if (d > maxSeam) { maxSeam = d; worstY = y }
                seamSum += d
                seams++
                y += step
            }
            // 接缝带（最差边界上下各 3 行）MAE；对照给出全图平均 MAE
            val win = 3
            val seamBandMae = if (worstY > win && worstY + win < h) maeAt(worstY - win, win * 2 + 1) else 0.0
            val globalMae = maeAt(0, h)
            return "bands=$seams maxD=${"%.3f".format(maxSeam)} " +
                "meanD=${"%.3f".format(if (seams > 0) seamSum / seams else 0.0)} " +
                "worstY=$worstY seamBandMae=${"%.2f".format(seamBandMae)} " +
                "globalMae=${"%.2f".format(globalMae)}"
        } finally {
            a.recycle(); b.recycle()
        }
    }
}
