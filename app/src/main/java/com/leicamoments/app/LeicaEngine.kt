package com.leicamoments.app

import android.content.Context
import android.graphics.Bitmap
import com.codex.leicaapp.AlexaLut
import com.codex.leicaapp.CustomLutPost
import com.codex.leicaapp.Dither
import com.codex.leicaapp.P9Post
import com.codex.leicaapp.LegendaryPostProcessor
import com.codex.leicaapp.MadridLegendary
import com.codex.leicaapp.MadridShader
import com.codex.leicaapp.StyleA

/**
 * L-Moment 滤镜引擎——统一入口。
 *
 * 处理链（带逐级降级）：
 *  - M3   : 完整链 applyOriginalFilter(LUT)+applyMonochrome(黑白) → 失败降级旧链 filter+grain
 *  - M9   : 完整链 applyStyle(styletrans 神经网络)+applyWarmOriginalFilter → 失败降级纯 LUT
 *  - M9P  : M9 同链但暖调 LUT 参数放大（更激进），降级每级保持激进语义
 *  - M10R : LUT(filter) + MadridShader 暗角/颗粒
 */
object LeicaEngine {

    // ---- M9+ 后推参数（真机调参改这里）----
    // native 暖调 LUT 的 ev/warmth 实测不敏感（-0.95→-1.3 输出差异≈JPEG 噪声），
    // 激进档由 Java 侧 ColorMatrix 后推实现：M9 完整链结果上再拧一档。
    const val M9P_BOOST_SATURATION = 1.25f
    const val M9P_BOOST_CONTRAST = 1.10f
    const val M9P_BOOST_WARM = 0.04f   // R 增益 +warm，B 增益 -warm（轻微暖偏）

    /** A 路线参数资产（离线脚本导出）。 */
    const val STYLE_A_ASSET = "styleA_s0.bin"

    enum class Style(val id: String, val label: String, val desc: String) {
        M3("M3", "M3", "黑白经典"),
        M9("M9", "M9", "彩色经典"),
        M9P("M9P", "M9+", "彩色经典·激进版"),
        M10R("M10R", "M10-R", "暗角颗粒"),
        M4("M4", "ARRI 2049", "浓青橙 · 电影暗调"),
        // ARRI 家族：均为「assets 3D LUT 查表 + 颗粒/暗角」单链（arri_bake.py 解析式烘焙）
        A709("A709", "ARRI 709", "基础影视 · 干净中性"),
        REVEAL("REVEAL", "Reveal", "通透还原 · 柔和低反差"),
        DUNE("DUNE", "Dune", "沙漠哑光 · 冷蓝砂橙"),
        LALALAND("LALALAND", "LaLaLand", "黄昏绚丽 · 霓虹饱和"),
        JOKER("JOKER", "Joker", "绿调阴郁 · 低饱和"),
        M13("M13", "CCD·冷白", "冷白胶片"),
        // CCD 家族：同一条 SepLUT 链路，差异只在 PortraitFilm.Profile（粉/暖为初值，待真机迭代）
        CCDP("CCDP", "CCD·粉", "粉白通透"),
        CCDW("CCDW", "CCD·暖", "柯达暖黄"),
        LP9("LP9", "L-P9", "P9 鲜艳"),
        LM9("LM9", "L-M9", "一瞬 M9 · CCD"),
        // M9「一体化蒸馏」微网（整个 M9 重链蒸馏成 0.64MB 学生网，全分辨率 512 分块直出）
        M9D("M9D", "M9 蒸馏", "一体化微网"),
        // A 路线：Lab 仿射 + 逐通道节点 LUT，参数由参考图闭式解出（无训练）
        SA("SA", "Style A", "闭式拟合 · 参考图直算");

        companion object {
            /** 仅「assets 3D LUT 查表」单链的风格：LUT 加载失败即输出原图。 */
            private val LUT_ONLY = setOf(M4, LP9, A709, REVEAL, DUNE, LALALAND, JOKER)

            /** CCD 家族（SepLUT 链路）：降级诊断走详情弹窗，其余档走 toast。 */
            private val CCD_FAMILY = setOf(M13, CCDP, CCDW)

            /**
             * UI 可见的内置色彩（单点真源：卡片列表、置顶区、缩略图预生成都从这里取）。
             *
             * Style A 暂时隐藏：实验性闭式拟合路线，不出现在任何分类与置顶里。
             * 枚举、处理链与 styleA_s0.bin 资产全部保留，分享码/备份/批量测试仍可按 id 解析；
             * 日后要恢复，删掉这里的过滤即可。
             */
            val UI_VISIBLE: List<Style> = values().filter { it != SA }

            fun fromId(id: String?): Style = values().firstOrNull { it.id == id } ?: M9

            fun isLutOnly(style: Style): Boolean = LUT_ONLY.contains(style)

            fun isCcd(style: Style): Boolean = CCD_FAMILY.contains(style)
        }
    }

