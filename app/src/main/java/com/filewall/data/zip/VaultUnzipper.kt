package com.filewall.data.zip

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.filewall.data.repo.VaultRepository
import com.filewall.model.VaultItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

/** Pop-up messages from unzipping (the size limit, and failures). The root screen shows them. */
object UnzipMessages {
    private val state = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = state.asStateFlow()
    fun show(text: String) { state.value = text }
    fun clear() { state.value = null }
}

/** Tapping a zip unzips it into the same folder in the vault. */
object VaultUnzipper {
    private const val TAG = "FileWallZip"
    private const val MAX_TOTAL = 500L * 1024 * 1024
    private const val LIMIT_TEXT =
        "This zip is larger than the 500 MB limit, so it was not unzipped. Export it and unzip it on a computer."

    fun launch(context: Context, repo: VaultRepository, item: VaultItem) {
        Toast.makeText(context, "Unzipping…", Toast.LENGTH_SHORT).show()
        CoroutineScope(Dispatchers.IO).launch {
            val n = unzip(item, repo)
            if (n >= 0) Handler(Looper.getMainLooper()).post {
                Toast.makeText(context, "Unzipped $n files", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** Number of files extracted, or -1 when a pop-up was shown instead. */
    suspend fun unzip(item: VaultItem, repo: VaultRepository): Int = withContext(Dispatchers.IO) {
        try {
            val plain = repo.materialisePreview(item)
            // Check the declared total first, so an oversized zip is refused before anything is added.
            val declared = ZipFile(plain).use { zf ->
                java.util.Collections.list(zf.entries()).filter { !it.isDirectory }.sumOf { maxOf(it.size, 0L) }
            }
            if (declared > MAX_TOTAL) {
                UnzipMessages.show(LIMIT_TEXT)
                return@withContext -1
            }
            var count = 0
            var total = 0L
            ZipInputStream(plain.inputStream()).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        val name = File(entry.name).name.ifBlank { "file_$count" }
                        val tmp = File(plain.parent, "unzip_${System.currentTimeMillis()}_$name")
                        tmp.outputStream().use { out ->
                            val buf = ByteArray(8192)
                            var n: Int
                            while (zip.read(buf).also { n = it } != -1) {
                                total += n
                                if (total > MAX_TOTAL) {
                                    tmp.delete()
                                    throw LimitExceeded()
                                }
                                out.write(buf, 0, n)
                            }
                        }
                        repo.importPlaintext(
                            name = name,
                            mimeType = "application/octet-stream",
                            hidden = item.hidden,
                            folderId = item.folderId,
                            addedAt = System.currentTimeMillis(),
                            source = tmp,
                        )
                        tmp.delete()
                        count++
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
            Log.i(TAG, "unzipped $count files from ${item.name}")
            count
        } catch (e: LimitExceeded) {
            UnzipMessages.show(LIMIT_TEXT)
            -1
        } catch (e: Exception) {
            Log.e(TAG, "unzip failed", e)
            UnzipMessages.show("This zip could not be unzipped. It may be damaged or use an unsupported format.")
            -1
        }
    }

    private class LimitExceeded : Exception()
}
