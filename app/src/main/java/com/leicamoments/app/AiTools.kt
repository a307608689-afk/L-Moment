package com.leicamoments.app

import org.json.JSONArray
import org.json.JSONObject

/**
 * AI 能碰到宿主 App 的唯一界面（实现方是 MainActivity）。
 *
 * **封闭白名单**：这里的方法只覆盖「读风格列表 / 读统计 / 调当前会话参数 / 出预览 /
 * 搜参考图 / 用参考图拟合风格」。**刻意没有任何写设置的方法**——AI 无法改动
 * 徽章开关、导出档位、置顶、伪 RAW 等任何用户偏好。
 */
interface AiHost {
    fun hasImage(): Boolean

    /** 内置风格 + 自定义色彩目录（只读）。 */
    fun styleCatalog(): JSONArray

    /** 当前照片统计；没有照片时返回 null。 */
    fun imageStats(): JSONObject?

    /** 应用八档参数（主线程执行），返回实际生效的值。 */
    fun applyParams(args: JSONObject): JSONObject

    /** 切换到某个现成色彩（内置 id 或自定义色彩 id）。 */
    fun applyStyle(styleId: String): JSONObject

    /** 当前画布的 JPEG base64（缩到 maxDim），给视觉模型看。 */
    fun previewBase64(maxDim: Int): String?

    /** 搜索参考图，返回 [{thumb,url,source,width,height}]。 */
    fun searchReference(query: String, limit: Int): JSONArray

    /** 用参考图拟合模型色彩并应用到预览，返回 {ok, name}。 */
    fun applyReferenceStyle(url: String): JSONObject

    /** 当前参数摘要（给模型确认）。 */
    fun currentSummary(): String
}

/**
 * 工具注册表：schema 与执行集中在同一处，避免"模型看到的"和"代码执行的"漂移。
 */
object AiTools {

    const val LIST_STYLES = "list_styles"
    const val ANALYZE_IMAGE = "analyze_image"
    const val APPLY_STYLE = "apply_style"
    const val APPLY_PARAMS = "apply_params"
    const val RENDER_PREVIEW = "render_preview"
    const val SEARCH_REFERENCE = "search_reference"
    const val APPLY_REFERENCE_STYLE = "apply_reference_style"

    fun schema(): JSONArray {
        val arr = JSONArray()
        arr.put(tool(LIST_STYLES, "列出应用里现成的色彩（内置 + 我的色彩），用于优先复用现成风格。", JSONObject()))
        arr.put(tool(ANALYZE_IMAGE, "分析当前照片：返回亮度分位、对比、饱和度、色温、主色等统计。", JSONObject()))
        arr.put(
            tool(
                APPLY_STYLE,
                "切换到一套现成色彩（内置或我的色彩）。style_id 必须来自 list_styles 的结果。"
                    + "切换是异步渲染，约 1~2 秒后生效，之后可以再调参数微调。",
                JSONObject().apply {
                    put("style_id", JSONObject().apply {
                        put("type", "string")
                        put("description", "list_styles 返回的 id")
                    })
                },
                required = listOf("style_id")
            )
        )
        arr.put(
            tool(
                APPLY_PARAMS,
                "调整当前照片的八档参数并立即生效。每档范围 -10..10，0 表示不变；单次调整建议不超过 ±4。",
                JSONObject().apply {
                    put("brightness", intProp("亮度 -10..10"))
                    put("exposure", intProp("曝光 -10..10"))
                    put("highlights", intProp("高光 -10..10（负值压暗高光）"))
                    put("shadows", intProp("阴影 -10..10（正值提亮暗部）"))
                    put("black", intProp("黑位 -10..10"))
                    put("saturation", intProp("饱和度 -10..10"))
                    put("contrast", intProp("对比度 -10..10"))
                    put("glow", intProp("柔光 -10..10（正值柔和朦胧，负值清晰化）"))
                },
                required = listOf("brightness", "exposure", "highlights", "shadows", "black", "saturation", "contrast", "glow")
            )
        )
        arr.put(
            tool(
                RENDER_PREVIEW,
                "请求当前照片的预览。系统会把预览图作为下一条消息发给你（若模型支持看图）。",
                JSONObject().apply { put("max_dim", intProp("最长边像素，默认 768")) }
            )
        )
        arr.put(
            tool(
                SEARCH_REFERENCE,
                "在网络上搜索风格参考图。返回若干候选（含缩略图与原图地址）。只在现成色彩明显无法满足、"
                    + "且用户想要某个具体风格时才使用；搜索词请用简短的英文或中文风格描述。",
                JSONObject().apply {
                    put("query", JSONObject().apply { put("type", "string"); put("description", "搜索词") })
                    put("limit", intProp("返回数量，默认 6，最多 8"))
                },
                required = listOf("query")
            )
        )
        arr.put(
            tool(
                APPLY_REFERENCE_STYLE,
                "用一张参考图拟合出新色彩（模型色彩）并应用到预览。url 必须来自 search_reference 的结果。"
                    + "调用后系统会生成一套临时色彩，用户确认后才会保存。",
                JSONObject().apply {
                    put("url", JSONObject().apply { put("type", "string"); put("description", "参考图原图地址") })
                },
                required = listOf("url")
            )
        )
        return arr
    }