    /**
     * 对 JPEG 字节应用指定风格，返回处理后 JPEG 字节。
     *
     * @param onFallback 完整链失败、走降级路径时回调（在处理线程触发，调用方自行切主线程）。
     */
    fun process(context: Context, jpeg: ByteArray, style: Style, onFallback: ((Style) -> Unit)? = null,
                allowCpuFallback: Boolean = true): ByteArray {
        val app = context.applicationContext
        // EXIF 方向归一（伴生 JPG 像素横存竖显）：全链路统一摆正像素 + Orientation 置 1，
        // 正常 JPG（Orientation 缺省/1）零开销直通
        val input = ImageUtils.normalizeExifOrientation(jpeg)
        return when (style) {
            Style.M3 -> processM3(app, input, onFallback)
            Style.M9 -> processM9(app, input, onFallback, allowCpuFallback)
            Style.M9P -> processM9Plus(app, input, onFallback, allowCpuFallback)
            Style.M10R -> processM10R(app, input, onFallback, allowCpuFallback)
            Style.M4 -> processM4(app, input, onFallback)
            Style.A709 -> processArri709(app, input, onFallback)
            Style.REVEAL -> processReveal(app, input, onFallback)
            Style.DUNE -> processDune(app, input, onFallback)
            Style.LALALAND -> processLaLaLand(app, input, onFallback)
            Style.JOKER -> processJoker(app, input, onFallback)
            // CCD 家族三档：同一条 SepLUT 链路，差异只在 PortraitFilm.Profile
            Style.M13 -> processCcd(app, input, PortraitFilm.Profile.COOL, onFallback)
            Style.CCDP -> processCcd(app, input, PortraitFilm.Profile.PINK, onFallback)
            Style.CCDW -> processCcd(app, input, PortraitFilm.Profile.WARM, onFallback)
            Style.LP9 -> processLP9(app, input, onFallback)
            Style.LM9 -> processLM9(app, input, onFallback, allowCpuFallback)
            Style.M9D -> processM9D(app, input, onFallback)
            Style.SA -> processSA(app, input, onFallback)
        }
    }

    /** A 路线：参数缺失/异常时 StyleA 原字节返回（同一引用），按降级语义上报。 */
    private fun processSA(app: Context, input: ByteArray,
                          onFallback: ((Style) -> Unit)?): ByteArray {
        val out = StyleA.process(app, input, STYLE_A_ASSET)
        if (out === input) {
            onFallback?.invoke(Style.SA)
        }
        return out
    }

    /**
     * 自定义色彩：对 JPEG 应用运行时烘焙的 3D LUT（filesDir/luts，[AlexaLut.FILE_PREFIX] 引用）。
     *
     * 与内置风格不同，自定义色彩不加颗粒/暗角——用户给的是参考图，不是预设风格。
     * 唯一例外是 [CustomLutPost]：参考图聚合后的 tone curve 高光段斜率可能 > 1，
     * LUT 在该输入域局部增益会把输入 1/255 级起伏放大成可见斑驳（太阳/天空高光带），
     * 故在高光 × 低饱和区把输出往其引导滤波低频拉回一档——它压制的是参考图里
     * 并不存在的噪声，不是参考本身的影调/色相。不触发时零开销直通。
     * LUT 缺失/损坏时返回原图并回调 [onFallback]。
     */
    fun processLut(context: Context, jpeg: ByteArray, lutRef: String, onFallback: (() -> Unit)? = null): ByteArray {
        val app = context.applicationContext
        val input = ImageUtils.normalizeExifOrientation(jpeg)
        val out = AlexaLut.process(app, input, lutRef, CustomLutPost.get())
        if (CustomLutPost.lastNote.isNotEmpty()) {
            DiagLog.i("LutPost", CustomLutPost.lastNote)
        }
        if (out === input) {
            DiagLog.w("Engine", "自定义 LUT 不可用: $lutRef，输出原图")
            onFallback?.invoke()
            return out
        }
        return out
    }

