package com.lmcamera.app

import org.json.JSONArray
import org.json.JSONObject

/**
 * OpenAI 兼容的 chat/completions 客户端（含 tools / function calling）。
 *
 * 只做两件事：发一轮对话、解析回包。**不持有会话状态**，状态全在 [AiSession]。
 * 任何异常都折叠成 [Reply.error]，调用方据此提示用户，不抛异常。
 *
 * 稳健性策略：
 * 1. 深度思考默认发「最低档」，按服务商方言注入；服务不认则剥掉重发一次；
 * 2. 「人数过多」/429/5xx 属瞬时失败，最多 3 次尝试（间隔 2.5s、6s）；
 * 3. 回复被输出上限截断（finish_reason=length 且内容为空）时，自动提高上限并关掉
 *    思考重试一次——这是「第二轮发出去没反应」的典型成因（思考吃光了 max_tokens）。
 */
class AiClient {

    data class ToolCall(val id: String, val name: String, val args: JSONObject)

    data class Reply(
        val content: String,
        val toolCalls: List<ToolCall>,
        val error: String,
        val finishReason: String = ""
    ) {
        val ok: Boolean get() = error.isEmpty()
        val hasToolCalls: Boolean get() = toolCalls.isNotEmpty()

        /** 既没说话也没调工具：对用户等于"什么都没发生"，必须显式处理。 */
        val isEmptyReply: Boolean get() = ok && content.isBlank() && !hasToolCalls
    }

