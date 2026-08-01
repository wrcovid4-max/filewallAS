package com.filewall.data.repo

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import com.filewall.data.crypto.VaultCrypto
import com.filewall.data.db.VaultDao
import com.filewall.model.FileCategory
import com.filewall.model.FolderWithCount
import com.filewall.model.StorageBreakdown
import com.filewall.model.VaultFolder
import com.filewall.model.VaultItem
import com.filewall.data.media.ThumbnailFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import java.io.OutputStream
import java.util.UUID

/**
 * Single door to the vault. Everything that touches ciphertext, the database or the
 * plaintext preview cache goes through here, which keeps the "where can plaintext exist"
 * question answerable by reading one file.
 */
class VaultRepository(
    private val context: Context,
    private val dao: VaultDao,
    private val crypto: VaultCrypto,
) {

    private val root = File(context.filesDir, "vault")
    private val blobDir = File(root, "blobs").apply { mkdirs() }
    private val thumbDir = File(root, "thumbs").apply { mkdirs() }
    private val previewDir = File(context.cacheDir, "preview")

    // ------------------------------------------------------------------ streams

    fun observeItems(hidden: Boolean): Flow<List<VaultItem>> = dao.observeItems(hidden)

    fun observeItem(id: String): Flow<VaultItem?> = dao.observeItem(id)

    fun observeFolders(hidden: Boolean): Flow<List<FolderWithCount>> =
        combine(dao.observeFolders(hidden), dao.observeFolderCounts()) { folders, counts ->
            val byId = counts.associate { it.folderId to it.count }
            folders.map { FolderWithCount(it, byId[it.id] ?: 0) }
        }

    val storage: Flow<StorageBreakdown> = dao.observeCategoryTotals().map { totals ->
        var photo = 0L
        var video = 0L
        var doc = 0L
        var other = 0L
        totals.forEach { row ->
            when (row.category) {
                FileCategory.PHOTO -> photo += row.bytes
                FileCategory.VIDEO -> video += row.bytes
                FileCategory.DOC -> doc += row.bytes
                FileCategory.OTHER -> other += row.bytes
            }
        }
        StorageBreakdown(photo, video, doc, other)
    }

    // ------------------------------------------------------------------ import

    /** Encrypts each picked document into the vault. Returns how many landed successfully. */
    suspend fun import(uris: List<Uri>, hidden: Boolean, folderId: String?): ImportResult =
        withContext(Dispatchers.IO) {
            var added = 0
            val failures = mutableListOf<String>()

            uris.forEach { uri ->
                val meta = readMetadata(uri)
                val category = FileCategory.fromMime(meta.mimeType, meta.name)
                val id = UUID.randomUUID().toString()
                val blob = File(blobDir, "$id.fw")

                val outcome = runCatching {
                    // The preview is built first: if the source is unreadable we find out
                    // before writing anything, and the tile never ships without an image.
                    val preview = ThumbnailFactory.fromUri(context, uri, category)

                    val plainBytes = context.contentResolver.openInputStream(uri)?.use { input ->
                        blob.outputStream().use { output -> crypto.encrypt(input, output) }
                    } ?: throw java.io.IOException("Could not open ${meta.name}")

                    val thumbName = preview?.let {
                        val name = "$id.fw"
                        crypto.encryptBytes(it.jpeg, File(thumbDir, name))
                        name
                    }

                    VaultItem(
                        id = id,
                        name = meta.name,
                        mimeType = meta.mimeType,
                        sizeBytes = if (plainBytes > 0) plainBytes else meta.size,
                        addedAt = System.currentTimeMillis(),
                        folderId = folderId,
                        hidden = hidden,
                        category = category,
                        blobName = blob.name,
                        thumbName = thumbName,
                        width = preview?.sourceWidth ?: 0,
                        height = preview?.sourceHeight ?: 0,
                    )
                }

                outcome.onSuccess { item ->
                    dao.upsertItem(item)
                    added++
                }.onFailure { error ->
                    blob.delete()
                    File(thumbDir, "$id.fw").delete()
                    failures += "${meta.name}: ${error.message ?: error.javaClass.simpleName}"
                }
            }

            ImportResult(added, failures)
        }

    data class ImportResult(val added: Int, val failures: List<String>)

    private data class SourceMetadata(val name: String, val size: Long, val mimeType: String)

    private fun readMetadata(uri: Uri): SourceMetadata {
        var name: String? = null
        var size = 0L
        runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        .takeIf { it >= 0 }
                        ?.let { name = cursor.getString(it) }
                    cursor.getColumnIndex(OpenableColumns.SIZE)
                        .takeIf { it >= 0 && !cursor.isNull(it) }
                        ?.let { size = cursor.getLong(it) }
                }
            }
        }
        val resolved = name ?: uri.lastPathSegment?.substringAfterLast('/') ?: "file"
        val mime = context.contentResolver.getType(uri)
            ?: MimeTypeMap.getSingleton()
                .getMimeTypeFromExtension(resolved.substringAfterLast('.', "").lowercase())
            ?: "application/octet-stream"
        return SourceMetadata(resolved, size, mime)
    }

    /** Adds an already-decrypted payload, used by archive restore. */
    suspend fun importPlaintext(
        name: String,
        mimeType: String,
        hidden: Boolean,
        folderId: String?,
        addedAt: Long,
        source: File,
    ): VaultItem = withContext(Dispatchers.IO) {
        val category = FileCategory.fromMime(mimeType, name)
        val id = UUID.randomUUID().toString()
        val blob = File(blobDir, "$id.fw")
        val plainBytes = source.inputStream().use { input ->
            blob.outputStream().use { output -> crypto.encrypt(input, output) }
        }
        val preview = ThumbnailFactory.fromFile(source, category)
        val thumbName = preview?.let {
            crypto.encryptBytes(it.jpeg, File(thumbDir, "$id.fw"))
            "$id.fw"
        }
        VaultItem(
            id = id,
            name = name,
            mimeType = mimeType,
            sizeBytes = plainBytes,
            addedAt = addedAt,
            folderId = folderId,
            hidden = hidden,
            category = category,
            blobName = blob.name,
            thumbName = thumbName,
            width = preview?.sourceWidth ?: 0,
            height = preview?.sourceHeight ?: 0,
        ).also { dao.upsertItem(it) }
    }

    // ------------------------------------------------------------------ reading

    /** Decrypted preview bytes for a tile, or null when the item has no preview. */
    suspend fun thumbnailBytes(item: VaultItem): ByteArray? = withContext(Dispatchers.IO) {
        val name = item.thumbName ?: return@withContext null
        val file = File(thumbDir, name)
        if (!file.exists()) return@withContext null
        runCatching { crypto.decryptBytes(file) }.getOrNull()
    }

    /** Full decrypted bytes. Only call for images — videos go through [materialisePreview]. */
    suspend fun fullBytes(item: VaultItem): ByteArray = withContext(Dispatchers.IO) {
        crypto.decryptBytes(blobFor(item))
    }

    suspend fun writeTo(item: VaultItem, target: OutputStream) = withContext(Dispatchers.IO) {
        crypto.decrypt(blobFor(item), target)
    }

    /** Exports one item to a user-chosen SAF destination. */
    suspend fun export(item: VaultItem, destination: Uri) = withContext(Dispatchers.IO) {
        context.contentResolver.openOutputStream(destination)?.use { out ->
            crypto.decrypt(blobFor(item), out)
        } ?: throw java.io.IOException("Could not write to the chosen location")
    }

    /**
     * Writes plaintext into the cache so an external viewer can open it.
     *
     * This is the only place plaintext hits the filesystem. [clearPreviewCache] runs on every
     * lock and on process start, and the directory is excluded from cloud backup.
     */
    suspend fun materialisePreview(item: VaultItem): File = withContext(Dispatchers.IO) {
        previewDir.mkdirs()
        val target = File(previewDir, item.name.sanitisedFileName())
        target.outputStream().use { out -> crypto.decrypt(blobFor(item), out) }
        target
    }

    fun clearPreviewCache() {
        previewDir.listFiles()?.forEach { it.delete() }
    }

    private fun blobFor(item: VaultItem): File = File(blobDir, item.blobName)

    // ------------------------------------------------------------------ mutation

    suspend fun rename(item: VaultItem, name: String) = withContext(Dispatchers.IO) {
        dao.renameItem(item.id, name.trim().ifBlank { item.name })
    }

    suspend fun move(item: VaultItem, folderId: String?) = withContext(Dispatchers.IO) {
        dao.setItemFolder(item.id, folderId)
    }

    suspend fun setHidden(ids: List<String>, hidden: Boolean) = withContext(Dispatchers.IO) {
        dao.setItemsHidden(ids, hidden)
    }

    suspend fun delete(items: List<VaultItem>) = withContext(Dispatchers.IO) {
        items.forEach { item ->
            File(blobDir, item.blobName).delete()
            item.thumbName?.let { File(thumbDir, it).delete() }
        }
        dao.deleteItemsByIds(items.map { it.id })
    }

    suspend fun createFolder(name: String, colorIndex: Int, hidden: Boolean): VaultFolder =
        withContext(Dispatchers.IO) {
            VaultFolder(
                id = UUID.randomUUID().toString(),
                name = name.trim().ifBlank { "Folder" },
                colorIndex = colorIndex,
                createdAt = System.currentTimeMillis(),
                hidden = hidden,
            ).also { dao.upsertFolder(it) }
        }

    suspend fun renameFolder(id: String, name: String) = withContext(Dispatchers.IO) {
        val trimmed = name.trim()
        if (trimmed.isNotEmpty()) dao.renameFolder(id, trimmed)
    }

    suspend fun recolorFolder(id: String, colorIndex: Int) = withContext(Dispatchers.IO) {
        dao.recolorFolder(id, colorIndex)
    }

    /** Deletes a folder and everything inside it. */
    suspend fun deleteFolder(id: String) = withContext(Dispatchers.IO) {
        delete(dao.itemsInFolder(id))
        dao.deleteFolderRow(id)
    }

    /** The toolbar's "Delete All": every folder on this side of the vault, contents included. */
    suspend fun deleteAllFolders(hidden: Boolean) = withContext(Dispatchers.IO) {
        dao.foldersFor(hidden).forEach { folder ->
            delete(dao.itemsInFolder(folder.id))
        }
        dao.deleteFoldersRows(hidden)
    }

    suspend fun countInFolders(hidden: Boolean): Int = withContext(Dispatchers.IO) {
        dao.foldersFor(hidden).sumOf { dao.itemsInFolder(it.id).size }
    }

    suspend fun allItems(): List<VaultItem> = withContext(Dispatchers.IO) { dao.allItems() }

    suspend fun allFolders(): List<VaultFolder> = withContext(Dispatchers.IO) { dao.allFolders() }

    suspend fun upsertFolders(folders: List<VaultFolder>) = withContext(Dispatchers.IO) {
        dao.upsertFolders(folders)
    }

    private fun String.sanitisedFileName(): String {
        val cleaned = replace(Regex("""[\\/:*?"<>|]"""), "_").trim().ifBlank { "file" }
        return cleaned.take(120)
    }
}
