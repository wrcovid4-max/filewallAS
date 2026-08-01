package com.filewall.data.wear

import android.content.Context
import android.graphics.BitmapFactory
import com.filewall.data.media.ThumbnailFactory
import com.filewall.data.repo.VaultRepository
import com.filewall.data.settings.SettingsStore
import com.filewall.model.FileCategory
import com.filewall.model.VaultItem
import com.filewall.shared.WearItem
import com.filewall.shared.WearProtocol
import com.google.android.gms.wearable.Asset
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first

/**
 * Phone half of the watch link.
 *
 * Two rules shape everything here. Hidden items never leave the phone — not their bytes,
 * not their names, not their existence. And nothing is pushed speculatively: the watch asks,
 * the phone answers, so a watch that is out of range costs nothing.
 */
class WearSyncManager(
    private val context: Context,
    private val repository: VaultRepository,
    private val settings: SettingsStore,
) {

    private val dataClient by lazy { Wearable.getDataClient(context) }

    /** Newest-first cap on how much of the vault the watch is told about. */
    private val manifestLimit = 60

    /** Rebuilds the manifest DataItem. No-ops (and clears) when watch sync is switched off. */
    suspend fun publishManifest(): Unit = withContext(Dispatchers.IO) {
        if (!settings.settings.first().syncToWatch) {
            clearAll()
            return@withContext
        }

        val items = repository.observeItems(hidden = false).first()
            .sortedByDescending { it.addedAt }
            .take(manifestLimit)

        val request = PutDataMapRequest.create(WearProtocol.PATH_MANIFEST)
        val map = request.dataMap

        val wearItems = items.map { item ->
            val thumb = thumbAssetFor(item)
            if (thumb != null) map.putAsset(WearProtocol.ManifestKey.thumb(item.id), thumb)
            WearItem(
                id = item.id,
                name = item.name,
                category = item.category.toWear(),
                sizeBytes = item.sizeBytes,
                addedAt = item.addedAt,
                hasThumb = thumb != null,
            )
        }

        map.putString(WearProtocol.ManifestKey.ITEMS_JSON, WearItem.encodeList(wearItems))
        map.putLong(WearProtocol.ManifestKey.GENERATED_AT, System.currentTimeMillis())

        val breakdown = repository.storage.first()
        map.putLong(WearProtocol.ManifestKey.TOTAL_BYTES, breakdown.totalBytes)
        map.putLong(WearProtocol.ManifestKey.PHOTO_BYTES, breakdown.photoBytes)
        map.putLong(WearProtocol.ManifestKey.VIDEO_BYTES, breakdown.videoBytes)
        map.putLong(WearProtocol.ManifestKey.DOC_BYTES, breakdown.docBytes)

        dataClient.putDataItem(request.asPutDataRequest().setUrgent()).await()
    }

    /**
     * Answers a watch's request for one full image.
     *
     * Only images are ever sent whole, and only downscaled — the watch is a viewfinder, not
     * a second copy of the vault.
     */
    suspend fun publishImage(itemId: String): Unit = withContext(Dispatchers.IO) {
        if (!settings.settings.first().syncToWatch) return@withContext

        val item = repository.observeItem(itemId).first() ?: return@withContext
        if (item.hidden || item.category != FileCategory.PHOTO) return@withContext

        val asset = downscaledAsset(repository.fullBytes(item), WearProtocol.IMAGE_MAX_EDGE)
            ?: return@withContext

        val request = PutDataMapRequest.create(WearProtocol.imagePath(itemId)).apply {
            dataMap.putAsset(WearProtocol.ImageKey.ASSET, asset)
            dataMap.putString(WearProtocol.ImageKey.NAME, item.name)
            dataMap.putLong(WearProtocol.ImageKey.GENERATED_AT, System.currentTimeMillis())
        }
        dataClient.putDataItem(request.asPutDataRequest().setUrgent()).await()
    }

    /** Wipes everything the watch is holding — used when sync is turned off or the vault empties. */
    suspend fun clearAll(): Unit = withContext(Dispatchers.IO) {
        runCatching {
            val uri = android.net.Uri.Builder()
                .scheme(com.google.android.gms.wearable.PutDataRequest.WEAR_URI_SCHEME)
                .path("/filewall")
                .build()
            dataClient.deleteDataItems(uri, com.google.android.gms.wearable.DataClient.FILTER_PREFIX).await()
        }
    }

    // ---------------------------------------------------------------- internals

    private suspend fun thumbAssetFor(item: VaultItem): Asset? {
        val stored = repository.thumbnailBytes(item) ?: return null
        return downscaledAsset(stored, WearProtocol.THUMB_MAX_EDGE)
    }

    /** Re-encodes to the watch's size budget; a 512px phone tile is wasteful on a 1.4" screen. */
    private fun downscaledAsset(jpeg: ByteArray, maxEdge: Int): Asset? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
        if (bounds.outWidth <= 0) return null

        val options = BitmapFactory.Options().apply {
            inSampleSize = ThumbnailFactory.sampleSizeFor(bounds.outWidth, bounds.outHeight, maxEdge)
        }
        val decoded = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, options) ?: return null
        val scaled = ThumbnailFactory.scaleToFit(decoded, maxEdge)
        val bytes = ThumbnailFactory.compress(scaled, quality = 75)
        if (scaled !== decoded) scaled.recycle()
        decoded.recycle()
        return Asset.createFromBytes(bytes)
    }
}