    fun chat(provider: AiProvider, messages: JSONArray, tools: JSONArray? = null): Reply {
        val url = provider.chatUrl
        if (url.isEmpty()) return Reply("", emptyList(), "没有配置服务地址，请到设置里填写")
        if (provider.apiKey.isEmpty()) return Reply("", emptyList(), "没有配置 API Key，请到设置里填写")

        var lastError = ""
        var withThinking = true
        var attempt = 0
        var boosted = false
        while (attempt < MAX_ATTEMPTS) {
            val body = buildBody(
                provider, messages, tools,
                withThinking = withThinking,
                maxTokens = if (boosted) BOOSTED_MAX_TOKENS else MAX_TOKENS
            )
            val resp = AiHttp.postJson(url, body.toString(), authHeaders(provider))
            if (resp.ok) {
                val reply = parseReply(resp.body)
                // 被输出上限截断且内容为空：多半是思考吃光了预算，升上限 + 关思考重试一次
                if (reply.isEmptyReply && reply.finishReason == "length" && !boosted) {
                    boosted = true
                    withThinking = false
                    continue
                }
                return reply
            }
            lastError = describeError(resp)

            // 服务不认思考参数：剥掉重发一次（不消耗重试次数，也不打扰用户）
            if (withThinking && isThinkingRejected(resp)) {
                withThinking = false
                continue
            }
            if (!retryable(resp) || attempt >= MAX_ATTEMPTS - 1) break
            try {
                Thread.sleep(if (attempt == 0) 2500L else 6000L)
            } catch (t: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
            attempt++
        }
        return Reply("", emptyList(), lastError)
    }

    /** 列出可用模型（部分服务不支持 /models，失败返回空表，不报错）。 */
    fun listModels(provider: AiProvider): List<String> {
        val url = provider.modelsUrl
        if (url.isEmpty() || provider.apiKey.isEmpty()) return emptyList()
        val resp = AiHttp.get(url, authHeaders(provider), 15000)
        if (!resp.ok) return emptyList()
        return try {
            val data = JSONObject(resp.body).optJSONArray("data") ?: return emptyList()
            val out = ArrayList<String>(data.length())
            for (i in 0 until data.length()) {
                val id = data.optJSONObject(i)?.optString("id", "") ?: ""
                if (id.isNotEmpty()) out.add(id)
            }
            out
        } catch (t: Throwable) {
            emptyList()
        }
    }

    /** 连通性测试：发一条极短对话，返回 null 表示成功，否则返回原因（含 HTTP 码与返回体）。 */
    fun ping(provider: AiProvider): String? {
        val messages = JSONArray().put(JSONObject().apply {
            put("role", "user")
            put("content", "ping")
        })
        val r = chat(provider, messages)
        return when {
            r.error.isNotEmpty() -> r.error
            r.isEmptyReply -> "服务返回了空内容（finish=${r.finishReason}）"
            else -> null
        }
    }

    // ---- 请求构造 ----

    private fun buildBody(
        provider: AiProvider,
        messages: JSONArray,
        tools: JSONArray?,
        withThinking: Boolean,
        maxTokens: Int
    ): JSONObject {
        val body = JSONObject().apply {
            put("model", provider.model)
            put("messages", messages)
            put("temperature", 0.3)
            put("max_tokens", maxTokens)
            put("stream", false)
            if (tools != null && tools.length() > 0) {
                put("tools", tools)
                put("tool_choice", "auto")
            }
        }
        if (withThinking) applyThinking(body, provider)
        return body
    }

    /**
     * 思考强度注入（默认最低档——新模型普遍无法真正关闭）。
     *
     * 各服务方言不同，按域名分发；未知域名按 OpenAI 通用方言 `reasoning_effort`
     * 先试一次，不认由 [isThinkingRejected] 回落。
     */
    private fun applyThinking(body: JSONObject, p: AiProvider) {
        val host = hostOf(p.baseUrl)
        when {
            // 火山方舟：thinking 三态；最低档用 reasoning_effort=minimal
            host.contains("volces.com") -> when (p.thinking) {
                AiProvider.THINK_OFF -> body.put("thinking", JSONObject().put("type", "disabled"))
                AiProvider.THINK_LOW -> body.put("reasoning_effort", "minimal")
                else -> Unit
            }

            // 百炼 / 硅基流动（Qwen 系）：enable_thinking + 预算
            host.contains("aliyuncs.com") || host.contains("siliconflow") -> when (p.thinking) {
                AiProvider.THINK_OFF -> body.put("enable_thinking", false)
                AiProvider.THINK_LOW -> {
                    body.put("enable_thinking", true)
                    body.put("thinking_budget", 512)
                }
                else -> Unit
            }

            // Gemini：3.x 不能关，只能降 effort
            host.contains("googleapis.com") -> when (p.thinking) {
                AiProvider.THINK_OFF -> body.put("reasoning_effort", "none")
                AiProvider.THINK_LOW -> body.put("reasoning_effort", "low")
                else -> Unit
            }

            // 自定义/未知服务：按通用方言试最低档
            else -> when (p.thinking) {
                AiProvider.THINK_LOW -> body.put("reasoning_effort", "low")
                AiProvider.THINK_OFF -> body.put("reasoning_effort", "none")
                else -> Unit
            }
        }
    }

    private fun hostOf(url: String): String = try {
        java.net.URI(url.trim()).host?.lowercase() ?: ""
    } catch (t: Throwable) {
        ""
    }

    // ---- 响应解析 ----

    private fun parseReply(body: String): Reply {
        return try {
            val root = JSONObject(body)
            val choices = root.optJSONArray("choices")
            if (choices == null || choices.length() == 0) {
                return Reply("", emptyList(), "服务返回了空结果：${body.take(200)}")
            }
            val choice = choices.optJSONObject(0)
            val finish = choice?.optString("finish_reason", "") ?: ""
            val msg = choice?.optJSONObject("message")
                ?: return Reply("", emptyList(), "服务返回格式不认识：${body.take(200)}")
            val content = msg.optString("content", "")
            val calls = ArrayList<ToolCall>()
            val tcs = msg.optJSONArray("tool_calls")
            if (tcs != null) {
                for (i in 0 until tcs.length()) {
                    val tc = tcs.optJSONObject(i) ?: continue
                    val fn = tc.optJSONObject("function") ?: continue
                    val name = fn.optString("name")
                    if (name.isEmpty()) continue
                    calls.add(
                        ToolCall(
                            id = tc.optString("id", "call_$i"),
                            name = name,
                            args = parseArgs(fn.optString("arguments", "{}"))
                        )
                    )
                }
            }
            Reply(content, calls, "", finish)
        } catch (t: Throwable) {
            Reply("", emptyList(), "解析响应失败：${t.message}")
        }
    }

    private fun authHeaders(p: AiProvider): Map<String, String> =
        mapOf("Authorization" to "Bearer ${p.apiKey}")

    private fun parseArgs(raw: String): JSONObject = try {
        if (raw.isBlank()) JSONObject() else JSONObject(raw)
    } catch (t: Throwable) {
        JSONObject()
    }

    /** 该服务是否明确拒绝了思考参数（400/422 且报错里点名这些字段）。 */
    private fun isThinkingRejected(resp: AiHttp.Response): Boolean {
        if (resp.code != 400 && resp.code != 422) return false
        val b = resp.body.lowercase()
        return b.contains("reasoning_effort") || b.contains("enable_thinking") ||
            b.contains("thinking_budget") || b.contains("\"thinking\"") ||
            b.contains("unknown parameter") || b.contains("invalid parameter") ||
            b.contains("unsupported") || b.contains("unrecognized")
    }

    /** 瞬时性失败：网络不可达、限流、服务端 5xx、以及国内厂商的排队文案。 */
    private fun retryable(resp: AiHttp.Response): Boolean {
        if (resp.code == -1 || resp.code == 429 || resp.code >= 500) return true
        val b = resp.body
        return b.contains("人数过多") || b.contains("繁忙") || b.contains("请稍后") ||
            b.contains("限流") || b.contains("并发") ||
            b.contains("rate limit", true) || b.contains("too many", true) ||
            b.contains("overloaded", true)
    }

    private fun describeError(resp: AiHttp.Response): String {
        if (resp.code == -1) return "网络不通：${resp.body.take(200)}"
        val detail = try {
            val o = JSONObject(resp.body)
            val msg = o.optJSONObject("error")?.optString("message", "")
            if (!msg.isNullOrEmpty()) msg else o.optString("message", "")
        } catch (t: Throwable) {
            resp.body
        }
        val head = when (resp.code) {
            401, 403 -> "鉴权失败（HTTP ${resp.code}）"
            404 -> "接口地址或模型名不存在（HTTP 404）"
            429 -> "限流/排队（HTTP 429）"
            else -> "HTTP ${resp.code}"
        }
        val bodyText = if (detail.isBlank()) resp.body else detail
        return if (bodyText.isBlank()) head else "$head：${bodyText.take(400)}"
    }

    private companion object {
        const val MAX_ATTEMPTS = 3
        const val MAX_TOKENS = 2000
        const val BOOSTED_MAX_TOKENS = 3000
    }
}
