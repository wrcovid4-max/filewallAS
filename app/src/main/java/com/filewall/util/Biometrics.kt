package com.filewall.util

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/** Why biometric unlock is or isn't offerable, so the UI can say something useful. */
enum class BiometricStatus {
    /** Hardware present, something enrolled, ready to prompt. */
    AVAILABLE,

    /** Sensor exists but the user has not enrolled a fingerprint or face. */
    NONE_ENROLLED,

    /** No biometric hardware on this device at all. */
    NO_HARDWARE,

    /** Present but temporarily unusable — sensor busy, or locked out after failures. */
    UNAVAILABLE,
}

/**
 * Thin wrapper over [BiometricPrompt].
 *
 * Only class 2 / class 3 biometrics are accepted; device credential is deliberately *not*
 * offered as a fallback, because the app's own PIN is the fallback and the screen-lock PIN
 * unlocking a hidden archive would defeat the point.
 */
object Biometrics {

    // Not `const`: `or` is a function call, which Kotlin will not fold into a constant.
    // BIOMETRIC_WEAK's bit range already covers BIOMETRIC_STRONG, so this accepts either.
    private val ALLOWED =
        BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.BIOMETRIC_WEAK

    /**
     * Distinguishes "this phone has no sensor" from "you haven't set up a fingerprint yet".
     *
     * The difference matters: one is permanent and one the user can fix in thirty seconds,
     * and a greyed-out switch that explains neither is just baffling.
     */
    fun status(context: Context): BiometricStatus =
        when (BiometricManager.from(context).canAuthenticate(ALLOWED)) {
            BiometricManager.BIOMETRIC_SUCCESS -> BiometricStatus.AVAILABLE
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> BiometricStatus.NONE_ENROLLED
            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> BiometricStatus.NO_HARDWARE
            else -> BiometricStatus.UNAVAILABLE
        }

    fun isAvailable(context: Context): Boolean = status(context) == BiometricStatus.AVAILABLE

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
