package com.filewall.data.sync

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Keeps this device's cross-device **sync** passphrase available between launches, the same
 * shape as [com.filewall.data.backup.AutoBackupSecret] but a distinct secret and a distinct
 * Keystore alias — this one gates the portable [SyncCrypto] key, not the Drive archive.
 *
 * The same trade-off applies: sealed with a Keystore key that never leaves this device, so a
 * copy of the app's data directory is useless without it, but *this* device can read it back
 * without prompting on every foreground sync. A brand new device still needs the passphrase
 * typed in once, because that seal cannot travel either — which is exactly what makes it safe
 * to store at all.
 */
class SyncPassphraseStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    val isStored: Boolean get() = prefs.contains(KEY_BLOB)

    fun store(passphrase: CharArray) {
        val plain = passphrase.toUtf8()
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, loadOrCreateKey()) }
            val sealed = cipher.doFinal(plain)
            prefs.edit().putString(KEY_BLOB, Base64.encodeToString(cipher.iv + sealed, Base64.NO_WRAP)).apply()
        } finally {
            plain.fill(0)
        }
    }

    fun read(): CharArray? {
        val encoded = prefs.getString(KEY_BLOB, null) ?: return null
        return runCatching {
            val payload = Base64.decode(encoded, Base64.NO_WRAP)
            require(payload.size > GCM_IV_LENGTH) { "Stored secret is truncated" }
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, loadOrCreateKey(), GCMParameterSpec(GCM_TAG_BITS, payload, 0, GCM_IV_LENGTH))
            }
            val plain = cipher.doFinal(payload, GCM_IV_LENGTH, payload.size - GCM_IV_LENGTH)
            try {
                plain.toUtf8Chars()
            } finally {
                plain.fill(0)
            }
        }.getOrNull()
    }

    fun clear() {
        prefs.edit().remove(KEY_BLOB).apply()
        runCatching { keyStore.deleteEntry(ALIAS) }
    }

    private fun loadOrCreateKey(): SecretKey {
        (keyStore.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private fun CharArray.toUtf8(): ByteArray {
        val buffer = Charsets.UTF_8.encode(CharBuffer.wrap(this))
        return ByteArray(buffer.remaining()).also { buffer.get(it) }
    }

    private fun ByteArray.toUtf8Chars(): CharArray {
        val buffer = Charsets.UTF_8.decode(ByteBuffer.wrap(this))
        return CharArray(buffer.remaining()).also { buffer.get(it) }
    }

    private companion object {
        const val PREFS_NAME = "filewall_sync"
        const val KEY_BLOB = "sealed_sync_passphrase"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "filewall_sync_passphrase_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_IV_LENGTH = 12
        const val GCM_TAG_BITS = 128
    }
}
