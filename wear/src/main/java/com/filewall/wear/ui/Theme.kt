package com.filewall.wear.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.wear.compose.material.Colors
import androidx.wear.compose.material.MaterialTheme

/** The phone's navy-and-lavender palette, adapted for an always-dark watch face. */
private val WearColors = Colors(
    primary = Color(0xFFB4C5FF),
    primaryVariant = Color(0xFF8E9BD4),
    secondary = Color(0xFF35B3A0),
    secondaryVariant = Color(0xFF1F7F72),
    background = Color(0xFF000000),
    surface = Color(0xFF16203A),
    error = Color(0xFFF2B8B5),
    onPrimary = Color(0xFF24136B),
    onSecondary = Color(0xFF00201C),
    onBackground = Color(0xFFEDF1FA),
    onSurface = Color(0xFFEDF1FA),
    onSurfaceVariant = Color(0xFFA9B4C9),
    onError = Color(0xFF601410),
)

@Composable
fun FileWallWearTheme(content: @Composable () -> Unit) {
    MaterialTheme(colors = WearColors, content = content)
}
