package com.filewall.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.filewall.model.ThemeMode

private val DarkColors = darkColorScheme(
    primary = Lavender,
    onPrimary = OnLavender,
    primaryContainer = Lavender,
    onPrimaryContainer = OnLavender,
    secondary = LavenderDim,
    onSecondary = OnLavender,
    background = VaultNavy,
    onBackground = TextPrimaryDark,
    surface = VaultNavy,
    onSurface = TextPrimaryDark,
    surfaceVariant = VaultCard,
    onSurfaceVariant = TextSecondaryDark,
    surfaceContainer = VaultCard,
    surfaceContainerHigh = VaultCardRaised,
    surfaceContainerHighest = VaultCardRaised,
    outline = VaultBorder,
    outlineVariant = VaultBorder,
    error = DangerPink,
    onError = OnDangerContainer,
    errorContainer = DangerContainer,
    onErrorContainer = OnDangerContainer,
    scrim = Color(0xCC000000),
)

private val LightColors = lightColorScheme(
    primary = DeepPurple,
    onPrimary = OnDeepPurple,
    primaryContainer = Color(0xFFE3E1FF),
    onPrimaryContainer = Color(0xFF1B1147),
    secondary = Color(0xFF5C5A82),
    onSecondary = Color(0xFFFFFFFF),
    background = PaperWhite,
    onBackground = TextPrimaryLight,
    surface = PaperWhite,
    onSurface = TextPrimaryLight,
    surfaceVariant = PaperCard,
    onSurfaceVariant = TextSecondaryLight,
    surfaceContainer = PaperCard,
    surfaceContainerHigh = Color(0xFFF0F2F9),
    surfaceContainerHighest = Color(0xFFE9ECF5),
    outline = PaperBorder,
    outlineVariant = PaperBorder,
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
    errorContainer = DangerContainer,
    onErrorContainer = OnDangerContainer,
    scrim = Color(0x99000000),
)

@Composable
fun FileWallTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    content: @Composable () -> Unit,
) {
    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val colors = if (dark) DarkColors else LightColors

    val view = LocalView.current
    if (!view.isInEditMode) {
        val context = LocalContext.current
        SideEffect {
            (context as? Activity)?.window?.let { window ->
                // Status bar icons have to flip with the theme, not with the system setting.
                WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !dark
                WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = !dark
            }
        }
    }

    MaterialTheme(
        colorScheme = colors,
        typography = FileWallTypography,
        shapes = FileWallShapes,
        content = content,
    )
}