    private fun processM3(app: Context, jpeg: ByteArray, onFallback: ((Style) -> Unit)?): ByteArray {
        // 完整链：LUT + monopan 黑白
        LegendaryPostProcessor.processM3(app, jpeg)?.let {
            if (it !== jpeg) return M3Mono.ensure(it)
        }
        // 降级旧链（filter + grain）+ 同样兜底黑白
        DiagLog.w("Engine", "M3 完整链不可用，降级旧链")
        val out = MadridLegendary.process(app, jpeg, "M3")
        onFallback?.invoke(Style.M3)
        return M3Mono.ensure(out)
    }

    private fun processM9(app: Context, jpeg: ByteArray, onFallback: ((Style) -> Unit)?,
                          allowCpuFallback: Boolean): ByteArray {
        // 完整链：styletrans + warm LUT（少数机型可用）
        LegendaryPostProcessor.processM9(app, jpeg)?.let { return it }
        // 降级 1：QNN CPU 后端补丁链（libQnnCpu.so，与 SoC 品牌无关）
        if (allowCpuFallback) {
            M9CpuFallback.run(app, jpeg)?.let {
                DiagLog.w("Engine", "M9 完整链不可用，CPU 补丁链接管")
                return it
            }
        }
        // 降级 1.25：蒸馏微网（整条 M9 重链蒸馏成 0.65MB 学生网，全分辨率 512 分块直出）
        M9Distill.run(app, jpeg)?.let {
            DiagLog.w("Engine", "M9 蒸馏微网链接管")
            return it
        }
        // 降级 1.5：ORT 神经网络降级链（全机型真 M9，约 10~25s）
        M9OrtFallback.run(app, jpeg)?.let {
            DiagLog.w("Engine", "M9 ORT 降级链接管")
            return it
        }
        // 降级 2：纯 LUT
        DiagLog.w("Engine", "M9 降级纯 LUT (allowCpuFallback=$allowCpuFallback)")
        diagNative()
        onFallback?.invoke(Style.M9)
        return MadridLegendary.process(app, jpeg, "M9")
    }

    /**
     * M9「一体化蒸馏」：0.64MB 学生网全分辨率 512 分块直出（取代 styletrans+ColorFix 两段重网络）。
     * 降级：蒸馏链失败 → ORT 重链（M9OrtFallback）→ 纯 LUT。
     */
    private fun processM9D(app: Context, jpeg: ByteArray, onFallback: ((Style) -> Unit)?): ByteArray {
        M9Distill.run(app, jpeg)?.let {
            DiagLog.w("Engine", "M9D 蒸馏微网链完成")
            return it
        }
        DiagLog.w("Engine", "M9D 蒸馏链不可用，回落 ORT 重链")
        M9OrtFallback.run(app, jpeg)?.let { return it }
        DiagLog.w("Engine", "M9D 降级纯 LUT")
        onFallback?.invoke(Style.M9D)
        return MadridLegendary.process(app, jpeg, "M9")
    }

