package com.filewall.data.backup

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
 * Keeps the archive passphrase available to the scheduled backup worker.
 *
 * This is a deliberate, bounded trade-off. A worker that fires at 3am cannot prompt anyone,
 * so unattended backup requires the passphrase to be readable by the device — and a device
 * that can read it is a device that can produce the archive.
 *
 * What it buys back: the passphrase is sealed with a **separate** AES-GCM key that never
 * leaves the Keystore, so it is inert on any other hardware. Someone with a copy of the
 * app's data directory gets nothing. And restoring on a *new* phone still requires the user
 * to type the passphrase, because that key cannot travel either.
 *
 * Manual Backup and Restore never touch this — they ask every time. Only the automatic
 * schedule stores anything, and switching it off erases the secret.
 */
class AutoBackupSecret(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val keyStore: KeyStore by lazy {
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
    }

    val isStored: Boolean get() = prefs.contains(KEY_BLOB)

    fun store(passphrase: CharArray) {
        val plain = passphrase.toUtf8()
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.ENCRYPT_MODE, loadOrCreateKey())
            }
            val sealed = cipher.doFinal(plain)
            val payload = cipher.iv + sealed
            prefs.edit().putString(KEY_BLOB, Base64.encodeToString(payload, Base64.NO_WRAP)).apply()
        } finally {
            plain.fill(0)
        }
    }

    /** Returns the passphrase, or null if none is stored or the key has been invalidated. */
    fun read(): CharArray? {
        val encoded = prefs.getString(KEY_BLOB, null) ?: return null
        return runCatching {
            val payload = Base64.decode(encoded, Base64.NO_WRAP)
            require(payload.size > GCM_IV_LENGTH) { "Stored secret is truncated" }

            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(
                    Cipher.DECRYPT_MODE,
                    loadOrCreateKey(),
                    GCMParameterSpec(GCM_TAG_BITS, payload, 0, GCM_IV_LENGTH),
                )
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
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    /** Encodes without ever materialising a String, which would linger in the heap. */
    private fun CharArray.toUtf8(): ByteArray {
        val buffer = Charsets.UTF_8.encode(CharBuffer.wrap(this))
        return ByteArray(buffer.remaining()).also { buffer.get(it) }
    }

    private fun ByteArray.toUtf8Chars(): CharArray {
        val buffer = Charsets.UTF_8.decode(ByteBuffer.wrap(this))
        return CharArray(buffer.remaining()).also { buffer.get(it) }
    }

    private companion object {
        const val PREFS_NAME = "filewall_autobackup"
        const val KEY_BLOB = "sealed_passphrase"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "filewall_autobackup_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_IV_LENGTH = 12
        const val GCM_TAG_BITS = 128
    }
}
