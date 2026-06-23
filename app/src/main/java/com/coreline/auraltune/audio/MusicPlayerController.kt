// MusicPlayerController.kt
// Phase 1 (T1) — local-file music playback via ExoPlayer WITH the AuralTune EQ.
//
// Fix recap: device decoder emits PCM_FLOAT which ExoPlayer's built-in
// SilenceSkipping rejects. setEnableFloatOutput(false) forces 16-bit decode, so
// our AuralTuneAudioProcessor (16-bit in → float EQ → 16-bit out) now sits in
// the chain ahead of SilenceSkipping without a format conflict.
package com.coreline.auraltune.audio

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.media3.common.MediaItem
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
import java.io.Closeable

@UnstableApi
class MusicPlayerController(
    context: Context,
    engine: AudioEngine,
) : Closeable {

    private val processor = AuralTuneAudioProcessor(engine)

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
                    }
                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        if (BuildConfig.DEBUG) Log.i(TAG, "isPlaying=$isPlaying")
                    }
                })
            }
    }

    fun play(uri: Uri) {
        if (BuildConfig.DEBUG) Log.i(TAG, "play() uri=$uri")
        player.setMediaItem(MediaItem.fromUri(uri))
        player.prepare()
        player.playWhenReady = true
    }

    fun pause() { player.pause() }
    fun resume() { player.play() }
    fun stop() { player.stop() }

    val isPlaying: Boolean get() = player.isPlaying

    override fun close() {
        player.release()
    }

    companion object { private const val TAG = "MusicPlayer" }
}
