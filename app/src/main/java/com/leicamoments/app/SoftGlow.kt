package com.leicamoments.app

import android.graphics.Bitmap
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * P3 第八档「柔光」（Orton 扩散 / 反向清晰化）。
 *
 * 正档 = 经典 Orton 柔光：模糊版 ×1.10 提亮 → 与原图 screen 混合 → 高光掩码限域 →
 *        强度混合；雾集中在中高光，阴影不上灰。负档 = 反向清晰化（unsharp，向模糊版
 *        反向拉），两方向共用同一次三趟分离盒模糊（近似高斯）。
 *
 * 半径 = min(w,h)/100：与分辨率无关的视觉口径，预览 1920 基准与全尺寸导出观感一致。
 * 8-bit 域执行：柔光是大面积平滑效果，JPEG 终态本就 8-bit；16-bit 重放链在降 8-bit 后
 * 调用本算子，量化差不可见（与 v10 删 dither 同一结论）。全像素条带链不接柔光：
 * 45MP 级半径 ~60-80px 超出条带重叠带，逐带处理必然带边不连续（LM9 v15 教训）。
 *
 * 线程安全：无共享状态，adjExecutor（预览）与导出 executor 可并发调用不同位图。
 */
internal object SoftGlow {

    const val MIN_LEVEL = -10
    const val MAX_LEVEL = 10

    /** 正档 +10 的最大混合强度（经典 Orton 观感区间 0.45~0.6） */
    private const val MAX_STRENGTH = 0.55f

    /** 负档 -10 的最大清晰化强度 */
    private const val MAX_CLARITY = 0.45f

    /** 高光掩码 smoothstep 端点（亮度 0..255）：柔光集中在中高光 */
    private const val MASK_LO = 115
    private const val MASK_HI = 235

    /**
     * 原地处理并返回同一位图（便于与 ToneCurve 链式衔接）；level==0 或失败原样返回。
     * 输入须为不透明 ARGB_8888（链路来源：JPEG 解码 / ToneCurve 输出，均满足）。
     * 像素防护：>26M px（50MP RAW 全尺寸预览级）跳过——px+blur 双 IntArray 瞬时 ~400MB
     * 必 OOM；24MP HiRes 导出（23.9M）仍全量执行，预览侧超大源只是暂无柔光效果不崩。
     */
    fun applyInPlace(bmp: Bitmap, level: Int): Bitmap {
        if (level == 0 || bmp.isRecycled) return bmp
        val w = bmp.width
        val h = bmp.height
        if (w < 3 || h < 3 || w.toLong() * h > 26_000_000L) return bmp
        return try {
            val px = IntArray(w * h)
            bmp.getPixels(px, 0, w, 0, 0, w, h)
            val blur = px.copyOf()
            boxBlur3(blur, w, h, max(2, min(w, h) / 100))
            val n = px.size
            if (level > 0) {
                val k = (MAX_STRENGTH * level / 10f * 255f).toInt()   // 0..~140
                for (i in 0 until n) {
                    val p = px[i]
                    val b = blur[i]
                    val pr = p shr 16 and 0xFF
                    val pg = p shr 8 and 0xFF
                    val pb = p and 0xFF
                    // 亮层：模糊版 ×1.10 提亮（Orton 的 glow 层）
                    val gr = min(255, ((b shr 16 and 0xFF) * 282) shr 8)
                    val gg = min(255, ((b shr 8 and 0xFF) * 282) shr 8)
                    val gb = min(255, ((b and 0xFF) * 282) shr 8)
                    // screen：255-(255-a)(255-g)/255
                    val sr = 255 - (255 - pr) * (255 - gr) / 255
                    val sg = 255 - (255 - pg) * (255 - gg) / 255
                    val sb = 255 - (255 - pb) * (255 - gb) / 255
                    // 高光掩码 smoothstep（亮度域），阴影不被上雾
                    val lum = (pr * 77 + pg * 150 + pb * 29) shr 8
                    var t = (lum - MASK_LO) * 255 / (MASK_HI - MASK_LO)
                    if (t < 0) t = 0 else if (t > 255) t = 255
                    val m = t * t * (3 * 255 - 2 * t) / (255 * 255)   // 0..255
                    val eff = k * m / 255                              // 0..k
                    val nr = (pr + (sr - pr) * eff / 255).coerceIn(0, 255)
                    val ng = (pg + (sg - pg) * eff / 255).coerceIn(0, 255)
                    val nb = (pb + (sb - pb) * eff / 255).coerceIn(0, 255)
                    px[i] = (0xFF shl 24) or (nr shl 16) or (ng shl 8) or nb
                }
            } else {
                val kf = MAX_CLARITY * (-level) / 10f                  // 0..0.45
                for (i in 0 until n) {
                    val p = px[i]
                    val b = blur[i]
                    val pr = p shr 16 and 0xFF
                    val pg = p shr 8 and 0xFF
                    val pb = p and 0xFF
                    val nr = (pr + (pr - (b shr 16 and 0xFF)) * kf).roundToInt().coerceIn(0, 255)
                    val ng = (pg + (pg - (b shr 8 and 0xFF)) * kf).roundToInt().coerceIn(0, 255)
                    val nb = (pb + (pb - (b and 0xFF)) * kf).roundToInt().coerceIn(0, 255)
                    px[i] = (0xFF shl 24) or (nr shl 16) or (ng shl 8) or nb
                }
            }
            bmp.setPixels(px, 0, w, 0, 0, w, h)
            bmp
        } catch (t: Throwable) {
            DiagLog.e("Glow", "柔光算子失败，返回原图", t)
            bmp
        }
    }

