package com.leicamoments.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * 一轮 AI 调色会话：消息历史 + 工具环（≤6 轮）+ 代次守卫。
 *
 * 线程模型：网络与工具执行都在**独立单线程 executor** 上（与图像管线的
 * `executor`/`adjExecutor` 隔离），UI 回调统一 post 回主线程。
 *
 * 失败语义：任何一步出错都只回调 [Listener.onError]，**不改变当前照片状态**；
 * 用户此前的编辑（含 AI 之前调好的参数）保持原样。
 */
class AiSession(private val ctx: Context, private val host: AiHost) {

    interface Listener {
        fun onBusy(busy: Boolean)
        fun onText(text: String)
        fun onError(msg: String)
        fun onPreviewChanged()
        fun onReferenceStyleApplied(name: String)
    }

    var listener: Listener? = null

    /** UI 时间线的一行：对话历史存在会话里，关掉抽屉/换图后重新打开可原样恢复。 */
    data class Line(val text: String, val error: Boolean, val muted: Boolean)

    private val lines = ArrayList<Line>()

    fun history(): List<Line> = synchronized(lines) { ArrayList(lines) }

    fun logLine(text: String, error: Boolean = false, muted: Boolean = false) {
        synchronized(lines) {
            lines.add(Line(text, error, muted))
            while (lines.size > 200) lines.removeAt(0)
        }
    }

    /**
     * 换了一张新照片：**清空模型上下文**（旧图的统计与结论对新图是噪声，会主动误导），
     * 但保留 UI 时间线记录，并插入一条分隔提示——历史可回看，判断只基于新图。
     */
    fun resetForNewImage() {
        messages = JSONArray()
        imageInjections = 0
        userImageStale = true
        logLine("—— 新照片 · 上下文已重置 ——", muted = true)
    }

    /** 只清 UI 时间线与消息（目前没有入口调用，留作「新对话」扩展）。 */
    fun clearHistory() {
        synchronized(lines) { lines.clear() }
        messages = JSONArray()
        imageInjections = 0
    }

    private val client = AiClient()
    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var messages = JSONArray()

    @Volatile
    private var gen = 0

    @Volatile
    private var busy = false

    private var imageInjections = 0
    /** 换图后需要把新照片再塞给视觉模型一次（只一次，避免每轮都传图烧 token） */
    private var userImageStale = true

    val isBusy: Boolean get() = busy
    val hasHistory: Boolean get() = messages.length() > 0

    /** 首轮：用户输入一句话（自动带上照片统计与预览图）。 */
    fun send(provider: AiProvider, text: String) {
        if (busy) return
        val g = ++gen
        busy = true
        notify { it.onBusy(true) }

        executor.execute {
            try {
                if (messages.length() == 0) {
                    messages.put(JSONObject().apply {
                        put("role", "system")
                        put("content", SYSTEM_PROMPT)
                    })
                }
                // 每轮都带当前照片统计：换图后模型据此重新判断，不会被旧结论带偏
                messages.put(buildUserMessage(provider, text, forceStats = true))
                val applied = runLoop(g, provider)
                if (g == gen && applied) notify { it.onPreviewChanged() }
            } catch (t: Throwable) {
                if (g == gen) notify { it.onError(t.message ?: "AI 请求失败") }
            } finally {
                if (g == gen) {
                    busy = false
                    notify { it.onBusy(false) }
                }
            }
        }
    }

    /** 零输入自动优化：固定指令，单轮往返出结果。 */
    fun autoOptimize(provider: AiProvider, userHint: String = "") {
        val extra = if (userHint.isBlank()) "" else "\n用户补充：$userHint"
        send(
            provider,
            "请自动优化这张照片：先 analyze_image 并 list_styles，挑一套最匹配的用 apply_style 切过去，"
                + "再用 apply_params 一次给出完整影调方案（单档不超过 ±5）。不要反问，直接出结果。$extra"
        )
    }

    /** 换图/关闭时作废在途请求。 */
    fun cancel() {
        gen++
        busy = false
    }

    fun clear() {
        messages = JSONArray()
        imageInjections = 0
    }

    // ---- 内部 ----

