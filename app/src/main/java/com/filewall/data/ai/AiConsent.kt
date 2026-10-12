package com.filewall.data.ai

import android.content.Context

/**
 * The one saved choice for Ask AI: "let Gemini see my files". When it is on, visible files are
 * not asked about each time. Hidden files are always asked, whatever this is set to.
 */
object AiConsent {
    private const val PREFS = "ai_consent"
    private const val ALL_FILES = "allow_all_files"

    fun allAllowed(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(ALL_FILES, false)

    fun setAllAllowed(context: Context, allowed: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(ALL_FILES, allowed)
            .apply()
    }
}
