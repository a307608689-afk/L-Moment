package com.lmcamera.app

import android.content.Context
import java.io.File
import java.nio.Buffer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * GPU（ncnn / Vulkan）推理通道。**与 ORT 完全并存**，不是替换。
 *
 * 契约（调用方必须遵守）：
 *  - 先 [prepare]（可带真实 tile 形状做预热），成功后再逐块 [run]；
 *  - [run] 返回 null 表示这次 GPU 不可用，调用方必须**继续走原来的 ORT 代码路径**，
 *    行为与改造前一致；不要因为 null 而报错给用户；
 *  - 失败会被计数，连续 [MAX_CONSECUTIVE_FAILS] 次后本次进程不再尝试 GPU（防止每块都
 *    卡一次几十秒的 shader 编译），并把原因记在 [lastError]。
 *
 * 为什么不在预览路径用：Vulkan 设备首次初始化 + shader 编译是秒级成本，小图收益为负。
 * 只挂导出 / 完美档 / 超分三条路径。
 */
internal object GpuNet {

    /**
     * 模型清单。资产命名约定：`assets/ncnn/<id>.param` + `assets/ncnn/<id>.bin`
     * （由 pnnx 转换脚本产出，见 m9_fb_work/ncnn_work/）。
     *
     * [inputs] 是 ONNX 图的输入顺序 —— ncnn 的 `input(blob_index)` 就按这个顺序取，
     * 而 M9 蒸馏微网的两个输入形状完全相同，**顺序错了会串权重且毫无报错**。
     * 首次接入新模型时，用日志里打印的 inputs=[...] 核对一次顺序。
     */
    enum class Model(val id: String, val inputs: Int) {
        COLORFIX("colorfix", 1),
        // rgb(3ch) + mask(1ch)，顺序必须与 ONNX 图的输入顺序一致
        STYLETRANS("styletrans_high", 2),
        SPAN_X2("span_x2", 1),
        M9_DISTILL("m9_distill", 2),
    }

    /** 资产版本号：改了 assets/ncnn 下的模型就 +1，强制设备端重新解包。 */
    private const val MODEL_VERSION = 2

    /** 连续失败多少次后放弃 GPU（本次进程内）。 */
    private const val MAX_CONSECUTIVE_FAILS = 2

    private class Entry(
        val model: Model,
        val handle: Long,
        val dir: File,
        val cachePath: String
    )

    /** key = "<modelId>:<vulkan>"，同一模型可同时存在 GPU / CPU 两套会话（对拍用）。 */
    private val entries = HashMap<String, Entry>()
    private val scratch = HashMap<Int, ByteBuffer>()

    private fun keyOf(model: Model, useVulkan: Boolean) = "${model.id}:$useVulkan"

    /** 设备级闩锁：native 库缺失 / Vulkan 建不起来 —— 全模型停用。 */
    @Volatile private var disabled = false
    @Volatile private var disabledReason = ""

    /**
     * 模型级闩锁：推理本身连续失败（算子不支持、显存不足）。
     * 只停这一个模型，不牵连另外两条链——否则一个模型的问题会把三条一起关掉。
     */
    private val modelDisabled = HashSet<String>()

    /** prepare 时暂存 applicationContext，供 run() 把失败判定落盘。 */
    @Volatile private var appCtx: Context? = null

    @Volatile private var failures = 0

    @Volatile var lastError: String = ""
        private set

    /** 最近一次预热耗时（ms），0 = 未预热。 */
    @Volatile var lastWarmupMs: Long = 0L
        private set

    // ---- 跨设备安全阀：首次使用做一次同块 A/B 标定 ------------------------------
    //
    // 「失败即回退」救不了「能跑但更慢」的设备（非 Adreno、老驱动、Vulkan 半残实现）。
    // 这里在每台设备、每个模型上只用一次的办法把它判掉：同一块输入 CPU / GPU 各跑一遍，
    // GPU 不比 CPU 快 20% 就永久写 "slow"，之后 prepare() 直接返回 false，业务走 ORT。

    private const val PREFS = "gpu_calib"

    private fun prefs(app: Context) =
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun verdict(app: Context, model: Model): String =
        prefs(app).getString(model.id, "") ?: ""

    /** 本机该模型是否已被判负：`slow`（跑得慢）或 `fail`（推理失败）。 */
    fun isBlockedByCalibration(app: Context, model: Model): Boolean {
        val v = verdict(app, model)
        return v.isNotEmpty() && v != "ok"
    }

