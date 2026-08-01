package com.filewall.data.media

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import com.filewall.data.crypto.VaultCrypto
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import javax.crypto.Cipher

/**
 * Lets ExoPlayer read an encrypted blob directly, with seeking, and without ever writing
 * plaintext to disk.
 *
 * The player asks for arbitrary byte ranges — scrubbing a video is nothing but a series of
 * seeks. AES-CTR makes that cheap: [VaultCrypto.cipherAt] re-derives the keystream at any
 * offset from the IV alone, so we position the file handle and the cipher to the same place
 * and stream from there.
 *
 * **Integrity:** a seeking reader can never verify the trailing HMAC, because it never reads
 * the whole file. Callers must run [VaultCrypto.verify] once before playback starts —
 * `VaultRepository.openForPlayback` does exactly that.
 */
@UnstableApi
class VaultDataSource(
    private val crypto: VaultCrypto,
    private val resolveBlob: (Uri) -> File?,
) : BaseDataSource(/* isNetwork = */ false) {

    private var handle: RandomAccessFile? = null
    private var cipher: Cipher? = null
    private var sourceUri: Uri? = null
    private var bytesRemaining = 0L
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)

        val blob = resolveBlob(dataSpec.uri)
            ?: throw IOException("No vault blob behind ${dataSpec.uri}")
        if (!blob.exists()) throw IOException("Vault blob is missing: ${blob.name}")

        val plainLength = crypto.plaintextSize(blob)
        val position = dataSpec.position
        if (position > plainLength) {
            throw IOException("Seek past end of ${blob.name} ($position > $plainLength)")
        }

        cipher = crypto.cipherAt(blob, position)
        handle = RandomAccessFile(blob, "r").apply {
            seek(crypto.ciphertextStart() + position)
        }

        bytesRemaining = if (dataSpec.length == C.LENGTH_UNSET.toLong()) {
            plainLength - position
        } else {
            minOf(dataSpec.length, plainLength - position)
        }

        sourceUri = dataSpec.uri
        opened = true
        transferStarted(dataSpec)
        return bytesRemaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT

        val want = minOf(length.toLong(), bytesRemaining).toInt()
        val ciphertext = ByteArray(want)
        val read = handle?.read(ciphertext, 0, want) ?: return C.RESULT_END_OF_INPUT
        if (read <= 0) return C.RESULT_END_OF_INPUT

        val plaintext = cipher?.update(ciphertext, 0, read) ?: ByteArray(0)
        // CTR is a stream cipher: update() emits exactly what it is fed. If that ever stops
        // holding, the byte accounting below would silently desync, so assert it instead.
        if (plaintext.size != read) {
            throw IOException("CTR produced ${plaintext.size} bytes for $read of input")
        }

        System.arraycopy(plaintext, 0, buffer, offset, read)
        bytesRemaining -= read
        bytesTransferred(read)
        return read
    }

    override fun getUri(): Uri? = sourceUri

    override fun close() {
        sourceUri = null
        cipher = null
        try {
            handle?.close()
        } finally {
            handle = null
            if (opened) {
                opened = false
                transferEnded()
            }
        }
    }

    /** Builds a fresh source per playback request, as ExoPlayer expects. */
    @UnstableApi
    class Factory(
        private val crypto: VaultCrypto,
        private val resolveBlob: (Uri) -> File?,
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource = VaultDataSource(crypto, resolveBlob)
    }

    companion object {
        /** Scheme handed to ExoPlayer in place of a real file path. */
        const val SCHEME = "fwvault"

        fun uriFor(blobName: String): Uri = Uri.Builder()
            .scheme(SCHEME)
            .authority("blob")
            .appendPath(blobName)
            .build()

        fun blobNameFrom(uri: Uri): String? =
            uri.lastPathSegment?.takeIf { uri.scheme == SCHEME && it.isNotBlank() }
    }
}
