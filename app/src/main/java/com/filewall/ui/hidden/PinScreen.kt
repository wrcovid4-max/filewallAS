package com.filewall.ui.hidden

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Backspace
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.filewall.R
import com.filewall.data.crypto.PinManager
import com.filewall.ui.common.stringResourceSafe
import kotlinx.coroutines.delay

/** Which step of the passcode flow the pad is showing. */
enum class PinStage { VERIFY, SET, CONFIRM }

/**
 * The "Unlock Hidden Vault" pad.
 *
 * Doubles as the enrolment flow: with no passcode set yet it walks SET -> CONFIRM before
 * ever unlocking, so the hidden side can never end up open but unprotected.
 */
@Composable
fun PinScreen(
    stage: PinStage,
    errorMessage: String?,
    biometricAvailable: Boolean,
    onEntryComplete: (String) -> Unit,
    onBiometricRequested: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var entry by remember(stage) { mutableStateOf("") }

    // Clear the pad a beat after a rejected code so the shake-free reset is still visible.
    LaunchedEffect(errorMessage) {
        if (errorMessage != null) {
            delay(150)
            entry = ""
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Icons.Filled.Lock,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(48.dp),
        )
        Spacer(Modifier.height(20.dp))

        Text(
            text = stringResourceSafe(
                when (stage) {
                    PinStage.VERIFY -> R.string.unlock_hidden_vault
                    PinStage.SET -> R.string.set_passcode
                    PinStage.CONFIRM -> R.string.confirm_passcode
                },
            ),
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = stringResourceSafe(
                when (stage) {
                    PinStage.VERIFY -> R.string.unlock_subtitle
                    PinStage.SET -> R.string.set_passcode_subtitle
                    PinStage.CONFIRM -> R.string.confirm_passcode_subtitle
                },
            ),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(28.dp))
        PinDots(filled = entry.length, error = errorMessage != null)

        if (errorMessage != null) {
            Spacer(Modifier.height(14.dp))
            Text(
                text = errorMessage,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
            )
        }

        Spacer(Modifier.height(28.dp))
        PinPad(
            onDigit = { digit ->
                if (entry.length < PinManager.PIN_LENGTH) entry += digit
            },
            onBackspace = { entry = entry.dropLast(1) },
            onConfirm = {
                if (entry.length == PinManager.PIN_LENGTH) onEntryComplete(entry)
            },
            confirmEnabled = entry.length == PinManager.PIN_LENGTH,
        )

        if (stage == PinStage.VERIFY && biometricAvailable) {
            Spacer(Modifier.height(12.dp))
            TextButton(onClick = onBiometricRequested) {
                Icon(Icons.Filled.Fingerprint, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResourceSafe(R.string.use_biometrics))
            }
        }
    }
}

@Composable
private fun PinDots(filled: Int, error: Boolean, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        repeat(PinManager.PIN_LENGTH) { index ->
            val active = index < filled
            Box(
                Modifier
                    .size(if (active) 16.dp else 14.dp)
                    .clip(CircleShape)
                    .background(
                        when {
                            error -> MaterialTheme.colorScheme.error
                            active -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        },
                    ),
            )
        }
    }
}

@Composable
private fun PinPad(
    onDigit: (Char) -> Unit,
    onBackspace: () -> Unit,
    onConfirm: () -> Unit,
    confirmEnabled: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        listOf("123", "456", "789").forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                row.forEach { digit ->
                    PinKey(modifier = Modifier.weight(1f), onClick = { onDigit(digit) }) {
                        Text(
                            text = digit.toString(),
                            style = MaterialTheme.typography.headlineSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            PinKey(modifier = Modifier.weight(1f), onClick = onBackspace) {
                Icon(
                    Icons.Filled.Backspace,
                    contentDescription = "Backspace",
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
            PinKey(modifier = Modifier.weight(1f), onClick = { onDigit('0') }) {
                Text(
                    text = "0",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            PinKey(
                modifier = Modifier.weight(1f),
                onClick = onConfirm,
                enabled = confirmEnabled,
            ) {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = "Confirm",
                    tint = if (confirmEnabled) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                    },
                )
            }
        }
    }
}

@Composable
private fun PinKey(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .height(66.dp)
            .clip(RoundedCornerShape(33.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
        content = { content() },
    )
}
