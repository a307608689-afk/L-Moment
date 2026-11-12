package com.lmcamera.app

import android.graphics.Bitmap
import android.net.Uri
import java.io.File

/**
 * 批量（多图）编辑的会话模型。
 *
 * 设计前提：MainActivity 仍是「单图状态机」——导出/渲染/调节全部直接读写成
 * 员字段。这里不做状态对象重写，只提供一个**字段冻结/装载的载体**：
 * 切图时把 MainActivity 的字段整体打包进 [EditItem]，切回时整体还原。
 * 因此本类只存数据，不持有 Activity / View 引用。
 *
 * 数据分三层（对应内存与生命周期策略）：
 *  - 轻状态：色彩选择、八档档位、HDR 开关 —— 几百字节，永久常驻；
 *  - 中状态：HDR 增益图、200MP 直通源（磁盘）、完美成片、超分成片 —— 与图绑定但不大；
 *  - 热状态 [Hot]：工作字节 / 预览位图 / 缩略图池 / RAW 16bit 基准 —— 由 MainActivity
 *    按 LRU 3 管理，驱逐时只需丢掉引用（位图回收顺序由宿主按「先换显示再回收」处理）。
 */
class EditItem(val id: Long, val uri: Uri) {

    companion object {
        /**
         * 14 档顺序：前 8 位与旧版完全一致（亮度/曝光/高光/阴影/黑色阶/饱和度/对比度/柔光），
         * 后 6 位为 2026-11 新增：色温/色调/黑白强度/颗粒/暗角/色彩强度（LUT 强度）。
         * 旧记录（长度 8）反序列化时按默认值补齐，禁止错位。
         */
        const val LEVEL_COUNT = 14
        const val LV_BRIGHT = 0
        const val LV_EXPOSURE = 1
        const val LV_HIGHLIGHT = 2
        const val LV_SHADOW = 3
        const val LV_BLACK = 4
        const val LV_SAT = 5
        const val LV_CONTRAST = 6
        const val LV_GLOW = 7
        const val LV_TEMP = 8
        const val LV_TINT = 9
        const val LV_MONO = 10
        const val LV_GRAIN = 11
        const val LV_VIGNETTE = 12
        const val LV_LUT_STRENGTH = 13

        /** 新增维度的默认值（与 UI 默认一致）：强度类 100，其余 0 */
        val DEFAULTS_TAIL = intArrayOf(0, 0, 0, 0, 0, 100)
    }

    // ---- 轻状态：常驻 ----

    /** 内置风格 id（与 [ColorEngine.Style.id] 对应）；null = 未选内置色 */
    var styleId: String? = null

    /** 自定义/模型色彩 id（与 CustomStyleStore 对应）；null = 未选自定义色 */
    var customId: String? = null

    /** 八档档位，索引见 [LV_BRIGHT] 等常量 */
    val levels = IntArray(LEVEL_COUNT)

    /** HDR 输出开关（用户选择值；源图自带增益图时装载期会被压制，见 MainActivity） */
    var hdrBoost = false

    /**
     * 用户是否**单独调整过**这张图。
     *
     * false 时该图跟随 [Recipe]（当前配方）——切到它时自动套用并渲染，
     * 全部导出前再把配方兜底写一遍，保证「预览 = 导出」。
     */
    var touched = false

    // ---- 中状态：与图绑定，可常驻 ----

    /** 导入期提取的源图 HDR 增益图（导出回嵌 UltraHDR 用） */
    var pendingHdr: HdrGainMap.HdrData? = null

    /** 50MP/200MP 全像素直通源（cache 文件，几十 MB 不进堆） */
    var fullResFile: File? = null
    var fullResHead: ByteArray? = null
    var fullResW = 0
    var fullResH = 0
    var fullResHdr: HdrGainMap.HdrData? = null

    /** 完美成片字节（非空 = 该图已生成完美档） */
    var perfectBytes: ByteArray? = null
    var perfectStyle: ColorEngine.Style? = null

    /** 已合成进工作字节的色彩（超分/完美链路，导出跳过二次套色） */
    var srBakedStyle: ColorEngine.Style? = null

    /**
     * 超分成片落盘文件。
     *
     * 超分完成后 MainActivity 的 originalBytes 被替换为成片；若该图被驱逐后重读
     * [uri]，拿到的是**超分前**的原始字节（结果丢失）。因此超分完成时把成片落一份
     * cache，冷加载优先读它。
     */
    var srResultFile: File? = null

