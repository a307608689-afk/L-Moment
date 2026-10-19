package com.leicamoments.app

import android.graphics.Bitmap

/**
 * 黑色阶算子（三段步进器 ±10 档）：整体移动黑场端点——
 * out = (v - b) / (1 - b)，b = level*STEP。
 * 正档抬高黑场（黑位收死、对比增强、暗部压沉）；负档降低黑场（黑位放灰、暗部微提）。
 * 预生成 256 级查找表，逐通道三次查表；b 上限 0.25 远离奇点 1.0。
 */
object BlackLevel {

    const val MIN_LEVEL = -10
    const val MAX_LEVEL = 10

    /** 每档黑阶位移（满量程比例，装机后口头微调的唯一入口）：±10 档 = ±0.25 */
    private const val STEP = 0.025f

    fun clamp(level: Int): Int = level.coerceIn(MIN_LEVEL, MAX_LEVEL)

    private fun lutFor(level: Int): IntArray {
        val b = (level * STEP).toDouble()
        val lut = IntArray(256)
        for (v in 0..255) {
            var y = (v / 255.0 - b) / (1.0 - b)
            if (y < 0.0) y = 0.0
            if (y > 1.0) y = 1.0
            lut[v] = (y * 255.0 + 0.5).toInt()
        }
        return lut
    }

    /** Bitmap 层应用，返回新 Bitmap；level==0 原样返回输入（不拷贝）。 */
    fun applyToBitmap(src: Bitmap, level: Int): Bitmap {
        if (level == 0) return src
        val lut = lutFor(level)
        val w = src.width
        val h = src.height
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)
        for (i in pixels.indices) {
            val p = pixels[i]
            val nr = lut[(p shr 16) and 0xFF]
            val ng = lut[(p shr 8) and 0xFF]
            val nb = lut[p and 0xFF]
            pixels[i] = (p and 0xFF000000.toInt()) or (nr shl 16) or (ng shl 8) or nb
        }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(pixels, 0, w, 0, 0, w, h)
        return out
    }
}
