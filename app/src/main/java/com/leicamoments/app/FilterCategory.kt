package com.leicamoments.app

/**
 * 滤镜条分类：**只影响 UI 展示**，不改动 LeicaEngine.Style 与处理链。
 *
 * [id] 与 SettingsStore 里的持久化值一一对应（all / leica / arri / ccd / fuji / kodak / custom）。
 *
 * [CUSTOM] 的 [styles] 恒为空：它装的是运行时创建的自定义色彩，
 * 数量走 [subtitle] 的动态入参，不能用 `styles.size` 推算。
 */
enum class FilterCategory(val id: String, val title: String, val styles: List<LeicaEngine.Style>) {

    /**
     * L-Moment：全量色彩（默认），含全部内置 + 全部自定义（自定义只在 CUSTOM 可新建）。
     *
     * 内置部分取 [LeicaEngine.Style.UI_VISIBLE]：隐藏项（Style A）不出现，副标题计数随之正确。
     */
    ALL("all", "L-Moment", LeicaEngine.Style.UI_VISIBLE),

    /** L一瞬 M 系列（枚举名与 id 保持不变，以兼容既有持久化数据） */
    LEICA("leica", "L一瞬", listOf(
        LeicaEngine.Style.M3,
        LeicaEngine.Style.M9,
        LeicaEngine.Style.M9P,
        LeicaEngine.Style.M10R,
        LeicaEngine.Style.LM9,
        LeicaEngine.Style.LP9,
        LeicaEngine.Style.M9D
    )),

    /** ARRI 电影调 */
    ARRI("arri", "ARRI", listOf(
        LeicaEngine.Style.A709,
        LeicaEngine.Style.M4,
        LeicaEngine.Style.REVEAL,
        LeicaEngine.Style.DUNE,
        LeicaEngine.Style.LALALAND,
        LeicaEngine.Style.JOKER
    )),

    /** CCD 胶片家族（同一 SepLUT 链路，冷白/粉/暖三档色彩） */
    CCD("ccd", "CCD", listOf(
        LeicaEngine.Style.M13,
        LeicaEngine.Style.CCDP,
        LeicaEngine.Style.CCDW
    )),

    /**
     * F-胶片家族（五档胶片模拟，解析式烘焙 LUT）。
     * 显示名走开源中性化口径（F 字母暗示、不出现显式品牌），id 仅为内部标识。
     */
    FUJI("fuji", "F 胶片", listOf(
        LeicaEngine.Style.FCHROME,
        LeicaEngine.Style.FCNEG,
        LeicaEngine.Style.FNN,
        LeicaEngine.Style.FPNH,
        LeicaEngine.Style.FACROS
    )),

    /**
     * K-胶片家族（四档，解析式烘焙 65³ LUT）。
     * 显示名同样走中性化口径：速度号即型号（K400/K200/K100/K64），界面不出现品牌名，
     * 与 [FUJI] 的「F 字母暗示」同构。
     */
    KODAK("kodak", "K 胶片", listOf(
        LeicaEngine.Style.KP400,
        LeicaEngine.Style.KG200,
        LeicaEngine.Style.KE100,
        LeicaEngine.Style.KC64
    )),

    /**
     * 「我的色彩」：自定义色彩（含模型色彩）的家，也是**唯一**的新建入口（常驻，
     * 即使一个自定义色彩都没有也要能进去点「新建」）。
     */
    CUSTOM("custom", "我的色彩", emptyList());

    /** 该分类是否会展示自定义色彩（= 是否放「新建」卡） */
    val hostsCustom: Boolean get() = this == CUSTOM

    /**
     * 副标题：内置分类是固定数量，「我的色彩」要算上运行时创建的色彩。
     * 必须传参——`CUSTOM.styles` 永远是空列表，静态计算会一直显示「0 种」。
     *
     * L-Moment 现在也展示自定义色彩，数量要加上去，否则副标题与实际卡片数对不上。
     */
    fun subtitle(customCount: Int): String = when (this) {
        CUSTOM -> if (customCount == 0) "暂无" else "$customCount 个"
        ALL -> "${styles.size + customCount} 种"
        else -> "${styles.size} 种"
    }

    companion object {
        fun fromId(id: String?): FilterCategory? = values().firstOrNull { it.id == id }
    }
}
