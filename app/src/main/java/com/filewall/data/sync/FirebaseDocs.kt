package com.filewall.data.sync

/**
 * Firestore document shapes for the multi-platform sync layer (see FIREBASE_BLUEPRINT.md).
 *
 * These are plain, Map-serialisable data classes deliberately kept independent of the Firebase
 * SDK: the SDK layer only has to turn a [Map] into a `DocumentSnapshot` and back, while all the
 * mapping, dedup and merge logic lives here where it can be reasoned about and unit-tested with
 * no emulator. Every synced entity is keyed by a deterministic [id] so writes are pure upserts.
 */
interface SyncDoc {
    val id: String
    /** Epoch millis of the last write; the referee for last-write-wins merges. */
    val updatedAt: Long
    /** 0 = live; otherwise the epoch millis the record was tombstoned. */
    val deletedAt: Long
}

/** A file / image record: `users/{uid}/files/{id}`. Superset of the app's VaultItem. */
data class FileDoc(
    override val id: String,
    val ownerUid: String,
    val name: String,
    val mimeType: String,
    val category: String,            // PHOTO | VIDEO | DOC | OTHER
    val sizeBytes: Long,
    val width: Int = 0,
    val height: Int = 0,
    val folderId: String? = null,
    val storagePath: String,
    val thumbPath: String? = null,
    val checksum: String? = null,    // sha256:… — secondary dedup key
    val hidden: Boolean = false,
    val archived: Boolean = false,
    override val deletedAt: Long = 0,
    val status: String = STATUS_READY,
    val createdAt: Long,
    override val updatedAt: Long,
) : SyncDoc {

    fun toMap(): Map<String, Any?> = linkedMapOf(
        "id" to id,
        "ownerUid" to ownerUid,
        "name" to name,
        "mimeType" to mimeType,
        "category" to category,
        "sizeBytes" to sizeBytes,
        "width" to width,
        "height" to height,
        "folderId" to folderId,
        "storagePath" to storagePath,
        "thumbPath" to thumbPath,
        "checksum" to checksum,
        "hidden" to hidden,
        "archived" to archived,
        "deletedAt" to deletedAt,
        "status" to status,
        "createdAt" to createdAt,
        "updatedAt" to updatedAt,
    )

    companion object {
        const val STATUS_UPLOADING = "uploading"
        const val STATUS_READY = "ready"

        fun storagePath(uid: String, id: String) = "users/$uid/files/$id"
        fun thumbPath(uid: String, id: String) = "users/$uid/thumbs/$id"

        fun fromMap(map: Map<String, Any?>): FileDoc {
            val id = map.str("id") ?: error("FileDoc missing id")
            val uid = map.str("ownerUid").orEmpty()
            return FileDoc(
                id = id,
                ownerUid = uid,
                name = map.str("name").orEmpty(),
                mimeType = map.str("mimeType") ?: "application/octet-stream",
                category = map.str("category") ?: "OTHER",
                sizeBytes = map.long("sizeBytes"),
                width = map.long("width").toInt(),
                height = map.long("height").toInt(),
                folderId = map.str("folderId"),
                storagePath = map.str("storagePath") ?: storagePath(uid, id),
                thumbPath = map.str("thumbPath"),
                checksum = map.str("checksum"),
                hidden = map.bool("hidden"),
                archived = map.bool("archived"),
                deletedAt = map.long("deletedAt"),
                status = map.str("status") ?: STATUS_READY,
                createdAt = map.long("createdAt"),
                updatedAt = map.long("updatedAt"),
            )
        }
    }
}

/** A folder record: `users/{uid}/folders/{id}`. */
data class FolderDoc(
    override val id: String,
    val ownerUid: String,
    val name: String,
    val parentId: String? = null,
    val path: String = "/",
    val colorIndex: Int = 0,
    val hidden: Boolean = false,
    override val deletedAt: Long = 0,
    val createdAt: Long,
    override val updatedAt: Long,
) : SyncDoc {

    fun toMap(): Map<String, Any?> = linkedMapOf(
        "id" to id,
        "ownerUid" to ownerUid,
        "name" to name,
        "parentId" to parentId,
        "path" to path,
        "colorIndex" to colorIndex,
        "hidden" to hidden,
        "deletedAt" to deletedAt,
        "createdAt" to createdAt,
        "updatedAt" to updatedAt,
    )

    companion object {
        fun fromMap(map: Map<String, Any?>): FolderDoc {
            val id = map.str("id") ?: error("FolderDoc missing id")
            return FolderDoc(
                id = id,
                ownerUid = map.str("ownerUid").orEmpty(),
                name = map.str("name").orEmpty(),
                parentId = map.str("parentId"),
                path = map.str("path") ?: "/",
                colorIndex = map.long("colorIndex").toInt(),
                hidden = map.bool("hidden"),
                deletedAt = map.long("deletedAt"),
                createdAt = map.long("createdAt"),
                updatedAt = map.long("updatedAt"),
            )
        }
    }
}

// -- lenient readers: Firestore hands back Long/Boolean/String/null with loose typing --

private fun Map<String, Any?>.str(key: String): String? = this[key] as? String
private fun Map<String, Any?>.long(key: String): Long = when (val v = this[key]) {
    is Number -> v.toLong()
    is String -> v.toLongOrNull() ?: 0L
    else -> 0L
}
private fun Map<String, Any?>.bool(key: String): Boolean = this[key] as? Boolean ?: false
