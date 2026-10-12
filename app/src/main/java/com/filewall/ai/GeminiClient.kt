package com.filewall.ai

import com.filewall.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Talks to Google Gemini for FileWall's Ask AI. The key comes from local.properties at build
 * time, the same way Cash Memer gets its key. Nothing is stored on Google's side by this app.
 */
object GeminiClient {

    /** One message in the chat. */
    data class Turn(val fromUser: Boolean, val text: String)

    private const val SYSTEM =
        "You help the user with their own files. When context is given, answer from it and say " +
            "clearly when the answer is not in it. Keep answers short and plain."

    private val MODELS = listOf("gemini-3.8-flash", "gemini-3.6-flash")

    /** Sends the question with the context and recent chat history. Failures come back as Result. */
    suspend fun ask(question: String, context: String, history: List<Turn>): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val key = BuildConfig.GEMINI_API_KEY
                check(key.isNotBlank()) { "GEMINI_API_KEY is missing from local.properties" }
                val body = buildBody(question, context, history).toString()
                var lastError: Exception? = null
                for (model in MODELS) {
                    try {
                        return@runCatching post(model, key, body)
                    } catch (e: Exception) {
                        lastError = e
                    }
                }
                throw lastError ?: IllegalStateException("No answer from Gemini")
            }
        }

    private fun buildBody(question: String, context: String, history: List<Turn>): JSONObject {
        val contents = JSONArray()
        history.forEach { turn ->
            contents.put(
                JSONObject()
                    .put("role", if (turn.fromUser) "user" else "model")
                    .put("parts", JSONArray().put(JSONObject().put("text", turn.text))),
            )
        }
        val prompt = if (context.isBlank()) question else "Context:\n$context\n\nQuestion: $question"
        contents.put(
            JSONObject()
                .put("role", "user")
                .put("parts", JSONArray().put(JSONObject().put("text", prompt))),
        )
        return JSONObject()
            .put("system_instruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", SYSTEM))))
            .put("contents", contents)
    }

    private fun post(model: String, key: String, body: String): String {
        val url = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15_000
            conn.readTimeout = 60_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("x-goog-api-key", key)
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val raw = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw IllegalStateException("HTTP $code from $model: ${raw.take(160)}")
            return JSONObject(raw)
                .getJSONArray("candidates")
                .getJSONObject(0)
                .getJSONObject("content")
                .getJSONArray("parts")
                .getJSONObject(0)
                .getString("text")
                .trim()
        } finally {
            conn.disconnect()
        }
    }
}
