package com.filewall.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.filewall.shared.WearCategory

/** Bucket a file falls into for the storage breakdown and the type badge on each tile. */
enum class FileCategory {
    PHOTO, VIDEO, DOC, OTHER;

    fun toWear(): WearCategory = when (this) {
        PHOTO -> WearCategory.PHOTO
        VIDEO -> WearCategory.VIDEO
        DOC -> WearCategory.DOC
        OTHER -> WearCategory.OTHER
    }

    companion object {
        fun fromMime(mimeType: String?, fileName: String? = null): FileCategory {
            val mime = mimeType?.lowercase().orEmpty()
            return when {
                mime.startsWith("image/") -> PHOTO
                mime.startsWith("video/") -> VIDEO
                mime.startsWith("audio/") -> OTHER
                mime.startsWith("text/") -> DOC
                mime in DOC_MIMES -> DOC
                else -> fromExtension(fileName)
            }
        }

        private fun fromExtension(fileName: String?): FileCategory {
            val ext = fileName?.substringAfterLast('.', "")?.lowercase().orEmpty()
            return when (ext) {
                "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "avif" -> PHOTO
                "mp4", "mkv", "webm", "mov", "3gp", "avi", "m4v" -> VIDEO
                "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "md", "csv",
                "rtf", "odt", "epub", "json", "xml",
                -> DOC
                else -> OTHER
            }
        }

        private val DOC_MIMES = setOf(
            "application/pdf",
            "application/msword",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.ms-excel",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "application/vnd.ms-powerpoint",
            "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            "application/rtf",
            "application/epub+zip",
            "application/json",
            "application/xml",
        )
    }
}

/**
 * One encrypted file in the vault.
 *
 * [blobName] and [thumbName] are opaque file names inside the app's private storage; the
 * original name only ever exists in this row, which itself lives in the app's private
 * database. Nothing on disk reveals what a blob contains.
 */
@Entity(
    tableName = "vault_items",
    indices = [Index("folderId"), Index("hidden"), Index("addedAt")],
)
data class VaultItem(
    @PrimaryKey val id: String,
    val name: String,
    val mimeType: String,
    val sizeBytes: Long,
    val addedAt: Long,
    val folderId: String?,
    val hidden: Boolean,
    val category: FileCategory,
    val blobName: String,
    val thumbName: String?,
    @ColumnInfo(defaultValue = "0") val width: Int = 0,
    @ColumnInfo(defaultValue = "0") val height: Int = 0,
    /** 0 = live; otherwise the epoch-millis the file was moved to Recently Deleted. */
    @ColumnInfo(defaultValue = "0") val deletedAt: Long = 0,
    /** True while the file sits in the Archive folder (still stored, hidden from the vault). */
    @ColumnInfo(defaultValue = "0") val archived: Boolean = false,
    /** Epoch millis of the last local mutation. The referee for cloud sync's last-write-wins. */
    @ColumnInfo(defaultValue = "0") val updatedAt: Long = 0,
) {
    val extension: String get() = name.substringAfterLast('.', "")

    /** PDFs render in-app; other documents hand off to an external viewer. */
    val isPdf: Boolean
        get() = mimeType.equals("application/pdf", ignoreCase = true) ||
            extension.equals("pdf", ignoreCase = true)
}

/** A user-made folder. Colour is an index into [FolderPalette]. */
@Entity(tableName = "vault_folders", indices = [Index("hidden")])
data class VaultFolder(
    @PrimaryKey val id: String,
    val name: String,
    val colorIndex: Int,
    val createdAt: Long,
    val hidden: Boolean,
    /** Epoch millis of the last local mutation. The referee for cloud sync's last-write-wins. */
    @ColumnInfo(defaultValue = "0") val updatedAt: Long = 0,
)

/** Folder tint plus its item count, which is what the folder grid actually renders. */
data class FolderWithCount(
    val folder: VaultFolder,
    val itemCount: Int,
)

/** Totals behind the Security tab's segmented storage bar. */
data class StorageBreakdown(
    val photoBytes: Long = 0,
    val videoBytes: Long = 0,
    val docBytes: Long = 0,
    val otherBytes: Long = 0,
) {
    val totalBytes: Long get() = photoBytes + videoBytes + docBytes + otherBytes
}

/** Ordering offered by the "Date Added" dropdown in the vault toolbar. */
enum class SortField { DATE_ADDED, NAME, SIZE, TYPE }

/** Which half of the Unlocked / Hidden pill is active. */
enum class VaultFilter { UNLOCKED, HIDDEN }

/** A non-folder listing the vault can show instead of the normal file grid. */
enum class SpecialView { NONE, TRASH, ARCHIVE }

/** Appearance choices in the Security tab. */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** Inactivity Auto-Lock options. [NEVER] is represented by a negative timeout. */
enum class AutoLock(val seconds: Int) {
    SECONDS_15(15),
    SECONDS_30(30),
    MINUTE_1(60),
    MINUTES_5(300),
    NEVER(-1);

    companion object {
        fun fromSeconds(value: Int): AutoLock =
            entries.firstOrNull { it.seconds == value } ?: SECONDS_15
    }
}
