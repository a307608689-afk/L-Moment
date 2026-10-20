package com.leicamoments.app

import android.content.Context
import android.graphics.Bitmap
import com.codex.leicaapp.AlexaLut
import com.codex.leicaapp.MadridShader

/**
 * 甲（LUT 家族全像素化）—— 条带算子实现。
 *
 * 为什么能按带切：LUT 查表只依赖**单像素**（65³/33³ 三线性插值），没有空间邻域；
 * 颗粒/暗角虽是全图坐标相关，但 [MadridShader] 已改为接受 `fullH + yOffset`，
 * 按「全图归一化 y」取样即与整图处理逐像素一致（颗粒周期 256 也由绝对 y 决定）。
 *
 * 两类实现：
 * - [LutOp]：点查表 + 颗粒/暗角 + 色调滑杆，O(带内存)；
 * - [CcdOp]：SepLUT 是**整图统计**算子，必须先在全图降采样上求一次 LUT（两遍法），
 *   之后逐带 apply 同一套查表 + 颗粒/暗角 + 滑杆。
 *
 * 不进这里的风格（见 [create] 返回 null）：M9/M9+/M10R/M9D/SA 走模型链，LP9 的
 * P9Post 是**全图空间场**（云边/天空掩码依赖整幅图像统计），单带无法复现，留待后续
 * 「全图 prepass」方案。
 */
internal object LutBandOps {

    /** 条带算子覆盖的风格判据（供 MainActivity 决定走全像素还是工作字节）。 */
    fun supports(style: LeicaEngine.Style?, customLutRef: String?): Boolean {
        // 自定义 LUT 走 LeicaEngine.processLut → AlexaLut 的 CustomLutPost 钩子：
        // 该钩子是**全图引导滤波**（半径 = 短边 × 0.008、触发判定取全图抽样），
        // 单带 apply 会把半径压到 4px 且逐带自算触发，接缝处必出色差，无法等价复现。
        // 宁可退回工作字节导出，也不产出与预览不一致的全像素图。
        if (customLutRef != null) return false
        if (style == null) return false
        return recipeOf(style) != null || profileOf(style) != null
    }

    /**
     * 构造条带算子；不支持时返回 null（调用方继续走工作字节导出，行为与改造前一致）。
     *
     * @param customLutRef 自定义 LUT 版本的 `file:` 引用（参数版本传 null）
     * @param levels 色调滑杆 [亮, 曝光, 高光, 阴影, 黑]（与导出路径同序）
     */
    fun create(
        context: Context,
        style: LeicaEngine.Style?,
        customLutRef: String?,
        levels: IntArray
    ): ImageUtils.StripBandOp? {
        val ctx = context.applicationContext
        if (customLutRef != null) return null   // 见 [supports]：全图引导滤波无法按带复现
        if (style == null) return null
        recipeOf(style)?.let { return LutOp(ctx, it.lut, it.grain, it.vignette, levels) }
        profileOf(style)?.let { return CcdOp(ctx, it, levels) }
        return null
    }

    // ---------------------------------------------------------------- 风格配方

    private class Recipe(val lut: String, val grain: Float, val vignette: Float)

    /** LUT_ONLY 家族（除 LP9）→ 资产 + MadridShader 常数（与 LeicaEngine 逐项对齐）。 */
    private fun recipeOf(style: LeicaEngine.Style): Recipe? = when (style) {
        LeicaEngine.Style.M4 -> Recipe(AlexaLut.LUT_ALEXA, 0.22f, 1.00f)
        LeicaEngine.Style.A709 -> Recipe(AlexaLut.LUT_ARRI709, 0.14f, 0.70f)
        LeicaEngine.Style.REVEAL -> Recipe(AlexaLut.LUT_ARRIREVEAL, 0.10f, 0.50f)
        LeicaEngine.Style.DUNE -> Recipe(AlexaLut.LUT_ARRI_DUNE, 0.26f, 0.85f)
        LeicaEngine.Style.LALALAND -> Recipe(AlexaLut.LUT_ARRI_LALA, 0.12f, 0.80f)
        LeicaEngine.Style.JOKER -> Recipe(AlexaLut.LUT_ARRI_JOKER, 0.30f, 1.00f)
        else -> null
    }

