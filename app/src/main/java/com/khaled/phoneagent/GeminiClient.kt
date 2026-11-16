package com.khaled.phoneagent

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Minimal client for the Google Gemini API (free tier available). No extra libraries needed. */
object GeminiClient {
    private const val BASE = "https://generativelanguage.googleapis.com/v1beta/models/"

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
        contents: JSONArray,
        tools: JSONArray
    ): JSONObject {
        val body = JSONObject()
            .put(
                "systemInstruction",
                JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system)))
            )
            .put("contents", contents)
            .put("tools", tools)
            .put(
                "toolConfig",
                JSONObject().put("functionCallingConfig", JSONObject().put("mode", "AUTO"))
            )

        val conn = URL("$BASE$model:generateContent").openConnection() as HttpURLConnection
        connection = conn
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15_000
            conn.readTimeout = 60_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("x-goog-api-key", apiKey)
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
