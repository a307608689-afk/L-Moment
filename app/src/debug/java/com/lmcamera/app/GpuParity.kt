package com.lmcamera.app

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * GPU（ncnn/Vulkan）与 ORT 的逐张量对拍台。**仅 debug 变体**。
 *
 * 它回答两个问题（P0 门槛）：
 *  1. 数值是否一致 —— 同一随机输入，两条后端输出的 8bit 最大差必须 ≤1 级；
 *  2. 是否真的更快 —— 同一张 tile 的 GPU 单次耗时必须 ≤ CPU 的一半。
 *
 * adb 用法：
 * ```
 * adb shell am start -n com.lmcamera.app/.BatchTestActivity \
 *   --es mode gpu --es model all --es outdir /sdcard/LMTestOut
 * ```
 * `--es model colorfix|styletrans|all`；`--ez cpu true` 强制 ncnn 走 CPU 后端
 * （用于区分「Vulkan 慢」与「ncnn 的 CPU 内核慢」）。结果写 `_gpu_report.txt`。
 */
internal object GpuParity {

    private const val TAG = "GpuParity"

    /** 与 M9Perfect.colorfix 的分块口径一致：step 512 / 中心 512 / margin 16 → 544。 */
    private const val COLORFIX_TILE = 544

    /** 与 M9OrtFallback 的 styletrans 输入一致（模型图中空间维固定）。 */
    private const val ST_W = 1120
    private const val ST_H = 864

    private class Result(
        val name: String,
        val ok: Boolean,
        val warmupMs: Long,
        val cpuMs: Long,
        val gpuMs: Long,
        val maxDU8: Double,
        val meanDU8: Double,
        val note: String
    ) {
        fun line(): String = ("%-16s %s warmup=%dms cpu=%dms gpu=%dms speedup=%.2fx " +
                "maxdU8=%.3f meandU8=%.4f %s")
            .format(name, if (ok) "OK  " else "FAIL", warmupMs, cpuMs, gpuMs,
                if (gpuMs > 0) cpuMs.toDouble() / gpuMs else 0.0, maxDU8, meanDU8, note)
    }

    fun runSuite(ctx: Context, outDir: File, which: String, useVulkan: Boolean) {
        val sb = StringBuilder()
        sb.append("mode=gpu which=$which vulkan=$useVulkan\n")
        sb.append("gpuCount=${runCatching { NcnnNet.nativeGpuCount() }.getOrDefault(-1)} ")
            .append("device=${runCatching { NcnnNet.nativeDeviceInfo() }.getOrDefault("n/a")}\n")
        sb.append("config: fp16=${GpuInferConfig.FP16} fp16arith=${GpuInferConfig.FP16_ARITHMETIC} ")
            .append("threads=${GpuInferConfig.NUM_THREADS} heap=${ImageUtils.heapUsageLabel()}\n")
        sb.append("models: colorfix.bin=${assetLen(ctx, "ncnn/colorfix.bin")}B ")
            .append("styletrans_high.bin=${assetLen(ctx, "ncnn/styletrans_high.bin")}B\n")
        Log.i(TAG, sb.toString())

        val jobs = when (which) {
            "colorfix" -> listOf("colorfix")
            "styletrans" -> listOf("styletrans")
            "span" -> listOf("span")
            else -> listOf("colorfix", "styletrans", "span")
        }
        for (job in jobs) {
            val r = try {
                when (job) {
                    "colorfix" -> colorfix(ctx, useVulkan)
                    "styletrans" -> styletrans(ctx, useVulkan)
                    else -> span(ctx, useVulkan)
                }
            } catch (t: Throwable) {
                Log.e(TAG, "$job 对拍异常", t)
                Result(job, false, -1, -1, -1, -1.0, -1.0, "EXCEPTION ${t.message}")
            }
            sb.append(r.line()).append('\n')
            sb.append("  heap=${ImageUtils.heapUsageLabel()} gpuLastError=${GpuNet.lastError}\n")
            Log.i(TAG, r.line())
            if (outDir.exists() || outDir.mkdirs()) {
                runCatching { File(outDir, "_gpu_report.txt").writeText(sb.toString()) }
            }
            GpuNet.release()
        }
        runCatching { File(outDir, "_gpu_done.txt").writeText("done\n") }
        sb.append("done\n")
        runCatching { File(outDir, "_gpu_report.txt").writeText(sb.toString()) }
        Log.i(TAG, "gpu parity done -> ${outDir.absolutePath}/_gpu_report.txt")
    }

