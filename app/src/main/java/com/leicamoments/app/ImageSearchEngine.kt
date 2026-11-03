package com.leicamoments.app

import android.content.Context

/**
 * 参考图搜索编排：按优先级顺延 + 15s 总预算 + 会话级失败黑名单 + 按词缓存。
 *
 * 设计要点：
 * - **不串行傻等**：整条链共用 15s，单个源拿到剩余时间片，超时/空结果立即顺延；
 * - **失败即拉黑**（仅本次进程）：同一会话里不再反复打同一个坏源；
 * - **按词缓存**：同一句话重复搜索不重复消耗免费额度。
 */
object ImageSearchEngine {

    data class SearchResult(
        val candidates: List<ImageCandidate>,
        val usedSource: String,
        val note: String
    ) {
        val isEmpty: Boolean get() = candidates.isEmpty()
    }

    private const val TOTAL_BUDGET_MS = 15_000L
    private const val MIN_SLICE_MS = 2_500L

    private val blacklist = HashSet<String>()
    private val cache = LinkedHashMap<String, SearchResult>()
    private const val CACHE_CAP = 24

    @Synchronized
    fun search(ctx: Context, query: String, limit: Int = 8): SearchResult {
        val q = query.trim()
        if (q.isEmpty()) return SearchResult(emptyList(), "", "没有搜索词")

        cache[q]?.let { return it }

        val configs = AiProviderStore.searchSources(ctx).filter { it.enabled }
        if (configs.isEmpty()) {
            return SearchResult(emptyList(), "", "未启用任何参考图搜索源").also { cache[q] = it }
        }

        val deadline = System.currentTimeMillis() + TOTAL_BUDGET_MS
        val triedFail = ArrayList<String>()

        for ((sid, label, _) in AiProviderStore.SOURCE_META) {
            val cfg = configs.firstOrNull { it.id == sid } ?: continue
            if (sid in blacklist) continue
            val provider = SearchProviders.all.firstOrNull { it.id == sid } ?: continue
            val remain = deadline - System.currentTimeMillis()
            if (remain < MIN_SLICE_MS) break

            val started = System.currentTimeMillis()
            val got = try {
                provider.search(q, limit, cfg).filter { it.passesFilter() }
            } catch (t: Throwable) {
                emptyList()
            }
            val cost = System.currentTimeMillis() - started
            DiagLog.w("AiSearch", "${provider.label} ${if (got.isEmpty()) "空" else "${got.size} 张"} ${cost}ms")

            if (got.isNotEmpty()) {
                val res = SearchResult(got, label, "来自 $label")
                cache[q] = res
                trimCache()
                return res
            }
            blacklist.add(sid)
            triedFail.add(label)
        }

        val res = SearchResult(
            emptyList(), "",
            if (triedFail.isEmpty()) "搜索已超时" else "已尝试 ${triedFail.joinToString("/")}，均无合适结果"
        )
        cache[q] = res
        trimCache()
        return res
    }

    /** 缓存里取（UI 层做候选网格时复用，避免重复请求）。 */
    @Synchronized
    fun cached(query: String): SearchResult? = cache[query.trim()]

    @Synchronized
    fun resetFailures() {
        blacklist.clear()
    }

    @Synchronized
    fun clearCache() {
        cache.clear()
    }

    private fun trimCache() {
        while (cache.size > CACHE_CAP) {
            val it = cache.keys.iterator()
            if (it.hasNext()) {
                it.next()
                it.remove()
            }
        }
    }
}
