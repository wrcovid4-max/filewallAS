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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipInputStream

/** Tapping a zip unzips it into the same folder in the vault. Returns how many files were extracted. */
object VaultUnzipper {
    private const val TAG = "FileWallZip"
    private const val MAX_ENTRY = 512L * 1024 * 1024

    fun launch(context: Context, repo: VaultRepository, item: VaultItem) {
        Toast.makeText(context, "Unzipping…", Toast.LENGTH_SHORT).show()
        CoroutineScope(Dispatchers.IO).launch {
            val n = unzip(item, repo)
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(context, "Unzipped $n files", Toast.LENGTH_SHORT).show()
            }
        }
    }

    suspend fun unzip(item: VaultItem, repo: VaultRepository): Int = withContext(Dispatchers.IO) {
        var count = 0
        runCatching {
            val plain = repo.materialisePreview(item)
            ZipInputStream(plain.inputStream()).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        val name = File(entry.name).name.ifBlank { "file_$count" }
                        val tmp = File(plain.parent, "unzip_${System.currentTimeMillis()}_$name")
                        var total = 0L
                        tmp.outputStream().use { out ->
                            val buf = ByteArray(8192)
                            var n: Int
                            while (zip.read(buf).also { n = it } != -1) {
                                total += n
                                if (total > MAX_ENTRY) throw IllegalStateException("Entry too large")
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
        }.onFailure { Log.e(TAG, "unzip failed", it) }
        count
    }
}
