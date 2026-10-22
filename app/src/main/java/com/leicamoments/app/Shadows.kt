package com.leicamoments.app

import android.graphics.Bitmap
import com.codex.leicaapp.MadridLegendary

/**
 * 阴影算子（三段步进器 ±10 档）：软肩硬顶阴影窗口——lum≤T0 增益全额、lum≥T1 严格 =1（亮部零触碰）、
 * T0~T1 之间 smoothstep 平滑过渡。纯 1D 色调曲线，无空间掩码，无边缘伪影。
 * 正值提亮阴影，负值压暗阴影回补对比；与曝光算子组合时先曝光后阴影（与预览/烘焙管线同序）。
 */
object Shadows {

    const val MIN_LEVEL = -10
    const val MAX_LEVEL = 10

    /** 每档阴影增益（装机后口头微调的唯一入口）：level*s 为暗部最大增益系数 */
    private const val STEP = 0.25f

    /** 阴影窗口下阈值：lum ≤ T0 增益全额生效 */
    private const val T0 = 0.18f

    /** 阴影窗口上阈值（硬顶）：lum ≥ T1 增益严格 =1，亮部一个百分点都不动 */
    private const val T1 = 0.50f

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
            // 软肩硬顶阴影窗口：≤T0 全额、≥T1 严格为零、之间 smoothstep 平滑过渡
            val t = ((lum - T0) / (T1 - T0)).coerceIn(0f, 1f)
            val smooth = t * t * (3f - 2f * t)
            val wgt = 1f - smooth
            val gain = 1f + s * wgt
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

    /**
     * 组合烘焙：亮度→曝光→高光→阴影→黑色阶→对比度合成单条曲线 + 饱和度逐像素，
     * 尾接柔光（SoftGlow，空间算子在曲线之后；见 SoftGlow 头注的 8-bit 域说明），
     * 一次 decode/encode（避免多道 JPEG95 重编码叠加损失），顺序与预览管线一致；
     * EXIF 回填源字节。八者均为 0 时原样返回。尾参缺省 0 = 旧调用零改动。
     */
    fun applyStackToJpeg(
        jpeg: ByteArray,
        brightLevel: Int,
        exposureLevel: Int,
        highlightLevel: Int,
        shadowLevel: Int,
        blackLevel: Int,
        satLevel: Int = 0,
        contrastLevel: Int = 0,
        glowLevel: Int = 0
    ): ByteArray {
        if (brightLevel == 0 && exposureLevel == 0 && highlightLevel == 0 &&
            shadowLevel == 0 && blackLevel == 0 && satLevel == 0 &&
            contrastLevel == 0 && glowLevel == 0
        ) return jpeg
        val bmp = ImageUtils.fromJpeg(jpeg) ?: return jpeg
        // 复合色调引擎：七参数合成单条曲线 + 逐像素饱和度，单次遍历 + 色相保护 + 抖动
        val adjusted = ToneCurve.applyToBitmap(
            bmp, ToneCurve.of(brightLevel, exposureLevel, highlightLevel,
                shadowLevel, blackLevel, satLevel, contrastLevel))
        if (adjusted !== bmp) bmp.recycle()
        if (glowLevel != 0) SoftGlow.applyInPlace(adjusted, glowLevel)
        return MadridLegendary.copyExif(jpeg, ImageUtils.toJpeg(adjusted, 95))
    }
}