    private fun runLoop(g: Int, provider: AiProvider): Boolean {
        var applied = false
        var rounds = 0
        while (rounds < MAX_ROUNDS) {
            if (g != gen) return applied
            rounds++
            val reply = client.chat(provider, messages, AiTools.schema())
            if (g != gen) return applied
            if (!reply.ok) {
                notify { it.onError(reply.error) }
                return applied
            }
            if (reply.content.isNotBlank()) {
                notify { it.onText(reply.content.trim()) }
            }

            if (!reply.hasToolCalls) return applied

            // 1) 记录 assistant 的 tool_calls（OpenAI 协议要求原样回传）
            messages.put(JSONObject().apply {
                put("role", "assistant")
                put("content", reply.content)
                put("tool_calls", JSONArray().apply {
                    reply.toolCalls.forEach { tc ->
                        put(JSONObject().apply {
                            put("id", tc.id)
                            put("type", "function")
                            put("function", JSONObject().apply {
                                put("name", tc.name)
                                put("arguments", tc.args.toString())
                            })
                        })
                    }
                })
            })

            // 2) 顺序执行
            var paramsApplied = false
            var styleApplied = false
            var refApplied = ""
            reply.toolCalls.forEach { tc ->
                if (g != gen) return applied
                val name = tc.name
                if (name != AiTools.ANALYZE_IMAGE) {
                    notify { it.onText(stepLine(name, tc.args)) }
                }
                val result = AiTools.execute(host, name, tc.args)
                messages.put(JSONObject().apply {
                    put("role", "tool")
                    put("tool_call_id", tc.id)
                    put("content", result.toString())
                })
                if (name == AiTools.APPLY_PARAMS && result.optBoolean("ok", false)) {
                    paramsApplied = true
                    applied = true
                }
                if (name == AiTools.APPLY_STYLE && result.optBoolean("ok", false)) {
                    styleApplied = true
                    applied = true
                }
                if (name == AiTools.APPLY_REFERENCE_STYLE && result.optBoolean("ok", false)) {
                    refApplied = result.optString("name", "参考色彩")
                }
                if (name == AiTools.SEARCH_REFERENCE && !result.optBoolean("ok", false)) {
                    notify { it.onText("搜索没有结果，将按参数直接调色。") }
                }
            }

            if (paramsApplied) notify { it.onPreviewChanged() }
            if (refApplied.isNotEmpty()) {
                val n = refApplied
                notify { it.onReferenceStyleApplied(n) }
            }

            // 换色彩是异步渲染（LUT/native 约 1~2s）：等它落地再继续，避免参数调到旧图上
            if (styleApplied) {
                try {
                    Thread.sleep(1600)
                } catch (t: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }

            // 参数落地后给预览链一点时间重算（recomputeAdjAsync 在另一条线程，异步上屏）
            if (paramsApplied) {
                try {
                    Thread.sleep(450)
                } catch (t: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }

            // 3) 视觉模型：把应用后的预览图补一条 user 消息，让它"看一眼"
            if (paramsApplied && provider.vision && imageInjections < MAX_IMAGE_INJECT) {
                val img = host.previewBase64(768)
                if (img != null) {
                    imageInjections++
                    messages.put(JSONObject().apply {
                        put("role", "user")
                        put("content", JSONArray().apply {
                            put(JSONObject().apply {
                                put("type", "text")
                                put("text", "这是应用你给出的参数后的预览（$rounds 轮）。如果还有明显问题就继续微调，"
                                    + "调整幅度要小；满意就不要调用工具，直接用一句话总结。")
                            })
                            put(JSONObject().apply {
                                put("type", "image_url")
                                put("image_url", JSONObject().apply {
                                    put("url", "data:image/jpeg;base64,$img")
                                })
                            })
                        })
                    })
                    continue
                }
            }
            // 工具结果已回喂：继续下一轮，让模型基于结果决定「继续调」还是「总结收尾」
        }
        if (rounds >= MAX_ROUNDS) {
            notify { it.onText("已调整到多轮上限，先给你这一版。可以继续说「再暗一点」之类继续微调。") }
        }
        return applied
    }

    private fun buildUserMessage(provider: AiProvider, text: String, forceStats: Boolean): JSONObject {
        val stats = if (forceStats) host.imageStats() else null
        val hint = StyleKeywordMap.hintText(text)
        val body = buildString {
            append(text)
            if (stats != null) {
                append("\n\n")
                append(ImageStats.promptBlock(stats))
            }
            append("\n\n")
            append(hint)
            append("\n当前参数：")
            append(host.currentSummary())
        }

        if (provider.vision && (messages.length() <= 1 || userImageStale)) {
            val img = host.previewBase64(768)
            if (img != null) {
                userImageStale = false
                return JSONObject().apply {
                    put("role", "user")
                    put("content", JSONArray().apply {
                        put(JSONObject().apply { put("type", "text"); put("text", body) })
                        put(JSONObject().apply {
                            put("type", "image_url")
                            put("image_url", JSONObject().apply { put("url", "data:image/jpeg;base64,$img") })
                        })
                    })
                }
            }
        }
        return JSONObject().apply {
            put("role", "user")
            put("content", body)
        }
    }

    private fun stepLine(name: String, args: JSONObject): String = when (name) {
        AiTools.LIST_STYLES -> "查看现成色彩"
        AiTools.ANALYZE_IMAGE -> "分析照片"
        AiTools.APPLY_PARAMS -> {
            val parts = ArrayList<String>()
            PARAM_LABELS.forEach { (key, label) ->
                val v = args.optInt(key, 0)
                if (v != 0) parts.add("$label${if (v > 0) "+$v" else "$v"}")
            }
            if (parts.isEmpty()) "参数未变化" else parts.joinToString(" · ")
        }
        AiTools.APPLY_STYLE -> "切换色彩：${args.optString("style_id")}"
        AiTools.RENDER_PREVIEW -> "生成预览"
        AiTools.SEARCH_REFERENCE -> "搜索参考图：${args.optString("query", "")}"
        AiTools.APPLY_REFERENCE_STYLE -> "用参考图拟合新色彩"
        else -> name
    }

    private fun notify(block: (Listener) -> Unit) {
        main.post {
            try {
                listener?.let(block)
            } catch (t: Throwable) {
                // UI 已销毁：忽略
            }
        }
    }

    companion object {
        const val MAX_ROUNDS = 6
        const val MAX_IMAGE_INJECT = 2

        private val PARAM_LABELS = listOf(
            "brightness" to "亮度",
            "exposure" to "曝光",
            "highlights" to "高光",
            "shadows" to "阴影",
            "black" to "黑位",
            "saturation" to "饱和",
            "contrast" to "对比",
            "glow" to "柔光"
        )

        private const val SYSTEM_PROMPT =
            "你是 LeicaMoments（徕卡风格相机滤镜 App）的内置调色助手，服务对象是摄影爱好者。\n" +
                "可用工具：list_styles（查看现成色彩）、analyze_image（照片统计）、apply_style（切换到某套现成色彩）、" +
                "apply_params（八档参数，-10..10，0=不变）、render_preview（请求预览）、" +
                "search_reference（搜索风格参考图）、apply_reference_style（用参考图拟合新色彩）。\n" +
                "工作准则：\n" +
                "1. 永远先复用现成色彩：先用 list_styles 看目录，能对上就用 apply_style 切过去，再按需 apply_params 微调；" +
                "只有确实无法满足才考虑参考图方案；\n" +
                "2. 调参克制：一般单档 ±1~±4，用户明确要求强烈时才更大；不要把所有参数一起推满；\n" +
                "3. 用户说「自动优化」时，先 analyze_image，再一次性 apply_params 给出完整方案，不要反复试探；\n" +
                "4. 只有用户描述了具体风格而现成色彩明显不匹配时，才 search_reference（搜索词用简短的中/英文风格描述）；" +
                "搜索到候选后用 apply_reference_style 取最合适的一张，不要贴 URL 给用户看；\n" +
                "5. 不要臆造工具，不要输出 JSON；\n" +
                "6. 最后用一句中文总结做了什么（例如「选 M9，提亮阴影 +2、饱和度 +1」），不要长篇大论；\n" +
                "7. 你无法修改任何 App 设置，也不要声称能修改。"
    }
}
