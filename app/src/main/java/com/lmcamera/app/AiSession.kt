package com.lmcamera.app

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
    }

    var listener: Listener? = null

    /**
     * 逐步撤销钩子：工具环每轮 [AiTools.APPLY_PARAMS] / [AiTools.APPLY_STYLE] 落地**前**
     * 触发（session executor 线程），宿主压一个撤销快照——一条消息内「切风格 → 粗调 →
     * 细调」逐级可回退。不走 [AiHost]（保持工具白名单封闭）。
     */
    var onUndoPoint: (() -> Unit)? = null

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
            if (!reply.hasToolCalls) {
                // 空回复（既无正文也无工具调用）不能被当成"正常结束"：那会让用户看到
                // 「处理中转一圈，什么都没发生」。截断要给明确原因和处理建议。
                if (reply.content.isBlank()) {
                    val reason = reply.finishReason
                    notify {
                        it.onText(
                            if (reason == "length") {
                                "模型这轮没有给出内容（输出被截断）。已停止，可以说「再试一次」或换个模型。"
                            } else {
                                "模型这轮没有返回内容（finish=${reason.ifBlank { "空" }}）。已停止，请再试一次。"
                            }
                        )
                    }
                    return applied
                }
                // 把最终答复写回历史：下一轮的消息序列才是「… assistant → user」，
                // 否则历史停在 tool/user 上，部分服务会退化出空回复。
                messages.put(JSONObject().apply {
                    put("role", "assistant")
                    put("content", reply.content)
                })
                notify { it.onText(reply.content.trim()) }
                return applied
            }

            if (reply.content.isNotBlank()) {
                notify { it.onText(reply.content.trim()) }
            }

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
            reply.toolCalls.forEach { tc ->
                if (g != gen) return applied
                val name = tc.name
                if (name != AiTools.ANALYZE_IMAGE) {
                    notify { it.onText(stepLine(name, tc.args)) }
                }
                // 逐步撤销：会改画面状态的工具落地前压快照（与消息起点快照相邻去重）
                if (name == AiTools.APPLY_PARAMS || name == AiTools.APPLY_STYLE) {
                    onUndoPoint?.invoke()
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
            }

            if (paramsApplied) notify { it.onPreviewChanged() }

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
            append("\n\n")
            append(catalogBlock())
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
        else -> name
    }

    /** 色彩目录注入：模型不必再调 list_styles，直接据此匹配（每轮刷新，自定义色彩即时可见） */
    private fun catalogBlock(): String {
        return try {
            val arr = host.styleCatalog()
            if (arr.length() == 0) return "【可用色彩目录】（空）"
            val sb = StringBuilder("【可用色彩目录】（用户描述能对上就直接 apply_style，不必再调 list_styles）\n")
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optString("id")
                val label = o.optString("label")
                val desc = o.optString("desc")
                val kind = o.optString("kind", "")
                val category = o.optString("category", "")
                sb.append("- ").append(label).append("（id=").append(id)
                if (category.isNotBlank()) sb.append("，").append(category)
                if (kind == "custom") sb.append("，我的色彩")
                if (desc.isNotBlank()) sb.append("；").append(desc)
                sb.append("）\n")
            }
            sb.toString().trimEnd()
        } catch (t: Throwable) {
            ""
        }
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
            "你是 L-Moment（相机滤镜调色 App）的内置调色助手，服务对象是摄影爱好者。\n" +
                "每条消息末尾都会附带【可用色彩目录】与照片统计，不必再调用 list_styles / analyze_image 去查这些信息。\n" +
                "可用工具：apply_style（切换到目录里的某套色彩）、apply_params（14 档参数：影调/色彩 -10..10，" +
                    "黑白/颗粒/暗角/风格强度 0..100，默认值 0 与 100=不变）、render_preview（请求预览）。\n" +
                "决策规则（严格按序执行，不许跳步）：\n" +
                "1. 目录中存在与用户描述匹配的色彩 → 必须 apply_style 切过去，再用 apply_params 微调；\n" +
                "2. 只有部分匹配（如「想要青橙但更暗」）→ apply_style 后必须 apply_params 补差；\n" +
                "3. 用户消息里带【参考拟合】标注 → 系统刚用用户上传的参考图学习出了一套色彩并已应用到画布：" +
                    "先结合用户备注与照片统计，用 apply_params 做针对性微调（粗到细分轮，每轮少量档位），" +
                    "不要建议更换目录里的现成色彩；\n" +
                "4. 目录无匹配且描述抽象（「好看一点」「氛围感」）→ 纯 apply_params；\n" +
                "5. 目录匹配不上用户描述的具体风格（某部电影、某个 App 滤镜、某种胶片）→ " +
                    "用 apply_params 尽力逼近，并提示可以上传参考图让系统学习拟合，不要承诺不存在的风格；\n" +
                "6. 用户说「自动优化」→ 直接 apply_params 给出完整方案，不套色彩；\n" +
                "7. 调参克制：单档 ±1~±4，用户明确要求强烈时才更大；不要把所有参数一起推满；\n" +
                "8. 不要臆造工具，不要输出 JSON；最后用一句中文总结做了什么" +
                    "（例如「选 M9，提亮阴影 +2、饱和度 +1」）；你无法修改任何 App 设置，也不要声称能修改。"
    }
}
