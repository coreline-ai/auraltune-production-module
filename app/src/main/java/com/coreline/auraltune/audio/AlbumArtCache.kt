// AlbumArtCache.kt
// Process-scoped LRU cache of per-track queue-row metadata: a SMALL cover thumbnail + artist +
// album, all pulled in ONE MediaMetadataRetriever open per track. Empty/failed results are cached
// too so art-less / tag-less tracks are not re-probed on every scroll. Bounded so a large queue
// stays cheap.
package com.coreline.auraltune.audio

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Cover + tag metadata for a queue row (any field may be null when absent). */
data class TrackMeta(
    val artwork: Bitmap? = null,
    val artist: String? = null,
    val album: String? = null,
) {
    companion object {
        val EMPTY = TrackMeta()
    }
}

class AlbumArtCache(context: Context, private val maxEntries: Int = 64) {

    private val appContext = context.applicationContext
    private val lock = Any()
    private val map = object : LinkedHashMap<String, TrackMeta>(16, 0.75f, /* accessOrder = */ true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, TrackMeta>): Boolean =
            size > maxEntries
    }

    /** Non-suspending peek: cached metadata, or null if not yet resolved. */
    fun peek(uri: Uri): TrackMeta? = synchronized(lock) { map[uri.toString()] }

    /** Resolve (off-thread) + cache metadata for [uri]. Returns [TrackMeta.EMPTY] on failure. */
    suspend fun get(uri: Uri): TrackMeta {
        val key = uri.toString()
        synchronized(lock) { map[key]?.let { return it } }
        val meta = withContext(Dispatchers.Default) { extract(uri) }
        synchronized(lock) { map[key] = meta }
        return meta
    }

    /** One MediaMetadataRetriever open → embedded art (downscaled) + artist + album. */
    private fun extract(uri: Uri): TrackMeta = runCatching {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(appContext, uri)
            val art = retriever.embeddedPicture?.let { ArtworkDecoder.decode(it, ROW_MAX_PX) }
            val artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)?.ifBlank { null }
            val album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)?.ifBlank { null }
            TrackMeta(art, artist, album)
        } finally {
            retriever.release()
        }
    }.getOrDefault(TrackMeta.EMPTY)

    companion object {
        const val ROW_MAX_PX = 128
    }
}
