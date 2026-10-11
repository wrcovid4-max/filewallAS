package com.filewall.data.media

import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.sync.Semaphore
import java.util.concurrent.ConcurrentHashMap
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

    // Up to three decodes run at once, so fast scrolling fills tiles in quickly.
    private val permits = Semaphore(3)
    // One lock per item: the same thumbnail is never decoded twice at the same time.
    private val itemLocks = ConcurrentHashMap<String, Mutex>()

    /** Cached bitmap if present, otherwise null — safe to call during composition. */
    fun peek(itemId: String): Bitmap? = cache.get(itemId)

    /** Decrypts and decodes the preview, caching the result. Null when the item has none. */
    suspend fun load(item: VaultItem): Bitmap? {
        cache.get(item.id)?.let { return it }

        val gate = itemLocks.getOrPut(item.id) { Mutex() }
        return withContext(Dispatchers.IO) {
            gate.withLock {
                cache.get(item.id)?.let { return@withLock it }
                permits.withPermit {
                    val bytes = repository.thumbnailBytes(item) ?: return@withPermit null
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.also { cache.put(item.id, it) }
                }
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