    /** 执行一个工具调用，返回给模型看的 JSON 文本。 */
    fun execute(host: AiHost, name: String, args: JSONObject): JSONObject {
        return try {
            when (name) {
                LIST_STYLES -> JSONObject().apply {
                    put("ok", true)
                    put("styles", host.styleCatalog())
                }

                ANALYZE_IMAGE -> {
                    val stats = host.imageStats()
                    if (stats == null) {
                        JSONObject().apply {
                            put("ok", false); put("error", "当前没有打开的照片")
                        }
                    } else {
                        JSONObject().apply {
                            put("ok", true)
                            put("stats", stats)
                            put("summary", stats.optString("summary"))
                        }
                    }
                }

                APPLY_STYLE -> {
                    val id = args.optString("style_id", "").trim()
                    if (id.isEmpty()) {
                        JSONObject().apply { put("ok", false); put("error", "缺少 style_id") }
                    } else {
                        host.applyStyle(id)
                    }
                }

                APPLY_PARAMS -> host.applyParams(args)

                RENDER_PREVIEW -> JSONObject().apply {
                    put("ok", host.hasImage())
                    put("note", "预览图会在下一条消息中提供（若当前模型支持视觉）")
                    put("params", host.currentSummary())
                }

                SEARCH_REFERENCE -> {
                    val q = args.optString("query", "").trim()
                    if (q.isEmpty()) {
                        JSONObject().apply { put("ok", false); put("error", "搜索词为空") }
                    } else {
                        val limit = args.optInt("limit", 6).coerceIn(1, 8)
                        val list = host.searchReference(q, limit)
                        JSONObject().apply {
                            put("ok", list.length() > 0)
                            put("query", q)
                            put("candidates", list)
                            if (list.length() == 0) put("error", "没有搜到合适的参考图")
                        }
                    }
                }

                APPLY_REFERENCE_STYLE -> {
                    val url = args.optString("url", "").trim()
                    if (url.isEmpty()) {
                        JSONObject().apply { put("ok", false); put("error", "缺少参考图地址") }
                    } else {
                        host.applyReferenceStyle(url)
                    }
                }

                else -> JSONObject().apply {
                    put("ok", false); put("error", "未知工具 $name")
                }
            }
        } catch (t: Throwable) {
            JSONObject().apply {
                put("ok", false)
                put("error", t.message ?: t.javaClass.simpleName)
            }
        }
    }

    fun isReferenceTool(name: String): Boolean =
        name == SEARCH_REFERENCE || name == APPLY_REFERENCE_STYLE

    private fun tool(name: String, desc: String, props: JSONObject, required: List<String> = emptyList()): JSONObject {
        val params = JSONObject().apply {
            put("type", "object")
            put("properties", props)
            put("required", JSONArray(required))
        }
        return JSONObject().apply {
            put("type", "function")
            put("function", JSONObject().apply {
                put("name", name)
                put("description", desc)
                put("parameters", params)
            })
        }
    }

    private fun intProp(desc: String): JSONObject = JSONObject().apply {
        put("type", "integer")
        put("description", desc)
    }
}
