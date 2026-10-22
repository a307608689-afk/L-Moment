package com.leicamoments.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream

/**
 * M3 手工黑白滤镜栈（monopan 在无 Adreno/Mali OpenCL 设备上失效时的兜底）。
 *
 * 五层结构（全部逐像素，纯 CPU）：
 *  1. 通道混合黑白（黄滤权重：肤色提亮、蓝天压深）——黑白性格来源
 *  2. 胶片 S 曲线（趾部压深 + 肩部缓滚降）
 *  3. 底灰/褪色（黑位抬升、白位封顶）
 *  4. 颗粒（亮度相关幅度：中间调最重；低频噪声场双线性采样，尺寸随分辨率等比）
 *  5. 输出恒为灰度
 *
 * 触发判据：解码后计算平均色度（三通道最大差值的均值）。
 *  - monopan 成功 → 真灰度 → 平均色度 ≈ 0（JPEG 子采样噪声 <2）→ 原样返回，本类零影响
 *  - monopan 失败 → LUT 彩色输出 → 平均色度明显 >0 → 接管
 * OOM 时逐级降采样重试（1/2/4），保证任何尺寸都出黑白；全部异常退回原图字节。
 */
object M3Mono {

    private const val TAG = "M3Mono"

    /** 全部可调参数集中于此（后续若以拟合参数替换，也填这里） */
    object Params {
        // 通道混合权重（黄滤：肤色提亮、蓝天压深）
        const val W_R = 0.54f
        const val W_G = 0.40f
        const val W_B = 0.06f
        // 底灰/白位封顶（0-255）
        const val FADE_BLACK = 10f
        const val FADE_WHITE = 246f
        // S 曲线混合度（0=原线性，1=全 smoothstep）
        const val CONTRAST_S = 0.42f
        // 颗粒最大幅度（中间调处，0-255 灰度单位）
        const val GRAIN_MAX = 13f
        // 颗粒视觉尺寸：噪声场按图像宽度的此比例降采样
        const val GRAIN_SIZE_DIV = 1400f
        // 平均色度判定阈值：低于此值认为已是黑白（JPEG 色度子采样噪声 <2）
        const val GRAY_MEAN_CHROMA = 2.5f
    }

    /** 最近一次运行的诊断信息（诊断日志/黑匣子用） */
    @Volatile
    var lastInfo: String? = null
        private set

