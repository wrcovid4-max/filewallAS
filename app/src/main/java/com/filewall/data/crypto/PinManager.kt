package com.filewall.data.crypto

import android.content.Context
import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Stores the hidden-archive passcode as a salted PBKDF2 digest and rate-limits guessing.
 *
 * The PIN never unlocks the file encryption keys — those live in the Keystore and are
 * gated by the OS. The PIN gates *visibility* of hidden items, which is what the
 * screenshots' "Unlock Hidden Vault" pad is for.
 */
class PinManager(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    val isPinSet: Boolean
        get() = prefs.contains(KEY_HASH) && prefs.contains(KEY_SALT)

    /** Milliseconds still to wait before another attempt is accepted, or 0. */
    fun lockoutRemainingMs(now: Long = System.currentTimeMillis()): Long =
        (prefs.getLong(KEY_LOCKOUT_UNTIL, 0L) - now).coerceAtLeast(0L)

    fun setPin(pin: String) {
        val salt = ByteArray(SALT_LENGTH).also { SecureRandom().nextBytes(it) }
        prefs.edit()
            .putString(KEY_SALT, salt.encode())
            .putString(KEY_HASH, derive(pin, salt).encode())
            .putInt(KEY_ITERATIONS, ITERATIONS)
            .putInt(KEY_FAILED, 0)
            .putLong(KEY_LOCKOUT_UNTIL, 0L)
            .apply()
    }

    fun clearPin() {
        prefs.edit().clear().apply()
    }

    sealed interface Result {
        data object Success : Result
        data object Wrong : Result
        data class LockedOut(val remainingMs: Long) : Result
        data object NotSet : Result
    }

    fun verify(pin: String): Result {
        if (!isPinSet) return Result.NotSet

        val waiting = lockoutRemainingMs()
        if (waiting > 0) return Result.LockedOut(waiting)

        val salt = prefs.getString(KEY_SALT, null)?.decode() ?: return Result.NotSet
        val expected = prefs.getString(KEY_HASH, null)?.decode() ?: return Result.NotSet
        val iterations = prefs.getInt(KEY_ITERATIONS, ITERATIONS)

        return if (MessageDigest.isEqual(derive(pin, salt, iterations), expected)) {
            prefs.edit().putInt(KEY_FAILED, 0).putLong(KEY_LOCKOUT_UNTIL, 0L).apply()
            Result.Success
        } else {
            val failed = prefs.getInt(KEY_FAILED, 0) + 1
            val editor = prefs.edit().putInt(KEY_FAILED, failed)
            if (failed >= ATTEMPTS_BEFORE_LOCKOUT) {
                // 30s, 60s, 120s … capped at five minutes.
                val steps = failed - ATTEMPTS_BEFORE_LOCKOUT
                val penalty = (BASE_LOCKOUT_MS shl steps.coerceAtMost(4)).coerceAtMost(MAX_LOCKOUT_MS)
                editor.putLong(KEY_LOCKOUT_UNTIL, System.currentTimeMillis() + penalty)
                editor.apply()
                Result.LockedOut(penalty)
            } else {
                editor.apply()
                Result.Wrong
            }
        }
    }

    private fun derive(pin: String, salt: ByteArray, iterations: Int = ITERATIONS): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, iterations, KEY_BITS)
        return try {
            SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private fun ByteArray.encode(): String = Base64.encodeToString(this, Base64.NO_WRAP)
    private fun String.decode(): ByteArray = Base64.decode(this, Base64.NO_WRAP)

    companion object {
        const val PIN_LENGTH = 4

        private const val PREFS_NAME = "filewall_passcode"
        private const val KEY_SALT = "salt"
        private const val KEY_HASH = "hash"
        private const val KEY_ITERATIONS = "iterations"
        private const val KEY_FAILED = "failed_attempts"
        private const val KEY_LOCKOUT_UNTIL = "lockout_until"

        private const val ALGORITHM = "PBKDF2WithHmacSHA256"
        private const val ITERATIONS = 120_000
        private const val KEY_BITS = 256
        private const val SALT_LENGTH = 16
        private const val ATTEMPTS_BEFORE_LOCKOUT = 5
        private const val BASE_LOCKOUT_MS = 30_000L
        private const val MAX_LOCKOUT_MS = 5 * 60_000L
    }
}
