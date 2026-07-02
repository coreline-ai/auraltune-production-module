// ArtworkDecoder.kt
// Decodes embedded album art into a downscaled bitmap used for BOTH the crisp now-playing/mini-player
// thumbnail AND the blurred player background. ~512px is small enough that decode CPU + memory stay
// cheap (~1MB), crisp enough for the small thumbnails (drawn at ~40–64dp), and on API 31+ the blur
// (Modifier.blur) handles the background softening.
//
// NOTE: call off the main thread (BitmapFactory / MediaMetadataRetriever are blocking).
package com.coreline.auraltune.audio

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri

object ArtworkDecoder {

    /** Default max dimension — serves both the crisp thumbnail and the blurred background. */
    const val DEFAULT_MAX_PX = 512

    /** Decode embedded cover bytes (from Media3 [MediaMetadata.artworkData]) → downscaled bitmap, or null. */
    fun decode(bytes: ByteArray, maxPx: Int = DEFAULT_MAX_PX): Bitmap? = runCatching {
        if (bytes.isEmpty()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxPx)
        }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    }.getOrNull()

    /** Fallback: extract the embedded picture directly from a content/file URI, or null. */
    fun fromUri(context: Context, uri: Uri, maxPx: Int = DEFAULT_MAX_PX): Bitmap? = runCatching {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSourceCompat(context, uri)
            retriever.embeddedPicture?.let { decode(it, maxPx) }
        } finally {
            // release() exists on all API levels (close() is API 29+, so we avoid .use{}).
            retriever.release()
        }
    }.getOrNull()

    /** Largest power-of-two sample size that keeps both dimensions >= [maxPx] (heaviest safe downscale). */
    private fun sampleSize(w: Int, h: Int, maxPx: Int): Int {
        if (w <= 0 || h <= 0) return 1
        var s = 1
        while (w / (s * 2) >= maxPx && h / (s * 2) >= maxPx) s *= 2
        return s
    }
}

/**
 * Set a [MediaMetadataRetriever]'s source for content:// / file:// (via the ContentResolver) OR
 * for bundled `asset:///name` URIs (via the AssetManager fd — the retriever can't resolve the asset
 * scheme itself). Assets must be stored uncompressed (see `noCompress "m4a"`) for openFd to work.
 */
internal fun MediaMetadataRetriever.setDataSourceCompat(context: Context, uri: Uri) {
    if (uri.scheme == "asset") {
        val name = uri.path.orEmpty().trimStart('/')
        context.assets.openFd(name).use { afd ->
            setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
        }
    } else {
        setDataSource(context, uri)
    }
}