    /** 三趟分离盒模糊（近似高斯）；原地改写 [px]。窗口半径 [r]，边缘钳位。 */
    private fun boxBlur3(px: IntArray, w: Int, h: Int, r: Int) {
        val win = 2 * r + 1
        val tmp = IntArray(px.size)
        var pass = 0
        while (pass < 3) {
            // 水平：px → tmp
            var y = 0
            while (y < h) {
                val row = y * w
                var sr = 0
                var sg = 0
                var sb = 0
                var x = -r
                while (x <= r) {
                    val p = px[row + x.coerceIn(0, w - 1)]
                    sr += p shr 16 and 0xFF
                    sg += p shr 8 and 0xFF
                    sb += p and 0xFF
                    x++
                }
                x = 0
                while (x < w) {
                    tmp[row + x] = (0xFF shl 24) or (sr / win shl 16) or (sg / win shl 8) or (sb / win)
                    val po = px[row + (x - r).coerceIn(0, w - 1)]
                    val pi = px[row + (x + r + 1).coerceIn(0, w - 1)]
                    sr += (pi shr 16 and 0xFF) - (po shr 16 and 0xFF)
                    sg += (pi shr 8 and 0xFF) - (po shr 8 and 0xFF)
                    sb += (pi and 0xFF) - (po and 0xFF)
                    x++
                }
                y++
            }
            // 垂直：tmp → px
            var x = 0
            while (x < w) {
                var sr = 0
                var sg = 0
                var sb = 0
                var yv = -r
                while (yv <= r) {
                    val p = tmp[yv.coerceIn(0, h - 1) * w + x]
                    sr += p shr 16 and 0xFF
                    sg += p shr 8 and 0xFF
                    sb += p and 0xFF
                    yv++
                }
                yv = 0
                while (yv < h) {
                    px[yv * w + x] = (0xFF shl 24) or (sr / win shl 16) or (sg / win shl 8) or (sb / win)
                    val po = tmp[(yv - r).coerceIn(0, h - 1) * w + x]
                    val pi = tmp[(yv + r + 1).coerceIn(0, h - 1) * w + x]
                    sr += (pi shr 16 and 0xFF) - (po shr 16 and 0xFF)
                    sg += (pi shr 8 and 0xFF) - (po shr 8 and 0xFF)
                    sb += (pi and 0xFF) - (po and 0xFF)
                    yv++
                }
                x++
            }
            pass++
        }
    }
}
