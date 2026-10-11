package com.filewall.ui.theme

import androidx.compose.ui.graphics.Color

// FileWall palette: a neutral base with one blue accent, after the Drive and iCloud look.
// Names are kept from the earlier theme so every screen picks up the new colours.

// Dark surfaces.
val VaultNavy = Color(0xFF111317)           // app background
val VaultCard = Color(0xFF1C2026)           // cards and bars
val VaultCardRaised = Color(0xFF262B33)     // raised cards, menus
val VaultBorder = Color(0xFF363C46)         // hairline borders
val Lavender = Color(0xFF8AB4F8)            // accent (dark)
val OnLavender = Color(0xFF0B2F66)
val LavenderDim = Color(0xFFA8C7FA)
val TextPrimaryDark = Color(0xFFE8EAED)
val TextSecondaryDark = Color(0xFF9AA0A6)

// Light surfaces.
val PaperWhite = Color(0xFFF8F9FC)          // app background
val PaperCard = Color(0xFFFFFFFF)           // cards and bars
val PaperBorder = Color(0xFFDADCE0)         // hairline borders
val DeepPurple = Color(0xFF1A73E8)          // accent (light)
val OnDeepPurple = Color(0xFFFFFFFF)
val TextPrimaryLight = Color(0xFF1F1F1F)
val TextSecondaryLight = Color(0xFF5F6368)

// Viewer and danger.
val ViewerPurple = Color(0xFF101216)        // viewer stage background (neutral, not purple now)
val DangerPink = Color(0xFFF28B82)
val DangerContainer = Color(0xFFFAD2CF)
val OnDangerContainer = Color(0xFF5C1A14)

// Storage breakdown legend.
val PhotoGreen = Color(0xFF34A853)
val VideoBlue = Color(0xFF4285F4)
val DocAmber = Color(0xFFFBBC04)
val OtherGrey = Color(0xFF9AA0A6)

/**
 * Folder tints. Only the base hue is stored on the folder row; the card fill and its outline
 * are derived from it at draw time, so the same index reads on both themes.
 */
object FolderPalette {
    val colors = listOf(
        Color(0xFF4285F4), // blue
        Color(0xFF34A853), // green
        Color(0xFFFBBC04), // amber
        Color(0xFFEA4335), // red
        Color(0xFF9334E6), // purple
        Color(0xFF12B5CB), // teal
        Color(0xFFE8710A), // orange
        Color(0xFF5F6368), // graphite
    )

    fun base(index: Int): Color = colors[index.mod(colors.size)]

    fun container(index: Int): Color = base(index).copy(alpha = 0.16f)

    fun outline(index: Int): Color = base(index).copy(alpha = 0.75f)
}
