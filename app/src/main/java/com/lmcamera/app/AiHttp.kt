package com.lmcamera.app

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

/**
 * 极简 HTTP 封装（HttpURLConnection）。
 *
 * 工程原本没有任何网络能力（依赖表里连 OkHttp 都没有），AI 功能只需要
 * 「一次性 JSON 往返」（对话补全 + 模型列表拉取），用系统自带的 HttpURLConnection 即可，
 * 不引入新依赖，也就不会给构建链增加拉包风险。
 */
internal object AiHttp {

    const val UA = "L-Moment/1.5.1 (Android)"

    data class Response(val code: Int, val body: String) {
        val ok: Boolean get() = code in 200..299
    }

    fun get(
        url: String,
        headers: Map<String, String> = emptyMap(),
        timeoutMs: Int = 15000
    ): Response = request("GET", url, null, headers, timeoutMs)

    fun postJson(
        url: String,
        json: String,
        headers: Map<String, String> = emptyMap(),
        timeoutMs: Int = 90000
    ): Response = request(
        "POST", url, json.toByteArray(Charsets.UTF_8),
        headers + mapOf("Content-Type" to "application/json; charset=utf-8"), timeoutMs
    )

    private fun request(
        method: String,
        url: String,
        body: ByteArray?,
        headers: Map<String, String>,
        timeoutMs: Int
    ): Response {
        var conn: HttpURLConnection? = null
        return try {
            conn = open(method, url, headers, timeoutMs)
            if (body != null) {
                conn.doOutput = true
                conn.setFixedLengthStreamingMode(body.size)
                conn.outputStream.use { it.write(body) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = try {
                stream?.let { readAll(wrap(conn, it)) } ?: ""
            } catch (t: Throwable) {
                ""
            }
            Response(code, text)
        } catch (t: Throwable) {
            Response(-1, t.message ?: t.javaClass.simpleName)
        } finally {
            conn?.disconnect()
        }
    }

    private fun open(
        method: String,
        url: String,
        headers: Map<String, String>,
        timeoutMs: Int
    ): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = 15000
        conn.readTimeout = timeoutMs
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", UA)
        conn.setRequestProperty("Accept", "application/json, image/*, */*")
        conn.setRequestProperty("Accept-Encoding", "gzip")
        headers.forEach { (k, v) -> if (v.isNotEmpty()) conn.setRequestProperty(k, v) }
        return conn
    }

    private fun wrap(conn: HttpURLConnection, stream: InputStream? = null): InputStream {
        val raw = stream ?: conn.inputStream
        val enc = conn.contentEncoding ?: ""
        return if (enc.contains("gzip", ignoreCase = true)) GZIPInputStream(raw) else raw
    }

    private fun readAll(input: InputStream): String {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n <= 0) break
            out.write(buf, 0, n)
        }
        input.close()
        return String(out.toByteArray(), Charsets.UTF_8)
    }
}
