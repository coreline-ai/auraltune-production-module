// AuralTuneMediaService.kt
// MediaSessionService that OWNS the ExoPlayer (with the inline AuralTune EQ) so playback survives
// the Activity/ViewModel: lock-screen + notification transport, Bluetooth/headset media buttons,
// and background playback. The UI connects via a MediaController (see MusicPlayerController).
//
// The engine, spectrum analyzer, and format telemetry are app singletons (ServiceLocator) shared
// with the UI, so EQ control + spectrum + format never cross the session/binder boundary.
package com.coreline.auraltune.audio

import android.content.Context
import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.coreline.audio.AudioEngine
import com.coreline.auraltune.AuralTuneApplication
import com.coreline.auraltune.audio.audiofx.PlayerDynamicsEqController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Build the playback ExoPlayer with the AuralTune EQ inserted as an [AudioProcessor].
 *
 * Format policy is identical to the previous in-ViewModel player: `setEnableFloatOutput(false)`
 * forces 16-bit decode so the processor (16-bit in → float EQ → 16-bit out) sits ahead of the
 * sink's SilenceSkipping/Sonic without a PCM_FLOAT conflict.
 */
@UnstableApi
internal fun buildAuralTunePlayer(
    context: Context,
    engine: AudioEngine,
    analyzer: SpectrumAnalyzer,
    telemetry: PlaybackTelemetry,
    processingState: PlaybackProcessingState,
): ExoPlayer {
    val processor = AuralTuneAudioProcessor(engine, analyzer, processingState) { sampleRateHz, bitDepth ->
        telemetry.update(sampleRateHz.takeIf { it > 0 }, bitDepth.takeIf { it > 0 })
    }
    val renderersFactory = object : DefaultRenderersFactory(context) {
        override fun buildAudioSink(
            context: Context,
            enableFloatOutput: Boolean,
            enableAudioTrackPlaybackParams: Boolean,
        ): AudioSink =
            DefaultAudioSink.Builder(context)
                .setEnableFloatOutput(false)
                .setAudioProcessors(arrayOf<AudioProcessor>(processor))
                .build()
    }
    val audioAttributes = AudioAttributes.Builder()
        .setUsage(C.USAGE_MEDIA)
        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
        .build()
    return ExoPlayer.Builder(context)
        .setRenderersFactory(renderersFactory)
        // Standard media player behaviour now that playback runs in a service:
        // request audio focus (pause on transient loss / duck) and pause when headphones unplug.
        .setAudioAttributes(audioAttributes, /* handleAudioFocus = */ true)
        .setHandleAudioBecomingNoisy(true)
        .build()
}

@UnstableApi
class AuralTuneMediaService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var dynamicsController: PlayerDynamicsEqController? = null

    override fun onCreate() {
        super.onCreate()
        val locator = (application as AuralTuneApplication).serviceLocator
        locator.spectrumAnalyzer.start()
        val processingState = locator.playbackProcessingState
        val controller = PlayerDynamicsEqController(
            statusSink = processingState::setDynamicsStatus,
            sampleRateProvider = { locator.audioEngine.sampleRate.toDouble() },
        )
        dynamicsController = controller
        val player = buildAuralTunePlayer(
            context = this,
            engine = locator.audioEngine,
            analyzer = locator.spectrumAnalyzer,
            telemetry = locator.playbackTelemetry,
            processingState = processingState,
        )
        player.addListener(object : Player.Listener {
            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                controller.onAudioSessionIdChanged(audioSessionId)
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                controller.onAudioSessionIdChanged(player.audioSessionId)
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                controller.onAudioSessionIdChanged(player.audioSessionId)
            }
        })
        serviceScope.launch {
            locator.settingsStore.playbackProcessingMode.collectLatest { processingState.setMode(it) }
        }
        serviceScope.launch {
            processingState.mode.collectLatest { controller.setMode(it) }
        }
        serviceScope.launch {
            combine(
                processingState.targetSpecs,
                processingState.targetHeadroomDb,
            ) { specs, headroomDb -> specs to headroomDb }
                .collectLatest { (specs, headroomDb) -> controller.setTargetSpecs(specs, headroomDb) }
        }
        mediaSession = MediaSession.Builder(this, player).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    /** 사용자 결정(2026-07-01): 태스크 스와이프 = 무조건 정지·종료. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        mediaSession?.player?.run {
            pause()
            stop()
        }
        stopSelf()
    }

    override fun onDestroy() {
        serviceScope.cancel()
        dynamicsController?.close()
        dynamicsController = null
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        // engine / analyzer / telemetry are process-lifetime app singletons — not released here.
        super.onDestroy()
    }
}