    /**
     * 「完美档」：OffLineM9 后段全量管线（StyleTrans high + 真 ColorFix + LeicaFilter），
     * 不套色——M9 色彩由 OffLineM9 管线自带，避免二次套色。
     *
     * 风格差异（与各自降级链口径一致）：
     *   M9  : m9 snapshot 暖调 LUT，默认参数 0.6 / −0.95 / 0.3
     *   M9+ : M9P_DENOISE/EV/WARMTH + Java 侧激进后推（保持"激进版"语义）
     *   M10R: 先过 M10R LUT(filter) → m10r snapshot 暖调 → MadridShader 暗角/颗粒 0.3
     *
     * @param onProgress 阶段进度 0..100（处理线程回调）
     * @return 处理后 JPEG；失败返回 null（调用方提示失败，不改图）
     */
    fun processPerfect(context: Context, jpeg: ByteArray, style: Style,
                       dng: ByteArray? = null,
                       onProgress: ((Int) -> Unit)? = null): ByteArray? {
        val app = context.applicationContext
        // ---- 二期：RAW 完整 OffLineM9 管线（前段 7 节点 + 后段；仅 M9/M9+ 语义）----
        if (dng != null && dng.isNotEmpty() && style != Style.M10R) {
            val exifFrom = ImageUtils.normalizeExifOrientation(jpeg)
            M9Perfect.runFull(app, dng, exifFrom, onProgress)?.let { out ->
                return when (style) {
                    Style.M9P -> boostSaturationContrast(out, M9P_BOOST_SATURATION,
                        M9P_BOOST_CONTRAST, M9P_BOOST_WARM)
                    else -> out
                }
            }
            DiagLog.w("Engine", "完美档完整管线不可用，回落后段（JPEG 路径）")
        }
        var work = ImageUtils.normalizeExifOrientation(jpeg)
        if (style == Style.M10R) work = MadridLegendary.process(app, work, "M10R")
        val warmBin = if (style == Style.M10R) "leica_filter_param_m10r_snapshot.bin"
        else "leica_filter_param_m9_snapshot.bin"
        val denoise: Float
        val ev: Float
        val warmth: Float
        if (style == Style.M9P) {
            denoise = LegendaryPostProcessor.M9P_DENOISE
            ev = LegendaryPostProcessor.M9P_EV
            warmth = LegendaryPostProcessor.M9P_WARMTH
        } else {
            denoise = 0.6f
            ev = -0.95f
            warmth = 0.3f
        }
        val out = M9Perfect.run(app, work, denoise, ev, warmth, warmBin, onProgress) ?: return null
        return when (style) {
            Style.M9P -> boostSaturationContrast(out, M9P_BOOST_SATURATION, M9P_BOOST_CONTRAST, M9P_BOOST_WARM)
            Style.M10R -> MadridShader.applyToJpeg(out, 0.3f)
            else -> out
        }
    }

    private fun processM9Plus(app: Context, jpeg: ByteArray, onFallback: ((Style) -> Unit)?,
                              allowCpuFallback: Boolean): ByteArray {
        // 完整链：styletrans + 暖调 LUT（参数放大实测无效，保留同 M9）+ Java 侧色彩后推
        LegendaryPostProcessor.processM9Plus(app, jpeg)?.let {
            return boostSaturationContrast(it, M9P_BOOST_SATURATION, M9P_BOOST_CONTRAST, M9P_BOOST_WARM)
        }
        // 降级 1：QNN CPU styletrans + 补暖调 LUT + 同样色彩后推（保持激进语义）
        if (allowCpuFallback) {
            M9CpuFallback.run(app, jpeg)?.let { cpu ->
                DiagLog.w("Engine", "M9P 完整链不可用，CPU 补丁链接管")
                val warmed = LegendaryPostProcessor.applyWarmLut(
                    app, cpu, LegendaryPostProcessor.M9P_DENOISE,
                    LegendaryPostProcessor.M9P_EV, LegendaryPostProcessor.M9P_WARMTH) ?: cpu
                return boostSaturationContrast(warmed, M9P_BOOST_SATURATION, M9P_BOOST_CONTRAST, M9P_BOOST_WARM)
            }
        }
        // 降级 1.25：蒸馏微网 + M9P 暖调参数 + 同款色彩后推（保持激进语义）
        M9Distill.run(app, jpeg, LegendaryPostProcessor.M9P_DENOISE,
            LegendaryPostProcessor.M9P_EV, LegendaryPostProcessor.M9P_WARMTH)?.let {
            DiagLog.w("Engine", "M9P 蒸馏微网链接管")
            return boostSaturationContrast(it, M9P_BOOST_SATURATION, M9P_BOOST_CONTRAST, M9P_BOOST_WARM)
        }
        // 降级 1.5：ORT 神经网络降级链 + M9P 暖调参数 + 同款色彩后推（保持激进语义）
        M9OrtFallback.run(app, jpeg, LegendaryPostProcessor.M9P_DENOISE,
            LegendaryPostProcessor.M9P_EV, LegendaryPostProcessor.M9P_WARMTH)?.let {
            DiagLog.w("Engine", "M9P ORT 降级链接管")
            return boostSaturationContrast(it, M9P_BOOST_SATURATION, M9P_BOOST_CONTRAST, M9P_BOOST_WARM)
        }
        // 降级 2：纯 M9 LUT + 同款色彩后推
        DiagLog.w("Engine", "M9P 降级纯 LUT (allowCpuFallback=$allowCpuFallback)")
        diagNative()
        onFallback?.invoke(Style.M9P)
        val lut = MadridLegendary.process(app, jpeg, "M9")
        return boostSaturationContrast(lut, M9P_BOOST_SATURATION, M9P_BOOST_CONTRAST, M9P_BOOST_WARM)
    }