    /** 入口：保证 M3 输出为黑白。任何异常在 sample<4 时降采样重试。 */
    fun ensure(jpeg: ByteArray): ByteArray {
        var sample = 1
        while (true) {
            var b: Bitmap? = null
            try {
                val opts = BitmapFactory.Options().apply {
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                    inSampleSize = sample
                    inMutable = true   // 必需：setPixels 只支持可变位图（immutable 会抛 IllegalStateException）
                }
                b = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, opts)
                if (b == null) { lastInfo = "decode null"; return jpeg }
                val w = b.width; val h = b.height
                val px = IntArray(w * h)
                b.getPixels(px, 0, w, 0, 0, w, h)

                // 平均色度检测（三通道最大差值的均值）
                var chromaSum = 0f; var total = 0
                val step = maxOf(1, px.size / 4096)
                var i = 0
                while (i < px.size) {
                    val p = px[i]
                    val r = (p shr 16) and 0xFF; val g = (p shr 8) and 0xFF; val bl = p and 0xFF
                    chromaSum += maxOf(
                        Math.abs(r - g).toFloat(),
                        Math.abs(g - bl).toFloat(),
                        Math.abs(r - bl).toFloat()
                    )
                    total++
                    i += step
                }
                val mean = chromaSum / total

                if (mean <= Params.GRAY_MEAN_CHROMA) {
                    lastInfo = "sample=$sample ${w}x$h meanChroma=$mean -> 已灰度跳过"
                    DiagLog.i(TAG, lastInfo!!)
                    b.recycle()
                    return jpeg   // 已是黑白（monopan 成功），原样返回
                }

                convert(px, w, h)
                b.setPixels(px, 0, w, 0, 0, w, h)
                val out = JpegEnc.encode(b)
                b.recycle()
                lastInfo = "converted sample=$sample ${w}x$h meanChroma=$mean"
                return out
            } catch (t: Throwable) {
                try { b?.recycle() } catch (ignored: Throwable) { }
                // 异常类型 + 堆栈顶写进诊断（null 消息的异常靠类型定位）
                val sw = java.io.StringWriter()
                t.printStackTrace(java.io.PrintWriter(sw))
                val stackTop = sw.toString().lineSequence()
                    .filter { it.trim().startsWith("at ") }
                    .take(3)
                    .joinToString(" | ")
                val info = "exception ${t.javaClass.simpleName}: ${t.message} @ $stackTop (sample=$sample)"
                DiagLog.w(TAG, info)
                if (sample >= 4) {
                    lastInfo = info
                    return jpeg
                }
                sample *= 2   // 任何异常都降采样重试（内存形状类异常在低分辨率下可避开）
            }
        }
    }

    private fun convert(px: IntArray, w: Int, h: Int) {
        val p = Params

        // 曲线查表：底灰/白位封顶 + S 曲线一次合成
        val range = p.FADE_WHITE - p.FADE_BLACK
        val curve = FloatArray(256)
        for (v in 0..255) {
            val u0 = (v - p.FADE_BLACK) / range
            val u = u0.coerceIn(0f, 1f)
            val s = u * u * (3f - 2f * u)
            val mixed = u + (s - u) * p.CONTRAST_S
            curve[v] = p.FADE_BLACK + mixed * range
        }

        // 低频噪声场（颗粒），xorshift 填充，-1..1
        val gs = maxOf(1f, w / p.GRAIN_SIZE_DIV)
        val nw = (w / gs).toInt() + 2
        val nh = (h / gs).toInt() + 2
        val noise = FloatArray(nw * nh)
        var seed = 0x9E3779B9.toInt()
        for (i in noise.indices) {
            seed = seed xor (seed shl 13)
            seed = seed xor (seed ushr 17)
            seed = seed xor (seed shl 5)
            noise[i] = (seed and 0xFFFF) / 32768f - 1f
        }

        val wR = p.W_R; val wG = p.W_G; val wB = p.W_B
        val gMax = p.GRAIN_MAX

        for (y in 0 until h) {
            val gy = y / gs
            val y0 = gy.toInt().coerceIn(0, nh - 2)
            val fy = gy - y0
            val row0 = y0 * nw; val row1 = row0 + nw

            for (x in 0 until w) {
                val gx = x / gs
                val x0 = gx.toInt().coerceIn(0, nw - 2)
                val fx = gx - x0

                val p0 = px[y * w + x]
                val r = (p0 shr 16) and 0xFF
                val g = (p0 shr 8) and 0xFF
                val bl = p0 and 0xFF

                // 1. 通道混合 + 2. 曲线（含底灰）
                val mix = wR * r + wG * g + wB * bl
                var v = curve[mix.toInt().coerceIn(0, 255)]

                // 3. 颗粒：亮度相关（中间调最重），双线性采样低频噪声场
                val t = v / 255f
                val shape = (t * (1f - t) * 4f).coerceIn(0f, 1f)
                val amp = gMax * (0.3f + 0.7f * shape)
                val n00 = noise[row0 + x0]; val n10 = noise[row0 + x0 + 1]
                val n01 = noise[row1 + x0]; val n11 = noise[row1 + x0 + 1]
                val nTop = n00 + (n10 - n00) * fx
                val nBot = n01 + (n11 - n01) * fx
                v += (nTop + (nBot - nTop) * fy) * amp

                val vi = v.toInt().coerceIn(0, 255)
                px[y * w + x] = (0xFF shl 24) or (vi shl 16) or (vi shl 8) or vi
            }
        }
    }
}
