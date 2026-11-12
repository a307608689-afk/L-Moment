package com.lmcamera.app

/**
 * 滤镜条分类：**只影响 UI 展示**，不改动 ColorEngine.Style 与处理链。
 *
 * [id] 与 SettingsStore 里的持久化值一一对应
 * （all / lm / arri / hassel / ccd / fuji / kodak / ricoh / custom）。
 *
 * [aiName] 是 **AI 目录专用的品牌全称**（去品牌化合规红线：UI 任何位置不出现品牌词，
 * 品牌信息只经 [MainActivity.styleCatalog] 注入给 LLM，让「徕卡/柯达/富士」这类
 * 用户口语能匹配到中性化命名的分类）。
 *
 * [CUSTOM] 的 [styles] 恒为空：它装的是运行时创建的自定义色彩，
 * 数量走 [subtitle] 的动态入参，不能用 `styles.size` 推算。
 */
enum class FilterCategory(val id: String, val title: String,
                          val aiName: String, val styles: List<ColorEngine.Style>) {

    /**
     * L-Moment：全量色彩（默认），含全部内置 + 全部自定义（自定义只在 CUSTOM 可新建）。
     *
     * 内置部分取 [ColorEngine.Style.UI_VISIBLE]：隐藏项（Style A）不出现，副标题计数随之正确。
     */
    ALL("all", "L-Moment", "L-Moment 全部色彩", ColorEngine.Style.UI_VISIBLE),

    /** L-色彩 M 系列 */
    LM("lm", "L-色彩", "徕卡 Leica 相机色彩（M 系复刻）", listOf(
        ColorEngine.Style.M3,
        ColorEngine.Style.M9,
        ColorEngine.Style.M9P,
        ColorEngine.Style.M10R,
        ColorEngine.Style.LM9,
        ColorEngine.Style.LP9,
        ColorEngine.Style.M9D
    )),

    /** ARRI 电影调 */
    ARRI("arri", "ARRI", "阿莱 ARRI 电影机色彩", listOf(
        ColorEngine.Style.A709,
        ColorEngine.Style.M4,
        ColorEngine.Style.REVEAL,
        ColorEngine.Style.DUNE,
        ColorEngine.Style.LALALAND,
        ColorEngine.Style.JOKER
    )),

    /**
     * H 色彩（HNCS 式自然还原，解析式烘焙 65³ LUT，八档）。
     *
     * 显示名与 F 胶片 / K 胶片同口径中性化：界面不出现显式品牌。
     * 每档一个可指认特征——自然（基准）/ 蓝（深青天空）/ 绿（橄榄植被）/
     * 人像（金黄肤色）/ 暖阳（黄昏金调）/ 灰（高级灰）/ 黑白（纯灰阶）/ 冷调（青灰）。
     */
    HASSEL("hassel", "H 色彩", "哈苏 Hasselblad（HNCS 自然色彩方案）", listOf(
        ColorEngine.Style.HN,
        ColorEngine.Style.HB,
        ColorEngine.Style.HG,
        ColorEngine.Style.HP,
        ColorEngine.Style.HW,
        ColorEngine.Style.HS,
        ColorEngine.Style.HM,
        ColorEngine.Style.HC
    )),

    /** CCD 胶片家族（同一 SepLUT 链路，冷白/粉/暖三档色彩） */
    CCD("ccd", "CCD", "CCD 数码相机直出胶片感", listOf(
        ColorEngine.Style.M13,
        ColorEngine.Style.CCDP,
        ColorEngine.Style.CCDW
    )),

    /**
     * F-胶片家族（五档胶片模拟，解析式烘焙 LUT）。
     * 显示名走开源中性化口径（F 字母暗示、不出现显式品牌），id 仅为内部标识。
     */
    FUJI("fuji", "F 胶片", "富士 Fujifilm 胶片模拟", listOf(
        ColorEngine.Style.FCHROME,
        ColorEngine.Style.FCNEG,
        ColorEngine.Style.FNN,
        ColorEngine.Style.FPNH,
        ColorEngine.Style.FACROS
    )),

    /**
     * K-胶片家族（四档，解析式烘焙 65³ LUT）。
     * 显示名同样走中性化口径：速度号即型号（K400/K200/K100/K64），界面不出现品牌名，
     * 与 [FUJI] 的「F 字母暗示」同构。
     */
    KODAK("kodak", "K 胶片", "柯达 Kodak 胶片（速度号体系）", listOf(
        ColorEngine.Style.KP400,
        ColorEngine.Style.KG200,
        ColorEngine.Style.KE100,
        ColorEngine.Style.KC64
    )),

    /**
     * R 色彩家族（十档，ricoh_bake.py 解析式烘焙）。
     * 显示名与卡片 label 走中性化口径：分类「R 色彩」、label 英文短标，
     * 不出现品牌名（与 [HASSEL]/[FUJI]/[KODAK] 同构）。
     * 每档一个可指认特征——正片（浓彩反转实黑）/ 漂白（银灰硬调）/
     * 交叉（青影黄绿）/ 复古（暖黄褪色抬黑）/ 硬黑白（实黑冲白）/
     * 标准（直出基准）/ 柔黑白（亮调灰阶）/ 负片（色罩感橙黑青白）/
     * 街拍（纪实硬调重颗粒）/ Cross 2（品黄变体，宾得 CP 变体体系）。
     */
    R("ricoh", "R 色彩", "理光 Ricoh GR 系列色彩", listOf(
        ColorEngine.Style.RPOS,
        ColorEngine.Style.RBLEACH,
        ColorEngine.Style.RCROSS,
        ColorEngine.Style.RRETRO,
        ColorEngine.Style.RHBW,
        ColorEngine.Style.RSTD,
        ColorEngine.Style.RSOFT,
        ColorEngine.Style.RNEG,
        ColorEngine.Style.RSTREET,
        ColorEngine.Style.RCROSS2
    )),

    /**
     * 「我的色彩」：自定义色彩（含模型色彩）的家，也是**唯一**的新建入口（常驻，
     * 即使一个自定义色彩都没有也要能进去点「新建」）。
     */
    CUSTOM("custom", "我的色彩", "用户创建的自定义色彩", emptyList());

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
