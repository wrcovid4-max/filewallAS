package com.filewall

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.filewall.di.AppContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class FileWallApp : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val container: AppContainer by lazy { AppContainer(this, appScope) }

    override fun onCreate() {
        super.onCreate()

        // A previous run may have died with plaintext still in the preview cache.
        container.repository.clearPreviewCache()

        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStop(owner: LifecycleOwner) {
                    // Leaving the app always closes the hidden archive, whatever the
                    // inactivity setting says — that setting governs idling *inside* the app.
                    container.lock.lock()
                }
            },
        )

        appScope.launch {
            // Keep the watch's manifest in step with the unlocked side of the vault.
            container.repository.observeItems(hidden = false)
                .map { items -> items.map { it.id to it.addedAt } }
                .distinctUntilChanged()
                .collect {
                    runCatching { container.wearSync.publishManifest() }
                }
        }

        appScope.launch {
            container.settings.settings
                .map { it.syncToWatch }
                .distinctUntilChanged()
                .drop(1)
                .collect { enabled ->
                    runCatching {
                        if (enabled) container.wearSync.publishManifest() else container.wearSync.clearAll()
                    }
                }
        }
    }
}
