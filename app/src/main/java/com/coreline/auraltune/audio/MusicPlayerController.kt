// MusicPlayerController.kt
// Phase 1 (T1) — local-file music playback via ExoPlayer WITH the AuralTune EQ.
//
// Media-session migration: the ExoPlayer now lives in [AuralTuneMediaService] (a MediaSessionService)
// so playback survives the Activity/ViewModel and gains lock-screen/notification transport, media
// buttons, and background playback. This controller no longer owns a player — it connects to the
// service via a MediaController (async) and mirrors it into [PlaybackUiState] for the declarative UI.
//
// The engine, spectrum analyzer, and audio-format telemetry are app singletons (ServiceLocator),
// shared with the service, so EQ control + spectrum + format never cross the session/binder boundary.
package com.coreline.auraltune.audio

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.coreline.auraltune.BuildConfig
import com.coreline.auraltune.R
import com.coreline.auraltune.data.PlaybackSnapshot
import com.coreline.auraltune.data.PlaybackTrack
import com.coreline.auraltune.data.SettingsStore
import com.google.common.util.concurrent.ListenableFuture
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
import kotlinx.coroutines.withContext
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
    /** ExoPlayer repeatMode: REPEAT_MODE_OFF(0) / ONE(1) / ALL(2). */
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val shuffleEnabled: Boolean = false,
    /** Current track's embedded cover art (small, downscaled) for the blurred player background. */
    val artwork: Bitmap? = null,
    /** Tag metadata for the secondary line under the filename (null = tag absent). */
    val artist: String? = null,
    val album: String? = null,
)