    // ---- 源标识（导入期确定，还原时必须一并写回） ----

    var sourceUri: Uri? = null
    var sourceIsRaw = false

    // ---- 热状态：LRU 3 ----

    var hot: Hot? = null

    /** 工作字节与派生位图。preview/result 由宿主按「先换显示再回收」的约定管理生命周期 */
    class Hot(
        val workBytes: ByteArray,
        val workW: Int,
        val workH: Int,
        val preview: Bitmap,
        val result: Bitmap?,
        /** 200px 风格缩略图池（键 = style.id / 自定义 id / [FilterEntry.ORIG_ID]） */
        val thumbs: MutableMap<String, Bitmap>,
        val rawLin16: RawToneMap.Lin16?,
        val rawLin16Level: Int,
        val rawPreviewJpeg: ByteArray?,
        val rawLinearLuma: RawToneMap.Luma16?,
        val pseudoLinear: Boolean,
        val pseudoFromHeif: Boolean
    )

    val hasHot: Boolean get() = hot != null

    /** 是否已经有任何色彩/档位（用于「未调整」判定与导出预检） */
    val hasSettings: Boolean
        get() = styleId != null || customId != null || levels.anyIndexed()


    /** 记录当前设置为已调整 */
    fun markTouched() {
        touched = true
    }

    /**
     * 把配方写进本项（仅在未单独调整时调用）。
     *
     * @param force true 时不看 [touched]，直接覆盖（全部导出前的兜底写入）
     */
    fun applyRecipe(r: Recipe, force: Boolean = false) {
        if (!force && touched) return
        styleId = r.styleId
        customId = r.customId
        r.levels.copyInto(levels)
        hdrBoost = r.hdrBoost
    }

    /** 清空本项的轻状态（重新导入同一批次前调用） */
    fun clearSettings() {
        styleId = null
        customId = null
        levels.fill(0)
        levels[EditItem.LV_LUT_STRENGTH] = 100   // 强度类默认 100，不能被清成 0
        hdrBoost = false
        touched = false
    }

    /** 释放磁盘产物（cache 文件），位图交由宿主按安全顺序处理 */
    fun releaseDisk() {
        runCatching { fullResFile?.delete() }
        fullResFile = null
        runCatching { srResultFile?.delete() }
        srResultFile = null
    }
}

/**
 * 当前色彩配方：从任意一张（通常是被用户操作过的那张）图提炼出的「这批图长什么样」。
 *
 * 语义（用户 2026-11-02 拍板）：
 *  - 单独调过的图保留自己的设置；
 *  - 从未调整的图跟随配方，切到它时立即套用并渲染 —— 保证预览就是导出结果；
 *  - 全部导出前再把配方兜底写一遍（覆盖从未显示过的图）。
 */
class Recipe {
    var styleId: String? = null
    var customId: String? = null
    val levels = IntArray(EditItem.LEVEL_COUNT)
    var hdrBoost = false

    /** 空配方 = 没有任何色彩，此时无法批量导出（与「无色彩不能导出」的既有语义一致） */
    val isEmpty: Boolean
        get() = styleId == null && customId == null && !levels.anyIndexed()

    fun copyFrom(source: EditItem) {
        styleId = source.styleId
        customId = source.customId
        source.levels.copyInto(levels)
        hdrBoost = source.hdrBoost
    }

    fun copyFrom(other: Recipe) {
        styleId = other.styleId
        customId = other.customId
        other.levels.copyInto(levels)
        hdrBoost = other.hdrBoost
    }

    /** 清空配方（换批次时调用，避免上一批的风格"继承"到新一批） */
    fun clear() {
        styleId = null
        customId = null
        levels.fill(0)
        levels[EditItem.LV_LUT_STRENGTH] = 100
        hdrBoost = false
    }

    fun applyTo(target: EditItem, force: Boolean = false) = target.applyRecipe(this, force)
}

/** 有任何非默认档位（[EditItem.LV_LUT_STRENGTH] 的默认是 100，其余 0） */
private fun IntArray.anyIndexed(): Boolean {
    for (i in indices) {
        val def = if (i == EditItem.LV_LUT_STRENGTH) 100 else 0
        if (this[i] != def) return true
    }
    return false
}
