package com.leicamoments.app

import org.json.JSONObject

/**
 * 八个搜索源适配器。
 *
 * 排序即优先级（见 [ImageSearchEngine]）：正规 API 在前，无 Key 的实验性接口在后。
 * 所有适配器都遵守同一契约：**失败返回空表、不抛异常**，让引擎能安静地顺延下一层。
 */
object SearchProviders {

    val all: List<ImageSearchProvider> = listOf(
        GoogleCse(), Brave(), Openverse(), Pixabay(), Unsplash(), Pexels(), BingCn(), DuckDuckGo()
    )

    // ---- 1. Google Custom Search ----
    private class GoogleCse : ImageSearchProvider {
        override val id = AiProviderStore.SRC_GOOGLE_CSE
        override val label = "Google"

        override fun search(query: String, limit: Int, cfg: AiSearchSource): List<ImageCandidate> {
            if (cfg.apiKey.isEmpty()) return emptyList()
            // extra = cx；若含 '|' 则是「自定义中转 base| cx」
            val parts = cfg.extra.split('|')
            val base = if (parts.size > 1) parts[0].trimEnd('/') else "https://www.googleapis.com/customsearch/v1"
            val cx = if (parts.size > 1) parts[1].trim() else parts[0].trim()
            if (cx.isEmpty()) return emptyList()
            val url = "$base?key=${enc(cfg.apiKey)}&cx=${enc(cx)}&q=${enc(query)}" +
                "&searchType=image&num=${limit.coerceIn(1, 10)}&imgType=photo&imgSize=large&safe=off"
            val r = AiHttp.get(url, timeoutMs = 12000)
            if (!r.ok) return emptyList()
            return try {
                val items = JSONObject(r.body).optJSONArray("items") ?: return emptyList()
                (0 until items.length()).mapNotNull { i ->
                    val o = items.optJSONObject(i) ?: return@mapNotNull null
                    val img = o.optJSONObject("image")
                    ImageCandidate(
                        thumbUrl = img?.optString("thumbnailLink", "") ?: o.optString("link"),
                        fullUrl = o.optString("link"),
                        width = img?.optInt("width", 0) ?: 0,
                        height = img?.optInt("height", 0) ?: 0,
                        source = "Google",
                        credit = o.optString("displayLink", "")
                    )
                }
            } catch (t: Throwable) {
                emptyList()
            }
        }
    }

    // ---- 2. Brave ----
    private class Brave : ImageSearchProvider {
        override val id = AiProviderStore.SRC_BRAVE
        override val label = "Brave"

        override fun search(query: String, limit: Int, cfg: AiSearchSource): List<ImageCandidate> {
            if (cfg.apiKey.isEmpty()) return emptyList()
            val base = if (cfg.extra.startsWith("http")) cfg.extra.trimEnd('/') else "https://api.search.brave.com/res/v1"
            val url = "$base/images/search?q=${enc(query)}&count=${limit.coerceIn(1, 20)}&safesearch=off"
            val r = AiHttp.get(url, mapOf("X-Subscription-Token" to cfg.apiKey), 12000)
            if (!r.ok) return emptyList()
            return try {
                val arr = JSONObject(r.body).optJSONArray("results") ?: return emptyList()
                (0 until arr.length()).mapNotNull { i ->
                    val o = arr.optJSONObject(i) ?: return@mapNotNull null
                    val props = o.optJSONObject("properties")
                    val thumb = o.optJSONObject("thumbnail")?.optString("src", "") ?: ""
                    ImageCandidate(
                        thumbUrl = thumb.ifEmpty { o.optString("url") },
                        fullUrl = props?.optString("url", "") ?: o.optString("url"),
                        width = props?.optInt("width", 0) ?: 0,
                        height = props?.optInt("height", 0) ?: 0,
                        source = "Brave",
                        credit = o.optString("source", "")
                    )
                }
            } catch (t: Throwable) {
                emptyList()
            }
        }
    }

    // ---- 3. Openverse（可无 Key） ----
    private class Openverse : ImageSearchProvider {
        override val id = AiProviderStore.SRC_OPENVERSE
        override val label = "Openverse"

