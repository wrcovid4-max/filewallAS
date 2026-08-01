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

    // ------------------------------------------------------------ random access

    /**
     * Confirms the whole blob is intact and returns its plaintext length.
     *
     * Callers that intend to seek around a blob should run this once up front: a seeking
     * reader can never check the trailing MAC, because it never reads the whole file.
     */
    @Throws(IOException::class, GeneralSecurityException::class)
    fun verify(source: File): Long = verifyMac(source)

    /**
     * A cipher primed to emit plaintext starting at [plaintextOffset].
     *
     * This is why the format uses CTR. The keystream for block *n* depends only on the IV
     * plus *n*, so jumping to an arbitrary offset costs one big-endian addition — no need to
     * decrypt everything before it. GCM could not do this at any price.
     */
    @Throws(IOException::class, GeneralSecurityException::class)
    fun cipherAt(source: File, plaintextOffset: Long): Cipher {
        require(plaintextOffset >= 0) { "Negative offset $plaintextOffset" }
        val counter = counterFor(readIv(source), plaintextOffset / AES_BLOCK)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, dataKey, IvParameterSpec(counter))
        }
        // Offsets rarely land on a block boundary; burn the leading keystream bytes of the
        // block we are starting inside so the very next byte out lines up with the file.
        val intoBlock = (plaintextOffset % AES_BLOCK).toInt()
        if (intoBlock > 0) cipher.update(ByteArray(intoBlock))
        return cipher
    }

    /** Byte offset of the first ciphertext byte in a blob. */
    fun ciphertextStart(): Long = HEADER_SIZE.toLong()

    // ---------------------------------------------------------------- internals

    private fun readIv(source: File): ByteArray {
        val iv = ByteArray(IV_LENGTH)
        FileInputStream(source).use { input ->
            val magic = ByteArray(MAGIC.size)
            readFully(input, magic)
            if (!magic.contentEquals(MAGIC)) throw IOException("Not a FileWall blob: ${source.name}")
            readFully(input, iv)
        }
        return iv
    }

    private fun newDecryptCipher(source: File): Cipher =
        Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, dataKey, IvParameterSpec(readIv(source)))
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

    // `internal` rather than `private` so the counter arithmetic below can be unit-tested;
    // seek math is the easiest part of this file to get subtly wrong.
    internal companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val ALIAS_DATA = "filewall_data_v1"
        const val ALIAS_MAC = "filewall_mac_v1"
        const val TRANSFORMATION = "AES/CTR/NoPadding"
        const val MAC_ALGORITHM = "HmacSHA256"
        const val IV_LENGTH = 16
        const val MAC_LENGTH = 32
        const val AES_BLOCK = 16
        const val BUFFER_SIZE = 64 * 1024

        /**
         * Adds [blockIndex] to the 128-bit big-endian counter block, the way CTR mode does
         * internally. Carries ripple leftwards; overflow past byte 0 wraps, exactly as the
         * cipher would.
         */
        fun counterFor(iv: ByteArray, blockIndex: Long): ByteArray {
            val counter = iv.copyOf()
            var remaining = blockIndex
            var carry = 0L
            var index = counter.size - 1
            while (index >= 0 && (remaining != 0L || carry != 0L)) {
                val sum = (counter[index].toLong() and 0xFF) + (remaining and 0xFF) + carry
                counter[index] = (sum and 0xFF).toByte()
                carry = sum ushr 8
                remaining = remaining ushr 8
                index--
            }
            return counter
        }
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