    /** ColorMatrix 饱和度+对比度+暖偏后推，解码/编码失败返回原字节。 */
    private fun boostSaturationContrast(jpeg: ByteArray, saturation: Float, contrast: Float,
                                        warmGain: Float = 0f): ByteArray {
        return try {
            val bmp = android.graphics.BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: return jpeg
            val cm = android.graphics.ColorMatrix()
            cm.setSaturation(saturation)
            if (warmGain != 0f) {
                val w = android.graphics.ColorMatrix()
                w.setScale(1f + warmGain, 1f, 1f - warmGain, 1f)
                cm.postConcat(w)
            }
            val c = android.graphics.ColorMatrix()
            val s = contrast
            val t = 128f * (1f - s)   // 对比度绕中点 128 旋转的正确偏移，如 s=1.10 → t=-12.8
            c.set(floatArrayOf(
                s, 0f, 0f, 0f, t,
                0f, s, 0f, 0f, t,
                0f, 0f, s, 0f, t,
                0f, 0f, 0f, 1f, 0f))
            cm.postConcat(c)
            val paint = android.graphics.Paint()
            paint.colorFilter = android.graphics.ColorMatrixColorFilter(cm)
            val out = android.graphics.Bitmap.createBitmap(bmp.width, bmp.height, Bitmap.Config.ARGB_8888)
            android.graphics.Canvas(out).drawBitmap(bmp, 0f, 0f, paint)
            bmp.recycle()
            // 后推增益（饱和 1.25 / 对比 1.10）会同步放大 8bit 量化台阶，且本方法自己就是
            // 一次「解码→矩阵→编码」的重量化点：蒸馏链上游的抖色在这里会被抹掉，必须在
            // 编码前补一次（幅度 1.0 LSB，与 M9Distill 出口同口径）。
            Dither.tpdf(out, 1.0f)
            val bos = java.io.ByteArrayOutputStream()
            out.compress(Bitmap.CompressFormat.JPEG, 95, bos)
            out.recycle()
            MadridLegendary.copyExif(jpeg, bos.toByteArray())
        } catch (t: Throwable) {
            DiagLog.e("Engine", "色彩后推失败，返回原字节", t)
            jpeg
        }
    }

    private fun processM10R(app: Context, jpeg: ByteArray, onFallback: ((Style) -> Unit)?,
                            allowCpuFallback: Boolean): ByteArray {
        // 1. LUT filter（MadridLegendary，legend18 m10r bin）
        val lut = MadridLegendary.process(app, jpeg, "M10R")
        // 2. styletrans + 暖调 LUT（LegendaryPostProcessor，m10r bin）；失败保留 lut 降级
        val ai = LegendaryPostProcessor.processM10R(app, lut)
        if (ai == null) {
            // 降级 1：QNN CPU 后端补丁链
            val cpu = if (allowCpuFallback) M9CpuFallback.run(app, lut) else null
            if (cpu != null) {
                DiagLog.w("Engine", "M10R styletrans 失败，CPU 补丁链接管")
                return MadridShader.applyToJpeg(cpu, 0.3f)
            }
            // 降级 1.25：蒸馏微网（m10r 暖调 bin）+ 暗角
            M9Distill.run(app, lut, 0.6f, -0.95f, 0.3f,
                warmBin = "leica_filter_param_m10r_snapshot.bin")?.let {
                DiagLog.w("Engine", "M10R 蒸馏微网链接管")
                return MadridShader.applyToJpeg(it, 0.3f)
            }
            // 降级 1.5：ORT 神经网络降级链（m10r 暖调 bin）+ 暗角
            M9OrtFallback.run(app, lut, 0.6f, -0.95f, 0.3f,
                warmBin = "leica_filter_param_m10r_snapshot.bin")?.let {
                DiagLog.w("Engine", "M10R ORT 降级链接管")
                return MadridShader.applyToJpeg(it, 0.3f)
            }
            // 降级 2：纯 LUT + 暗角
            DiagLog.w("Engine", "M10R 降级纯 LUT+暗角 (allowCpuFallback=$allowCpuFallback)")
            diagNative()
            onFallback?.invoke(Style.M10R)
        }
        // 3. 暗角 + 颗粒
        return MadridShader.applyToJpeg(ai ?: lut, 0.3f)
    }

