package com.filewall.data.sync

import android.content.Context
import android.content.Intent
import com.filewall.data.firebase.FirebaseAuthManager
import com.filewall.data.firebase.FirebaseGate
import com.filewall.data.firebase.FirebaseSyncManager
import com.filewall.data.repo.VaultRepository
import com.filewall.data.settings.SettingsStore
import com.filewall.model.SortField
import com.filewall.model.ThemeMode
import com.filewall.model.VaultFolder
import com.filewall.model.VaultItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import java.io.IOException
import java.security.MessageDigest

/**
 * The one class every screen and every mutation goes through to reach the cloud
 * (FIREBASE_BLUEPRINT.md, wired onto the concrete FileWall data model).
 *
 * Nothing else in the app calls [FirebaseAuthManager] or [FirebaseSyncManager] directly —
 * before this class existed those two were fully built and completely unused, which is the
 * actual reason sync "wasn't working": there was no bug in them, nothing ever called them.
 *
 * Design choices worth knowing about before changing this file:
 * - **Metadata sync is unconditional; byte sync needs a sync passphrase.** A file's row
 *   (name, folder, hidden/archived flags, timestamps) is genuinely harmless as plaintext
 *   metadata and syncs the moment you're signed in. The *bytes* only ever leave the device
 *   as ciphertext under [SyncCrypto], which needs a passphrase this device doesn't already
 *   have unless you type it once — see [setSyncPassphrase]. Until then, [syncNow] still syncs
 *   every file's metadata (so other devices can *see* it exists and where), it just leaves
 *   `status="metadata_only"` until the bytes follow.
 * - **Hidden items are included on purpose.** The user's explicit call: hidden-vault items
 *   sync like everything else, flagged `hidden=true` in the doc, encrypted like everything
 *   else. Nothing here treats hidden specially beyond carrying the flag through.
 * - **No per-mutation push hooks.** Instead of threading a push call through every mutation
 *   method on [VaultRepository] (rename, move, hide, trash, restore, archive, import — a lot
 *   of call sites to keep in sync forever), [syncNow] does a cheap diff pass over
 *   `updatedAt`-stamped rows. [com.filewall.ui.FileWallRoot] calls it a couple of seconds after
 *   any local change (see the debounced `LaunchedEffect` there) and Security has a manual
 *   "Sync now". Same end result, one call site to maintain.
 */
