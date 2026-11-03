package com.leicamoments.app

import org.json.JSONArray
import org.json.JSONObject

/**
 * OpenAI 兼容的 chat/completions 客户端（含 tools / function calling）。
 *
 * 只做两件事：发一轮对话、解析回包。**不持有会话状态**，状态全在 [AiSession]。
 * 任何异常都折叠成 [Reply.error]，调用方据此提示用户，不抛异常。
 *
 * 免费模型常在高峰期返回「人数过多」/429/5xx——这类是瞬时的，这里做最多 3 次尝试
 * （间隔 2.5s、6s），比让用户自己反复点更可靠。
 */
class AiClient {

    data class ToolCall(val id: String, val name: String, val args: JSONObject)

    data class Reply(
        val content: String,
        val toolCalls: List<ToolCall>,
        val error: String
    ) {
        val ok: Boolean get() = error.isEmpty()
        val hasToolCalls: Boolean get() = toolCalls.isNotEmpty()
    }

    fun chat(provider: AiProvider, messages: JSONArray, tools: JSONArray? = null): Reply {
        val url = provider.chatUrl
        if (url.isEmpty()) return Reply("", emptyList(), "没有配置服务地址，请到设置里填写")
        if (provider.apiKey.isEmpty()) return Reply("", emptyList(), "没有配置 API Key，请到设置里填写")

        val body = JSONObject().apply {
            put("model", provider.model)
            put("messages", messages)
            put("temperature", 0.3)
            put("max_tokens", 1200)
            put("stream", false)
            if (tools != null && tools.length() > 0) {
                put("tools", tools)
                put("tool_choice", "auto")
            }
        }

        var lastError = ""
        for (attempt in 0 until MAX_ATTEMPTS) {
            val resp = AiHttp.postJson(url, body.toString(), authHeaders(provider))
            if (resp.ok) return parseReply(resp.body)
            lastError = describeError(resp)
            if (!retryable(resp) || attempt >= MAX_ATTEMPTS - 1) break
            try {
                Thread.sleep(if (attempt == 0) 2500L else 6000L)
            } catch (t: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
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
            else -> null
        }
    }

    // ---- 内部 ----

    private fun parseReply(body: String): Reply {
        return try {
            val root = JSONObject(body)
            val choices = root.optJSONArray("choices")
            if (choices == null || choices.length() == 0) {
                return Reply("", emptyList(), "服务返回了空结果：${body.take(200)}")
            }
            val msg = choices.optJSONObject(0)?.optJSONObject("message")
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
            Reply(content, calls, "")
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
            o.optJSONObject("error")?.optString("message", "")
                ?.takeIf { it.isNotEmpty() }
                ?: o.optString("message", "")
                ?: resp.body
        } catch (t: Throwable) {
            resp.body
        }
        val head = when (resp.code) {
            401, 403 -> "鉴权失败（HTTP ${resp.code}）"
            404 -> "接口地址或模型名不存在（HTTP 404）"
            429 -> "限流/排队（HTTP 429）"
            else -> "HTTP ${resp.code}"
        }
        val body = if (detail.isBlank()) resp.body else detail
        return if (body.isBlank()) head else "$head：${body.take(400)}"
    }

    private companion object {
        const val MAX_ATTEMPTS = 3
    }
}
