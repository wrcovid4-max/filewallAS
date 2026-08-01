package com.filewall.wear.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.filewall.shared.WearItem
import com.filewall.shared.WearProtocol
import com.google.android.gms.wearable.Asset
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.PutDataRequest
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/** What the watch currently knows about the phone's vault. */
data class WatchState(
    val items: List<WearItem> = emptyList(),
    val totalBytes: Long = 0,
    val connected: Boolean = false,
    val loaded: Boolean = false,
)

/**
 * Watch half of the Data Layer link.
 *
 * The watch holds no vault of its own — it mirrors whatever manifest the phone last
 * published, and asks for a full image only when one is actually opened. Nothing here can
 * request a hidden item, because hidden items are never named in the manifest.
 */
class WatchRepository(private val context: Context) : DataClient.OnDataChangedListener {

    private val dataClient by lazy { Wearable.getDataClient(context) }
    private val messageClient by lazy { Wearable.getMessageClient(context) }
    private val capabilityClient by lazy { Wearable.getCapabilityClient(context) }

    private val _state = MutableStateFlow(WatchState())
    val state: StateFlow<WatchState> = _state.asStateFlow()

    private val _thumbnails = MutableStateFlow<Map<String, Bitmap>>(emptyMap())
    val thumbnails: StateFlow<Map<String, Bitmap>> = _thumbnails.asStateFlow()

    private val _images = MutableStateFlow<Map<String, Bitmap>>(emptyMap())
    val images: StateFlow<Map<String, Bitmap>> = _images.asStateFlow()

    // Assets are handles, not bytes: they can only be read back through the client, so the
    // handles are kept until something actually asks for the picture.
    private val thumbAssets = ConcurrentHashMap<String, Asset>()
    private val imageAssets = ConcurrentHashMap<String, Asset>()

    fun start() {
        dataClient.addListener(this)
    }

    fun stop() {
        dataClient.removeListener(this)
    }

    // ------------------------------------------------------------------ syncing

    /** Reads whatever is already cached locally, then asks the phone for something fresher. */
    suspend fun refresh() {
        val reachable = hasPhone()
        _state.update { it.copy(connected = reachable) }

        readCachedManifest()

        if (reachable) {
            runCatching { broadcast(WearProtocol.PATH_REQUEST_MANIFEST, ByteArray(0)) }
        }
    }

    /** Asks the phone to publish one full-size image. The result arrives via [onDataChanged]. */
    suspend fun requestImage(itemId: String) {
        if (_images.value.containsKey(itemId) || imageAssets.containsKey(itemId)) return
        // It may already be sitting in the Data Layer from a previous open.
        readCachedImage(itemId)
        if (imageAssets.containsKey(itemId)) return
        runCatching { broadcast(WearProtocol.PATH_REQUEST_IMAGE, itemId.toByteArray()) }
    }

    private suspend fun reachableNodes(): List<String> = withContext(Dispatchers.IO) {
        runCatching {
            capabilityClient
                .getCapability(WearProtocol.CAPABILITY_PHONE, CapabilityClient.FILTER_REACHABLE)
                .await()
                .nodes
                .map { it.id }
        }.getOrDefault(emptyList())
    }

    private suspend fun hasPhone(): Boolean = reachableNodes().isNotEmpty()

    private suspend fun broadcast(path: String, payload: ByteArray) = withContext(Dispatchers.IO) {
        reachableNodes().forEach { nodeId ->
            runCatching { messageClient.sendMessage(nodeId, path, payload).await() }
        }
    }

    private suspend fun readCachedManifest() = withContext(Dispatchers.IO) {
        runCatching {
            val buffer = dataClient.getDataItems(wearUri(WearProtocol.PATH_MANIFEST)).await()
            try {
                buffer.firstOrNull()?.let { applyManifest(DataMapItem.fromDataItem(it)) }
            } finally {
                buffer.release()
            }
        }
        _state.update { it.copy(loaded = true) }
    }

    private suspend fun readCachedImage(itemId: String) = withContext(Dispatchers.IO) {
        runCatching {
            val buffer = dataClient.getDataItems(wearUri(WearProtocol.imagePath(itemId))).await()
            try {
                buffer.firstOrNull()?.let { applyImage(itemId, DataMapItem.fromDataItem(it)) }
            } finally {
                buffer.release()
            }
        }
        Unit
    }

    // ---------------------------------------------------------------- callbacks

    override fun onDataChanged(events: DataEventBuffer) {
        // The buffer is recycled the moment this returns, so read everything out first.
        val manifests = mutableListOf<DataMapItem>()
        val imagePayloads = mutableListOf<Pair<String, DataMapItem>>()

        events.forEach { event ->
            if (event.type != DataEvent.TYPE_CHANGED) return@forEach
            val item = event.dataItem
            val path = item.uri.path ?: return@forEach
            when {
                path == WearProtocol.PATH_MANIFEST -> manifests.add(DataMapItem.fromDataItem(item))

                path.startsWith(WearProtocol.PATH_IMAGE_PREFIX) ->
                    WearProtocol.itemIdFromImagePath(path)?.let { id ->
                        imagePayloads.add(id to DataMapItem.fromDataItem(item))
                    }
            }
        }

        manifests.forEach { applyManifest(it) }
        imagePayloads.forEach { (id, mapItem) -> applyImage(id, mapItem) }
    }

    private fun applyManifest(mapItem: DataMapItem) {
        val map = mapItem.dataMap
        val items = WearItem.decodeList(map.getString(WearProtocol.ManifestKey.ITEMS_JSON))

        _state.update {
            it.copy(
                items = items,
                totalBytes = map.getLong(WearProtocol.ManifestKey.TOTAL_BYTES),
                connected = true,
                loaded = true,
            )
        }

        // A file deleted on the phone should stop being drawable here too.
        thumbAssets.clear()
        items.filter { it.hasThumb }.forEach { entry ->
            map.getAsset(WearProtocol.ManifestKey.thumb(entry.id))?.let { thumbAssets[entry.id] = it }
        }
        val live = items.map { it.id }.toSet()
        _thumbnails.update { cached -> cached.filterKeys { it in live } }
        _images.update { cached -> cached.filterKeys { it in live } }
    }

    private fun applyImage(itemId: String, mapItem: DataMapItem) {
        mapItem.dataMap.getAsset(WearProtocol.ImageKey.ASSET)?.let { imageAssets[itemId] = it }
    }

    // ------------------------------------------------------------------ decoding

    /** Decodes a thumbnail on demand, memoised so scrolling does not re-read the asset. */
    suspend fun loadThumbnail(itemId: String): Bitmap? {
        _thumbnails.value[itemId]?.let { return it }
        val asset = thumbAssets[itemId] ?: return null
        val bitmap = decode(asset) ?: return null
        _thumbnails.update { it + (itemId to bitmap) }
        return bitmap
    }

    suspend fun loadImage(itemId: String): Bitmap? {
        _images.value[itemId]?.let { return it }
        val asset = imageAssets[itemId] ?: return null
        val bitmap = decode(asset) ?: return null
        _images.update { it + (itemId to bitmap) }
        return bitmap
    }

    private suspend fun decode(asset: Asset): Bitmap? = withContext(Dispatchers.IO) {
        runCatching {
            dataClient.getFdForAsset(asset).await().inputStream.use { stream ->
                BitmapFactory.decodeStream(stream)
            }
        }.getOrNull()
    }

    private fun wearUri(path: String): Uri = Uri.Builder()
        .scheme(PutDataRequest.WEAR_URI_SCHEME)
        .path(path)
        .build()
}