class SyncCoordinator(
    private val context: Context,
    private val repository: VaultRepository,
    private val settingsStore: SettingsStore,
    private val auth: FirebaseAuthManager,
    private val sync: FirebaseSyncManager,
    private val passphraseStore: SyncPassphraseStore,
) {

    private val cursorPrefs = context.getSharedPreferences("filewall_sync_cursor", Context.MODE_PRIVATE)

    private val _status = MutableStateFlow<SyncStatus>(if (isSignedIn) SyncStatus.Idle else SyncStatus.SignedOut)
    val status: StateFlow<SyncStatus> = _status.asStateFlow()

    val isAvailable: Boolean get() = FirebaseGate.isConfigured
    val isSignedIn: Boolean get() = isAvailable && auth.uid != null
    val hasSyncPassphrase: Boolean get() = passphraseStore.isStored
    val signInIntent: Intent get() = auth.signInIntent

    suspend fun completeSignIn(data: Intent?) {
        auth.completeSignIn(data)
        _status.value = SyncStatus.Idle
    }

    suspend fun signOut() {
        auth.signOut()
        passphraseStore.clear()
        cursorPrefs.edit().clear().apply()
        _status.value = SyncStatus.SignedOut
    }

    /** Sets the passphrase for *this* device and immediately syncs so bytes start flowing. */
    suspend fun setSyncPassphrase(passphrase: CharArray) {
        passphraseStore.store(passphrase)
        syncNow()
    }

    // -------------------------------------------------------------------- the sync pass

    /**
     * One bounded, safe-to-repeat reconciliation. Pulls the full remote file/folder/settings
     * state, diffs it against local via [SyncReconciler], applies whichever side is newer, then
     * pushes anything local that changed since the last pass. Never duplicates: every write is
     * `set(deterministicId, merge)` on the transport side (see [FirebaseSyncManager]).
     */
    suspend fun syncNow() {
        if (!isAvailable) {
            _status.value = SyncStatus.Error("Cloud sync isn't configured yet (see GOOGLE_SETUP.md)")
            return
        }
        if (!isSignedIn) {
            _status.value = SyncStatus.SignedOut
            return
        }

        try {
            _status.value = SyncStatus.Syncing("Checking for changes…")
            val uid = requireNotNull(auth.uid)

            val remoteFiles = sync.fetchFiles()
            val remoteFolders = sync.fetchFolders()
            val localItems = repository.allItems()
            val localFolders = repository.allFolders()

            // ---- folders first: files reference folderId, so folders should land before files
            val folderActions = SyncReconciler.reconcileSets(
                localFolders.map { SyncMappers.toFolderDoc(it, uid, it.updatedAt) },
                remoteFolders,
            )
            var pulled = 0
            var pushed = 0
            for ((id, action) in folderActions) {
                when (action) {
                    SyncReconciler.Action.UPSERT_LOCAL -> {
                        remoteFolders.first { it.id == id }.let { doc ->
                            repository.upsertRemoteFolder(SyncMappers.toVaultFolder(doc))
                        }
                        pulled++
                    }
                    SyncReconciler.Action.PUSH_REMOTE -> {
                        localFolders.firstOrNull { it.id == id }?.let { sync.pushFolder(it) }
                        pushed++
                    }
                    SyncReconciler.Action.DELETE_LOCAL, SyncReconciler.Action.NONE -> Unit
                }
            }

            // ---- files: metadata always; bytes only when a sync passphrase is set
            val passphrase = passphraseStore.read()
            val fileActions = SyncReconciler.reconcileSets(
                localItems.map { SyncMappers.toFileDoc(it, uid, checksum = null, updatedAt = it.updatedAt) },
                remoteFiles,
            )
            var bytesSkipped = 0
            for ((id, action) in fileActions) {
                when (action) {
                    SyncReconciler.Action.UPSERT_LOCAL -> {
                        val doc = remoteFiles.first { it.id == id }
                        repository.upsertRemoteItem(SyncMappers.toVaultItem(doc))
                        if (passphrase != null) {
                            runCatching {
                                val cipher = sync.downloadBytes(id)
                                val plain = SyncCrypto.decrypt(cipher, passphrase)
                                repository.writeIncomingBlob(SyncMappers.toVaultItem(doc), plain)
                            }
                        } else {
                            bytesSkipped++
                        }
                        pulled++
                    }
                    SyncReconciler.Action.DELETE_LOCAL -> {
                        repository.trashLocalOnly(id, System.currentTimeMillis())
                    }
                    SyncReconciler.Action.PUSH_REMOTE -> {
                        localItems.firstOrNull { it.id == id }?.let { item ->
                            val checksum = passphrase?.let { pp ->
                                runCatching {
                                    val plain = repository.encryptedBlobBytes(item)
                                    val cipher = SyncCrypto.encrypt(plain, pp)
                                    sync.uploadBytes(id, cipher)
                                    sha256(cipher)
                                }.getOrNull()
                            } ?: run { bytesSkipped++; null }
                            sync.pushFile(item, checksum)
                        }
                        pushed++
                    }
                    SyncReconciler.Action.NONE -> Unit
                }
            }
            passphrase?.fill('\u0000')

            // ---- settings: cosmetic/organizational fields only — never security posture
            // (auto-lock, biometrics, screenshots, passcode fallback stay per-device on purpose)
            syncSettings(uid)

            cursorPrefs.edit().putLong(LAST_SYNC_KEY, System.currentTimeMillis()).apply()
            _status.value = when {
                bytesSkipped > 0 && !hasSyncPassphrase ->
                    SyncStatus.Error("Signed in, but no sync passphrase set — files won't sync until one is")
                else -> SyncStatus.Synced(System.currentTimeMillis())
            }
        } catch (io: IOException) {
            _status.value = SyncStatus.Error(io.message ?: "Sync failed")
        } catch (error: Exception) {
            _status.value = SyncStatus.Error(error.message ?: "Sync failed")
        }
    }

    private suspend fun syncSettings(uid: String) {
        val local = settingsStore.settings.first()
        val remote = sync.fetchSettings()
        val remoteUpdatedAt = (remote?.get("updatedAt") as? Number)?.toLong() ?: 0L
        val localCursor = cursorPrefs.getLong(SETTINGS_PUSHED_AT_KEY, 0L)

        if (remote != null && remoteUpdatedAt > localCursor) {
            (remote["theme"] as? String)?.let {
                runCatching { ThemeMode.valueOf(it) }.getOrNull()?.let { mode -> settingsStore.setTheme(mode) }
            }
            (remote["gridView"] as? Boolean)?.let { settingsStore.setGridView(it) }
            (remote["sortField"] as? String)?.let {
                runCatching { SortField.valueOf(it) }.getOrNull()?.let { f -> settingsStore.setSortField(f) }
            }
            (remote["sortAscending"] as? Boolean)?.let { settingsStore.setSortAscending(it) }
            (remote["showDocPreviews"] as? Boolean)?.let { settingsStore.setShowDocPreviews(it) }
            cursorPrefs.edit().putLong(SETTINGS_PUSHED_AT_KEY, remoteUpdatedAt).apply()
        } else {
            sync.pushSettings(
                mapOf(
                    "theme" to local.theme.name,
                    "gridView" to local.gridView,
                    "sortField" to local.sortField.name,
                    "sortAscending" to local.sortAscending,
                    "showDocPreviews" to local.showDocPreviews,
                ),
            )
            cursorPrefs.edit().putLong(SETTINGS_PUSHED_AT_KEY, System.currentTimeMillis()).apply()
        }
    }

    private fun sha256(bytes: ByteArray): String =
        "sha256:" + MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private companion object {
        const val LAST_SYNC_KEY = "last_sync_at"
        const val SETTINGS_PUSHED_AT_KEY = "settings_synced_at"
    }
}
