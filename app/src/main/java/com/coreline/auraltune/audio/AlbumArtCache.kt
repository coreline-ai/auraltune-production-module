// AlbumArtCache.kt
// Process-scoped LRU cache of SMALL per-track cover thumbnails for the queue list. Each queue row
// resolves its own track's art by URI; null (no-art) results are cached too so art-less tracks are
// not re-probed on every scroll. Small (128px) + bounded so a large queue stays cheap.
package com.coreline.auraltune.audio

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AlbumArtCache(context: Context, private val maxEntries: Int = 64) {

    private val appContext = context.applicationContext
    private val lock = Any()
    private val map = object : LinkedHashMap<String, Bitmap?>(16, 0.75f, /* accessOrder = */ true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap?>): Boolean =
            size > maxEntries
    }

    /** Non-suspending peek: cached bitmap, or null if absent OR cached as no-art. */
    fun peek(uri: Uri): Bitmap? = synchronized(lock) { map[uri.toString()] }

    /** Decode (off-thread) + cache the thumbnail for [uri]; null if the track has no embedded art. */
    suspend fun get(uri: Uri): Bitmap? {
        val key = uri.toString()
        synchronized(lock) { if (map.containsKey(key)) return map[key] }
        val bmp = withContext(Dispatchers.Default) { ArtworkDecoder.fromUri(appContext, uri, ROW_MAX_PX) }
        synchronized(lock) { map[key] = bmp }
        return bmp
    }

    companion object {
        const val ROW_MAX_PX = 128
    }
}