    /**
     * 降级死因自动取证：抓系统 logcat 里 native 引擎（mage/Styletrans/Qnn/MIALGO）与
     * CPU 链（M9CPU exit=…）的最近报错，收进 DiagLog 环形缓冲——用户长按标题导出的
     * 常规日志即自带死因，无需任何额外操作。异常静默（取证失败不影响降级输出）。
     */
    private fun diagNative() {
        try {
            val lc = ProcessBuilder("logcat", "-d", "-t", "1200").start()
            val text = lc.inputStream.bufferedReader().use { it.readText() }
            lc.waitFor()
            val excerpt = text.lineSequence()
                .filter {
                    // "mage" 必须大小写敏感：不敏感会命中所有 "Image"（Skia/HWUI 噪声），
                    // 挤占 takeLast(40) 配额，把 Qnn/MAGE 真死因挤出摘录
                    it.contains("Mage") || it.contains("MAGE") ||
                            it.contains("Styletrans", true) ||
                            it.contains("DIPS") || it.contains("Qnn", true) ||
                            it.contains("MIALGO", true) || it.contains("M9CPU")
                }
                .filter { !it.contains("Image decoding logging dropped") }
                .toList()
                .takeLast(40)
                .joinToString("\n")
                .take(2000)
            if (excerpt.isNotBlank()) DiagLog.w("M9CPU", excerpt)
        } catch (t: Throwable) {
            DiagLog.e("M9CPU", "logcat 取证失败: ${t.message}", null)
        }
    }

    private fun processM4(app: Context, jpeg: ByteArray, onFallback: ((Style) -> Unit)?): ByteArray {
        // Alexa/2049 青橙电影：固定 65³ 3D LUT（离线烘焙）
        val out = AlexaLut.process(app, jpeg)
        if (out === jpeg) {
            // LUT 失败：整链降级，回原图
            DiagLog.w("Engine", "M4 LUT 加载失败，输出原图")
            onFallback?.invoke(Style.M4)
            return out
        }
        // 轻暗角 + 颗粒收尾（密度独立于 M10R 0.3 / M13 0.18）
        return MadridShader.applyToJpeg(out, 0.22f)
    }

    /**
     * ARRI 家族统一收尾：assets 3D LUT 查表 → 颗粒 + 暗角。
     *
     * 网格边长由文件长度反推（33³/65³ 混用，见 AlexaLut#decode），配方与校验见
     * 预设参数与离线校验脚本。LUT 失败=输出原图并回调。
     */
    private fun processArriLut(app: Context, jpeg: ByteArray, style: Style, asset: String,
                               grain: Float, vignette: Float,
                               onFallback: ((Style) -> Unit)?): ByteArray {
        val out = AlexaLut.process(app, jpeg, asset)
        if (out === jpeg) {
            // LUT 失败：整链降级，回原图
            DiagLog.w("Engine", "${style.label} LUT 加载失败，输出原图")
            onFallback?.invoke(style)
            return out
        }
        return MadridShader.applyToJpeg(out, grain, vignette)
    }

    /** ARRI 709：Rec.709 观感直出（33³ LUT，中性低反差、轻微膝部压缩） */
    private fun processArri709(app: Context, jpeg: ByteArray, onFallback: ((Style) -> Unit)?): ByteArray =
        processArriLut(app, jpeg, Style.A709, AlexaLut.LUT_ARRI709, 0.14f, 0.70f, onFallback)

    /** Reveal：还原通透、低反差柔和（33³ LUT，灰阶中段近乎中性） */
    private fun processReveal(app: Context, jpeg: ByteArray, onFallback: ((Style) -> Unit)?): ByteArray =
        processArriLut(app, jpeg, Style.REVEAL, AlexaLut.LUT_ARRIREVEAL, 0.10f, 0.50f, onFallback)

    /** Dune：沙漠哑光——低饱和、蓝带压暗、绿植转黄绿（65³ LUT） */
    private fun processDune(app: Context, jpeg: ByteArray, onFallback: ((Style) -> Unit)?): ByteArray =
        processArriLut(app, jpeg, Style.DUNE, AlexaLut.LUT_ARRI_DUNE, 0.26f, 0.85f, onFallback)

    /** LaLaLand：黄昏绚丽——蓝紫霓虹提饱和、暖调高光（65³ LUT） */
    private fun processLaLaLand(app: Context, jpeg: ByteArray, onFallback: ((Style) -> Unit)?): ByteArray =
        processArriLut(app, jpeg, Style.LALALAND, AlexaLut.LUT_ARRI_LALA, 0.12f, 0.80f, onFallback)

