package com.filewall.data.drive

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log

/**
 * A Google Drive folder chosen with Android's own folder picker. No Drive API or Google Cloud
 * setup is needed: Android gives the app permission to write into the folder the user picked.
 * Files saved here are plain copies, so they open anywhere.
 */
object DriveFolder {
    private const val PREFS = "drive_folder"
    private const val KEY = "tree_uri"

    fun save(context: Context, treeUri: Uri) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }.onFailure { Log.e("FileWallDoc", "drive: could not keep access to the folder", it) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY, treeUri.toString())
            .apply()
        Log.i("FileWallDoc", "drive: folder chosen")
    }

    fun get(context: Context): Uri? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, null)
            ?.let { Uri.parse(it) }

    /** The name of the chosen folder as Drive shows it, or null if it cannot be read. */
    fun displayName(context: Context): String? {
        val tree = get(context) ?: return null
        val folder = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        return runCatching {
            context.contentResolver
                .query(folder, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)
                ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        }.getOrNull()
    }

    /** Creates an empty file with this name in the chosen folder and returns its address. */
    fun createTarget(context: Context, name: String): Uri? {
        val tree = get(context) ?: return null
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val mime = when (name.substringAfterLast('.', "").lowercase()) {
            "pdf" -> "application/pdf"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "txt" -> "text/plain"
            else -> "application/octet-stream"
        }
        val target = DocumentsContract.createDocument(context.contentResolver, parent, mime, name)
        if (target == null) {
            Log.w("FileWallDoc", "drive: the folder did not accept $name")
        } else {
            Log.i("FileWallDoc", "drive: created target for $name")
        }
        return target
    }
}
