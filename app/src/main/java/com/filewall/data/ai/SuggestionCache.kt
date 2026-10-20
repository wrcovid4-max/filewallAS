package com.filewall.data.ai

import android.content.Context

/** Suggested questions for a document, saved so they are written once per version of the file. */
object SuggestionCache {
    private const val PREFS = "ai_suggestions"

    fun get(context: Context, key: String): List<String>? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(key, null)
            ?.split("\n")
            ?.filter { it.isNotBlank() }

    fun put(context: Context, key: String, questions: List<String>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(key, questions.joinToString("\n"))
            .apply()
    }
}