@UnstableApi
class MusicPlayerController(
    context: Context,
    private val settings: SettingsStore,
    private val analyzer: SpectrumAnalyzer,
    private val telemetry: PlaybackTelemetry,
) : Closeable {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())

    /** 재생 중 음원의 실시간 주파수 스펙트럼(밴드 레벨 0..1, post-EQ) — 플레이어 막대 시각화용. */
    val spectrum: StateFlow<FloatArray> get() = analyzer.spectrum

    /** Title source of truth — parallel to the player's media-item list (same order/index). */
    private var queue: List<TrackInfo> = emptyList()
    private var ticker: Job? = null

    // Cold-start race guard. The MediaController connects asynchronously and [restore] runs on
    // connection; until then the (service) player is empty/IDLE. A play tap landing in that window
    // is recorded here and applied by [maybeConsumeDeferredPlay] once the track settles at
    // READY/ENDED (the "press play once, nothing happens; press again and it plays" bug). Main-thread only.
    private var restoreComplete = false
    private var pendingPlayRequest = false

    // Blurred-background album art. Keyed by the current track's URI so we decode ONCE per track. Main-thread only.
    private var currentArtwork: Bitmap? = null
    private var artworkKey: String? = null

    private val _state = MutableStateFlow(PlaybackUiState())
    val state: StateFlow<PlaybackUiState> = _state.asStateFlow()

    // Transport actions invoked before the controller connects are buffered here and flushed on connect.
    private val pendingActions = ArrayDeque<(MediaController) -> Unit>()
    private var controller: MediaController? = null

    private val sessionToken = SessionToken(appContext, ComponentName(appContext, AuralTuneMediaService::class.java))
    private val controllerFuture: ListenableFuture<MediaController> =
        MediaController.Builder(appContext, sessionToken).buildAsync()

    private val playerListener = object : Player.Listener {
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
            refreshArtwork(); publish(); saveSnapshot()
        }
        override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
            // 메타데이터가 뒤늦게 확정될 때(가수/앨범/포맷) UI도 갱신 — 전환 직후 stale 값 방지.
            refreshArtwork(); publish()
        }
        override fun onPositionDiscontinuity(
            old: Player.PositionInfo,
            new: Player.PositionInfo,
            reason: Int,
        ) = publish()
        override fun onRepeatModeChanged(repeatMode: Int) = publish()
        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) = publish()
    }

    init {
        // Connect to the media service; wire up listener + restore once connected (async).
        controllerFuture.addListener({
            val c = runCatching { controllerFuture.get() }.getOrNull() ?: return@addListener
            controller = c
            onControllerConnected(c)
        }, ContextCompat.getMainExecutor(appContext))
        // Republish when the render pipeline reports a new audio format (sample rate / bit depth).
        scope.launch { telemetry.format.collect { publish() } }
    }

    /** Runs [block] with the connected controller, or buffers it until connection completes. */
    private fun withController(block: (MediaController) -> Unit) {
        val c = controller
        if (c != null) block(c) else pendingActions.addLast(block)
    }

    private fun onControllerConnected(c: MediaController) {
        c.addListener(playerListener)
        if (c.mediaItemCount > 0) {
            // Reconnect: the service player outlived this controller — rebuild the local mirror
            // from the player's items instead of restoring the snapshot.
            queue = (0 until c.mediaItemCount).map { i ->
                val mi = c.getMediaItemAt(i)
                val uri = mi.localConfiguration?.uri ?: mi.requestMetadata.mediaUri ?: Uri.EMPTY
                TrackInfo(uri, mi.mediaMetadata.title?.toString() ?: resolveTitle(uri))
            }
            restoreComplete = true
            refreshArtwork()
        } else {
            restore() // loads the saved snapshot into the (empty) service player
        }
        // Apply persisted repeat/shuffle to the session player.
        scope.launch {
            c.repeatMode = settings.repeatMode.first()
            c.shuffleModeEnabled = settings.shuffleEnabled.first()
            publish()
        }
        // Flush transport actions that arrived before connection (they run after restore's guard).
        val buffered = pendingActions.toList()
        pendingActions.clear()
        buffered.forEach { it(c) }
        if (pendingPlayRequest) maybeConsumeDeferredPlay(c.playbackState)
        publish()
    }

    // ── Queue / transport ──────────────────────────────────────────────────────

    /** Replace the queue with [uris] and start playing at [startIndex]. */
    fun setQueueAndPlay(uris: List<Uri>, startIndex: Int = 0) {
        if (uris.isEmpty()) return
        uris.forEach(::persistRead)
        queue = uris.map { TrackInfo(it, resolveTitle(it)) }
        withController { c ->
            c.setMediaItems(queue.map(::toMediaItem), startIndex.coerceIn(0, queue.lastIndex), 0L)
            c.prepare()
            c.playWhenReady = true
            publish(playbackError = null)
            saveSnapshot()
        }
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
        withController { c ->
            added.forEach { c.addMediaItem(toMediaItem(it)) }
            if (wasEmpty) { c.prepare(); c.playWhenReady = true }
            publish(playbackError = null)
            saveSnapshot()
        }
    }

    /**
     * Jump to a queue entry and play it. Re-[prepare]s first when the player is IDLE — otherwise a
     * prior decode error, a never-prepared player, or a restored-paused queue leaves the player in
     * STATE_IDLE where seek + play is silently ignored (the bug: tapping a queue row did nothing).
     */
    fun playIndex(index: Int) {
        if (index !in queue.indices) return
        withController { c ->
            ensurePrepared()
            c.seekTo(index, 0L)
            c.playWhenReady = true
            publish()
        }
    }

    fun removeFromQueue(index: Int) {
        if (index !in queue.indices) return
        val removed = queue[index].uri
        withController { c ->
            c.removeMediaItem(index)
            queue = queue.toMutableList().apply { removeAt(index) }
            if (queue.none { it.uri == removed }) releaseRead(removed) // 더 안 쓰면 영속 권한 해제
            publish()
            saveSnapshot()
        }
    }

    fun clearQueue() {
        queue.forEach { releaseRead(it.uri) }
        withController { c ->
            c.clearMediaItems()
            queue = emptyList()
            telemetry.clear()
            publish(playbackError = null)
            saveSnapshot()
        }
    }

    fun next() = withController { c ->
        if (c.hasNextMediaItem()) { ensurePrepared(); c.seekToNextMediaItem() }
        publish()
    }

    fun previous() = withController { c ->
        ensurePrepared()
        c.seekToPreviousMediaItem()
        publish()
    }

    fun togglePlayPause() = withController { c -> if (c.isPlaying) c.pause() else startPlayback() }
    fun pause() = withController { c -> c.pause() }
    fun resume() = startPlayback()
    fun seekTo(ms: Long) = withController { c -> c.seekTo(ms.coerceAtLeast(0L)); publish() }
    fun stop() = withController { c -> c.stop() }

    /** 반복 모드 순환: 한번만(OFF) → 전체(ALL) → 1곡(ONE) → 한번만. 영속한다. */
    fun cycleRepeatMode() = withController { c ->
        val next = when (c.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
        c.repeatMode = next
        scope.launch { settings.setRepeatMode(next) }
        publish()
    }

    /** 셔플(랜덤) 토글. 영속한다. */
    fun toggleShuffle() = withController { c ->
        val next = !c.shuffleModeEnabled
        c.shuffleModeEnabled = next
        scope.launch { settings.setShuffleEnabled(next) }
        publish()
    }

    fun consumePlaybackError() {
        if (_state.value.playbackError != null) {
            _state.value = _state.value.copy(playbackError = null)
        }
    }

    /**
     * 재생 시작/재개. 컨트롤러 미연결/버퍼링/복원 전이면 보류(pendingPlayRequest), IDLE이면 재prepare,
     * **큐 끝(ENDED)이면 처음(현재 곡 0초)으로 되감아** 다시 재생한다.
     */
    private fun startPlayback() {
        val c = controller
        if (c == null || (!restoreComplete && queue.isEmpty()) || c.playbackState == Player.STATE_BUFFERING) {
            pendingPlayRequest = true
            return
        }
        when (c.playbackState) {
            Player.STATE_IDLE -> { c.prepare(); c.playWhenReady = true }
            // 큐 0번이 아니라 '현재 곡'을 처음부터(seekTo(0L) = 현재 미디어아이템 0초).
            Player.STATE_ENDED -> { c.seekTo(0L); c.playWhenReady = true }
            else -> { // STATE_READY
                if (isAtEnd()) c.seekTo(0L) // 끝에서 멈춘 '현재 곡'을 다시 누르면 처음부터
                c.play()
            }
        }
        publish()
    }

    /** IDLE(에러/미prepare/복원직후)면 재생 전에 재prepare — seek+play가 묻히는 문제 방지. */
    private fun ensurePrepared() {
        val c = controller ?: return
        if (c.playbackState == Player.STATE_IDLE) c.prepare()
    }

    /**
     * 콜드 스타트 경합의 마무리: 컨트롤러 연결/복원 동안 들어온 재생 탭은 [startPlayback]에서
     * [pendingPlayRequest]로 보류됐다. 트랙이 READY/ENDED로 자리잡는 순간 적용한다(끝 위치면 처음부터).
     */
    private fun maybeConsumeDeferredPlay(playbackState: Int) {
        if (!pendingPlayRequest) return
        val c = controller ?: return
        if (playbackState != Player.STATE_READY && playbackState != Player.STATE_ENDED) return
        pendingPlayRequest = false
        if (playbackState == Player.STATE_ENDED || isAtEnd()) c.seekTo(0L) // 현재 곡 처음부터
        c.play()
        publish()
    }

    /** 현재(또는 복원된) 위치가 곡 끝 이내인지 — 끝에서 멈춘 트랙 재시작 판단용. */
    private fun isAtEnd(): Boolean {
        val c = controller ?: return false
        val dur = c.duration
        return dur > 0 && c.currentPosition >= dur - END_EPSILON_MS
    }

    val isPlaying: Boolean get() = controller?.isPlaying == true

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
        val c = controller ?: return
        val idx = c.currentMediaItemIndex
        // 결정 ①: 상단 줄은 파일명 우선(큐와 통일), 파일명 없을 때만 태그 제목 폴백.
        val title = queue.getOrNull(idx)?.title
            ?: c.mediaMetadata.title?.toString()
            ?: ""
        val meta = c.mediaMetadata
        val artist = meta.artist?.toString()?.ifBlank { null }
        val album = meta.albumTitle?.toString()?.ifBlank { null }
        val fmt = telemetry.format.value
        _state.value = PlaybackUiState(
            hasMedia = c.mediaItemCount > 0,
            isPlaying = c.isPlaying,
            title = title,
            positionMs = c.currentPosition.coerceAtLeast(0L),
            durationMs = c.duration.takeIf { it > 0L } ?: 0L,
            queue = queue,
            currentIndex = if (queue.isEmpty()) -1 else idx,
            audioBitDepth = fmt.bitDepth,
            audioSampleRateHz = fmt.sampleRateHz,
            playbackError = playbackError,
            repeatMode = c.repeatMode,
            shuffleEnabled = c.shuffleModeEnabled,
            artwork = currentArtwork,
            artist = artist,
            album = album,
        )
    }

    // Decode the current track's embedded cover ONCE per track (URI-keyed), off the main thread,
    // then publish. On track change we clear the old art first so the UI crossfades to the default
    // while decoding.
    //
    // Decode from the track's URI (authoritative for THIS track). We deliberately do NOT read
    // player.mediaMetadata.artworkData: on rapid skips it can still hold the PREVIOUS track's art
    // at transition time, which would then bind to the new key and stick (the later
    // onMediaMetadataChanged is short-circuited by the key guard) — showing the wrong cover.
    private fun refreshArtwork() {
        val c = controller ?: return
        val uri = queue.getOrNull(c.currentMediaItemIndex)?.uri
        val key = uri?.toString()
        if (key == null) {
            if (artworkKey != null || currentArtwork != null) {
                artworkKey = null; currentArtwork = null; publish()
            }
            return
        }
        if (key == artworkKey) return // already decoded / decoding for this track
        artworkKey = key
        currentArtwork = null
        publish() // show default background immediately while we decode
        scope.launch {
            val bmp = withContext(Dispatchers.Default) { ArtworkDecoder.fromUri(appContext, uri) }
            if (key == artworkKey) { // still the current track (stale decodes from fast skips discarded)
                currentArtwork = bmp
                publish()
            }
        }
    }

    private fun handlePlayerError(error: PlaybackException) {
        val c = controller ?: return
        stopTicker()
        val failedIndex = c.currentMediaItemIndex.takeIf { it in queue.indices }
        val failed = failedIndex?.let { queue[it] }
        val failedTitle = failed?.title ?: appContext.getString(R.string.player_unknown_track)
        val shouldContinue = c.playWhenReady
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
                c.setMediaItems(queue.map(::toMediaItem), nextIndex, 0L)
                c.prepare()
                c.playWhenReady = shouldContinue
            } else {
                c.clearMediaItems()
                telemetry.clear()
            }
        } else {
            c.clearMediaItems()
            queue = emptyList()
            telemetry.clear()
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
        val c = controller ?: return
        val q = queue
        val idx = c.currentMediaItemIndex.coerceAtLeast(0)
        val pos = c.currentPosition.coerceAtLeast(0L)
        scope.launch {
            settings.setPlaybackSnapshot(
                if (q.isEmpty()) null
                else PlaybackSnapshot(q.map { PlaybackTrack(it.uri.toString(), it.title) }, idx, pos),
            )
        }
    }

    /**
     * 컨트롤러 연결 시 1회: 저장된 큐/현재 곡/위치 복원. 저장된 큐가 없으면(첫 실행/큐 비움)
     * 번들 데모 곡([DEFAULT_ASSET_URI])을 기본 트랙으로 로딩한다. 항상 '일시정지'로 노출(자동 재생 금지).
     */
    private fun restore() {
        scope.launch {
            try {
                if (queue.isNotEmpty()) return@launch
                val c = controller ?: return@launch
                val snap = settings.playbackSnapshot.first()
                val saved = snap?.tracks
                    ?.takeIf { it.isNotEmpty() }
                    ?.map { TrackInfo(Uri.parse(it.uri), it.title) }
                if (queue.isNotEmpty()) return@launch // suspend 사이 사용자 추가 가드
                // 저장된 큐가 있으면 그대로 복원, 없으면 번들 데모 곡 1개를 기본 로딩.
                val tracks = saved ?: listOf(TrackInfo(DEFAULT_ASSET_URI, DEFAULT_ASSET_TITLE))
                val index = (snap?.index ?: 0).coerceIn(0, tracks.lastIndex)
                val pos = if (saved != null) (snap?.positionMs ?: 0L).coerceAtLeast(0L) else 0L
                queue = tracks
                c.setMediaItems(tracks.map(::toMediaItem), index, pos)
                c.prepare()
                // 로딩 중 들어온 재생 탭은 [pendingPlayRequest]에 기록되며 [maybeConsumeDeferredPlay]가 적용.
                c.playWhenReady = false
                refreshArtwork()
                publish()
            } finally {
                restoreComplete = true
                if (queue.isEmpty()) pendingPlayRequest = false
            }
        }
    }

    override fun close() {
        stopTicker()
        scope.coroutineContext[Job]?.cancel()
        controller?.removeListener(playerListener)
        MediaController.releaseFuture(controllerFuture)
        controller = null
        // engine / analyzer / telemetry are process-lifetime app singletons — not owned here.
    }

    companion object {
        private const val TAG = "MusicPlayer"
        private const val POSITION_POLL_MS = 500L
        private const val SAVE_EVERY_TICKS = 10 // ~5s마다 위치 저장
        private const val END_EPSILON_MS = 500L // 복원 위치가 곡 끝 이내면 처음부터 재생
        // 번들 데모 곡(assets/cheonsangyeon.m4a). 저장된 큐가 없을 때 기본 로딩. 표시 제목은 파일명 관례.
        private val DEFAULT_ASSET_URI: Uri = Uri.parse("asset:///cheonsangyeon.m4a")
        private const val DEFAULT_ASSET_TITLE = "천상연_이창섭.m4a"
    }
}
