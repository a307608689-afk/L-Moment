package com.leicamoments.app

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Bitmap
import android.os.Bundle
import android.util.Log
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

    @SuppressLint("SdCardPath")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        thr = intent.getFloatExtra("thr", 0.01f)
        level = intent.getIntExtra("level", -1)
        val rawOnly = intent.getBooleanExtra("rawonly", false)
        // 导出 16bit 线性 sRGB（w,h 头 + u16 RGB），供 PC 端做曲线/局部色调原型搜索
        val dumpLin = intent.getBooleanExtra("dumplin", false)
        val styles = (intent.getStringExtra("style") ?: "LM9,LP9").split(",").map { it.trim() }.filter { it.isNotEmpty() }
        val indir = intent.getStringExtra("indir") ?: "/sdcard/LeicaTestIn"
        val outdir = intent.getStringExtra("outdir") ?: "/sdcard/LeicaTestOut"
        val limit = intent.getIntExtra("limit", 0)
        Log.i(tag, "启动批量测试 styles=$styles indir=$indir outdir=$outdir limit=$limit thr=$thr level=$level rawonly=$rawOnly dumplin=$dumpLin")

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
}
