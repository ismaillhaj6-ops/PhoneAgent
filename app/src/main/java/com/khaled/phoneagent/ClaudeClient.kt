package com.khaled.phoneagent

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Minimal client for the Anthropic Messages API (no extra libraries needed). */
object ClaudeClient {
    private const val ENDPOINT = "https://api.anthropic.com/v1/messages"

    @Volatile
    private var connection: HttpURLConnection? = null

    /** Used by the emergency stop button: kills the request that is in flight. */
    fun cancel() {
        try {
            connection?.disconnect()
        } catch (_: Exception) {
        }
    }

    /** Blocking call. Run it on Dispatchers.IO. */
    @Throws(IOException::class)
    fun send(
        apiKey: String,
        model: String,
        system: String,
        messages: JSONArray,
        tools: JSONArray
    ): JSONObject {
        val body = JSONObject()
            .put("model", model)
            .put("max_tokens", 1024)
            .put("system", system)
            .put("messages", messages)
            .put("tools", tools)
            // One tool at a time keeps the confirmation flow simple and safe.
            .put("tool_choice", JSONObject().put("type", "auto").put("disable_parallel_tool_use", true))

        val conn = URL(ENDPOINT).openConnection() as HttpURLConnection
        connection = conn
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15_000
            conn.readTimeout = 60_000
            conn.doOutput = true
            conn.setRequestProperty("content-type", "application/json")
            conn.setRequestProperty("x-api-key", apiKey)
            conn.setRequestProperty("anthropic-version", "2023-06-01")
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (code !in 200..299) throw IOException("API error $code: ${extractError(text)}")
            return JSONObject(text)
        } finally {
            conn.disconnect()
            if (connection === conn) connection = null
        }
    }

    private fun extractError(text: String): String = try {
        JSONObject(text).getJSONObject("error").getString("message")
    } catch (_: Exception) {
        text.take(300)
    }
}
