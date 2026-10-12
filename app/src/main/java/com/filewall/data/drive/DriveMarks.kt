package com.filewall.data.drive

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Which files were sent to the Drive folder, so the vault can show the Drive mark on them. */
object DriveMarks {
    private const val PREFS = "drive_marks"
    private const val KEY = "ids"

    private val state = MutableStateFlow<Set<String>>(emptySet())
    val ids: StateFlow<Set<String>> = state.asStateFlow()
    private var loaded = false

    fun load(context: Context) {
        if (loaded) return
        loaded = true
        val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet(KEY, emptySet())
            .orEmpty()
        state.value = saved.toSet()
    }

    fun add(context: Context, id: String) {
        load(context)
        val next = state.value + id
        state.value = next
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putStringSet(KEY, HashSet(next))
            .apply()
    }
}
