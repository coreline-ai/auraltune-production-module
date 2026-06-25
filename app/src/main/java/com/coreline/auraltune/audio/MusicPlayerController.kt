// MusicPlayerController.kt
// Phase 1 (T1) — local-file music playback via ExoPlayer WITH the AuralTune EQ.
//
// GUI redesign: now exposes reactive [state] (StateFlow) and a QUEUE so the dedicated Player tab
// (full transport + playlist) and the docked mini-player on the AutoEQ/OPRA tabs share ONE
// playback session. ExoPlayer owns the playlist natively (setMediaItems / addMediaItem /
// seekToNext/Previous); we mirror it into [PlaybackUiState] for the declarative UI.
//
// Fix recap: device decoder emits PCM_FLOAT which ExoPlayer's built-in SilenceSkipping rejects.
// setEnableFloatOutput(false) forces 16-bit decode, so AuralTuneAudioProcessor (16-bit in → float
// EQ → 16-bit out) sits ahead of SilenceSkipping without a format conflict.
package com.coreline.auraltune.audio

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import com.coreline.audio.AudioEngine
import com.coreline.auraltune.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.Closeable

/** One queue entry. [title] is the SAF display name (or file tag title when available). */
data class TrackInfo(val uri: Uri, val title: String)

/** Reactive snapshot of the shared playback session for the player + mini-player UIs. */
data class PlaybackUiState(
    val hasMedia: Boolean = false,
    val isPlaying: Boolean = false,
    val title: String = "",
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val queue: List<TrackInfo> = emptyList(),
    val currentIndex: Int = -1,
)

@UnstableApi
class MusicPlayerController(
    context: Context,
    engine: AudioEngine,
) : Closeable {

    private val appContext = context.applicationContext
    private val processor = AuralTuneAudioProcessor(engine)
    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())

    /** Title source of truth — parallel to ExoPlayer's media-item list (same order/index). */
    private var queue: List<TrackInfo> = emptyList()
    private var ticker: Job? = null

    private val _state = MutableStateFlow(PlaybackUiState())
    val state: StateFlow<PlaybackUiState> = _state.asStateFlow()

    private val player: ExoPlayer = run {
        val renderersFactory = object : DefaultRenderersFactory(context) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean,
            ): AudioSink {
                return DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(false) // 16-bit decode → no SilenceSkipping conflict
                    .setAudioProcessors(arrayOf<AudioProcessor>(processor))
                    .build()
            }
        }
        ExoPlayer.Builder(context)
            .setRenderersFactory(renderersFactory)
            .build()
            .apply {
                addListener(object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) {
                        if (BuildConfig.DEBUG) Log.e(TAG, "player error: ${error.errorCodeName}", error)
                    }
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (BuildConfig.DEBUG) Log.i(TAG, "state=$playbackState (1=IDLE 2=BUFFERING 3=READY 4=ENDED)")
                        publish()
                    }
                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        if (isPlaying) startTicker() else stopTicker()
                        publish()
                    }
                    override fun onMediaItemTransition(item: MediaItem?, reason: Int) = publish()
                    override fun onPositionDiscontinuity(
                        old: Player.PositionInfo,
                        new: Player.PositionInfo,
                        reason: Int,
                    ) = publish()
                })
            }
    }

    // ── Queue / transport ──────────────────────────────────────────────────────

    /** Replace the queue with [uris] and start playing at [startIndex]. */
    fun setQueueAndPlay(uris: List<Uri>, startIndex: Int = 0) {
        if (uris.isEmpty()) return
        queue = uris.map { TrackInfo(it, resolveTitle(it)) }
        player.setMediaItems(queue.map(::toMediaItem), startIndex.coerceIn(0, queue.lastIndex), 0L)
        player.prepare()
        player.playWhenReady = true
        publish()
    }

    /** Back-compat single-file entry point — plays [uri] as a one-item queue. */
    fun play(uri: Uri) = setQueueAndPlay(listOf(uri))

    /** Append [uris] to the queue; if nothing is loaded, start playing the first added track. */
    fun addToQueue(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val wasEmpty = queue.isEmpty()
        val added = uris.map { TrackInfo(it, resolveTitle(it)) }
        queue = queue + added
        added.forEach { player.addMediaItem(toMediaItem(it)) }
        if (wasEmpty) {
            player.prepare()
            player.playWhenReady = true
        }
        publish()
    }

    /** Jump to a queue entry and play it. */
    fun playIndex(index: Int) {
        if (index !in queue.indices) return
        player.seekTo(index, 0L)
        player.play()
        publish()
    }

    fun removeFromQueue(index: Int) {
        if (index !in queue.indices) return
        player.removeMediaItem(index)
        queue = queue.toMutableList().apply { removeAt(index) }
        publish()
    }

    fun clearQueue() {
        player.clearMediaItems()
        queue = emptyList()
        publish()
    }

    fun next() { if (player.hasNextMediaItem()) player.seekToNextMediaItem(); publish() }
    fun previous() { player.seekToPreviousMediaItem(); publish() }
    fun togglePlayPause() { if (player.isPlaying) player.pause() else player.play() }
    fun pause() = player.pause()
    fun resume() = player.play()
    fun seekTo(ms: Long) { player.seekTo(ms.coerceAtLeast(0L)); publish() }
    fun stop() { player.stop() }

    val isPlaying: Boolean get() = player.isPlaying

    // ── Internal ──────────────────────────────────────────────────────────────

    private fun toMediaItem(t: TrackInfo): MediaItem =
        MediaItem.Builder()
            .setUri(t.uri)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(t.title).build())
            .build()

    private fun startTicker() {
        if (ticker?.isActive == true) return
        ticker = scope.launch {
            while (true) {
                publish()
                delay(POSITION_POLL_MS)
            }
        }
    }

    private fun stopTicker() {
        ticker?.cancel()
        ticker = null
    }

    private fun publish() {
        val idx = player.currentMediaItemIndex
        val title = player.mediaMetadata.title?.toString()
            ?: queue.getOrNull(idx)?.title
            ?: ""
        _state.value = PlaybackUiState(
            hasMedia = player.mediaItemCount > 0,
            isPlaying = player.isPlaying,
            title = title,
            positionMs = player.currentPosition.coerceAtLeast(0L),
            durationMs = player.duration.takeIf { it > 0L } ?: 0L,
            queue = queue,
            currentIndex = if (queue.isEmpty()) -1 else idx,
        )
    }

    /** Resolve a human title from a SAF/content uri (DISPLAY_NAME), falling back to the path. */
    private fun resolveTitle(uri: Uri): String {
        runCatching {
            appContext.contentResolver.query(
                uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null,
            )?.use { c ->
                val col = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (col >= 0 && c.moveToFirst()) {
                    c.getString(col)?.takeIf { it.isNotBlank() }?.let { return it }
                }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "Unknown"
    }

    override fun close() {
        stopTicker()
        scope.coroutineContext[Job]?.cancel()
        player.release()
    }

    companion object {
        private const val TAG = "MusicPlayer"
        private const val POSITION_POLL_MS = 500L
    }
}
