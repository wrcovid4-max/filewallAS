package com.filewall.data.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.filewall.model.FileCategory
import java.io.File
import java.io.InputStream

/**
 * Builds the square-ish previews shown on the file tiles.
 *
 * Sources are read straight from the incoming [Uri] so the import path never has to
 * materialise plaintext on disk: the picker stream is opened once to measure, once to
 * decode the preview, and once more to feed the encryptor.
 */
object ThumbnailFactory {

    /** Longest edge of the stored preview. Comfortably covers a two-column grid tile. */
    const val THUMB_MAX_EDGE = 512

    /** Encoded preview bytes plus the source dimensions, or null when there is nothing to show. */
    data class Preview(
        val jpeg: ByteArray,
        val sourceWidth: Int,
        val sourceHeight: Int,
    ) {
        // Data classes with an array member need these to behave sanely.
        override fun equals(other: Any?): Boolean =
            this === other || (other is Preview && jpeg.contentEquals(other.jpeg))

        override fun hashCode(): Int = jpeg.contentHashCode()
    }

    fun fromUri(context: Context, uri: Uri, category: FileCategory): Preview? = when (category) {
        FileCategory.PHOTO -> imagePreview(
            openStream = { context.contentResolver.openInputStream(uri) },
        )

        FileCategory.VIDEO -> videoPreview { retriever ->
            retriever.setDataSource(context, uri)
        }

        else -> null
    }

    fun fromFile(file: File, category: FileCategory): Preview? = when (category) {
        FileCategory.PHOTO -> imagePreview(openStream = { file.inputStream() })
        FileCategory.VIDEO -> videoPreview { retriever -> retriever.setDataSource(file.absolutePath) }
        else -> null
    }

    // ------------------------------------------------------------------ images

    private fun imagePreview(openStream: () -> InputStream?): Preview? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        openStream()?.use { BitmapFactory.decodeStream(it, null, bounds) } ?: return null
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, THUMB_MAX_EDGE)
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        val decoded = openStream()?.use { BitmapFactory.decodeStream(it, null, options) } ?: return null

        val rotation = openStream()?.use { readExifRotation(it) } ?: 0
        val oriented = applyRotation(decoded, rotation)
        val scaled = scaleToFit(oriented, THUMB_MAX_EDGE)

        return Preview(
            jpeg = compress(scaled),
            sourceWidth = bounds.outWidth,
            sourceHeight = bounds.outHeight,
        ).also {
            if (scaled !== decoded) scaled.recycle()
            if (oriented !== decoded && oriented !== scaled) oriented.recycle()
            decoded.recycle()
        }
    }

    private fun readExifRotation(stream: InputStream): Int =
        runCatching {
            when (
                ExifInterface(stream).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL,
                )
            ) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90
                ExifInterface.ORIENTATION_ROTATE_180 -> 180
                ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }
        }.getOrDefault(0)

    // ------------------------------------------------------------------ videos

    private fun videoPreview(bind: (MediaMetadataRetriever) -> Unit): Preview? {
        val retriever = MediaMetadataRetriever()
        return try {
            bind(retriever)
            val frame = retriever.frameAtTime ?: return null
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                ?.toIntOrNull() ?: frame.width
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                ?.toIntOrNull() ?: frame.height
            val scaled = scaleToFit(frame, THUMB_MAX_EDGE)
            Preview(compress(scaled), width, height).also {
                if (scaled !== frame) scaled.recycle()
                frame.recycle()
            }
        } catch (_: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    // ------------------------------------------------------------------ shared

    fun sampleSizeFor(width: Int, height: Int, maxEdge: Int): Int {
        var sample = 1
        var longest = maxOf(width, height)
        while (longest / 2 >= maxEdge) {
            longest /= 2
            sample *= 2
        }
        return sample
    }

    fun scaleToFit(source: Bitmap, maxEdge: Int): Bitmap {
        val longest = maxOf(source.width, source.height)
        if (longest <= maxEdge || longest == 0) return source
        val ratio = maxEdge.toFloat() / longest
        val width = (source.width * ratio).toInt().coerceAtLeast(1)
        val height = (source.height * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(source, width, height, true)
    }

    private fun applyRotation(source: Bitmap, degrees: Int): Bitmap {
        if (degrees == 0) return source
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    }

    fun compress(bitmap: Bitmap, quality: Int = 80): ByteArray =
        java.io.ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, quality, it) }
            .toByteArray()
}
