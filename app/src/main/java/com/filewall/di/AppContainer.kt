package com.filewall.di

import android.content.Context
import com.filewall.data.backup.DriveBackup
import com.filewall.data.backup.VaultArchive
import com.filewall.data.crypto.PinManager
import com.filewall.data.crypto.VaultCrypto
import com.filewall.data.db.VaultDatabase
import com.filewall.data.media.ThumbnailStore
import com.filewall.data.repo.VaultRepository
import com.filewall.data.settings.SettingsStore
import com.filewall.data.wear.WearSyncManager
import com.filewall.ui.lock.LockController
import kotlinx.coroutines.CoroutineScope

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

    val wearSync: WearSyncManager by lazy { WearSyncManager(appContext, repository, settings) }

    val lock: LockController by lazy {
        LockController(settings, repository, thumbnails, appScope)
    }
}
