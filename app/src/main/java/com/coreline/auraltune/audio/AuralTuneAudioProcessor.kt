// AuralTuneAudioProcessor.kt
// Phase 1 (T1) — Media3 AudioProcessor that routes ExoPlayer's decoded PCM
// through the native AuralTune EQ engine.
//
// Strategy (A): the processor ALWAYS outputs PCM_FLOAT. 16-bit input is
// converted to float; the engine runs in-place on the (direct) output buffer.
// This keeps the native engine unmodified and self-contained on the app side.
//
// Stereo only: the engine assumes stereo-interleaved float. Mono / other channel
// counts are float-converted but pass through un-EQ'd (applyEngine = false).
package com.coreline.auraltune.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import com.coreline.audio.AudioEngine
import java.nio.ByteBuffer

@UnstableApi
class AuralTuneAudioProcessor(private val engine: AudioEngine) : BaseAudioProcessor() {

    /** True only for stereo input — the engine's interleaved-stereo contract. */
    private var applyEngine = false

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        // Accept the two PCM encodings ExoPlayer realistically emits.
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT &&
            inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT
        ) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }

        applyEngine = inputAudioFormat.channelCount == 2
        // Lock the engine's pre-warp target to the actual stream rate so AutoEQ
        // center frequencies don't drift. Engine update is RT-safe (snapshot publish).
        if (applyEngine && engine.sampleRate != inputAudioFormat.sampleRate) {
            engine.updateSampleRate(inputAudioFormat.sampleRate)
        }

        // Always emit float32; same rate / channel count.
        return AudioProcessor.AudioFormat(
            inputAudioFormat.sampleRate,
            inputAudioFormat.channelCount,
            C.ENCODING_PCM_FLOAT,
        )
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val position = inputBuffer.position()
        val limit = inputBuffer.limit()
        val byteCount = limit - position
        if (byteCount == 0) return

        val is16Bit = inputAudioFormat.encoding == C.ENCODING_PCM_16BIT
        val channels = inputAudioFormat.channelCount
        val frames = if (is16Bit) byteCount / (2 * channels) else byteCount / (4 * channels)
        val outBytes = frames * channels * 4

        // BaseAudioProcessor hands back a direct, native-order ByteBuffer.
        val out = replaceOutputBuffer(outBytes)

        if (is16Bit) {
            // 16-bit signed PCM → normalized float [-1, 1).
            var i = position
            while (i < limit) {
                out.putFloat(inputBuffer.getShort(i) / 32768.0f)
                i += 2
            }
        } else {
            out.put(inputBuffer) // already float32
        }
        inputBuffer.position(limit)

        // engine.process() reads the direct buffer's base address (position-
        // independent) and rewrites it in place.
        if (applyEngine && frames > 0) {
            engine.process(out, frames)
        }
        out.flip()
    }
}