    /** 模型级停用：只影响这一个模型，并把判定落盘，同时把设置开关关掉（用户可见）。 */
    private fun disableModel(app: Context?, model: Model, reason: String) {
        synchronized(this) { modelDisabled.add(model.id) }
        if (app != null) {
            prefs(app).edit().putString(model.id, "fail").apply()
            SettingsStore.setGpuAccelEnabled(app, false)
        }
        lastError = reason
        DiagLog.w(TAG, "${model.id} GPU 已停用" + (if (app != null) "（本机持久，开关已自动关闭）" else "") + "：$reason")
    }

    /** 还需要做标定吗（尚未判定过）。 */
    fun needsProbe(app: Context, model: Model): Boolean =
        GpuInferConfig.CALIBRATE && verdict(app, model).isEmpty()

    /**
     * 记录一次同块 A/B 的结果。返回 GPU 是否仍然划算。
     * 判据：GPU ≤ CPU 的 80%（至少快 20%）才算过关，留余量吸收测量抖动。
     * 判负时把设置开关一起关掉——不让用户停在"开着但一直用 CPU"的误解里。
     */
    fun reportProbe(app: Context, model: Model, gpuMs: Long, cpuMs: Long): Boolean {
        if (gpuMs <= 0 || cpuMs <= 0) return true     // 数据不可用就不阻断
        val ok = gpuMs * 100 <= cpuMs * 80
        prefs(app).edit().putString(model.id, if (ok) "ok" else "slow").apply()
        if (!ok) {
            SettingsStore.setGpuAccelEnabled(app, false)
            lastError = "calibration slow: gpu ${gpuMs}ms vs cpu ${cpuMs}ms"
        }
        DiagLog.w(TAG, "${model.id} 标定 gpu=${gpuMs}ms cpu=${cpuMs}ms → " +
                (if (ok) "保留 GPU" else "GPU 不划算，本机走 CPU，开关已自动关闭"))
        return ok
    }

    /** 是否具备 GPU 通道的硬件/编译条件（不含「该链是否开启」的判断）。 */
    @Suppress("UNUSED_PARAMETER")
    fun isAvailable(app: Context): Boolean {
        if (!GpuInferConfig.ENABLED) return false
        if (disabled) return false
        if (!NcnnNet.isLoaded()) {
            disable("native library not loaded")
            return false
        }
        return try {
            NcnnNet.nativeGpuCount() > 0
        } catch (t: Throwable) {
            disable("gpu probe failed: ${t.message}")
            false
        }
    }

    fun describeDevice(): String = try {
        NcnnNet.nativeDeviceInfo()
    } catch (t: Throwable) {
        "probe failed: ${t.message}"
    }

    /**
     * 运行时总闸 = 编译期开关 × 设置页开关（默认关）。
     *
     * 关闭时**不建会话、不加载模型、不初始化 Vulkan**；若上次开过还留着会话，
     * 在下次导出开始（prepare）时释放 —— 这样导出中途改开关不会造成半张图换后端。
     */
    fun isEnabled(app: Context): Boolean =
        GpuInferConfig.ENABLED && SettingsStore.gpuAccelEnabled(app)

