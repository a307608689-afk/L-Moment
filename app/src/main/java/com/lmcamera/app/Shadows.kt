package com.lmcamera.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.codex.lmapp.MadridLegendary

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
     *
     * 风格强度（lutPct < 100 且 baseJpeg 非空）：decode 后、曲线之前把「已套风格的结果」
     * 往「风格前基图」线性拉回（[AdjustOps.blendLut]/[AdjustOps.blendLutInPlace]，
     * 与预览链 blendLut(bO, bR, strength) 同语义同顺序）——曲线档位作用在混合结果上，
     * 与预览一致。混合失败按 100% 输出（宁可风格全量也不出半成品偏色）。
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
        glowLevel: Int = 0,
        extras: AdjustOps.Extras = AdjustOps.Extras(),
        baseJpeg: ByteArray? = null,
        lutPct: Int = 100
    ): ByteArray {
        val strengthOn = baseJpeg != null && lutPct in 1..99
        if (brightLevel == 0 && exposureLevel == 0 && highlightLevel == 0 &&
            shadowLevel == 0 && blackLevel == 0 && satLevel == 0 &&
            contrastLevel == 0 && glowLevel == 0 && extras.isNeutral && !strengthOn
        ) return jpeg
        // 需要强度混合时解成 mutable 位图（就地混合省一份第三位图，24MP 画布必要）
        val bmp = if (strengthOn) {
            try {
                val opts = BitmapFactory.Options()
                opts.inMutable = true
                BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, opts) ?: return jpeg
            } catch (t: Throwable) {
                ImageUtils.fromJpeg(jpeg) ?: return jpeg
            }
        } else {
            ImageUtils.fromJpeg(jpeg) ?: return jpeg
        }
        var work = bmp
        if (strengthOn) {
            val baseJ = baseJpeg!!
            try {
                val baseBmp = ImageUtils.fromJpeg(baseJ)
                if (baseBmp != null) {
                    try {
                        if (baseBmp.width == work.width && baseBmp.height == work.height &&
                            work.isMutable
                        ) {
                            AdjustOps.blendLutInPlace(baseBmp, work, lutPct)
                        } else {
                            // 尺寸不一致（理论不发生，base 与 styled 同一工作画布）：退 blendLut
                            val blended = AdjustOps.blendLut(baseBmp, work, lutPct)
                            if (blended !== baseBmp && blended !== work) {
                                work.recycle()
                                work = blended
                            }
                        }
                    } finally {
                        baseBmp.recycle()
                    }
                } else {
                    DiagLog.w("Shadows", "风格强度混合：基图解码失败，按 100% 输出")
                }
            } catch (t: Throwable) {
                DiagLog.e("Shadows", "风格强度混合失败，按 100% 输出", t)
            }
        }
        // 复合色调引擎：七参数合成单条曲线 + 逐像素饱和度，单次遍历 + 色相保护 + 抖动
        val adjusted = ToneCurve.applyToBitmap(
            work, ToneCurve.of(brightLevel, exposureLevel, highlightLevel,
                shadowLevel, blackLevel, satLevel, contrastLevel))
        if (adjusted !== work) work.recycle()
        if (glowLevel != 0) SoftGlow.applyInPlace(adjusted, glowLevel)
        // 新维度（色温/色调/黑白/颗粒/暗角）：与预览链同序（柔光之后），单次 encode 前落地
        val withExtras = if (extras.isNeutral) adjusted else {
            val out = AdjustOps.applyExtras(adjusted, extras)
            if (out !== adjusted) adjusted.recycle()
            out
        }
        return MadridLegendary.copyExif(jpeg, ImageUtils.toJpeg(withExtras, 95))
    }
}