    private fun profileOf(style: LeicaEngine.Style): PortraitFilm.Profile? = when (style) {
        LeicaEngine.Style.M13 -> PortraitFilm.Profile.COOL
        LeicaEngine.Style.CCDP -> PortraitFilm.Profile.PINK
        LeicaEngine.Style.CCDW -> PortraitFilm.Profile.WARM
        else -> null
    }

    // ---------------------------------------------------------------- 算子

    private class LutOp(
        private val ctx: Context,
        private val lut: String,
        private val grain: Float,
        private val vignette: Float,
        private val levels: IntArray
    ) : ImageUtils.StripBandOp {

        override fun prepare(reader: ImageUtils.StripSource): Boolean = true

        override fun apply(band: Bitmap, top: Int, rows: Int, fullW: Int, fullH: Int): Bitmap? {
            if (!AlexaLut.applyInPlace(ctx, band, lut)) return null
            if (grain > 0f || vignette > 0f) {
                MadridShader.apply(band, grain, vignette, fullH, top)
            }
            return LevelStack.apply(band, levels)
        }
    }

    /** 两遍法：整图降采样求 LUT（一次），再逐带套用。 */
    private class CcdOp(
        private val ctx: Context,
        private val profile: PortraitFilm.Profile,
        private val levels: IntArray
    ) : ImageUtils.StripBandOp {

        private var bundle: PortraitFilm.LutBundle? = null

        override fun prepare(reader: ImageUtils.StripSource): Boolean {
            val sample = reader.sample(SAMPLE_EDGE)
                ?: reader.read(0, minOf(SAMPLE_EDGE, reader.height))
                ?: return false
            val b = try {
                PortraitFilm.computeBundle(ctx, sample)
            } finally {
                sample.recycle()
            }
            bundle = b
            if (b == null) DiagLog.w("BandOp", "${profile.name} 整图 LUT 求解失败：${PortraitFilm.lastError}")
            return b != null
        }

        override fun apply(band: Bitmap, top: Int, rows: Int, fullW: Int, fullH: Int): Bitmap? {
            val b = bundle ?: return null
            if (!PortraitFilm.applyBundle(band, profile, b)) return null
            if (profile.grain > 0f || profile.vignette > 0f) {
                MadridShader.apply(band, profile.grain, profile.vignette, fullH, top)
            }
            return LevelStack.apply(band, levels)
        }

        override fun release() {
            bundle = null
        }
    }

    /**
     * 两遍法采样边长：与 [PortraitFilm.INFER_EDGE] 取同一档（256），
     * 保证条带导出的 LUT 与预览/工作版链「同一张图 → 同一套查表」，避免仅因采样分辨率
     * 差异造成的整体偏色（实测 512 档与预览链 MAE 1.74，256 档收敛到 ~0）。
     */
    private const val SAMPLE_EDGE = 256
}

/**
 * 色调五滑杆的条带版：全部是单像素查表（Brightness/Exposure/Highlights/Shadows/BlackLevel），
 * 顺序与 [Shadows.applyStackToJpeg] 的整图路径一致。
 */
internal object LevelStack {

    fun apply(src: Bitmap, levels: IntArray): Bitmap {
        var cur = src
        cur = step(cur, levels[0]) { b, v -> Brightness.applyToBitmap(b, v) }
        cur = step(cur, levels[1]) { b, v -> Exposure.applyToBitmap(b, v) }
        cur = step(cur, levels[2]) { b, v -> Highlights.applyToBitmap(b, v) }
        cur = step(cur, levels[3]) { b, v -> Shadows.applyToBitmap(b, v) }
        cur = step(cur, levels[4]) { b, v -> BlackLevel.applyToBitmap(b, v) }
        return cur
    }

    private inline fun step(cur: Bitmap, level: Int, f: (Bitmap, Int) -> Bitmap): Bitmap {
        if (level == 0) return cur
        val next = f(cur, level)
        if (next !== cur) cur.recycle()
        return next
    }
}
