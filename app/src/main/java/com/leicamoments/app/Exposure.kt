package com.leicamoments.app

import android.graphics.Bitmap
import com.codex.leicaapp.MadridLegendary

/**
 * 曝光算子（三段步进器 ±10 档）：整幅画面统一曝光调整，策略为标准摄影曝光——
 * sRGB→线性化→×2^EV→回 sRGB，高光按曝光自然滚出白场（非线性硬裁），负档均匀压暗。
 * 每档预生成 256 级查找表，逐像素三次查表，无分支。
 * 用途：RAW 直出偏暗/偏亮时的画面曝光微调；预览路径 applyToBitmap（≤2048px，百毫秒级），
 * 导出/超分路径 applyToJpeg（全分辨率烘焙 + EXIF 回填）。
 */
object Exposure {

    const val MIN_LEVEL = -10
    const val MAX_LEVEL = 10

    /** 每档曝光量 EV（装机后口头微调的唯一入口）：±10 档 = ±1.5EV 总行程 */
    private const val EV_PER_LEVEL = 0.15f

    fun clamp(level: Int): Int = level.coerceIn(MIN_LEVEL, MAX_LEVEL)

    private fun srgbToLin(x: Float): Float {
        val d = x.toDouble()
        val lin = if (d <= 0.04045) d / 12.92 else Math.pow((d + 0.055) / 1.055, 2.4)
        return lin.toFloat()
    }

    private fun linToSrgb(x: Float): Float {
        val d = x.toDouble()
        val srgb = if (d <= 0.0031308) d * 12.92 else 1.055 * Math.pow(d, 1.0 / 2.4) - 0.055
        return srgb.toFloat()
    }

    /** 档位 → 256 级 LUT（sRGB 域输入输出，线性域乘增益） */
    private fun lutFor(level: Int): IntArray {
        val gain = Math.pow(2.0, (level * EV_PER_LEVEL).toDouble())
        val lut = IntArray(256)
        for (v in 0..255) {
            var lin = srgbToLin(v / 255f) * gain
            if (lin > 1.0) lin = 1.0
            lut[v] = (linToSrgb(lin.toFloat()) * 255f + 0.5f).toInt().coerceIn(0, 255)
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

    /**
     * 全分辨率字节层应用：decode → 算子 → JPEG95 → 回填源 EXIF（机型/参数/GPS 不丢）。
     * level==0 原样返回输入字节。
     */
    fun applyToJpeg(jpeg: ByteArray, level: Int): ByteArray {
        if (level == 0) return jpeg
        val bmp = ImageUtils.fromJpeg(jpeg) ?: return jpeg
        val adjusted = applyToBitmap(bmp, level)
        if (adjusted !== bmp) bmp.recycle()
        return MadridLegendary.copyExif(jpeg, ImageUtils.toJpeg(adjusted, 95))
    }
}
