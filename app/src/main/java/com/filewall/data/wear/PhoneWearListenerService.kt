package com.filewall.data.wear

import android.util.Log
import com.filewall.FileWallApp
import com.filewall.shared.WearProtocol
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.runBlocking

/**
 * Answers the watch. Play Services delivers these callbacks on a background thread and
 * keeps the process alive for their duration, so blocking here is the intended shape.
 */
class PhoneWearListenerService : WearableListenerService() {

    private val sync: WearSyncManager
        get() = (applicationContext as FileWallApp).container.wearSync

    override fun onMessageReceived(event: MessageEvent) {
        when (event.path) {
            WearProtocol.PATH_REQUEST_MANIFEST -> runBlocking {
                runCatching { sync.publishManifest() }
                    .onFailure { Log.w(TAG, "Could not publish manifest", it) }
            }

            WearProtocol.PATH_REQUEST_IMAGE -> {
                val itemId = event.data.decodeToString()
                if (itemId.isNotBlank()) {
                    runBlocking {
                        runCatching { sync.publishImage(itemId) }
                            .onFailure { Log.w(TAG, "Could not publish image $itemId", it) }
                    }
                }
            }

            else -> super.onMessageReceived(event)
        }
    }

    private companion object {
        const val TAG = "FileWallWear"
    }
}
