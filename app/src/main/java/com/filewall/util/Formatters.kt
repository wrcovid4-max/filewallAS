package com.filewall.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Byte sizes exactly as the original renders them: "269.4 MB", "131.0 KB", "97.4 KB".
 * Base 1024, one decimal place, and a bare "0 B" for empty.
 */
fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unit = 0
    while (value >= 1024.0 && unit < units.lastIndex) {
        value /= 1024.0
        unit++
    }
    return if (unit == 0) {
        "${value.toLong()} ${units[unit]}"
    } else {
        String.format(Locale.US, "%.1f %s", value, units[unit])
    }
}

/** "Jul 30, 2026 13:08" — the format used on the Item Details sheet. */
fun formatTimestamp(millis: Long): String =
    SimpleDateFormat("MMM d, yyyy HH:mm", Locale.getDefault()).format(Date(millis))

/** Human label for the auto-lock hint, e.g. "30 seconds", "5 minutes". */
fun formatDuration(seconds: Int): String = when {
    seconds < 60 -> "$seconds seconds"
    seconds == 60 -> "1 minute"
    else -> "${seconds / 60} minutes"
}
