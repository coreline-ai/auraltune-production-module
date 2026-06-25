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
import android.content.Intent
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
import com.coreline.auraltune.data.PlaybackSnapshot
import com.coreline.auraltune.data.PlaybackTrack
import com.coreline.auraltune.data.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
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
    private val settings: SettingsStore,
) : Closeable {

    private val appContext = context.applicationContext
    private val analyzer = SpectrumAnalyzer()
    private val processor = AuralTuneAudioProcessor(engine, analyzer)
    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())

    /** 재생 중 음원의 실시간 주파수 스펙트럼(밴드 레벨 0..1, post-EQ) — 플레이어 막대 시각화용. */
    val spectrum: StateFlow<FloatArray> get() = analyzer.spectrum

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
                        if (isPlaying) startTicker() else { stopTicker(); saveSnapshot() }
                        publish()
                    }
                    override fun onMediaItemTransition(item: MediaItem?, reason: Int) {
                        publish(); saveSnapshot()
                    }
                    override fun onPositionDiscontinuity(
                        old: Player.PositionInfo,
                        new: Player.PositionInfo,
                        reason: Int,
                    ) = publish()
                })
            }
    }

    init {
        analyzer.start()
        // 앱 재시작 시 마지막 큐/현재 곡/위치를 복원(자동 재생 없이 '일시정지'로 노출).
        restore()
    }

    // ── Queue / transport ──────────────────────────────────────────────────────

    /** Replace the queue with [uris] and start playing at [startIndex]. */
    fun setQueueAndPlay(uris: List<Uri>, startIndex: Int = 0) {
        if (uris.isEmpty()) return
        uris.forEach(::persistRead)
        queue = uris.map { TrackInfo(it, resolveTitle(it)) }
        player.setMediaItems(queue.map(::toMediaItem), startIndex.coerceIn(0, queue.lastIndex), 0L)
        player.prepare()
        player.playWhenReady = true
        publish()
        saveSnapshot()
    }

    /** Back-compat single-file entry point — plays [uri] as a one-item queue. */
    fun play(uri: Uri) = setQueueAndPlay(listOf(uri))

    /** Append [uris] to the queue; if nothing is loaded, start playing the first added track. */
    fun addToQueue(uris: List<Uri>) {
        if (uris.isEmpty()) return
        uris.forEach(::persistRead)
        val wasEmpty = queue.isEmpty()
        val added = uris.map { TrackInfo(it, resolveTitle(it)) }
        queue = queue + added
        added.forEach { player.addMediaItem(toMediaItem(it)) }
        if (wasEmpty) {
            player.prepare()
            player.playWhenReady = true
        }
        publish()
        saveSnapshot()
    }

    /**
     * Jump to a queue entry and play it. Re-[prepare]s first when the player is IDLE — otherwise a
     * prior decode error, a never-prepared player, or a restored-paused queue leaves the player in
     * STATE_IDLE where seek + play is silently ignored (the bug: tapping a queue row did nothing).
     */
    fun playIndex(index: Int) {
        if (index !in queue.indices) return
        ensurePrepared()
        player.seekTo(index, 0L)
        player.playWhenReady = true
        publish()
    }

    fun removeFromQueue(index: Int) {
        if (index !in queue.indices) return
        val removed = queue[index].uri
        player.removeMediaItem(index)
        queue = queue.toMutableList().apply { removeAt(index) }
        if (queue.none { it.uri == removed }) releaseRead(removed) // 더 안 쓰면 영속 권한 해제
        publish()
        saveSnapshot()
    }

    fun clearQueue() {
        queue.forEach { releaseRead(it.uri) }
        player.clearMediaItems()
        queue = emptyList()
        publish()
        saveSnapshot()
    }

    fun next() { if (player.hasNextMediaItem()) { ensurePrepared(); player.seekToNextMediaItem() }; publish() }
    fun previous() { ensurePrepared(); player.seekToPreviousMediaItem(); publish() }
    fun togglePlayPause() { if (player.isPlaying) player.pause() else startPlayback() }
    fun pause() = player.pause()
    fun resume() = startPlayback()
    fun seekTo(ms: Long) { player.seekTo(ms.coerceAtLeast(0L)); publish() }
    fun stop() { player.stop() }

    /**
     * 재생 시작/재개. IDLE이면 재prepare, **큐 끝(ENDED)이면 처음(0번 곡 0초)으로 되감아** 다시 재생한다.
     * (ENDED에서 play()만 호출하면 재생 위치가 끝이라 아무 일도 안 일어나던 문제 수정.)
     */
    private fun startPlayback() {
        when (player.playbackState) {
            Player.STATE_IDLE -> { player.prepare(); player.playWhenReady = true }
            Player.STATE_ENDED -> { player.seekTo(0, 0L); player.playWhenReady = true }
            else -> player.play()
        }
        publish()
    }

    /** IDLE(에러/미prepare/복원직후)면 재생 전에 재prepare — seek+play가 묻히는 문제 방지. */
    private fun ensurePrepared() {
        if (player.playbackState == Player.STATE_IDLE) player.prepare()
    }

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
            var tick = 0
            while (true) {
                publish()
                if (++tick % SAVE_EVERY_TICKS == 0) saveSnapshot() // 위치 주기 저장(~5s)
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

    /** SAF content:// URI에 영속 읽기 권한을 받아 재시작 후에도 읽게 한다(문서 URI만 성공; 그 외 무시). */
    private fun persistRead(uri: Uri) {
        runCatching {
            appContext.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private fun releaseRead(uri: Uri) {
        runCatching {
            appContext.contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    /** 현재 큐 + 현재 곡 인덱스/위치를 저장(재시작 복원용). 빈 큐면 스냅샷 제거. */
    private fun saveSnapshot() {
        val q = queue
        val idx = player.currentMediaItemIndex.coerceAtLeast(0)
        val pos = player.currentPosition.coerceAtLeast(0L)
        scope.launch {
            settings.setPlaybackSnapshot(
                if (q.isEmpty()) null
                else PlaybackSnapshot(q.map { PlaybackTrack(it.uri.toString(), it.title) }, idx, pos),
            )
        }
    }

    /** 앱 재시작 시 1회: 저장된 큐/현재 곡/위치 복원. 자동 재생 없이 '일시정지'로 노출(사용자가 재생). */
    private fun restore() {
        scope.launch {
            if (queue.isNotEmpty()) return@launch
            val snap = settings.playbackSnapshot.first() ?: return@launch
            if (snap.tracks.isEmpty() || queue.isNotEmpty()) return@launch // suspend 사이 사용자 추가 가드
            val tracks = snap.tracks.map { TrackInfo(Uri.parse(it.uri), it.title) }
            queue = tracks
            player.setMediaItems(
                tracks.map(::toMediaItem),
                snap.index.coerceIn(0, tracks.lastIndex),
                snap.positionMs.coerceAtLeast(0L),
            )
            player.prepare()
            player.playWhenReady = false
            publish()
        }
    }

    override fun close() {
        stopTicker()
        analyzer.close()
        scope.coroutineContext[Job]?.cancel()
        player.release()
    }

    companion object {
        private const val TAG = "MusicPlayer"
        private const val POSITION_POLL_MS = 500L
        private const val SAVE_EVERY_TICKS = 10 // ~5s마다 위치 저장
    }
}
