package com.leicamoments.app

/**
 * 滤镜条卡片条目：内置风格与自定义色彩共用一套标识。
 *
 * 为什么不再用 `LeicaEngine.Style?`：
 * - 自定义色彩不是 enum 成员，`Style?` 的 null 已被「原图」占用；
 * - 卡片在置顶重排后下标会变，只能按 [id] 定位（[com.leicamoments.app.MainActivity.imgByStyle]
 *   与 `thumbCache` 也一并按 id 键控）。
 */
sealed interface FilterEntry {

    val id: String
    val label: String

    /** 原图 */
    object Orig : FilterEntry {
        override val id: String = ORIG_ID
        override val label: String = "原图"
    }

    /** 「新建自定义色彩」入口卡（仅「我的色彩」分类出现） */
    object Create : FilterEntry {
        override val id: String = CREATE_ID
        override val label: String = ""
    }

    /**
     * 「AI 调色」入口卡：常驻滤镜条末尾，是上滑手势之外的第二入口。
     *
     * 它不是色彩——点它不改变当前样式，只打开 AI 抽屉，因此不参与置顶/重命名/删除。
     */
    object Ai : FilterEntry {
        override val id: String = AI_ID
        override val label: String = "AI"
    }

    data class BuiltIn(val style: LeicaEngine.Style) : FilterEntry {
        override val id: String get() = style.id
        override val label: String get() = style.label
    }

    /**
     * 自定义色彩：只存 id，名称实时查 [CustomStyleStore]。
     * 存快照的话，重命名/增强后卡片与提示会停留在旧名字上。
     */
    data class Custom(val styleId: String) : FilterEntry {
        override val id: String get() = styleId
        override val label: String get() = CustomStyleStore.nameOf(styleId)
    }

    companion object {
        const val ORIG_ID = "__orig"
        const val CREATE_ID = "__create"
        const val AI_ID = "__ai"
    }
}