        override fun search(query: String, limit: Int, cfg: AiSearchSource): List<ImageCandidate> {
            val base = if (cfg.extra.startsWith("http")) cfg.extra.trimEnd('/') else "https://api.openverse.org"
            val url = "$base/v1/images/?q=${enc(query)}&page_size=${limit.coerceIn(1, 20)}&license_type=all"
            val headers = if (cfg.apiKey.isNotEmpty()) mapOf("Authorization" to "Bearer ${cfg.apiKey}") else emptyMap()
            val r = AiHttp.get(url, headers, 15000)
            if (!r.ok) return emptyList()
            return try {
                val arr = JSONObject(r.body).optJSONArray("results") ?: return emptyList()
                (0 until arr.length()).mapNotNull { i ->
                    val o = arr.optJSONObject(i) ?: return@mapNotNull null
                    ImageCandidate(
                        thumbUrl = o.optString("thumbnail", "").ifEmpty { o.optString("url") },
                        fullUrl = o.optString("url"),
                        width = o.optInt("width", 0),
                        height = o.optInt("height", 0),
                        source = "Openverse",
                        credit = buildString {
                            val creator = o.optString("creator", "")
                            if (creator.isNotEmpty()) append(creator)
                            val lic = o.optString("license", "")
                            if (lic.isNotEmpty()) append(if (isEmpty()) "" else " · ").append(lic.uppercase())
                        }
                    )
                }
            } catch (t: Throwable) {
                emptyList()
            }
        }
    }

    // ---- 4. Pixabay（国内直连主力） ----
    private class Pixabay : ImageSearchProvider {
        override val id = AiProviderStore.SRC_PIXABAY
        override val label = "Pixabay"

        override fun search(query: String, limit: Int, cfg: AiSearchSource): List<ImageCandidate> {
            if (cfg.apiKey.isEmpty()) return emptyList()
            val base = if (cfg.extra.startsWith("http")) cfg.extra.trimEnd('/') else "https://pixabay.com/api"
            val url = "$base/?key=${enc(cfg.apiKey)}&q=${enc(query)}&image_type=photo" +
                "&per_page=${limit.coerceIn(3, 20)}&min_width=800&safesearch=true"
            val r = AiHttp.get(url, timeoutMs = 15000)
            if (!r.ok) return emptyList()
            return try {
                val arr = JSONObject(r.body).optJSONArray("hits") ?: return emptyList()
                (0 until arr.length()).mapNotNull { i ->
                    val o = arr.optJSONObject(i) ?: return@mapNotNull null
                    ImageCandidate(
                        thumbUrl = o.optString("webformatURL", ""),
                        fullUrl = o.optString("largeImageURL", "").ifEmpty { o.optString("webformatURL") },
                        width = o.optInt("imageWidth", 0),
                        height = o.optInt("imageHeight", 0),
                        source = "Pixabay",
                        credit = o.optString("user", "")
                    )
                }
            } catch (t: Throwable) {
                emptyList()
            }
        }
    }

    // ---- 5. Unsplash ----
    private class Unsplash : ImageSearchProvider {
        override val id = AiProviderStore.SRC_UNSPLASH
        override val label = "Unsplash"

        override fun search(query: String, limit: Int, cfg: AiSearchSource): List<ImageCandidate> {
            if (cfg.apiKey.isEmpty()) return emptyList()
            val base = if (cfg.extra.startsWith("http")) cfg.extra.trimEnd('/') else "https://api.unsplash.com"
            val url = "$base/search/photos?query=${enc(query)}&per_page=${limit.coerceIn(1, 20)}"
            val r = AiHttp.get(url, mapOf("Authorization" to "Client-ID ${cfg.apiKey}"), 15000)
            if (!r.ok) return emptyList()
            return try {
                val arr = JSONObject(r.body).optJSONArray("results") ?: return emptyList()
                (0 until arr.length()).mapNotNull { i ->
                    val o = arr.optJSONObject(i) ?: return@mapNotNull null
                    val urls = o.optJSONObject("urls")
                    ImageCandidate(
                        thumbUrl = urls?.optString("small", "") ?: "",
                        fullUrl = urls?.optString("regular", "") ?: "",
                        width = o.optInt("width", 0),
                        height = o.optInt("height", 0),
                        source = "Unsplash",
                        credit = o.optJSONObject("user")?.optString("name", "") ?: ""
                    )
                }
            } catch (t: Throwable) {
                emptyList()
            }
        }
    }

    // ---- 6. Pexels ----
    private class Pexels : ImageSearchProvider {
        override val id = AiProviderStore.SRC_PEXELS
        override val label = "Pexels"

