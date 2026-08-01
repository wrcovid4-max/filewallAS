package com.filewall.data.backup

import android.content.Context
import android.net.Uri
import com.filewall.data.repo.VaultRepository
import com.filewall.model.VaultFolder
import com.filewall.model.VaultItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.crypto.Cipher
import javax.crypto.CipherOutputStream
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Portable, passphrase-protected snapshot of the whole vault: a single `.fwvault` file.
 *
 * The on-device keys live in the Keystore and cannot be exported, so a backup that is
 * restorable on a *different* phone has to be re-keyed from something the user carries in
 * their head. Hence the passphrase — and the same file is what Drive sync uploads.
 *
 * ```
 * [ "FWARCH01" 8B ][ salt 16B ][ iterations 4B ][ iv 16B ][ AES-CTR(zip) … ][ HMAC 32B ]
 * ```
 */
class VaultArchive(
    private val context: Context,
    private val repository: VaultRepository,
) {

    class WrongPassphraseException : GeneralSecurityException("Wrong passphrase or damaged archive")

    // ------------------------------------------------------------------ export

    suspend fun exportTo(destination: Uri, passphrase: CharArray): Long =
        withContext(Dispatchers.IO) {
            context.contentResolver.openOutputStream(destination)?.use { raw ->
                writeArchive(raw, passphrase)
            } ?: throw IOException("Could not write to the chosen location")
        }

    suspend fun exportTo(destination: File, passphrase: CharArray): Long =
        withContext(Dispatchers.IO) {
            destination.outputStream().use { raw -> writeArchive(raw, passphrase) }
        }

    private suspend fun writeArchive(raw: OutputStream, passphrase: CharArray): Long {
        val salt = ByteArray(SALT_LENGTH).also { SecureRandom().nextBytes(it) }
        val keys = deriveKeys(passphrase, salt, ITERATIONS)

        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, keys.aes)
        }
        val mac = Mac.getInstance(MAC_ALGORITHM).apply { init(keys.hmac) }

        raw.write(MAGIC)
        raw.write(salt)
        raw.write(ITERATIONS.toBigEndian())
        raw.write(cipher.iv)

        val items = repository.allItems()
        val folders = repository.allFolders()
        var bytes = 0L

        val macStream = MacOutputStream(raw, mac)
        ZipOutputStream(CipherOutputStream(macStream, cipher)).use { zip ->
            zip.putNextEntry(ZipEntry(MANIFEST_ENTRY))
            zip.write(buildManifest(items, folders).toString().toByteArray())
            zip.closeEntry()

            items.forEach { item ->
                zip.putNextEntry(ZipEntry(blobEntry(item.id)))
                repository.writeTo(item, zip)
                zip.closeEntry()
                bytes += item.sizeBytes
            }
        }
        // CipherOutputStream.close() flushed the final block through MacOutputStream, so the
        // MAC now covers every ciphertext byte. MacOutputStream deliberately leaves `raw` open.
        raw.write(mac.doFinal())
        raw.flush()
        return bytes
    }

    private fun buildManifest(items: List<VaultItem>, folders: List<VaultFolder>): JSONObject =
        JSONObject().apply {
            put("version", FORMAT_VERSION)
            put("createdAt", System.currentTimeMillis())
            put(
                "folders",
                JSONArray().apply {
                    folders.forEach { folder ->
                        put(
                            JSONObject().apply {
                                put("id", folder.id)
                                put("name", folder.name)
                                put("colorIndex", folder.colorIndex)
                                put("createdAt", folder.createdAt)
                                put("hidden", folder.hidden)
                            },
                        )
                    }
                },
            )
            put(
                "items",
                JSONArray().apply {
                    items.forEach { item ->
                        put(
                            JSONObject().apply {
                                put("id", item.id)
                                put("name", item.name)
                                put("mimeType", item.mimeType)
                                put("sizeBytes", item.sizeBytes)
                                put("addedAt", item.addedAt)
                                put("folderId", item.folderId ?: JSONObject.NULL)
                                put("hidden", item.hidden)
                                put("entry", blobEntry(item.id))
                            },
                        )
                    }
                },
            )
        }

    // ----------------------------------------------------------------- restore

    data class RestoreResult(val items: Int, val folders: Int)

    suspend fun restoreFrom(source: Uri, passphrase: CharArray): RestoreResult =
        withContext(Dispatchers.IO) {
            context.contentResolver.openInputStream(source)?.use { raw ->
                readArchive(raw, passphrase)
            } ?: throw IOException("Could not read the chosen file")
        }

    suspend fun restoreFrom(source: File, passphrase: CharArray): RestoreResult =
        withContext(Dispatchers.IO) {
            source.inputStream().use { raw -> readArchive(raw, passphrase) }
        }

    private suspend fun readArchive(raw: InputStream, passphrase: CharArray): RestoreResult {
        val staging = File(context.cacheDir, "restore").apply {
            deleteRecursively()
            mkdirs()
        }
        try {
            val zipFile = File(staging, "payload.zip")
            // Verify before ingesting: nothing reaches the vault until the HMAC checks out.
            decryptToZip(raw, passphrase, zipFile)
            return ingest(zipFile, staging)
        } finally {
            staging.deleteRecursively()
        }
    }

    /** Decrypts the body to [target] and only returns once the trailing HMAC matches. */
    private fun decryptToZip(raw: InputStream, passphrase: CharArray, target: File) {
        val magic = ByteArray(MAGIC.size).also { raw.readFully(it) }
        if (!magic.contentEquals(MAGIC)) throw IOException("Not a FileWall archive")

        val salt = ByteArray(SALT_LENGTH).also { raw.readFully(it) }
        val iterations = ByteArray(4).also { raw.readFully(it) }.toIntBigEndian()
        if (iterations !in MIN_ITERATIONS..MAX_ITERATIONS) throw IOException("Archive header is damaged")
        val iv = ByteArray(IV_LENGTH).also { raw.readFully(it) }

        val keys = deriveKeys(passphrase, salt, iterations)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, keys.aes, IvParameterSpec(iv))
        }
        val mac = Mac.getInstance(MAC_ALGORITHM).apply { init(keys.hmac) }

        val trailer = target.outputStream().use { out ->
            // The MAC is the last 32 bytes and a content stream has no length we can trust,
            // so hold the tail back as we go rather than seeking.
            raw.streamHoldingBack(MAC_LENGTH) { buffer, offset, length ->
                mac.update(buffer, offset, length)
                cipher.update(buffer, offset, length)?.let { out.write(it) }
            }
        }
        cipher.doFinal()?.takeIf { it.isNotEmpty() }?.let { target.appendBytes(it) }

        if (!MessageDigest.isEqual(mac.doFinal(), trailer)) throw WrongPassphraseException()
    }

    private suspend fun ingest(zipFile: File, staging: File): RestoreResult {
        val manifest = readManifest(zipFile) ?: throw IOException("Archive has no manifest")

        val folders = manifest.optJSONArray("folders")?.let { array ->
            List(array.length()) { index ->
                val obj = array.getJSONObject(index)
                VaultFolder(
                    id = obj.getString("id"),
                    name = obj.optString("name", "Folder"),
                    colorIndex = obj.optInt("colorIndex", 0),
                    createdAt = obj.optLong("createdAt", System.currentTimeMillis()),
                    hidden = obj.optBoolean("hidden", false),
                )
            }
        }.orEmpty()
        repository.upsertFolders(folders)

        val entries = manifest.optJSONArray("items") ?: JSONArray()
        val byEntry = buildMap {
            for (index in 0 until entries.length()) {
                val obj = entries.getJSONObject(index)
                put(obj.optString("entry"), obj)
            }
        }

        var restored = 0
        ZipInputStream(zipFile.inputStream().buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val meta = byEntry[entry.name]
                if (entry.isDirectory || meta == null) {
                    zip.closeEntry()
                    continue
                }
                // Staged by counter, never by the entry's own name — a hand-crafted archive
                // cannot talk us into writing outside the staging directory.
                val staged = File(staging, "item_$restored.bin")
                staged.outputStream().use { out -> zip.copyTo(out) }
                zip.closeEntry()

                repository.importPlaintext(
                    name = meta.optString("name", "file"),
                    mimeType = meta.optString("mimeType", "application/octet-stream"),
                    hidden = meta.optBoolean("hidden", false),
                    folderId = meta.optString("folderId").takeIf { !meta.isNull("folderId") && it.isNotBlank() },
                    addedAt = meta.optLong("addedAt", System.currentTimeMillis()),
                    source = staged,
                )
                staged.delete()
                restored++
            }
        }
        return RestoreResult(restored, folders.size)
    }

    private fun readManifest(zipFile: File): JSONObject? {
        ZipInputStream(zipFile.inputStream().buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: return null
                if (entry.name == MANIFEST_ENTRY) {
                    return JSONObject(zip.readBytes().decodeToString())
                }
                zip.closeEntry()
            }
        }
    }

    // ------------------------------------------------------------------ crypto

    private class Keys(val aes: SecretKeySpec, val hmac: SecretKeySpec)

    private fun deriveKeys(passphrase: CharArray, salt: ByteArray, iterations: Int): Keys {
        val spec = PBEKeySpec(passphrase, salt, iterations, 512)
        val material = try {
            SecretKeyFactory.getInstance(KDF_ALGORITHM).generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
        return Keys(
            aes = SecretKeySpec(material.copyOfRange(0, 32), "AES"),
            hmac = SecretKeySpec(material.copyOfRange(32, 64), MAC_ALGORITHM),
        )
    }

    /** Passes the underlying stream's bytes through the MAC, and never closes it. */
    private class MacOutputStream(out: OutputStream, private val mac: Mac) : FilterOutputStream(out) {
        override fun write(b: Int) {
            mac.update(b.toByte())
            out.write(b)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            mac.update(b, off, len)
            out.write(b, off, len)
        }

        override fun close() {
            flush()
        }
    }

    companion object {
        const val EXTENSION = "fwvault"
        const val MIME_TYPE = "application/octet-stream"
        const val MIN_PASSPHRASE_LENGTH = 8
        const val DEFAULT_FILE_NAME = "filewall-backup.fwvault"

        private const val FORMAT_VERSION = 1
        private const val MANIFEST_ENTRY = "manifest.json"
        private const val KDF_ALGORITHM = "PBKDF2WithHmacSHA256"
        private const val TRANSFORMATION = "AES/CTR/NoPadding"
        private const val MAC_ALGORITHM = "HmacSHA256"
        private const val ITERATIONS = 210_000
        private const val MIN_ITERATIONS = 1_000
        private const val MAX_ITERATIONS = 2_000_000
        private const val SALT_LENGTH = 16
        private const val IV_LENGTH = 16
        private const val MAC_LENGTH = 32
        private const val BUFFER_SIZE = 64 * 1024

        private val MAGIC = "FWARCH01".toByteArray()

        private fun blobEntry(id: String) = "blobs/$id"

        private fun Int.toBigEndian() = byteArrayOf(
            (this ushr 24).toByte(),
            (this ushr 16).toByte(),
            (this ushr 8).toByte(),
            this.toByte(),
        )

        private fun ByteArray.toIntBigEndian(): Int =
            (this[0].toInt() and 0xFF shl 24) or
                (this[1].toInt() and 0xFF shl 16) or
                (this[2].toInt() and 0xFF shl 8) or
                (this[3].toInt() and 0xFF)

        private fun InputStream.readFully(out: ByteArray) {
            var offset = 0
            while (offset < out.size) {
                val read = read(out, offset, out.size - offset)
                if (read <= 0) throw IOException("Archive truncated")
                offset += read
            }
        }

        /**
         * Streams everything except the final [trailerSize] bytes to [onChunk] and returns
         * those held-back bytes. Lets us MAC-verify a stream whose length we never learn.
         */
        private inline fun InputStream.streamHoldingBack(
            trailerSize: Int,
            onChunk: (ByteArray, Int, Int) -> Unit,
        ): ByteArray {
            val hold = ByteArray(trailerSize)
            var held = 0
            val buffer = ByteArray(BUFFER_SIZE)

            while (true) {
                val read = read(buffer)
                if (read <= 0) break

                val total = held + read
                if (total <= trailerSize) {
                    System.arraycopy(buffer, 0, hold, held, read)
                    held = total
                    continue
                }

                val emit = total - trailerSize
                val fromHold = minOf(emit, held)
                if (fromHold > 0) onChunk(hold, 0, fromHold)
                val fromBuffer = emit - fromHold
                if (fromBuffer > 0) onChunk(buffer, 0, fromBuffer)

                val leftInHold = held - fromHold
                if (leftInHold > 0) System.arraycopy(hold, fromHold, hold, 0, leftInHold)
                val leftInBuffer = read - fromBuffer
                System.arraycopy(buffer, fromBuffer, hold, leftInHold, leftInBuffer)
                held = leftInHold + leftInBuffer
            }

            if (held < trailerSize) throw IOException("Archive truncated")
            return hold
        }
    }
}
