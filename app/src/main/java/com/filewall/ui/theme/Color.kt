package com.filewall.ui.theme

import androidx.compose.ui.graphics.Color

// Dark palette — the vault's home turf, taken straight from the original screens.
val VaultNavy = Color(0xFF0E1626)
val VaultCard = Color(0xFF141E31)
val VaultCardRaised = Color(0xFF1A2537)
val VaultBorder = Color(0xFF2A3652)
val Lavender = Color(0xFFB4C5FF)
val OnLavender = Color(0xFF24136B)
val LavenderDim = Color(0xFF8E9BD4)
val TextPrimaryDark = Color(0xFFEDF1FA)
val TextSecondaryDark = Color(0xFFA9B4C9)

// Light palette — the "Light" option under Appearance.
val PaperWhite = Color(0xFFF6F7FC)
val PaperCard = Color(0xFFFFFFFF)
val PaperBorder = Color(0xFFDDE2EF)
val DeepPurple = Color(0xFF4A2FA8)
val OnDeepPurple = Color(0xFFFFFFFF)
val TextPrimaryLight = Color(0xFF141A28)
val TextSecondaryLight = Color(0xFF525C70)

// Shared accents.
val ViewerPurple = Color(0xFF4A1391)
val DangerPink = Color(0xFFF2B8B5)
val DangerContainer = Color(0xFFF9DEDC)
val OnDangerContainer = Color(0xFF601410)

// Storage breakdown legend.
val PhotoGreen = Color(0xFF4CAF50)
val VideoBlue = Color(0xFF2196F3)
val DocAmber = Color(0xFFFFC107)
val OtherGrey = Color(0xFF8794AD)

/**
 * Folder tints.
 *
 * Only the base hue is stored on the folder row; the card fill and its outline are derived
 * from it at draw time, so the same index reads correctly on both the navy and paper themes.
 */
object FolderPalette {
    val colors = listOf(
        Color(0xFF8B7BE8), // violet — "Food" in the original
        Color(0xFF35B3A0), // teal   — "Google by A…" in the original
        Color(0xFF5B9CF8), // blue
        Color(0xFF57C271), // green
        Color(0xFFE9B44C), // amber
        Color(0xFFE8748C), // rose
        Color(0xFF6C7BE8), // indigo
        Color(0xFF8794AD), // slate
    )

    fun base(index: Int): Color = colors[index.mod(colors.size)]

    fun container(index: Int): Color = base(index).copy(alpha = 0.18f)

    fun outline(index: Int): Color = base(index).copy(alpha = 0.85f)
}
