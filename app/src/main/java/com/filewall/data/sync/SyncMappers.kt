package com.filewall.data.sync

import com.filewall.model.FileCategory
import com.filewall.model.VaultFolder
import com.filewall.model.VaultItem

/**
 * Translates between the app's local models and the Firestore documents.
 *
 * Local blob/thumb file names are derived from the id (`"$id.fw"`, the same convention
 * `VaultRepository` already uses), so they never need to travel over the wire — the sync
 * manager downloads the Storage object to that deterministic local path.
 */
object SyncMappers {

    fun toFileDoc(
        item: VaultItem,
        ownerUid: String,
        checksum: String?,
        updatedAt: Long,
    ): FileDoc = FileDoc(
        id = item.id,
        ownerUid = ownerUid,
        name = item.name,
        mimeType = item.mimeType,
        category = item.category.name,
        sizeBytes = item.sizeBytes,
        width = item.width,
        height = item.height,
        folderId = item.folderId,
        storagePath = FileDoc.storagePath(ownerUid, item.id),
        thumbPath = item.thumbName?.let { FileDoc.thumbPath(ownerUid, item.id) },
        checksum = checksum,
        hidden = item.hidden,
        archived = item.archived,
        deletedAt = item.deletedAt,
        status = FileDoc.STATUS_READY,
        createdAt = item.addedAt,
        updatedAt = updatedAt,
    )

    fun toVaultItem(doc: FileDoc): VaultItem = VaultItem(
        id = doc.id,
        name = doc.name,
        mimeType = doc.mimeType,
        sizeBytes = doc.sizeBytes,
        addedAt = doc.createdAt,
        folderId = doc.folderId,
        hidden = doc.hidden,
        category = runCatching { FileCategory.valueOf(doc.category) }.getOrDefault(FileCategory.OTHER),
        blobName = "${doc.id}.fw",
        thumbName = doc.thumbPath?.let { "${doc.id}.fw" },
        width = doc.width,
        height = doc.height,
        deletedAt = doc.deletedAt,
        archived = doc.archived,
    )

    fun toFolderDoc(
        folder: VaultFolder,
        ownerUid: String,
        updatedAt: Long,
    ): FolderDoc = FolderDoc(
        id = folder.id,
        ownerUid = ownerUid,
        name = folder.name,
        parentId = null,               // the app's folders are flat today
        path = "/" + folder.name,
        colorIndex = folder.colorIndex,
        hidden = folder.hidden,
        deletedAt = 0,
        createdAt = folder.createdAt,
        updatedAt = updatedAt,
    )

    fun toVaultFolder(doc: FolderDoc): VaultFolder = VaultFolder(
        id = doc.id,
        name = doc.name,
        colorIndex = doc.colorIndex,
        createdAt = doc.createdAt,
        hidden = doc.hidden,
    )
}