    /**
     * 解包模型并建会话。幂等：已就绪直接返回 true。
     *
     * @param warmShapes 每个输入的 intArrayOf(c, h, w)，传 null 表示不预热（首块自己承担编译）
     * @param onStage    阶段性回调（拷贝/加载/预热），用于前台进度提示
     */
    fun prepare(
        app: Context,
        model: Model,
        warmShapes: Array<IntArray>? = null,
        useVulkan: Boolean = true,
        onStage: ((String) -> Unit)? = null
    ): Boolean {
        if (!GpuInferConfig.ENABLED) return false
        appCtx = app.applicationContext
        // 设置页开关关闭：不建会话。若上次开过还留着会话，这里顺手释放
        // （prepare 只在导出/处理开始时调用，所以不会切到进行中的那一张图）。
        if (!SettingsStore.gpuAccelEnabled(app)) {
            if (entries.isNotEmpty()) {
                release()
                if (GpuInferConfig.LOG) DiagLog.w(TAG, "GPU 加速已关闭，会话已释放")
            }
            return false
        }
        synchronized(this) {
            val key = keyOf(model, useVulkan)
            entries[key]?.let { if (it.handle != 0L) return true }
            if (disabled) return false
            // 判负（标定慢 / 之前推理失败过）或本进程内已停用：连会话都不建，直接走 ORT
            if (useVulkan && (isBlockedByCalibration(app, model) || modelDisabled.contains(model.id))) {
                lastError = "gpu disabled for ${model.id} on this device"
                // 用户可能又把开关拨开了：这里把开关关回去，避免"开着却一直用 CPU"的误解
                if (SettingsStore.gpuAccelEnabled(app)) {
                    SettingsStore.setGpuAccelEnabled(app, false)
                }
                if (GpuInferConfig.LOG) {
                    DiagLog.w(TAG, "${model.id} 本机已判负，沿用 CPU；开关已自动关闭")
                }
                return false
            }

            val dir = modelDir(app, model)
            onStage?.invoke("拷贝模型")
            val files = ensureModelFiles(app, model, dir)
            if (files == null) {
                disable("asset missing: assets/ncnn/${model.id}.param|.bin")
                return false
            }
            val (paramFile, binFile) = files
            val cacheFile = File(dir, "${model.id}.vkpipeline")
            val cachePath = cacheFile.absolutePath

            onStage?.invoke("加载模型")
            val handle = try {
                NcnnNet.nativeCreate(
                    paramFile.absolutePath,
                    binFile.absolutePath,
                    cachePath,
                    GpuInferConfig.NUM_THREADS,
                    if (useVulkan) 1 else 0,
                    if (GpuInferConfig.FP16) 1 else 0,
                    if (GpuInferConfig.FP16_ARITHMETIC) 1 else 0
                )
            } catch (t: Throwable) {
                0L
            }
            if (handle == 0L) {
                disable("create failed: ${NcnnNet.nativeLastError()}")
                return false
            }
            entries[key] = Entry(model, handle, dir, cachePath)
            if (GpuInferConfig.LOG) {
                DiagLog.w(TAG, "${model.id} 会话就绪 vulkan=$useVulkan ${NcnnNet.nativeDeviceInfo()}")
            }

            if (warmShapes != null) {
                onStage?.invoke("预热")
                warmUp(model, warmShapes, useVulkan)
            }
            return true
        }
    }

    /**
     * 用零张量按真实形状跑一次（触发 shader 编译）并把 pipeline cache 落盘。
     * 首次调用可能几十秒（大网），必须在前台可见的进度里做。
     */
    fun warmUp(model: Model, shapes: Array<IntArray>, useVulkan: Boolean = true): Boolean {
        val e = entries[keyOf(model, useVulkan)] ?: return false
        val c = IntArray(shapes.size) { shapes[it][0] }
        val h = IntArray(shapes.size) { shapes[it][1] }
        val w = IntArray(shapes.size) { shapes[it][2] }
        val t0 = System.currentTimeMillis()
        val ms = try {
            NcnnNet.nativeWarmup(e.handle, c, h, w)
        } catch (t: Throwable) {
            -1L
        }
        if (ms < 0) {
            disable("warmup failed: ${NcnnNet.nativeLastError()}")
            return false
        }
        lastWarmupMs = System.currentTimeMillis() - t0
        val saved = try {
            NcnnNet.nativeFlushCache(e.handle)
        } catch (t: Throwable) {
            false
        }
        if (GpuInferConfig.LOG) {
            DiagLog.w(TAG, "${model.id} 首块编译 ${lastWarmupMs}ms（native ${ms}ms）cache=$saved")
        }
        return true
    }

