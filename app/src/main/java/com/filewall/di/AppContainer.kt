package com.filewall.di

import android.content.Context
import com.filewall.data.backup.AutoBackupSecret
import com.filewall.data.backup.DriveBackup
import com.filewall.data.backup.VaultArchive
import com.filewall.data.crypto.PinManager
import com.filewall.data.crypto.VaultCrypto
import com.filewall.data.db.VaultDatabase
import com.filewall.data.firebase.FirebaseAuthManager
import com.filewall.data.firebase.FirebaseGate
import com.filewall.data.firebase.FirebaseSyncManager
import com.filewall.data.media.ThumbnailStore
import com.filewall.data.repo.VaultRepository
import com.filewall.data.settings.SettingsStore
import com.filewall.data.sync.SyncCoordinator
import com.filewall.data.sync.SyncPassphraseStore
import com.filewall.data.wear.WearSyncManager
import com.filewall.ui.lock.LockController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Hand-rolled dependency graph.
 *
 * The object count here is small enough that a DI framework would add a build step and an
 * annotation processor without removing any real work, so everything is constructed once,
 * lazily, and read off [com.filewall.FileWallApp].
 */
class AppContainer(context: Context, appScope: CoroutineScope) {

    private val appContext = context.applicationContext

    val crypto: VaultCrypto by lazy { VaultCrypto() }

    private val database: VaultDatabase by lazy { VaultDatabase.create(appContext) }

    val repository: VaultRepository by lazy {
        VaultRepository(appContext, database.vaultDao(), crypto)
    }

    val settings: SettingsStore by lazy { SettingsStore(appContext) }

    val pinManager: PinManager by lazy { PinManager(appContext) }

    val thumbnails: ThumbnailStore by lazy { ThumbnailStore(repository) }

    val archive: VaultArchive by lazy { VaultArchive(appContext, repository) }

    val drive: DriveBackup by lazy { DriveBackup(appContext) }

    val autoBackupSecret: AutoBackupSecret by lazy { AutoBackupSecret(appContext) }

    val wearSync: WearSyncManager by lazy { WearSyncManager(appContext, repository, settings) }

    // ---- Firebase cloud sync (FIREBASE_BLUEPRINT.md) ----
    // FirebaseGate.init() already ran in FileWallApp.onCreate(); these are no-ops to construct
    // even when it returned false — every real Firebase call inside them checks FirebaseGate.
    val firebaseAuth: FirebaseAuthManager by lazy { FirebaseAuthManager(appContext) }
    val firebaseSync: FirebaseSyncManager by lazy { FirebaseSyncManager(firebaseAuth) }
    val syncPassphrase: SyncPassphraseStore by lazy { SyncPassphraseStore(appContext) }
    val syncCoordinator: SyncCoordinator by lazy {
        SyncCoordinator(appContext, repository, settings, firebaseAuth, firebaseSync, syncPassphrase)
    }

    val lock: LockController by lazy {
        LockController(settings, repository, thumbnails, appScope)
    }

    /**
     * Item the user asked to open from their watch, waiting for the UI to pick it up.
     *
     * Lives on the container rather than in an Activity extra because the notification can
     * arrive while the app is already running, in which case there is no fresh Intent to read.
     */
    val pendingOpen = MutableStateFlow<String?>(null)
}
