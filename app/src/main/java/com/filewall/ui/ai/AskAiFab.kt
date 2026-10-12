package com.filewall.ui.ai

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The Ask AI sphere: a round, shaded gradient button with a sparkle. Used on the home screen
 * and inside documents, so the two always look the same.
 */
@Composable
fun AskAiFab(onClick: () -> Unit, modifier: Modifier = Modifier, size: Dp = 56.dp) {
    Box(
        modifier
            .size(size)
            .shadow(10.dp, CircleShape)
            .clip(CircleShape)
            .background(
                Brush.radialGradient(
                    listOf(Color(0xFFF59BC8), Color(0xFFA24FE6), Color(0xFF4A1A9E)),
                ),
            )
            .clickable(onClickLabel = "Ask AI", role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Filled.AutoAwesome,
            contentDescription = "Ask AI",
            tint = Color.White,
            modifier = Modifier.size(size * 0.46f),
        )
    }
}