        override fun search(query: String, limit: Int, cfg: AiSearchSource): List<ImageCandidate> {
            if (cfg.apiKey.isEmpty()) return emptyList()
            val base = if (cfg.extra.startsWith("http")) cfg.extra.trimEnd('/') else "https://api.pexels.com"
            val url = "$base/v1/search?query=${enc(query)}&per_page=${limit.coerceIn(1, 20)}&size=large"
            val r = AiHttp.get(url, mapOf("Authorization" to cfg.apiKey), 15000)
            if (!r.ok) return emptyList()
            return try {
                val arr = JSONObject(r.body).optJSONArray("photos") ?: return emptyList()
                (0 until arr.length()).mapNotNull { i ->
                    val o = arr.optJSONObject(i) ?: return@mapNotNull null
                    val src = o.optJSONObject("src")
                    ImageCandidate(
                        thumbUrl = src?.optString("medium", "") ?: "",
                        fullUrl = src?.optString("large2x", "").orEmpty().ifEmpty { src?.optString("original", "") ?: "" },
                        width = o.optInt("width", 0),
                        height = o.optInt("height", 0),
                        source = "Pexels",
                        credit = o.optString("photographer", "")
                    )
                }
            } catch (t: Throwable) {
                emptyList()
            }
        }
    }

    // ---- 7. 必应中国（实验性：网页解析，免 Key） ----
    private class BingCn : ImageSearchProvider {
        override val id = AiProviderStore.SRC_BING_CN
        override val label = "必应"

        private val murl = Regex("murl&quot;:&quot;(.*?)&quot;")
        private val turl = Regex("turl&quot;:&quot;(.*?)&quot;")

        override fun search(query: String, limit: Int, cfg: AiSearchSource): List<ImageCandidate> {
            val url = "https://cn.bing.com/images/async?q=${enc(query)}&first=1&count=${limit.coerceIn(1, 20)}&mmasync=1"
            val r = AiHttp.get(url, mapOf("Referer" to "https://cn.bing.com/images/search?q=${enc(query)}"), 12000)
            if (!r.ok) return emptyList()
            return try {
                val full = murl.findAll(r.body).map { unescape(it.groupValues[1]) }.toList()
                val thumbs = turl.findAll(r.body).map { unescape(it.groupValues[1]) }.toList()
                full.take(limit).mapIndexed { i, u ->
                    ImageCandidate(
                        thumbUrl = thumbs.getOrElse(i) { u },
                        fullUrl = u,
                        width = 0,
                        height = 0,
                        source = "必应"
                    )
                }
            } catch (t: Throwable) {
                emptyList()
            }
        }

        private fun unescape(s: String): String = s
            .replace("\\/", "/")
            .replace("&amp;", "&")
    }

    // ---- 8. DuckDuckGo（实验性：i.js 需要先取 vqd） ----
    private class DuckDuckGo : ImageSearchProvider {
        override val id = AiProviderStore.SRC_DUCKDUCKGO
        override val label = "DuckDuckGo"

        private val vqdRegex = Regex("vqd=[\"']?([\\d-]+)[\"']?")

        override fun search(query: String, limit: Int, cfg: AiSearchSource): List<ImageCandidate> {
            val htmlUrl = "https://duckduckgo.com/?q=${enc(query)}&iax=images&ia=images"
            val html = AiHttp.get(htmlUrl, timeoutMs = 12000)
            if (!html.ok) return emptyList()
            val vqd = vqdRegex.find(html.body)?.groupValues?.get(1) ?: return emptyList()
            val url = "https://duckduckgo.com/i.js?l=zh-cn&o=json&q=${enc(query)}&vqd=$vqd&f=,,,&p=1"
            val r = AiHttp.get(
                url,
                mapOf("Referer" to "https://duckduckgo.com/", "X-Requested-With" to "XMLHttpRequest"),
                12000
            )
            if (!r.ok) return emptyList()
            return try {
                val arr = JSONObject(r.body).optJSONArray("results") ?: return emptyList()
                (0 until minOf(arr.length(), limit)).mapNotNull { i ->
                    val o = arr.optJSONObject(i) ?: return@mapNotNull null
                    ImageCandidate(
                        thumbUrl = o.optString("thumbnail", ""),
                        fullUrl = o.optString("image", ""),
                        width = o.optInt("width", 0),
                        height = o.optInt("height", 0),
                        source = "DuckDuckGo",
                        credit = o.optString("title", "").take(24)
                    )
                }
            } catch (t: Throwable) {
                emptyList()
            }
        }
    }

    private fun enc(s: String): String =
        java.net.URLEncoder.encode(s, "UTF-8")
}
