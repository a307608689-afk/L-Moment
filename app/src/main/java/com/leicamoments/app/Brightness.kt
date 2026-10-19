package com.leicamoments.app

import android.graphics.Bitmap

/**
 * 亮度算子（三段步进器 ±10 档）：sRGB 域全画面均匀增益 out = v × (1 + level*STEP)。
 * 与曝光算子的分工：曝光是线性域物理 EV（高光按曝光滚出），亮度是显示域均匀缩放（各档等比例提/压，
 * 不改变黑场与白场端点）。预生成 256 级查找表，逐通道三次查表。
 */
object Brightness {

    const val MIN_LEVEL = -10
    const val MAX_LEVEL = 10

    /** 每档 sRGB 增益（装机后口头微调的唯一入口）：±10 档 = ×0.7~×1.3 */
    private const val STEP = 0.03f

    fun clamp(level: Int): Int = level.coerceIn(MIN_LEVEL, MAX_LEVEL)

    private fun lutFor(level: Int): IntArray {
        val gain = 1.0 + level * STEP
        val lut = IntArray(256)
        for (v in 0..255) {
            var y = v * gain
            if (y > 255.0) y = 255.0
            lut[v] = (y + 0.5).toInt()
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
