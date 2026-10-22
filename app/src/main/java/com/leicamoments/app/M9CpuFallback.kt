package com.leicamoments.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import java.io.File
import java.io.FileOutputStream

/**
 * M9/M10-R 完整链（QNN HTP）失败后的 CPU 降级链。
 *
 * 原理：小米 MAGE 引擎的 QNN 执行器支持多后端（libQnnCpu/Gpu/Htp）。
 * 本方案使用两个二进制补丁副本（主链零改动）：
 *  - libstyletrans_cpu.so : runner 副本，dlopen 目标改为 libmialgo_styl.so / libQnnCpu.so / libQnnSystm.so
 *  - libmialgo_styl.so    : 引擎副本，libQnnHtp.so -> libQnnCpu.so
 *  - libQnnCpu.so / libQnnSystm.so : QNN SDK 2.28.0 CPU 后端
 *
 * 流程：JPEG -> RGB565 临时文件 -> spawn runner -> 读 RGB888 输出 -> JPEG。
 * 任一环节失败返回 null（调用方继续走纯 LUT 降级，零回归）。
 */
object M9CpuFallback {

    private const val TAG = "M9CPU"

    /** 最近一次 CPU 链尝试的状态描述（供 UI 展示），null = 未尝试 */
    @Volatile
    var lastStatus: String? = null
        private set

