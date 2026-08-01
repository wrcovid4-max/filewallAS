package com.filewall.util

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/**
 * Thin wrapper over [BiometricPrompt].
 *
 * Only strong (class 3) and weak (class 2) biometrics are accepted; device credential is
 * deliberately *not* offered as a fallback, because the app's own PIN is the fallback and
 * the screen-lock PIN unlocking a hidden archive would defeat the point.
 */
object Biometrics {

    // Not `const`: `or` is a function call, which Kotlin will not fold into a constant.
    private val ALLOWED =
        BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.BIOMETRIC_WEAK

    /** True when the device has enrolled biometrics we can actually prompt for. */
    fun isAvailable(context: Context): Boolean =
        BiometricManager.from(context).canAuthenticate(ALLOWED) == BiometricManager.BIOMETRIC_SUCCESS

    fun prompt(
        activity: FragmentActivity,
        title: String,
        subtitle: String,
        negativeLabel: String,
        onSuccess: () -> Unit,
        onFailure: (String?) -> Unit,
    ) {
        val prompt = BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    onSuccess()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    // A cancel or a tap on the negative button is a choice, not a failure.
                    val silent = errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON ||
                        errorCode == BiometricPrompt.ERROR_USER_CANCELED ||
                        errorCode == BiometricPrompt.ERROR_CANCELED
                    onFailure(if (silent) null else errString.toString())
                }
            },
        )

        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle(title)
                .setSubtitle(subtitle)
                .setNegativeButtonText(negativeLabel)
                .setAllowedAuthenticators(ALLOWED)
                .setConfirmationRequired(false)
                .build(),
        )
    }
}
