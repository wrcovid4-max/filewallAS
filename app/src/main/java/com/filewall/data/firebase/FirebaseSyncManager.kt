package com.filewall.data.firebase

import com.filewall.data.sync.FileDoc
import com.filewall.data.sync.FolderDoc
import com.filewall.data.sync.SyncMappers
import com.filewall.model.VaultFolder
import com.filewall.model.VaultItem
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions
import com.google.firebase.storage.FirebaseStorage
import kotlinx.coroutines.tasks.await

/**
 * The Firestore/Storage half of the sync layer (FIREBASE_BLUEPRINT.md §2–§3).
 *
 * It only *transports*: metadata upserts and tombstones go to Firestore, bytes go to Storage,
 * and snapshot listeners hand remote docs back to the caller — which runs them through
 * [com.filewall.data.sync.SyncReconciler] and into Room. Every write is `set(merge)` on a
 * deterministic id, so it is idempotent and can never duplicate.
 *
 * Bytes are uploaded exactly as given: pass ciphertext for a zero-knowledge (Tier A) vault, or
 * plaintext for Tier B. Key management across devices is the caller's concern, not this class's.
 */
class FirebaseSyncManager(private val auth: FirebaseAuthManager) {

    private val db get() = FirebaseFirestore.getInstance()
    private val storage get() = FirebaseStorage.getInstance()

    private fun uid(): String = auth.uid ?: error("Sign in to Firebase first")
    private fun files() = db.collection(USERS).document(uid()).collection(FILES)
    private fun folders() = db.collection(USERS).document(uid()).collection(FOLDERS)

    // ------------------------------------------------------------------ push

    suspend fun pushFile(item: VaultItem, checksum: String?) {
        val doc = SyncMappers.toFileDoc(item, uid(), checksum, now())
        files().document(doc.id).set(doc.toMap(), SetOptions.merge()).await()
    }

    suspend fun pushFolder(folder: VaultFolder) {
        val doc = SyncMappers.toFolderDoc(folder, uid(), now())
        folders().document(doc.id).set(doc.toMap(), SetOptions.merge()).await()
    }

    /** Soft-delete: tombstone the record so every device converges on the deletion. */
    suspend fun tombstoneFile(id: String) {
        files().document(id).set(mapOf("deletedAt" to now(), "updatedAt" to now()), SetOptions.merge()).await()
    }

    // ---------------------------------------------------------------- listen

    fun observeFiles(onChange: (List<FileDoc>) -> Unit): ListenerRegistration =
        files().addSnapshotListener { snapshot, error ->
            if (error != null || snapshot == null) return@addSnapshotListener
            onChange(snapshot.documents.mapNotNull { doc -> doc.data?.let { FileDoc.fromMap(it) } })
        }

    fun observeFolders(onChange: (List<FolderDoc>) -> Unit): ListenerRegistration =
        folders().addSnapshotListener { snapshot, error ->
            if (error != null || snapshot == null) return@addSnapshotListener
            onChange(snapshot.documents.mapNotNull { doc -> doc.data?.let { FolderDoc.fromMap(it) } })
        }

    // ------------------------------------------------------------- one-shot fetch

    /** Bounded fetch for a manual/background sync pass — §3.6's delta query, unbounded here. */
    suspend fun fetchFiles(): List<FileDoc> =
        files().get().await().documents.mapNotNull { doc -> doc.data?.let { FileDoc.fromMap(it) } }

    suspend fun fetchFolders(): List<FolderDoc> =
        folders().get().await().documents.mapNotNull { doc -> doc.data?.let { FolderDoc.fromMap(it) } }

    // ---------------------------------------------------------------- settings

    private fun settingsDoc() = db.collection(USERS).document(uid()).collection(META).document(SETTINGS)

    suspend fun pushSettings(fields: Map<String, Any?>) {
        settingsDoc().set(fields + mapOf("updatedAt" to now()), SetOptions.merge()).await()
    }

    suspend fun fetchSettings(): Map<String, Any?>? = settingsDoc().get().await().data

    fun observeSettings(onChange: (Map<String, Any?>) -> Unit): ListenerRegistration =
        settingsDoc().addSnapshotListener { snapshot, error ->
            if (error != null || snapshot?.data == null) return@addSnapshotListener
            onChange(snapshot.data!!)
        }

    // ------------------------------------------------------------------ bytes

    suspend fun uploadBytes(fileId: String, bytes: ByteArray) {
        storage.reference.child(FileDoc.storagePath(uid(), fileId)).putBytes(bytes).await()
    }

    suspend fun downloadBytes(fileId: String, maxBytes: Long = MAX_DOWNLOAD): ByteArray =
        storage.reference.child(FileDoc.storagePath(uid(), fileId)).getBytes(maxBytes).await()

    private fun now() = System.currentTimeMillis()

    private companion object {
        const val USERS = "users"
        const val FILES = "files"
        const val FOLDERS = "folders"
        const val META = "meta"
        const val SETTINGS = "settings"
        const val MAX_DOWNLOAD = 200L * 1024 * 1024   // 200 MiB per fetch
    }
}
