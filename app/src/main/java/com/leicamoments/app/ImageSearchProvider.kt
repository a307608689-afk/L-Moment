package com.leicamoments.app

/**
 * 一张参考图候选：缩略图给 UI 网格用，原图给模型色彩拟合用。
 *
 * [source] 是来源展示名（Pixabay 的免费条款要求展示来源），[credit] 是作者/许可，
 * UI 在缩略图角落统一显示一行小字。
 */
data class ImageCandidate(
    val thumbUrl: String,
    val fullUrl: String,
    val width: Int,
    val height: Int,
    val source: String,
    val credit: String = ""
) {
    /** 元数据已知时的硬过滤：最短边 ≥800、宽高比 0.5~2.0。 */
    fun passesFilter(): Boolean {
        if (fullUrl.isEmpty()) return false
        if (width <= 0 || height <= 0) return true          // 元数据缺失时交给拟合端判断
        if (minOf(width, height) < 800) return false
        val ratio = width.toFloat() / height.toFloat()
        return ratio in 0.5f..2.0f
    }
}

/** 统一搜索接口：新增一个图源 = 实现一个类。 */
interface ImageSearchProvider {
    val id: String
    val label: String

    /** 同步阻塞调用，调用方保证在后台线程。失败返回空表，不抛异常。 */
    fun search(query: String, limit: Int, cfg: AiSearchSource): List<ImageCandidate>
}