    // ---------------------------------------------------------------- colorfix

    private fun colorfix(ctx: Context, useVulkan: Boolean): Result {
        val c = 6
        val h = COLORFIX_TILE
        val w = COLORFIX_TILE
        val n = c * h * w
        val x = FloatArray(n) { (Math.random().toFloat() - 0.5f) * 2f }   // 值域 [-1,1]，与训练口径一致

        val cpuT0 = System.currentTimeMillis()
        val ref = ortRun(ctx, "m9fb_colorfix.onnx",
            arrayOf("x"), arrayOf(intArrayOf(c, h, w)), arrayOf(x))
        val cpuMs = System.currentTimeMillis() - cpuT0
        if (ref == null) return Result("colorfix", false, -1, cpuMs, -1, -1.0, -1.0, "ORT failed")

        val cpuBuf = direct(x)
        val ok = GpuNet.prepare(ctx, GpuNet.Model.COLORFIX,
            arrayOf(intArrayOf(c, h, w)), useVulkan)
        if (!ok) return Result("colorfix", false, -1, cpuMs, -1, -1.0, -1.0,
            "prepare failed: ${GpuNet.lastError}")
        val warm = GpuNet.lastWarmupMs

        val gpuT0 = System.currentTimeMillis()
        val out = GpuNet.run(GpuNet.Model.COLORFIX, arrayOf(cpuBuf),
            intArrayOf(c), intArrayOf(h), intArrayOf(w), useVulkan)
        val gpuMs = System.currentTimeMillis() - gpuT0
        if (out == null) return Result("colorfix", false, warm, cpuMs, -1, -1.0, -1.0,
            "run failed: ${GpuNet.lastError}")

        val d = diffU8(ref, out)
        return Result("colorfix", true, warm, cpuMs, gpuMs, d.first, d.second, "")
    }

    // ------------------------------------------------------------- styletrans

    private fun styletrans(ctx: Context, useVulkan: Boolean): Result {
        val h = ST_H
        val w = ST_W
        val rgb = FloatArray(3 * h * w) { Math.random().toFloat() }
        val mask = FloatArray(h * w) { if (Math.random() > 0.5) 1f else 0f }

        val cpuT0 = System.currentTimeMillis()
        val ref = ortRun(ctx, "m9fb_styletrans_high.onnx",
            arrayOf("rgb", "mask"),
            arrayOf(intArrayOf(3, h, w), intArrayOf(1, h, w)),
            arrayOf(rgb, mask))
        val cpuMs = System.currentTimeMillis() - cpuT0
        if (ref == null) return Result("styletrans", false, -1, cpuMs, -1, -1.0, -1.0, "ORT failed")

        val ok = GpuNet.prepare(ctx, GpuNet.Model.STYLETRANS,
            arrayOf(intArrayOf(3, h, w), intArrayOf(1, h, w)), useVulkan)
        if (!ok) return Result("styletrans", false, -1, cpuMs, -1, -1.0, -1.0,
            "prepare failed: ${GpuNet.lastError}")
        val warm = GpuNet.lastWarmupMs

        val gpuT0 = System.currentTimeMillis()
        val out = GpuNet.run(GpuNet.Model.STYLETRANS,
            arrayOf(direct(rgb), direct(mask)),
            intArrayOf(3, 1), intArrayOf(h, h), intArrayOf(w, w), useVulkan)
        val gpuMs = System.currentTimeMillis() - gpuT0
        if (out == null) return Result("styletrans", false, warm, cpuMs, -1, -1.0, -1.0,
            "run failed: ${GpuNet.lastError}")

        val d = diffU8(ref, out)
        return Result("styletrans", true, warm, cpuMs, gpuMs, d.first, d.second, "")
    }

    // -------------------------------------------------------------------- span

