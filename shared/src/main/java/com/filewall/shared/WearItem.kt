package com.filewall.shared

import org.json.JSONArray
import org.json.JSONObject

/** Category buckets shown in the storage breakdown, on both phone and watch. */
enum class WearCategory {
    PHOTO, VIDEO, DOC, OTHER;

    companion object {
        fun fromName(raw: String?): WearCategory =
            entries.firstOrNull { it.name == raw } ?: OTHER
    }
}

/**
 * One vault entry as the watch sees it. Deliberately minimal: no paths, no ciphertext,
 * nothing that would let a lost watch reveal anything the phone has not already sent.
 */
data class WearItem(
    val id: String,
    val name: String,
    val category: WearCategory,
    val sizeBytes: Long,
    val addedAt: Long,
    val hasThumb: Boolean,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put(KEY_ID, id)
        put(KEY_NAME, name)
        put(KEY_CATEGORY, category.name)
        put(KEY_SIZE, sizeBytes)
        put(KEY_ADDED, addedAt)
        put(KEY_HAS_THUMB, hasThumb)
    }

    companion object {
        private const val KEY_ID = "id"
        private const val KEY_NAME = "name"
        private const val KEY_CATEGORY = "category"
        private const val KEY_SIZE = "size"
        private const val KEY_ADDED = "added"
        private const val KEY_HAS_THUMB = "thumb"

        fun fromJson(obj: JSONObject): WearItem = WearItem(
            id = obj.getString(KEY_ID),
            name = obj.optString(KEY_NAME, ""),
            category = WearCategory.fromName(obj.optString(KEY_CATEGORY)),
            sizeBytes = obj.optLong(KEY_SIZE, 0L),
            addedAt = obj.optLong(KEY_ADDED, 0L),
            hasThumb = obj.optBoolean(KEY_HAS_THUMB, false),
        )

        fun encodeList(items: List<WearItem>): String {
            val array = JSONArray()
            items.forEach { array.put(it.toJson()) }
            return array.toString()
        }

        fun decodeList(json: String?): List<WearItem> {
            if (json.isNullOrBlank()) return emptyList()
            return runCatching {
                val array = JSONArray(json)
                buildList(array.length()) {
                    for (i in 0 until array.length()) add(fromJson(array.getJSONObject(i)))
                }
            }.getOrDefault(emptyList())
        }
    }
}