    /** Joker：绿调阴郁——低饱和、绿带压向青绿、重颗粒暗角（33³ LUT） */
    private fun processJoker(app: Context, jpeg: ByteArray, onFallback: ((Style) -> Unit)?): ByteArray =
        processArriLut(app, jpeg, Style.JOKER, AlexaLut.LUT_ARRI_JOKER, 0.30f, 1.00f, onFallback)

    /**
     * CCD 家族统一收尾：SepLUT 打底 + 档位色彩层 → 颗粒/暗角（强度随档位，见 [PortraitFilm.Profile]）。
     *
     * 三档共用同一个 ONNX session（权重与档位无关），逐像素参数由 PortraitFilm 按档缓存。
     * SepLUT 不可用时降级 M9 纯 LUT（保持彩色胶片语义），并按实际档位回调。
     */
    private fun processCcd(app: Context, jpeg: ByteArray, profile: PortraitFilm.Profile,
                           onFallback: ((Style) -> Unit)?): ByteArray {
        val style = when (profile) {
            PortraitFilm.Profile.COOL -> Style.M13
            PortraitFilm.Profile.PINK -> Style.CCDP
            PortraitFilm.Profile.WARM -> Style.CCDW
        }
        val out = PortraitFilm.process(app, jpeg, profile)
        if (out != null) return MadridShader.applyToJpeg(out, profile.grain, profile.vignette)
        // 降级：SepLUT 不可用 → M9 纯 LUT 档
        DiagLog.w("Engine", "${style.id} SepLUT 失败(${PortraitFilm.lastError})，降级 M9 LUT")
        onFallback?.invoke(style)
        return MadridLegendary.process(app, jpeg, "M9")
    }

    private fun processLP9(app: Context, jpeg: ByteArray, onFallback: ((Style) -> Unit)?): ByteArray {
        // L-P9「P9 鲜艳」：测量式拟合 65³ 3D LUT（离线烘焙）
        // v2：pre 域色度清理（P9Post）——治"云斑显形"（LUT 饱和增益把云 vs 蓝天色度差放大 ×1.5）
        val out = AlexaLut.process(app, jpeg, "lut_p9vibrant.bin", P9Post.get())
        if (out === jpeg) {
            // LUT 失败：整链降级，回原图
            DiagLog.w("Engine", "LP9 LUT 加载失败，输出原图")
            onFallback?.invoke(Style.LP9)
            return out
        }
        // 极轻暗角收尾（无颗粒，P9 直出干净；密度独立于 M4 0.22 / M13 0.18）
        return MadridShader.applyToJpeg(out, 0f, 0.3f)
    }

    /**
     * L-M9「一瞬 M9」：M9Net 蒸馏微网（1.16MB int8；512 分块 + 64px 羽化重叠，全分辨率直出）。
     *
     * 取代旧的「65³ LUT + Lm9Post 局部算子」链——旧链形态错了：LUT 逐像素同映射，表达不了真机
     * M9 的 lux/CCT 条件化与空间自适应，只能靠手搓天空/肤色掩码补丁去凑，补丁互相打架。
     * 现链：监督来自离线真机管线（offlinem9_ref）的双档成对渲染，微网只学风格层那一段。
     *
     * 无暗角无颗粒（M9 直出干净）。失败（OOM/资产缺失/推理异常）→ 回落 M9 正常链并提醒用户。
     */
    private fun processLM9(app: Context, jpeg: ByteArray, onFallback: ((Style) -> Unit)?,
                           allowCpuFallback: Boolean): ByteArray {
        // 主路径 v0：统计同源（非学习）。与目标风格池的统计量对齐；
        // 学习式微网目前仍偏暗（用离线目标训练所致），暂不作主路径，留作次路径与轨道 B 迭代。
        Lm9Stats.run(app, jpeg)?.let { return it }
        Lm9Net.run(app, jpeg)?.let {
            DiagLog.w("Engine", "L-M9 统计链不可用，走学习式微网")
            return it
        }
        DiagLog.w("Engine", "L-M9 两条链均不可用，回落 M9 正常链")
        onFallback?.invoke(Style.LM9)
        return processM9(app, jpeg, onFallback, allowCpuFallback)
    }
}