    /**
     * 超分快速档（SPAN x2，256² → 512²）。
     *
     * 参考端用的是**反量化后的 fp32 图**（debug 资产 span_fp32.onnx），因为 GPU 跑的
     * 就是它；同时额外报告它与线上 int8 资产的偏差，便于判断换后端是否带来可见差异。
     */
    private fun span(ctx: Context, useVulkan: Boolean): Result {
        val h = 256
        val w = 256
        val rgb = FloatArray(3 * h * w) { Math.random().toFloat() }

        val cpuT0 = System.currentTimeMillis()
        val ref = ortRun(ctx, "span_fp32.onnx",
            arrayOf("input"), arrayOf(intArrayOf(3, h, w)), arrayOf(rgb))
        val cpuMs = System.currentTimeMillis() - cpuT0
        if (ref == null) return Result("span", false, -1, cpuMs, -1, -1.0, -1.0, "fp32 ref failed")

        val i8 = ortRun(ctx, "fast_span_x2.onnx",
            arrayOf("input"), arrayOf(intArrayOf(3, h, w)), arrayOf(rgb))
        val i8diff = if (i8 != null) diffU8(ref, i8) else (-1.0 to -1.0)
        val note = "int8vsFp32 meandU8=%.3f".format(i8diff.second)

        val ok = GpuNet.prepare(ctx, GpuNet.Model.SPAN_X2,
            arrayOf(intArrayOf(3, h, w)), useVulkan)
        if (!ok) return Result("span", false, -1, cpuMs, -1, -1.0, -1.0,
            "prepare failed: ${GpuNet.lastError}")
        val warm = GpuNet.lastWarmupMs

        val gpuT0 = System.currentTimeMillis()
        val out = GpuNet.run(GpuNet.Model.SPAN_X2, arrayOf(direct(rgb)),
            intArrayOf(3), intArrayOf(h), intArrayOf(w), useVulkan)
        val gpuMs = System.currentTimeMillis() - gpuT0
        if (out == null) return Result("span", false, warm, cpuMs, -1, -1.0, -1.0,
            "run failed: ${GpuNet.lastError}")

        val d = diffU8(ref, out)
        return Result("span", true, warm, cpuMs, gpuMs, d.first, d.second, note)
    }

    // ------------------------------------------------------------------ utils

    /** ORT 参考输出（只取第一个输出）。模型来自 assets 原文件，即线上 ORT 路径的那一份。 */
    private fun ortRun(
        ctx: Context,
        asset: String,
        names: Array<String>,
        chw: Array<IntArray>,
        data: Array<FloatArray>
    ): FloatArray? {
        val env = OrtEnvironment.getEnvironment()
        val bytes = ctx.assets.open(asset).use { it.readBytes() }
        val opt = OrtSession.SessionOptions().apply { setIntraOpNumThreads(OrtThreads.pick()) }
        return try {
            env.createSession(bytes, opt).use { sess ->
                val feeds = HashMap<String, OnnxTensor>(names.size)
                try {
                    for (i in names.indices) {
                        val s = chw[i]
                        val shape = longArrayOf(1, s[0].toLong(), s[1].toLong(), s[2].toLong())
                        feeds[names[i]] = OnnxTensor.createTensor(
                            env, FloatBuffer.wrap(data[i]), shape)
                    }
                    sess.run(feeds).use { res ->
                        val t = res[0] as OnnxTensor
                        val fb = t.floatBuffer
                        val arr = FloatArray(fb.remaining())
                        fb.get(arr)
                        arr
                    }
                } finally {
                    for (t in feeds.values) runCatching { t.close() }
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "ORT 参考失败 $asset", t)
            null
        }
    }

    private fun direct(a: FloatArray): FloatBuffer {
        val bb = ByteBuffer.allocateDirect(a.size * 4).order(ByteOrder.nativeOrder())
        val fb = bb.asFloatBuffer()
        fb.put(a)
        fb.rewind()
        return fb
    }

    /** 模型输出是 [-1,1] 或 [0,1]，统一按 8bit 码值度量：max|Δ| × 127.5。 */
    private fun diffU8(ref: FloatArray, out: FloatArray): Pair<Double, Double> {
        val n = minOf(ref.size, out.size)
        if (n == 0) return 255.0 to 255.0
        var max = 0.0
        var sum = 0.0
        for (i in 0 until n) {
            val d = Math.abs(ref[i] - out[i]).toDouble()
            if (d > max) max = d
            sum += d
        }
        return (max * 127.5) to (sum / n * 127.5)
    }

    private fun assetLen(ctx: Context, name: String): Long = runCatching {
        ctx.assets.openFd(name).use { it.length }
    }.getOrElse {
        runCatching { ctx.assets.open(name).use { ins -> ins.available().toLong() } }.getOrDefault(-1L)
    }
}
