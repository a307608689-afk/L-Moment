package com.lmcamera.app

import android.content.Context
import android.graphics.Bitmap
import com.codex.lmapp.AlexaLut
import com.codex.lmapp.MadridShader

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
    fun supports(style: ColorEngine.Style?, customLutRef: String?): Boolean {
        // 自定义 LUT 走 ColorEngine.processLut → AlexaLut 的 CustomLutPost 钩子：
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
        style: ColorEngine.Style?,
        customLutRef: String?,
        levels: IntArray,
        extras: AdjustOps.Extras = AdjustOps.Extras(),
        lutPct: Int = 100
    ): ImageUtils.StripBandOp? {
        val ctx = context.applicationContext
        if (customLutRef != null) return null   // 见 [supports]：全图引导滤波无法按带复现
        if (style == null) return null
        recipeOf(style)?.let { return LutOp(ctx, it.lut, it.grain, it.vignette, levels, extras, lutPct) }
        profileOf(style)?.let { return CcdOp(ctx, it, levels, extras, lutPct) }
        return null
    }

    // ---------------------------------------------------------------- 风格配方

    private class Recipe(val lut: String, val grain: Float, val vignette: Float)

    /** LUT_ONLY 家族（除 LP9）→ 资产 + MadridShader 常数（与 ColorEngine 逐项对齐）。 */
    private fun recipeOf(style: ColorEngine.Style): Recipe? = when (style) {
        ColorEngine.Style.M4 -> Recipe(AlexaLut.LUT_ALEXA, 0.22f, 1.00f)
        ColorEngine.Style.A709 -> Recipe(AlexaLut.LUT_ARRI709, 0.14f, 0.70f)
        ColorEngine.Style.REVEAL -> Recipe(AlexaLut.LUT_ARRIREVEAL, 0.10f, 0.50f)
        ColorEngine.Style.DUNE -> Recipe(AlexaLut.LUT_ARRI_DUNE, 0.26f, 0.85f)
        ColorEngine.Style.LALALAND -> Recipe(AlexaLut.LUT_ARRI_LALA, 0.12f, 0.80f)
        ColorEngine.Style.JOKER -> Recipe(AlexaLut.LUT_ARRI_JOKER, 0.30f, 1.00f)
        // F-胶片家族（颗粒/暗角与 ColorEngine 逐项对齐）
        ColorEngine.Style.FCHROME -> Recipe(AlexaLut.LUT_FUJI_CHROME, 0.14f, 0.55f)
        ColorEngine.Style.FCNEG -> Recipe(AlexaLut.LUT_FUJI_CNEG, 0.16f, 0.55f)
        ColorEngine.Style.FNN -> Recipe(AlexaLut.LUT_FUJI_NN, 0.12f, 0.45f)
        ColorEngine.Style.FPNH -> Recipe(AlexaLut.LUT_FUJI_PNH, 0.10f, 0.40f)
        ColorEngine.Style.FACROS -> Recipe(AlexaLut.LUT_FUJI_ACROS, 0.15f, 0.60f)
        // K-胶片家族（颗粒/暗角与 ColorEngine.process 逐项对齐，漏一项即预览与全像素不一致）
        ColorEngine.Style.KP400 -> Recipe(AlexaLut.LUT_KODAK_400, 0.12f, 0.45f)
        ColorEngine.Style.KG200 -> Recipe(AlexaLut.LUT_KODAK_200, 0.20f, 0.55f)
        ColorEngine.Style.KE100 -> Recipe(AlexaLut.LUT_KODAK_100, 0.10f, 0.38f)
        ColorEngine.Style.KC64 -> Recipe(AlexaLut.LUT_KODAK_64, 0.13f, 0.50f)
        // H 色彩家族（颗粒/暗角与 ColorEngine.process 逐项对齐，漏一项即预览与全像素不一致）
        ColorEngine.Style.HN -> Recipe(AlexaLut.LUT_HASSEL_HN, 0.09f, 0.45f)
        ColorEngine.Style.HB -> Recipe(AlexaLut.LUT_HASSEL_HB, 0.08f, 0.55f)
        ColorEngine.Style.HG -> Recipe(AlexaLut.LUT_HASSEL_HG, 0.10f, 0.45f)
        ColorEngine.Style.HP -> Recipe(AlexaLut.LUT_HASSEL_HP, 0.07f, 0.40f)
        ColorEngine.Style.HW -> Recipe(AlexaLut.LUT_HASSEL_HW, 0.14f, 0.70f)
        ColorEngine.Style.HS -> Recipe(AlexaLut.LUT_HASSEL_HS, 0.14f, 0.40f)
        ColorEngine.Style.HM -> Recipe(AlexaLut.LUT_HASSEL_HM, 0.14f, 0.50f)
        ColorEngine.Style.HC -> Recipe(AlexaLut.LUT_HASSEL_HC, 0.07f, 0.38f)
        // R 色彩家族（颗粒/暗角与 ColorEngine.process 逐项对齐，漏一项即预览与全像素不一致）
        ColorEngine.Style.RPOS -> Recipe(AlexaLut.LUT_R_POS, 0.10f, 0.45f)
        ColorEngine.Style.RBLEACH -> Recipe(AlexaLut.LUT_R_BLEACH, 0.16f, 0.60f)
        ColorEngine.Style.RCROSS -> Recipe(AlexaLut.LUT_R_CROSS, 0.14f, 0.55f)
        ColorEngine.Style.RRETRO -> Recipe(AlexaLut.LUT_R_RETRO, 0.16f, 0.55f)
        ColorEngine.Style.RHBW -> Recipe(AlexaLut.LUT_R_HBW, 0.22f, 0.70f)
        ColorEngine.Style.RSTD -> Recipe(AlexaLut.LUT_R_STD, 0.08f, 0.35f)
        ColorEngine.Style.RSOFT -> Recipe(AlexaLut.LUT_R_SOFT, 0.12f, 0.30f)
        ColorEngine.Style.RNEG -> Recipe(AlexaLut.LUT_R_NEG, 0.12f, 0.40f)
        ColorEngine.Style.RSTREET -> Recipe(AlexaLut.LUT_R_STREET, 0.25f, 0.90f)
        ColorEngine.Style.RCROSS2 -> Recipe(AlexaLut.LUT_R_CROSS2, 0.14f, 0.55f)
        else -> null
    }

    private fun profileOf(style: ColorEngine.Style): PortraitFilm.Profile? = when (style) {
        ColorEngine.Style.M13 -> PortraitFilm.Profile.COOL
        ColorEngine.Style.CCDP -> PortraitFilm.Profile.PINK
        ColorEngine.Style.CCDW -> PortraitFilm.Profile.WARM
        else -> null
    }

    // ---------------------------------------------------------------- 算子

    private class LutOp(
        private val ctx: Context,
        private val lut: String,
        private val grain: Float,
        private val vignette: Float,
        private val levels: IntArray,
        private val extras: AdjustOps.Extras,
        private val lutPct: Int
    ) : ImageUtils.StripBandOp {

        override fun prepare(reader: ImageUtils.StripSource): Boolean = true

        override fun apply(band: Bitmap, top: Int, rows: Int, fullW: Int, fullH: Int): Bitmap? {
            // LUT 强度 <100：留一份带内原底，套 LUT 后线性拉回（与预览 blendLut 同语义）
            val pre = if (lutPct < 100) band.copy(Bitmap.Config.ARGB_8888, false) else null
            if (!AlexaLut.applyInPlace(ctx, band, lut)) {
                pre?.recycle()
                return null
            }
            if (pre != null) {
                val blended = AdjustOps.blendLut(pre, band, lutPct)
                if (blended !== band) {
                    val px = IntArray(band.width * band.height)
                    blended.getPixels(px, 0, band.width, 0, 0, band.width, band.height)
                    band.setPixels(px, 0, band.width, 0, 0, band.width, band.height)
                    blended.recycle()
                }
                pre.recycle()
            }
            if (grain > 0f || vignette > 0f) {
                MadridShader.apply(band, grain, vignette, fullH, top)
            }
            val toned = LevelStack.apply(band, levels)
            // 用户新维度（色温/色调/黑白/颗粒/暗角）：与预览同序，作用在曲线之后
            return if (extras.isNeutral) toned else {
                val out = AdjustOps.applyExtras(toned, extras, fullH, top)
                if (out !== toned) toned.recycle()
                out
            }
        }
    }

    /** 两遍法：整图降采样求 LUT（一次），再逐带套用。 */
    private class CcdOp(
        private val ctx: Context,
        private val profile: PortraitFilm.Profile,
        private val levels: IntArray,
        private val extras: AdjustOps.Extras,
        private val lutPct: Int
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
            val pre = if (lutPct < 100) band.copy(Bitmap.Config.ARGB_8888, false) else null
            if (!PortraitFilm.applyBundle(band, profile, b)) {
                pre?.recycle()
                return null
            }
            if (pre != null) {
                val blended = AdjustOps.blendLut(pre, band, lutPct)
                if (blended !== band) {
                    val px = IntArray(band.width * band.height)
                    blended.getPixels(px, 0, band.width, 0, 0, band.width, band.height)
                    band.setPixels(px, 0, band.width, 0, 0, band.width, band.height)
                    blended.recycle()
                }
                pre.recycle()
            }
            if (profile.grain > 0f || profile.vignette > 0f) {
                MadridShader.apply(band, profile.grain, profile.vignette, fullH, top)
            }
            val toned = LevelStack.apply(band, levels)
            return if (extras.isNeutral) toned else {
                val out = AdjustOps.applyExtras(toned, extras, fullH, top)
                if (out !== toned) toned.recycle()
                out
            }
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
 * 色调五滑杆的条带版：复合色调引擎单次遍历（4096 级亮度曲线 + 色相保护 + 抖动），
 * 顺序与参数语义与 [Shadows.applyStackToJpeg] 的整图路径完全一致（同一 ToneCurve）。
 */
internal object LevelStack {

    fun apply(src: Bitmap, levels: IntArray): Bitmap {
        // P3：levels 兼容 5 元（旧调用）与 7 元（含饱和度/对比度）两种长度。
        // 柔光档不在此消费：空间算子半径（min/100）超条带重叠带，逐带处理必带边
        // 不连续（LM9 v15 教训）——条带链只吃曲线类档位，柔光仅整图路径生效。
        val next = ToneCurve.applyToBitmap(
            src, ToneCurve.of(
                levels[0], levels[1], levels[2], levels[3], levels[4],
                levels.getOrElse(5) { 0 }, levels.getOrElse(6) { 0 }
            ))
        if (next !== src) src.recycle()   // 与旧 step() 同约：输入带由本层回收
        return next
    }
}
