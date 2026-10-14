package com.filewall.data.ai

import android.content.Context

/** Three questions written for one document, saved so Gemini writes them only once per document. */
object DocSuggestions {
    private const val PREFS = "doc_suggestions"

    fun load(context: Context, docId: String): List<String>? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(docId, null)
            ?.split('\n')
            ?.filter { it.isNotBlank() }
            ?.takeIf { it.size == 3 }

    fun save(context: Context, docId: String, questions: List<String>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(docId, questions.joinToString("\n"))
            .apply()
    }
}
