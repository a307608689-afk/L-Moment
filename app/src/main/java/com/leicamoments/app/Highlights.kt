package com.leicamoments.app

import android.graphics.Bitmap

/**
 * 高光算子（三段步进器 ±10 档）：软底硬肩高光窗口——lum≥T1 增益全额、lum≤T0 严格 =1（暗部零触碰）、
 * T0~T1 之间 smoothstep 平滑过渡。纯 1D 色调曲线，无空间掩码，无边缘伪影。
 * 正档提亮高光，负档压暗高光回细节（天空/云层/灯光的反差不丢）。
 */
object Highlights {

    const val MIN_LEVEL = -10
    const val MAX_LEVEL = 10

    /** 每档高光增益（装机后口头微调的唯一入口）：level*s 为亮部最大增益系数 */
    private const val STEP = 0.25f

    /** 高光窗口下阈值（硬底）：lum ≤ T0 增益严格 =1，暗部一个百分点都不动 */
    private const val T0 = 0.50f

    /** 高光窗口上阈值：lum ≥ T1 增益全额生效 */
    private const val T1 = 0.82f

    fun clamp(level: Int): Int = level.coerceIn(MIN_LEVEL, MAX_LEVEL)

    /** Bitmap 层应用，返回新 Bitmap；level==0 原样返回输入（不拷贝）。 */
    fun applyToBitmap(src: Bitmap, level: Int): Bitmap {
        if (level == 0) return src
        val w = src.width
        val h = src.height
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)
        val s = level * STEP
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            val lum = (0.299f * r + 0.587f * g + 0.114f * b) / 255f
            // 软底硬肩高光窗口：≤T0 严格为零、≥T1 全额、之间 smoothstep 平滑过渡
            val t = ((lum - T0) / (T1 - T0)).coerceIn(0f, 1f)
            val smooth = t * t * (3f - 2f * t)
            val gain = 1f + s * smooth
            var nr = r * gain
            var ng = g * gain
            var nb = b * gain
            // P0 止血：补下限 clamp，负增益不得进通道（防符号位铺开出怪色）
            if (nr > 255f) nr = 255f else if (nr < 0f) nr = 0f
            if (ng > 255f) ng = 255f else if (ng < 0f) ng = 0f
            if (nb > 255f) nb = 255f else if (nb < 0f) nb = 0f
            pixels[i] = (p and 0xFF000000.toInt()) or
                (nr.toInt() shl 16) or (ng.toInt() shl 8) or nb.toInt()
        }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(pixels, 0, w, 0, 0, w, h)
        return out
    }
}
