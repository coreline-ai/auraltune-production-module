// ArtworkDecoder.kt
// Decodes embedded album art into a SMALL downscaled bitmap for the blurred player background.
// Small on purpose: the background is heavily blurred (an ambient colour wash), so a ~96px source
// upscaled + blurred looks premium while keeping decode CPU + memory negligible.
//
// NOTE: call off the main thread (BitmapFactory / MediaMetadataRetriever are blocking).
package com.coreline.auraltune.audio

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri

object ArtworkDecoder {

    /** Default max dimension for the blur-source bitmap. Small = heavily blurred + cheap. */
    const val DEFAULT_MAX_PX = 96

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
            retriever.setDataSource(context, uri)
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