    /** 对 JPEG 字节执行 CPU styletrans；失败返回 null。 */
    fun run(app: Context, jpeg: ByteArray): ByteArray? {
        lastStatus = null
        try {
            val workDir = File(app.cacheDir, "m9cpu").apply { mkdirs() }
            val inPath = File(workDir, "in.raw").absolutePath
            val outPath = File(workDir, "out.raw").absolutePath
            val modelDir = File(app.filesDir, "leica-v4").absolutePath
            val nlb = app.applicationInfo.nativeLibraryDir
            val runner = "$nlb/libstyletrans_cpu.so"

            // 1. 解码并摆正（rotation 参数传 0）
            val bmp = decodeUpright(jpeg) ?: run { lastStatus = "decode fail"; return null }
            // 偶数对齐（runner 要求宽高为偶数）
            val w = bmp.width - (bmp.width % 2)
            val h = bmp.height - (bmp.height % 2)
            val aligned = if (w != bmp.width || h != bmp.height) {
                Bitmap.createBitmap(bmp, 0, 0, w, h)
            } else bmp

            // 2. 写 RGB565 输入（尺寸 w*h*2）
            val pixels = IntArray(w * h)
            aligned.getPixels(pixels, 0, w, 0, 0, w, h)
            val raw = ByteArray(w * h * 2)
            var o = 0
            for (p in pixels) {
                val r = (p shr 16) and 0xF8
                val g = (p shr 8) and 0xFC
                val b = p and 0xF8
                val v = (r shl 8) or (g shl 3) or (b shr 3)
                raw[o++] = (v shr 8).toByte()
                raw[o++] = v.toByte()
            }
            FileOutputStream(inPath).use { it.write(raw) }

            // 3. spawn runner（argv 与小米 m9base 协议一致）
            val argv = listOf(
                runner, nlb, modelDir, inPath, outPath,
                w.toString(), h.toString(),
                "0",   // rotation
                "200", // lux
                "5500" // cct
            )
            // 桩库仅 libcdsprpc（非高通设备缺此厂商库）从 assets 解压到 filesDir/m9stub，
            // 仅通过 LD_LIBRARY_PATH 注入 CPU 子进程——绝不进 nativeLibraryDir。
            // 注意：不注入 libOpenCL 桩——有真 OpenCL 的设备（Adreno/Mali）必须用厂商真库
            // （引擎白名单只认 Adreno/Mali 且预处理 kernel 跑在 GPU 上）；无 OpenCL 的设备
            // 引擎自然报错 → 优雅降级 LUT。
            val stubDir = File(app.filesDir, "m9stub").apply { mkdirs() }
            for (name in listOf("libcdsprpc.so")) {
                val f = File(stubDir, name)
                app.assets.open("m9stub/$name").use { ins ->
                    FileOutputStream(f).use { ins.copyTo(it) }
                }
            }
            val pb = ProcessBuilder(argv).redirectErrorStream(true)
            // 子进程不继承 App 进程库路径，bionic linker 也不搜索可执行文件所在目录：
            //  - nativeLibraryDir 必须显式加入，否则 libmialgo_styl 的 DT_NEEDED
            //    （libc++_shared/libQnnCpu/libQnnSystem 等）裸名解析全部失败 → exit=6
            //  - libmialgo_styl 还硬依赖 libOpenCL.so（裸名，Adreno 在 /vendor/lib64），
            //    linker 的 (default) 命名空间不替 app 子进程搜 vendor 目录 → 同样 exit=6，
            //    故追加 /vendor/lib64；nlb 保持最前，防止 vendor 自带 libQnnHtp 等反超打包版本。
            //  - 切勿追加 /system/lib64：system 库由默认命名空间自动供给，显式加载会
            //    阴差阳错（实测 liblog.so 的 __hash_memory 符号在子进程符号上下文解析
            //    失败 → exit=6）
            pb.environment()["LD_LIBRARY_PATH"] = "$nlb:$stubDir:/vendor/lib64"
            // 系统自动供给的 liblog.so 引用平台 libc++ 的 __hash_memory，而 bundled
            // libc++_shared（旧版 NDK）未导出该符号且 dlopen 不进全局组 → 子进程绑定
            // 失败 exit=6。LD_PRELOAD 把平台 libc++ 提前放进全局组供其绑定。
            // 注意：LD_PRELOAD 条目不存在时是致命错误（exit=1，视作主可执行文件
            // 的 DT_NEEDED），必须先探测文件存在，只设真实路径；一个都不在则不设
            // （fail-open，退回 liblog 绑定失败的旧行为）
            val preloads = listOf(
                "/apex/com.android.runtime/lib64/bionic/libc++.so",
                "/system/lib64/libc++.so",
                "/system_ext/lib64/libc++.so",
            ).filter { File(it).exists() }
            if (preloads.isNotEmpty()) {
                pb.environment()["LD_PRELOAD"] = preloads.joinToString(" ")
            }
            val proc = pb.start()
            val output = proc.inputStream.bufferedReader().use { it.readText() }
            val exit = proc.waitFor()
            DiagLog.w(TAG, "exit=$exit out=$output")
            // 完整诊断信息落盘（用户不可见，只进诊断日志）
            File(workDir, "last_run.txt").writeText("exit=$exit\nargv=$argv\noutput:\n$output")
            lastStatus = "exit=$exit\n$output"

            // runner 返回码：0=成功；1=argc；2/3=参数域；4=fopen(in)；5=fread；6=dlopen；
            // 7=符号；8=init；9=run；10=fopen(out)；11=写入不完整
            if (exit != 0) return null

            // 4. 读 RGB888 输出（w*h*3）
            val outRaw = File(outPath).readBytes()
            if (outRaw.size < w * h * 3) { lastStatus += " short-out"; return null }
            val outPixels = IntArray(w * h)
            var q = 0
            var i = 0
            while (q < outPixels.size) {
                val r = outRaw[i++].toInt() and 0xFF
                val g = outRaw[i++].toInt() and 0xFF
                val b = outRaw[i++].toInt() and 0xFF
                outPixels[q++] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
            val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            result.setPixels(outPixels, 0, w, 0, 0, w, h)
            val encoded = JpegEnc.encode(result)
            result.recycle()
            if (aligned !== bmp) aligned.recycle()
            // 回填源 EXIF（机型/拍摄参数/GPS），下游 applyToJpeg/applyWarmLut 才有可续传的 APP1 段
            return com.codex.leicaapp.MadridLegendary.copyExif(jpeg, encoded)
        } catch (t: Throwable) {
            DiagLog.e(TAG, "cpu chain failed", t)
            lastStatus = "exception: ${t.message}"
            return null
        }
    }

    private fun decodeUpright(jpeg: ByteArray): Bitmap? {
        val bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: return null
        val rotation = try {
            val exif = androidx.exifinterface.media.ExifInterface(java.io.ByteArrayInputStream(jpeg))
            when (exif.getAttributeInt(
                androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION, 1)) {
                3 -> 180f; 6 -> 90f; 8 -> 270f
                else -> 0f
            }
        } catch (t: Throwable) { 0f }
        if (rotation == 0f) return bmp
        val m = Matrix().apply { postRotate(rotation) }
        return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
    }
}
