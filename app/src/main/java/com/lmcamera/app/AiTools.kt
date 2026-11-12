package com.lmcamera.app

import org.json.JSONArray
import org.json.JSONObject

/**
 * AI 能碰到宿主 App 的唯一界面（实现方是 MainActivity）。
 *
 * **封闭白名单**：这里的方法只覆盖「读风格列表 / 读统计 / 调当前会话参数 / 出预览」。
 * **刻意没有任何写设置的方法**——AI 无法改动徽章开关、导出档位、置顶、伪 RAW 等
 * 任何用户偏好。参考图拟合不走本接口（端侧宿主直连，见 [AiSheetDialog] 的发送流程）。
 */
interface AiHost {
    fun hasImage(): Boolean

    /** 内置风格 + 自定义色彩目录（只读）。 */
    fun styleCatalog(): JSONArray

    /** 当前照片统计；没有照片时返回 null。 */
    fun imageStats(): JSONObject?

    /** 应用 14 档参数（主线程执行），返回实际生效的值。 */
    fun applyParams(args: JSONObject): JSONObject

    /** 切换到某个现成色彩（内置 id 或自定义色彩 id）。 */
    fun applyStyle(styleId: String): JSONObject

    /** 当前画布的 JPEG base64（缩到 maxDim），给视觉模型看。 */
    fun previewBase64(maxDim: Int): String?

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

    fun schema(): JSONArray {
        val arr = JSONArray()
        arr.put(tool(LIST_STYLES, "列出应用里现成的色彩（内置 + 我的色彩），用于优先复用现成风格。", JSONObject()))
        arr.put(tool(ANALYZE_IMAGE, "分析当前照片：返回亮度分位、对比、饱和度、色温、主色等统计。", JSONObject()))
        arr.put(
            tool(
                APPLY_STYLE,
                "切换到一套现成色彩。【可用色彩目录】已在每条消息末尾注入，无需先调 list_styles；"
                    + "style_id 必须来自目录。切换是异步渲染，约 1~2 秒后生效，之后可以再调参数微调。",
                JSONObject().apply {
                    put("style_id", JSONObject().apply {
                        put("type", "string")
                        put("description", "色彩目录中给出的 id")
                    })
                },
                required = listOf("style_id")
            )
        )
        arr.put(
            tool(
                APPLY_PARAMS,
                "调整当前照片的 14 档参数并立即生效。影调/色彩档位范围 -10..10，0 表示不变，单次调整建议不超过 ±4；"
                    + "强度类（黑白/颗粒/暗角/风格强度）范围 0..100，默认分别为 0/0/0/100。"
                    + "单轮最多动 4 个参数，粗到细分轮进行。",
                JSONObject().apply {
                    put("brightness", intProp("亮度 -10..10"))
                    put("exposure", intProp("曝光 -10..10"))
                    put("highlights", intProp("高光 -10..10（负值压暗高光）"))
                    put("shadows", intProp("阴影 -10..10（正值提亮暗部）"))
                    put("black", intProp("黑位 -10..10"))
                    put("saturation", intProp("饱和度 -10..10"))
                    put("contrast", intProp("对比度 -10..10"))
                    put("glow", intProp("柔光 -10..10（正值柔和朦胧，负值清晰化）"))
                    put("temp", intProp("色温 -10..10（正=暖，负=冷）"))
                    put("tint", intProp("色调 -10..10（正=品红，负=绿）"))
                    put("mono", intProp("黑白强度 0..100（0=彩色）"))
                    put("grain", intProp("颗粒叠加 0..100（叠加在风格内置颗粒之上）"))
                    put("vignette", intProp("暗角叠加 0..100"))
                    put("lutStrength", intProp("风格强度 0..100（当前色彩模式/风格的施加强度，100=原样，0=无风格）"))
                },
                required = listOf(
                    "brightness", "exposure", "highlights", "shadows", "black",
                    "saturation", "contrast", "glow", "temp", "tint",
                    "mono", "grain", "vignette", "lutStrength"
                )
            )
        )
        arr.put(
            tool(
                RENDER_PREVIEW,
                "请求当前照片的预览。系统会把预览图作为下一条消息发给你（若模型支持看图）。",
                JSONObject().apply { put("max_dim", intProp("最长边像素，默认 768")) }
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
