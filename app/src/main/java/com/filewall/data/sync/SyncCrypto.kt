package com.filewall.data.sync

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * The "Tier A" portable encryption from FIREBASE_BLUEPRINT.md §5.
 *
 * [VaultCrypto] deliberately cannot do this job: its AES key lives in the Android Keystore and
 * is non-exportable *by design*, so a phone-only key can never be the thing a second phone, an
 * iPhone or a browser tab decrypts with. Cloud sync needs a key every signed-in device can
 * re-derive on its own — the same way `BACKUP_FORMAT.md`'s archive passphrase already works,
 * just applied per-blob instead of to one zip.
 *
 * The password is the user's **sync passphrase** — a separate secret from the hidden-vault PIN
 * and from the local-archive passphrase, entered once per device and (optionally) sealed
 * locally the same way `AutoBackupSecret` seals the archive passphrase, so it doesn't have to
 * be retyped on every background sync. Firebase Storage/Firestore only ever see the output of
 * [encrypt] — ciphertext Google cannot read, matching the zero-knowledge promise the vault is
 * built on even once files leave the device.
 */
object SyncCrypto {

    /** `[salt 16B][iv 12B][ciphertext][16B GCM tag]` — self-contained, no external state needed. */
    fun encrypt(plaintext: ByteArray, passphrase: CharArray): ByteArray {
        val salt = ByteArray(SALT_LENGTH).also { SecureRandom().nextBytes(it) }
        val key = deriveKey(passphrase, salt)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, key)
        }
        val body = cipher.doFinal(plaintext)
        return salt + cipher.iv + body
    }

    fun decrypt(payload: ByteArray, passphrase: CharArray): ByteArray {
        require(payload.size > SALT_LENGTH + GCM_IV_LENGTH) { "Sync payload is truncated" }
        val salt = payload.copyOfRange(0, SALT_LENGTH)
        val iv = payload.copyOfRange(SALT_LENGTH, SALT_LENGTH + GCM_IV_LENGTH)
        val body = payload.copyOfRange(SALT_LENGTH + GCM_IV_LENGTH, payload.size)
        val key = deriveKey(passphrase, salt)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        }
        return cipher.doFinal(body)
    }

    /**
     * A fingerprint of the passphrase alone (fixed salt, never secret) so the app can warn
     * "that doesn't look like the same passphrase this account was set up with" *before*
     * spending a slow KDF pass on every blob and getting a wall of decrypt failures. Never
     * stored anywhere that isn't already gated by the user's own Firebase auth.
     */
    fun fingerprint(passphrase: CharArray): String {
        val key = deriveKey(passphrase, FINGERPRINT_SALT)
        return key.encoded.joinToString("") { "%02x".format(it) }.take(16)
    }

    private fun deriveKey(passphrase: CharArray, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(passphrase, salt, ITERATIONS, KEY_BITS)
        val raw = SecretKeyFactory.getInstance(KDF_ALGORITHM).generateSecret(spec).encoded
        return SecretKeySpec(raw, "AES")
    }

    private const val KDF_ALGORITHM = "PBKDF2WithHmacSHA256"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    // Matches VaultArchive's iteration count so the two share one "how slow is this on a
    // mid-range phone" budget instead of inventing a second number to tune.
    private const val ITERATIONS = 210_000
    private const val KEY_BITS = 256
    private const val SALT_LENGTH = 16
    private const val GCM_IV_LENGTH = 12
    private const val GCM_TAG_BITS = 128
    private val FINGERPRINT_SALT = ByteArray(SALT_LENGTH) { 0x46 } // constant, non-secret
}
