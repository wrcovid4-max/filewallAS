package com.filewall.shared

/**
 * Paths and keys shared by the phone app and the Wear OS companion. Both modules compile
 * against this file, so a typo can never desynchronise the two halves of the Data Layer.
 */
object WearProtocol {

    /** Capability the phone advertises so the watch can find a reachable node. */
    const val CAPABILITY_PHONE = "filewall_phone"

    /** DataItem holding the manifest of every item the phone is willing to mirror. */
    const val PATH_MANIFEST = "/filewall/manifest"

    /** MessageClient path: watch -> phone, "send me a fresh manifest". Empty payload. */
    const val PATH_REQUEST_MANIFEST = "/filewall/request_manifest"

    /** MessageClient path: watch -> phone, "send me the full image for this id". Payload = item id UTF-8. */
    const val PATH_REQUEST_IMAGE = "/filewall/request_image"

    /** DataItem prefix for a single full-size image. Full path is [imagePath]. */
    const val PATH_IMAGE_PREFIX = "/filewall/image/"

    /** MessageClient path: phone -> watch, "sync is off / nothing to show". Payload = reason UTF-8. */
    const val PATH_SYNC_DISABLED = "/filewall/sync_disabled"

    fun imagePath(itemId: String): String = PATH_IMAGE_PREFIX + itemId

    fun itemIdFromImagePath(path: String): String? =
        if (path.startsWith(PATH_IMAGE_PREFIX)) path.removePrefix(PATH_IMAGE_PREFIX) else null

    /** Keys inside the manifest DataMap. */
    object ManifestKey {
        const val ITEMS_JSON = "items_json"
        const val GENERATED_AT = "generated_at"
        const val TOTAL_BYTES = "total_bytes"
        const val PHOTO_BYTES = "photo_bytes"
        const val VIDEO_BYTES = "video_bytes"
        const val DOC_BYTES = "doc_bytes"

        /** Asset key for item `id`'s thumbnail, stored alongside the manifest. */
        fun thumb(id: String) = "thumb_$id"
    }

    /** Keys inside a single-image DataMap. */
    object ImageKey {
        const val ASSET = "image"
        const val NAME = "name"
        const val GENERATED_AT = "generated_at"
    }

    /** Longest edge, in pixels, of thumbnails pushed to the watch. */
    const val THUMB_MAX_EDGE = 160

    /** Longest edge, in pixels, of full images pushed to the watch. */
    const val IMAGE_MAX_EDGE = 640
}
