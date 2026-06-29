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
import com.coreline.auraltune.R
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
    val audioBitDepth: Int? = null,
    val audioSampleRateHz: Int? = null,
    val playbackError: String? = null,
)

@UnstableApi
class MusicPlayerController(
    context: Context,
    engine: AudioEngine,
    private val settings: SettingsStore,
) : Closeable {

    private val appContext = context.applicationContext
    private val analyzer = SpectrumAnalyzer()
    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
    @Volatile private var currentAudioBitDepth: Int? = null
    @Volatile private var currentAudioSampleRateHz: Int? = null
    private val processor = AuralTuneAudioProcessor(engine, analyzer) { sampleRateHz, bitDepth ->
        currentAudioSampleRateHz = sampleRateHz.takeIf { it > 0 }
        currentAudioBitDepth = bitDepth.takeIf { it > 0 }
        scope.launch { publish() }
    }

    /** 재생 중 음원의 실시간 주파수 스펙트럼(밴드 레벨 0..1, post-EQ) — 플레이어 막대 시각화용. */
    val spectrum: StateFlow<FloatArray> get() = analyzer.spectrum

    /** Title source of truth — parallel to ExoPlayer's media-item list (same order/index). */
    private var queue: List<TrackInfo> = emptyList()
    private var ticker: Job? = null

    // Cold-start race guard. [restore] runs async and suspends on a DataStore read; until it
    // completes the queue is empty and the player is STATE_IDLE, and restore() ends by forcing
    // playWhenReady=false. A play tap landing in that window was otherwise swallowed — set on an
    // empty IDLE player, then overwritten by restore — so the user had to press play twice
    // (the "press play once, nothing happens; press again and it plays" bug, seen on slow cold
    // starts). We record the intent here and let restore() honor it. Main-thread only.
    private var restoreComplete = false
    private var pendingPlayRequest = false

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
                        handlePlayerError(error)
                    }
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (BuildConfig.DEBUG) Log.i(TAG, "state=$playbackState (1=IDLE 2=BUFFERING 3=READY 4=ENDED)")
                        maybeConsumeDeferredPlay(playbackState)
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
        publish(playbackError = null)
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
        publish(playbackError = null)
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
        currentAudioBitDepth = null
        currentAudioSampleRateHz = null
        publish(playbackError = null)
        saveSnapshot()
    }

    fun next() { if (player.hasNextMediaItem()) { ensurePrepared(); player.seekToNextMediaItem() }; publish() }
    fun previous() { ensurePrepared(); player.seekToPreviousMediaItem(); publish() }
    fun togglePlayPause() { if (player.isPlaying) player.pause() else startPlayback() }
    fun pause() = player.pause()
    fun resume() = startPlayback()
    fun seekTo(ms: Long) { player.seekTo(ms.coerceAtLeast(0L)); publish() }
    fun stop() { player.stop() }

    fun consumePlaybackError() {
        if (_state.value.playbackError != null) {
            _state.value = _state.value.copy(playbackError = null)
        }
    }

    /**
     * 재생 시작/재개. IDLE이면 재prepare, **큐 끝(ENDED)이면 처음(0번 곡 0초)으로 되감아** 다시 재생한다.
     * (ENDED에서 play()만 호출하면 재생 위치가 끝이라 아무 일도 안 일어나던 문제 수정.)
     */
    private fun startPlayback() {
        // Cold-start race: until [restore] has loaded the saved queue the player is IDLE/empty,
        // and while the (restored) track is still BUFFERING its duration is unknown — so we can't
        // yet tell whether it is parked at its end. In both cases defer the tap and let
        // [maybeConsumeDeferredPlay] apply it once the track settles at READY/ENDED.
        if ((!restoreComplete && queue.isEmpty()) || player.playbackState == Player.STATE_BUFFERING) {
            pendingPlayRequest = true
            return
        }
        when (player.playbackState) {
            Player.STATE_IDLE -> { player.prepare(); player.playWhenReady = true }
            Player.STATE_ENDED -> { player.seekTo(0, 0L); player.playWhenReady = true }
            else -> { // STATE_READY
                if (isAtEnd()) player.seekTo(0, 0L) // 끝에서 멈춘 트랙을 다시 누르면 처음부터
                player.play()
            }
        }
        publish()
    }

    /** IDLE(에러/미prepare/복원직후)면 재생 전에 재prepare — seek+play가 묻히는 문제 방지. */
    private fun ensurePrepared() {
        if (player.playbackState == Player.STATE_IDLE) player.prepare()
    }

    /**
     * 콜드 스타트 경합의 마무리: [restore]가 저장된 큐를 불러오는 동안 들어온 재생 탭은
     * [startPlayback]에서 [pendingPlayRequest]로 보류됐다. 복원된 트랙이 READY/ENDED로
     * 자리잡는 순간 그 의도를 적용한다. 복원 위치가 곡 끝이면(직전 세션에서 끝까지 재생됨)
     * 처음부터 다시 재생한다 — 끝에서 멈춰 "재생을 눌러도 안 되는" 현상 방지.
     */
    private fun maybeConsumeDeferredPlay(playbackState: Int) {
        if (!pendingPlayRequest) return
        if (playbackState != Player.STATE_READY && playbackState != Player.STATE_ENDED) return
        pendingPlayRequest = false
        if (playbackState == Player.STATE_ENDED || isAtEnd()) player.seekTo(0, 0L)
        player.play()
        publish()
    }

    /** 현재(또는 복원된) 위치가 곡 끝 이내인지 — 끝에서 멈춘 트랙 재시작 판단용. */
    private fun isAtEnd(): Boolean {
        val dur = player.duration
        return dur > 0 && player.currentPosition >= dur - END_EPSILON_MS
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

    private fun publish(playbackError: String? = _state.value.playbackError) {
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
            audioBitDepth = currentAudioBitDepth,
            audioSampleRateHz = currentAudioSampleRateHz,
            playbackError = playbackError,
        )
    }

    private fun handlePlayerError(error: PlaybackException) {
        stopTicker()
        val failedIndex = player.currentMediaItemIndex.takeIf { it in queue.indices }
        val failed = failedIndex?.let { queue[it] }
        val failedTitle = failed?.title ?: appContext.getString(R.string.player_unknown_track)
        val shouldContinue = player.playWhenReady
        val message = appContext.getString(
            R.string.player_error_failed_track_format,
            failedTitle,
            error.errorCodeName,
        )

        if (failedIndex != null && failed != null) {
            queue = queue.toMutableList().apply { removeAt(failedIndex) }
            if (queue.none { it.uri == failed.uri }) releaseRead(failed.uri)
            if (queue.isNotEmpty()) {
                val nextIndex = failedIndex.coerceAtMost(queue.lastIndex)
                player.setMediaItems(queue.map(::toMediaItem), nextIndex, 0L)
                player.prepare()
                player.playWhenReady = shouldContinue
            } else {
                player.clearMediaItems()
                currentAudioBitDepth = null
                currentAudioSampleRateHz = null
            }
        } else {
            player.clearMediaItems()
            queue = emptyList()
            currentAudioBitDepth = null
            currentAudioSampleRateHz = null
        }

        if (BuildConfig.DEBUG) Log.w(TAG, "removed failed track after ${error.errorCodeName}: $failedTitle")
        saveSnapshot()
        publish(playbackError = message)
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
            try {
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
                // 복원은 항상 '일시정지'로 노출(자동 재생 금지). 스냅샷 로딩 중 들어온 재생 탭은
                // [pendingPlayRequest]에 기록되며, 트랙이 READY/ENDED로 자리잡는 순간
                // [maybeConsumeDeferredPlay]가 적용한다(끝 위치면 처음부터).
                player.playWhenReady = false
                publish()
            } finally {
                restoreComplete = true
                // 복원할 큐가 없으면(스냅샷 없음/조기 반환) 보류된 재생 의도를 비운다 —
                // 이후 큐 추가가 의도치 않게 자동 재생되지 않도록.
                if (queue.isEmpty()) pendingPlayRequest = false
            }
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
        private const val END_EPSILON_MS = 500L // 복원 위치가 곡 끝 이내면 처음부터 재생
    }
}
