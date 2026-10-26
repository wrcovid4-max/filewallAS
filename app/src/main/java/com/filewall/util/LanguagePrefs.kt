package com.filewall.util

import android.content.Context

/**
 * The language picked in the 🌐 menu, kept in plain SharedPreferences as well as the settings
 * store. Picking a language restarts the screen, which can cancel a write that is still running
 * in the background; this one is committed on the spot, so the choice is never lost.
 * An empty tag means "follow the phone's language".
 */
object LanguagePrefs {
    private const val FILE = "filewall_language"
    private const val KEY = "tag"

    fun save(context: Context, tag: String) {
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putString(KEY, tag).commit()
    }

    /** The saved tag, or null if the user has never picked one. */
    fun read(context: Context): String? =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getString(KEY, null)
}
