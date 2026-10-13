package com.filewall.data.ai

import android.content.Context
import com.filewall.ai.GeminiClient
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** A saved Ask AI conversation. Kept only in the app's private folder on this phone. */
data class SavedChat(
    val id: String,
    val title: String,
    val subtitle: String,
    val updatedAt: Long,
    val turns: List<GeminiClient.Turn>,
)

/** Recent chats, as in Cash Memer. Newest first, capped at 50. Never written for hidden files. */
object ChatHistory {
    private const val FILE = "ai_chats.json"
    private const val MAX_CHATS = 50

    private fun file(context: Context) = File(context.filesDir, FILE)

    @Synchronized
    fun all(context: Context): List<SavedChat> {
        val f = file(context)
        if (!f.exists()) return emptyList()
        return runCatching {
            val array = JSONArray(f.readText())
            List(array.length()) { i -> fromJson(array.getJSONObject(i)) }
        }.getOrDefault(emptyList()).sortedByDescending { it.updatedAt }
    }

    @Synchronized
    fun save(context: Context, chat: SavedChat) {
        val kept = (all(context).filterNot { it.id == chat.id } + chat)
            .sortedByDescending { it.updatedAt }
            .take(MAX_CHATS)
        write(context, kept)
    }

    @Synchronized
    fun delete(context: Context, id: String) {
        write(context, all(context).filterNot { it.id == id })
    }

    private fun write(context: Context, chats: List<SavedChat>) {
        val array = JSONArray()
        chats.forEach { array.put(toJson(it)) }
        file(context).writeText(array.toString())
    }

    private fun toJson(chat: SavedChat): JSONObject {
        val turns = JSONArray()
        chat.turns.forEach { turn ->
            turns.put(JSONObject().put("fromUser", turn.fromUser).put("text", turn.text).apply { turn.page?.let { put("page", it) } })
        }
        return JSONObject()
            .put("id", chat.id)
            .put("title", chat.title)
            .put("subtitle", chat.subtitle)
            .put("updatedAt", chat.updatedAt)
            .put("turns", turns)
    }

    private fun fromJson(o: JSONObject): SavedChat {
        val turns = o.getJSONArray("turns")
        return SavedChat(
            id = o.getString("id"),
            title = o.getString("title"),
            subtitle = o.getString("subtitle"),
            updatedAt = o.getLong("updatedAt"),
            turns = List(turns.length()) { i ->
                val t = turns.getJSONObject(i)
                GeminiClient.Turn(t.getBoolean("fromUser"), t.getString("text"), if (t.has("page")) t.getInt("page") else null)
            },
        )
    }
}
