// MusicPlayerController.kt
// Phase 1 (T1) — wraps an ExoPlayer whose audio sink runs every decoded PCM
// block through AuralTuneAudioProcessor (→ native EQ engine).
//
// The engine instance is shared with the rest of the app (single owner). Music
// playback and the TestTone path (AudioPlayerService) are mutually exclusive:
// callers MUST NOT run both at once, since each drives engine.process() from its
// own audio thread.
package com.coreline.auraltune.audio

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import com.coreline.audio.AudioEngine
import java.io.Closeable

@UnstableApi
class MusicPlayerController(
    context: Context,
    engine: AudioEngine,
) : Closeable {

    private val processor = AuralTuneAudioProcessor(engine)

    private val player: ExoPlayer = run {
        // Inject our AudioProcessor into the default audio sink so all decoded
        // PCM flows through the engine before AudioTrack.
        val renderersFactory = object : DefaultRenderersFactory(context) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean,
            ): AudioSink {
                return DefaultAudioSink.Builder(context)
                    .setAudioProcessors(arrayOf(processor))
                    .build()
            }
        }
        ExoPlayer.Builder(context)
            .setRenderersFactory(renderersFactory)
            .build()
    }

    /** Start (or restart) playback of a local file URI. */
    fun play(uri: Uri) {
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
}