    /**
     * CHW float32 推理。输入顺序必须与 [Model.inputs] / ONNX 图的输入顺序一致。
     *
     * @param ins 每个输入一块 FloatBuffer，长度 = c*h*w
     * @param cs/hs/ws 每个输入的通道/高/宽
     * @return CHW float[C*H_out*W_out]；null = 本次不可用，调用方回退 ORT
     */
    fun run(
        model: Model,
        ins: Array<FloatBuffer>,
        cs: IntArray,
        hs: IntArray,
        ws: IntArray,
        useVulkan: Boolean = true
    ): FloatArray? {
        if (!GpuInferConfig.ENABLED || disabled || modelDisabled.contains(model.id)) return null
        val e = entries[keyOf(model, useVulkan)] ?: run {
            lastError = "not prepared: ${model.id} vulkan=$useVulkan"
            return null
        }
        if (ins.size != cs.size || ins.size != hs.size || ins.size != ws.size) {
            lastError = "shape list mismatch"
            return null
        }
        val buffers = arrayOfNulls<Buffer>(ins.size)
        for (i in ins.indices) {
            val need = cs[i] * hs[i] * ws[i]
            val src = ins[i]
            if (src.isDirect && src.capacity() >= need) {
                buffers[i] = src
            } else {
                // 非 direct（例如 FloatBuffer.wrap(array)）→ 拷进复用的 direct 缓冲
                val cached = scratch[i]
                val bb = if (cached != null && cached.capacity() >= need * 4) {
                    cached
                } else {
                    ByteBuffer.allocateDirect(need * 4).order(ByteOrder.nativeOrder())
                        .also { scratch[i] = it }
                }
                bb.rewind()
                val fb = bb.asFloatBuffer()
                fb.rewind()
                val dup = src.duplicate()
                dup.position(0)
                dup.limit(need)
                fb.put(dup)
                buffers[i] = bb
            }
        }
        @Suppress("UNCHECKED_CAST")
        val out = try {
            NcnnNet.nativeRun(e.handle, buffers as Array<Buffer>, cs, hs, ws)
        } catch (t: Throwable) {
            lastError = "run threw: ${t.message}"
            null
        }
        if (out == null) {
            failures++
            lastError = NcnnNet.nativeLastError().ifEmpty { lastError }
            if (failures >= MAX_CONSECUTIVE_FAILS) {
                // 只停这个模型：另外两条链可能完全正常，不该被一起关掉
                failures = 0
                disableModel(appCtx, model, "continuous failures: $lastError")
            }
            return null
        }
        failures = 0
        return out
    }

    /** 释放所有会话（导出结束 / 内存吃紧 / Activity 销毁时调用）。 */
    fun release() {
        synchronized(this) {
            for ((_, e) in entries) {
                try {
                    if (GpuInferConfig.LOG) NcnnNet.nativeFlushCache(e.handle)
                    NcnnNet.nativeDestroy(e.handle)
                } catch (_: Throwable) {
                }
            }
            entries.clear()
            scratch.clear()
        }
    }

    private fun disable(reason: String) {
        disabled = true
        disabledReason = reason
        lastError = reason
        DiagLog.w(TAG, "GPU 通道已禁用：$reason")
    }

    private fun modelDir(app: Context, model: Model): File =
        File(File(app.filesDir, "ncnn"), model.id).also { if (!it.exists()) it.mkdirs() }

    /**
     * assets → filesDir/ncnn/<id>/。用版本戳 + 长度判断是否需要重解包；
     * 大 bin 做哈希不划算（30MB ≈ 100ms），版本戳已足够（模型只在发版时变）。
     */
    private fun ensureModelFiles(app: Context, model: Model, dir: File): Pair<File, File>? {
        val param = File(dir, "${model.id}.param")
        val bin = File(dir, "${model.id}.bin")
        val stamp = File(dir, "stamp.txt")

        // 版本号 + 两个文件的长度 + param 的摘要：只要资产被改过（哪怕长度没变）就重解包。
        // 只比版本号会在"改了 param 却没改 MODEL_VERSION"时悄悄用旧模型，这是最难查的一类错。
        val srcParam = try {
            app.assets.open("ncnn/${model.id}.param").use { it.readBytes() }
        } catch (t: Throwable) {
            DiagLog.w(TAG, "${model.id} param 读取失败：${t.message}")
            null
        } ?: return null
        val srcBinLen = try {
            app.assets.openFd("ncnn/${model.id}.bin").use { it.length }
        } catch (t: Throwable) {
            -1L
        }

        val want = "$MODEL_VERSION:${model.id}:${srcParam.size}:$srcBinLen:${md5(srcParam)}"
        val fresh = stamp.exists() && stamp.readText().trim() == want &&
                param.length() == srcParam.size.toLong() && bin.length() > 0
        if (fresh) return param to bin
        return try {
            param.writeBytes(srcParam)
            app.assets.open("ncnn/${model.id}.bin").use { input ->
                bin.outputStream().use { out -> input.copyTo(out, 1 shl 16) }
            }
            stamp.writeText(want)
            param to bin
        } catch (t: Throwable) {
            DiagLog.w(TAG, "${model.id} 模型解包失败：${t.message}")
            null
        }
    }

    private fun md5(b: ByteArray): String = try {
        val d = java.security.MessageDigest.getInstance("MD5").digest(b)
        val sb = StringBuilder(d.size * 2)
        for (x in d) sb.append("%02x".format(x.toInt() and 0xFF))
        sb.toString()
    } catch (t: Throwable) {
        "?"
    }

    private const val TAG = "GPUNET"
}
