package com.filewall.data.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.IvParameterSpec

/**
 * Encrypt-then-MAC file storage backed by the Android Keystore.
 *
 * Layout of every blob on disk:
 *
 * ```
 * [ magic "FWV1" 4B ][ iv 16B ][ ciphertext … ][ HMAC-SHA256 32B ]
 * ```
 *
 * AES-256-CTR is used rather than GCM on purpose: GCM buffers an entire message before it
 * will release verified plaintext, which would mean holding a whole video in RAM. CTR
 * streams in constant memory and is seekable, and the trailing HMAC (computed over the
 * magic, the IV and the ciphertext) supplies the integrity GCM would otherwise give us.
 *
 * The two keys never leave the Keystore, so a lifted copy of `/data/data/com.filewall`
 * is inert without the device it came from.
 */
class VaultCrypto {

    private val keyStore: KeyStore by lazy {
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
    }

    private val dataKey: SecretKey get() = loadOrCreateAesKey()
    private val macKey: SecretKey get() = loadOrCreateMacKey()

    // ---------------------------------------------------------------- writing

    /** Encrypts everything readable from [source] into [target]. Returns plaintext byte count. */
    @Throws(IOException::class, GeneralSecurityException::class)
    fun encrypt(source: InputStream, target: OutputStream): Long {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, dataKey)
        }
        val iv = cipher.iv
        require(iv.size == IV_LENGTH) { "Unexpected IV length ${iv.size}" }

        val mac = Mac.getInstance(MAC_ALGORITHM).apply { init(macKey) }

        target.write(MAGIC)
        mac.update(MAGIC)
        target.write(iv)
        mac.update(iv)

        var plainBytes = 0L
        val buffer = ByteArray(BUFFER_SIZE)
        while (true) {
            val read = source.read(buffer)
            if (read <= 0) break
            plainBytes += read
            val chunk = cipher.update(buffer, 0, read)
            if (chunk != null && chunk.isNotEmpty()) {
                target.write(chunk)
                mac.update(chunk)
            }
        }
        val tail = cipher.doFinal()
        if (tail != null && tail.isNotEmpty()) {
            target.write(tail)
            mac.update(tail)
        }
        target.write(mac.doFinal())
        target.flush()
        return plainBytes
    }

    /** Convenience wrapper for small payloads such as thumbnails. */
    @Throws(IOException::class, GeneralSecurityException::class)
    fun encryptBytes(plain: ByteArray, target: File) {
        target.parentFile?.mkdirs()
        target.outputStream().use { out -> encrypt(plain.inputStream(), out) }
    }

    // ---------------------------------------------------------------- reading

    /**
     * Verifies the trailing HMAC, then streams plaintext into [target].
     *
     * The MAC is checked in a separate pass before a single plaintext byte is emitted, so a
     * tampered blob can never be handed to a decoder or written to the preview cache.
     */
    @Throws(IOException::class, GeneralSecurityException::class)
    fun decrypt(source: File, target: OutputStream) {
        val cipherLength = verifyMac(source)
        val cipher = newDecryptCipher(source)

        FileInputStream(source).use { input ->
            skipFully(input, HEADER_SIZE.toLong())
            var remaining = cipherLength
            val buffer = ByteArray(BUFFER_SIZE)
            while (remaining > 0) {
                val want = minOf(remaining, BUFFER_SIZE.toLong()).toInt()
                val read = input.read(buffer, 0, want)
                if (read <= 0) throw IOException("Truncated vault blob: ${source.name}")
                remaining -= read
                val chunk = cipher.update(buffer, 0, read)
                if (chunk != null && chunk.isNotEmpty()) target.write(chunk)
            }
            val tail = cipher.doFinal()
            if (tail != null && tail.isNotEmpty()) target.write(tail)
        }
        target.flush()
    }

    /** Reads a whole blob into memory. Only use for thumbnails and images. */
    @Throws(IOException::class, GeneralSecurityException::class)
    fun decryptBytes(source: File): ByteArray {
        val out = java.io.ByteArrayOutputStream(plaintextSize(source).toInt().coerceAtLeast(32))
        decrypt(source, out)
        return out.toByteArray()
    }

    /** Plaintext length implied by the blob's size, without touching the key material. */
    fun plaintextSize(source: File): Long =
        (source.length() - HEADER_SIZE - MAC_LENGTH).coerceAtLeast(0L)

    // ---------------------------------------------------------------- internals

    private fun newDecryptCipher(source: File): Cipher {
        val iv = ByteArray(IV_LENGTH)
        FileInputStream(source).use { input ->
            val magic = ByteArray(MAGIC.size)
            readFully(input, magic)
            if (!magic.contentEquals(MAGIC)) throw IOException("Not a FileWall blob: ${source.name}")
            readFully(input, iv)
        }
        return Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, dataKey, IvParameterSpec(iv))
        }
    }

    /** Returns the ciphertext length once the MAC has been confirmed. */
    @Throws(IOException::class, GeneralSecurityException::class)
    private fun verifyMac(source: File): Long {
        val total = source.length()
        if (total < HEADER_SIZE + MAC_LENGTH) throw IOException("Vault blob too short: ${source.name}")
        val signedLength = total - MAC_LENGTH

        val mac = Mac.getInstance(MAC_ALGORITHM).apply { init(macKey) }
        val stored = ByteArray(MAC_LENGTH)

        FileInputStream(source).use { input ->
            var remaining = signedLength
            val buffer = ByteArray(BUFFER_SIZE)
            while (remaining > 0) {
                val want = minOf(remaining, BUFFER_SIZE.toLong()).toInt()
                val read = input.read(buffer, 0, want)
                if (read <= 0) throw IOException("Truncated vault blob: ${source.name}")
                mac.update(buffer, 0, read)
                remaining -= read
            }
            readFully(input, stored)
        }

        if (!MessageDigest.isEqual(mac.doFinal(), stored)) {
            throw GeneralSecurityException("Integrity check failed for ${source.name}")
        }
        return signedLength - HEADER_SIZE
    }

    private fun loadOrCreateAesKey(): SecretKey {
        (keyStore.getKey(ALIAS_DATA, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS_DATA,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_CTR)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                // The system picks every IV; we only ever supply one when decrypting.
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    private fun loadOrCreateMacKey(): SecretKey {
        (keyStore.getKey(ALIAS_MAC, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_HMAC_SHA256,
            ANDROID_KEYSTORE,
        )
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS_MAC,
                KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY,
            )
                .setDigests(KeyProperties.DIGEST_SHA256)
                .build(),
        )
        return generator.generateKey()
    }

    /** Drops both keys, which permanently shreds every blob still on disk. */
    fun destroyKeys() {
        runCatching { keyStore.deleteEntry(ALIAS_DATA) }
        runCatching { keyStore.deleteEntry(ALIAS_MAC) }
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val ALIAS_DATA = "filewall_data_v1"
        const val ALIAS_MAC = "filewall_mac_v1"
        const val TRANSFORMATION = "AES/CTR/NoPadding"
        const val MAC_ALGORITHM = "HmacSHA256"
        const val IV_LENGTH = 16
        const val MAC_LENGTH = 32
        const val BUFFER_SIZE = 64 * 1024
        val MAGIC = byteArrayOf('F'.code.toByte(), 'W'.code.toByte(), 'V'.code.toByte(), '1'.code.toByte())
        const val HEADER_SIZE = 4 + IV_LENGTH

        fun readFully(input: InputStream, out: ByteArray) {
            var offset = 0
            while (offset < out.size) {
                val read = input.read(out, offset, out.size - offset)
                if (read <= 0) throw IOException("Unexpected end of blob")
                offset += read
            }
        }

        fun skipFully(input: InputStream, count: Long) {
            var remaining = count
            while (remaining > 0) {
                val skipped = input.skip(remaining)
                if (skipped <= 0) {
                    if (input.read() < 0) throw IOException("Unexpected end of blob")
                    remaining--
                } else {
                    remaining -= skipped
                }
            }
        }
    }
}
