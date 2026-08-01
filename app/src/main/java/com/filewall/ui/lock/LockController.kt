package com.filewall.ui.lock

import android.os.SystemClock
import com.filewall.data.media.ThumbnailStore
import com.filewall.data.repo.VaultRepository
import com.filewall.data.settings.SettingsStore
import com.filewall.model.AutoLock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Owns whether the hidden archive is currently open, and closes it again on idle.
 *
 * This is app-scoped rather than screen-scoped on purpose: walking from the Hidden tab to
 * Security and back must not silently re-open the archive, and the timer has to keep
 * running while the user is looking at an image.
 */
class LockController(
    private val settings: SettingsStore,
    private val repository: VaultRepository,
    private val thumbnails: ThumbnailStore,
    scope: CoroutineScope,
) {

    private val _unlocked = MutableStateFlow(false)

    /** True while hidden items may be listed and opened. */
    val hiddenUnlocked: StateFlow<Boolean> = _unlocked.asStateFlow()

    @Volatile
    private var lastInteractionAt = SystemClock.elapsedRealtime()

    init {
        scope.launch {
            // A one-second tick is plenty for a 15-second floor and costs nothing while locked.
            while (true) {
                delay(TICK_MS)
                if (!_unlocked.value) continue

                val autoLock = settings.settings.map { it.autoLock }.first()
                if (autoLock == AutoLock.NEVER) continue

                val idleMs = SystemClock.elapsedRealtime() - lastInteractionAt
                if (idleMs >= autoLock.seconds * 1_000L) lock()
            }
        }
    }

    /** Called on every touch anywhere in the app to restart the idle countdown. */
    fun touch() {
        lastInteractionAt = SystemClock.elapsedRealtime()
    }

    fun unlock() {
        touch()
        _unlocked.value = true
    }

    /** Closes the archive and destroys anything decrypted that is still lying around. */
    fun lock() {
        if (!_unlocked.value) {
            // Still worth wiping: a preview may have been exported from the unlocked side.
            repository.clearPreviewCache()
            return
        }
        _unlocked.value = false
        repository.clearPreviewCache()
        thumbnails.clear()
    }

    private companion object {
        const val TICK_MS = 1_000L
    }
}
