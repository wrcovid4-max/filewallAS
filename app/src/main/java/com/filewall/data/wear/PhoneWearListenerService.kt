package com.filewall.data.wear

import android.util.Log
import com.filewall.FileWallApp
import com.filewall.shared.WearProtocol
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * Answers the watch. Play Services delivers these callbacks on a background thread and
 * keeps the process alive for their duration, so blocking here is the intended shape.
 */
class PhoneWearListenerService : WearableListenerService() {

    private val container get() = (applicationContext as FileWallApp).container

    override fun onMessageReceived(event: MessageEvent) {
        when (event.path) {
            WearProtocol.PATH_REQUEST_MANIFEST -> runBlocking {
                runCatching { container.wearSync.publishManifest() }
                    .onFailure { Log.w(TAG, "Could not publish manifest", it) }
            }

            WearProtocol.PATH_REQUEST_IMAGE -> withItemId(event) { itemId ->
                runBlocking {
                    runCatching { container.wearSync.publishImage(itemId) }
                        .onFailure { Log.w(TAG, "Could not publish image $itemId", it) }
                }
            }

            WearProtocol.PATH_OPEN_ON_PHONE -> withItemId(event) { itemId ->
                runBlocking {
                    runCatching {
                        val item = container.repository.observeItem(itemId).first()
                        // A hidden item is not in the manifest, so the watch should not know
                        // its id at all — refuse rather than announce that it exists.
                        if (item != null && !item.hidden) {
                            HandoffNotifier(applicationContext).notifyOpenRequest(item)
                        }
                    }.onFailure { Log.w(TAG, "Could not hand off $itemId", it) }
                }
            }

            else -> super.onMessageReceived(event)
        }
    }

    private inline fun withItemId(event: MessageEvent, block: (String) -> Unit) {
        val itemId = event.data?.decodeToString().orEmpty()
        if (itemId.isNotBlank()) block(itemId)
    }

    private companion object {
        const val TAG = "FileWallWear"
    }
}
