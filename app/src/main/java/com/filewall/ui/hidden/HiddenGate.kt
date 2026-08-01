package com.filewall.ui.hidden

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import com.filewall.R
import com.filewall.data.crypto.PinManager
import com.filewall.util.Biometrics
import kotlinx.coroutines.delay

/**
 * Gate in front of the hidden archive: sets a passcode if there isn't one, otherwise
 * verifies, with biometrics offered (or required) according to the Security tab.
 */
@Composable
fun HiddenGate(
    pinManager: PinManager,
    biometricEnabled: Boolean,
    disablePasscodeFallback: Boolean,
    onUnlocked: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findFragmentActivity() }
    val biometricAvailable = remember(biometricEnabled) {
        biometricEnabled && activity != null && Biometrics.isAvailable(context)
    }

    var stage by remember { mutableStateOf(if (pinManager.isPinSet) PinStage.VERIFY else PinStage.SET) }
    var firstEntry by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var lockoutRemaining by remember { mutableStateOf(pinManager.lockoutRemainingMs()) }
    var biometricPrompted by remember { mutableStateOf(false) }

    val lockedOutText = stringResource(R.string.passcode_locked_out, (lockoutRemaining / 1000) + 1)
    val wrongText = stringResource(R.string.passcode_wrong)
    val mismatchText = stringResource(R.string.passcode_mismatch)
    val biometricTitle = stringResource(R.string.biometric_title)
    val biometricSubtitle = stringResource(R.string.biometric_subtitle)
    val biometricNegative = stringResource(R.string.biometric_negative)

    fun launchBiometricPrompt() {
        val host = activity ?: return
        Biometrics.prompt(
            activity = host,
            title = biometricTitle,
            subtitle = biometricSubtitle,
            negativeLabel = biometricNegative,
            onSuccess = onUnlocked,
            onFailure = { message -> error = message },
        )
    }

    // Offer the sensor straight away rather than making the user reach for it.
    LaunchedEffect(biometricAvailable, stage) {
        if (biometricAvailable && stage == PinStage.VERIFY && !biometricPrompted) {
            biometricPrompted = true
            launchBiometricPrompt()
        }
    }

    // Count the lockout down live so the message stays honest.
    LaunchedEffect(lockoutRemaining) {
        if (lockoutRemaining > 0) {
            delay(1_000)
            lockoutRemaining = pinManager.lockoutRemainingMs()
            if (lockoutRemaining == 0L) error = null
        }
    }

    val pinRequired = !(disablePasscodeFallback && biometricAvailable) || !pinManager.isPinSet

    if (!pinRequired) {
        BiometricOnlyGate(
            errorMessage = error,
            onRetry = { launchBiometricPrompt() },
            modifier = modifier,
        )
        return
    }

    PinScreen(
        stage = stage,
        errorMessage = if (lockoutRemaining > 0) lockedOutText else error,
        biometricAvailable = biometricAvailable,
        onBiometricRequested = { launchBiometricPrompt() },
        onEntryComplete = { entered ->
            when (stage) {
                PinStage.SET -> {
                    firstEntry = entered
                    error = null
                    stage = PinStage.CONFIRM
                }

                PinStage.CONFIRM -> {
                    if (entered == firstEntry) {
                        pinManager.setPin(entered)
                        onUnlocked()
                    } else {
                        firstEntry = null
                        error = mismatchText
                        stage = PinStage.SET
                    }
                }

                PinStage.VERIFY -> when (val result = pinManager.verify(entered)) {
                    is PinManager.Result.Success -> {
                        error = null
                        onUnlocked()
                    }

                    is PinManager.Result.Wrong -> error = wrongText

                    is PinManager.Result.LockedOut -> lockoutRemaining = result.remainingMs

                    is PinManager.Result.NotSet -> stage = PinStage.SET
                }
            }
        },
        modifier = modifier,
    )
}

/** Shown when the user has switched off the PIN fallback: biometrics or nothing. */
@Composable
private fun BiometricOnlyGate(
    errorMessage: String?,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.Filled.Fingerprint,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(64.dp),
        )
        Spacer(Modifier.height(20.dp))
        Text(
            text = stringResource(R.string.unlock_hidden_vault),
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = stringResource(R.string.disable_passcode_fallback_desc),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (errorMessage != null) {
            Spacer(Modifier.height(16.dp))
            Text(
                text = errorMessage,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
            )
        }
        Spacer(Modifier.height(28.dp))
        Button(onClick = onRetry) {
            Icon(Icons.Filled.Fingerprint, contentDescription = null)
            Spacer(Modifier.height(0.dp))
            Text(
                text = stringResource(R.string.use_biometrics),
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}

private fun android.content.Context.findFragmentActivity(): FragmentActivity? = when (this) {
    is FragmentActivity -> this
    is android.content.ContextWrapper -> baseContext.findFragmentActivity()
    else -> null
}
