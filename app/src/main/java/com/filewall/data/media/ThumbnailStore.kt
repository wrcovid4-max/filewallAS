package com.filewall.data.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import com.filewall.data.repo.VaultRepository
import com.filewall.model.VaultItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Memory cache for tile previews.
 *
 * Every thumbnail costs a Keystore round trip plus a JPEG decode, and a scrolling grid asks
 * for the same ones over and over — so decoded bitmaps are held in an LRU sized against the
 * app's heap, and evicted wholesale when the vault locks.
 */
class ThumbnailStore(private val repository: VaultRepository) {

    private val cache = object : LruCache<String, Bitmap>(cacheSizeKb()) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    private val loadLock = Mutex()

    /** Cached bitmap if present, otherwise null — safe to call during composition. */
    fun peek(itemId: String): Bitmap? = cache.get(itemId)

    /** Decrypts and decodes the preview, caching the result. Null when the item has none. */
    suspend fun load(item: VaultItem): Bitmap? {
        cache.get(item.id)?.let { return it }

        return withContext(Dispatchers.IO) {
            // One decode at a time per item: a fast scroll can otherwise ask for the same
            // thumbnail from several composables before the first one finishes.
            loadLock.withLock {
                cache.get(item.id)?.let { return@withLock it }
                val bytes = repository.thumbnailBytes(item) ?: return@withLock null
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                bitmap?.also { cache.put(item.id, it) }
            }
        }
    }

    fun evict(itemId: String) {
        cache.remove(itemId)
    }

    fun clear() {
        cache.evictAll()
    }

    private companion object {
        fun cacheSizeKb(): Int {
            val maxKb = (Runtime.getRuntime().maxMemory() / 1024).toInt()
            return (maxKb / 8).coerceIn(4 * 1024, 48 * 1024)
        }
    }
}
