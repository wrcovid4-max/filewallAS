package com.filewall.util

import android.content.Context
import android.os.Build
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/** Why biometric unlock is or isn't offerable, so the UI can say something useful. */
enum class BiometricStatus {
    /** Ready to prompt — a fingerprint/face is enrolled, or the device has a secure lock. */
    AVAILABLE,

    /** Sensor exists but nothing is enrolled and there's no device lock to fall back to. */
    NONE_ENROLLED,

    /** No biometric hardware and no secure lock screen at all. */
    NO_HARDWARE,

    /** Present but temporarily unusable — sensor busy, or locked out after failures. */
    UNAVAILABLE,
}

/**
 * Thin wrapper over [BiometricPrompt].
 *
 * Accepts fingerprint/face (class 2/3) and **falls back to the device PIN, pattern or
 * password** when no biometric is enrolled. That fallback is deliberate: without it the
 * feature is dead on any phone whose owner hasn't set up a fingerprint (and on most
 * emulators), which is exactly what "biometric doesn't work" turned out to be. The app's
 * own passcode still exists as the in-app fallback below all of this.
 */
object Biometrics {

    // Not `const`: `or` is a function call. BIOMETRIC_WEAK covers STRONG too.
    private val BIOMETRIC_ONLY =
        BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.BIOMETRIC_WEAK

    private val BIOMETRIC_OR_CREDENTIAL =
        BIOMETRIC_ONLY or BiometricManager.Authenticators.DEVICE_CREDENTIAL

    /** True when a fingerprint or face is actually enrolled and usable right now. */
    private fun canUseBiometric(context: Context): Boolean =
        BiometricManager.from(context).canAuthenticate(BIOMETRIC_ONLY) ==
            BiometricManager.BIOMETRIC_SUCCESS

    /**
     * Available if we can prompt for *anything* the user has set up — biometric, or the
     * device lock as a fallback. On API 30+ that's a single query; below it, check the two
     * separately since combining the constants isn't supported there.
     */
    fun status(context: Context): BiometricStatus {
        val manager = BiometricManager.from(context)
        val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            manager.canAuthenticate(BIOMETRIC_OR_CREDENTIAL)
        } else {
            val bio = manager.canAuthenticate(BIOMETRIC_ONLY)
            if (bio == BiometricManager.BIOMETRIC_SUCCESS) bio else manager.canAuthenticate(BIOMETRIC_ONLY)
        }
        return when (result) {
            BiometricManager.BIOMETRIC_SUCCESS -> BiometricStatus.AVAILABLE
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> BiometricStatus.NONE_ENROLLED
            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE,
            BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE,
            -> BiometricStatus.NO_HARDWARE
            else -> BiometricStatus.UNAVAILABLE
        }
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

        val builder = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setConfirmationRequired(false)

        val useCredentialFallback = !canUseBiometric(activity)

        when {
            // Nothing enrolled but a device lock exists: allow biometric-or-credential.
            // A negative button is not permitted alongside DEVICE_CREDENTIAL — the user
            // cancels with Back instead.
            useCredentialFallback && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ->
                builder.setAllowedAuthenticators(BIOMETRIC_OR_CREDENTIAL)

            useCredentialFallback -> {
                @Suppress("DEPRECATION")
                builder.setDeviceCredentialAllowed(true)
            }

            else -> {
                builder.setAllowedAuthenticators(BIOMETRIC_ONLY)
                builder.setNegativeButtonText(negativeLabel)
            }
        }

        prompt.authenticate(builder.build())
    }
}
