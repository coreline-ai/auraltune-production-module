// AuralTuneAudioProcessor.kt
// Phase 1 (T1) — Media3 AudioProcessor that routes ExoPlayer's decoded PCM
// through the native AuralTune EQ engine.
//
// Format policy: the processor keeps the SAME output encoding as the input
// (16-bit in → 16-bit out). The engine works in float, so 16-bit input is
// converted to a float scratch buffer, processed in place, then written back
// as 16-bit.
//
// WHY not output PCM_FLOAT: DefaultAudioSink appends its own toggleable
// processors (SilenceSkippingAudioProcessor, Sonic) AFTER ours. Those reject
// PCM_FLOAT (encoding=4) and throw UnhandledAudioFormatException, which kills
// playback. Keeping the encoding unchanged stays compatible with that chain.
//
// Stereo only: the engine assumes stereo-interleaved float. Mono / other
// channel counts pass through untouched (applyEngine = false).
package com.coreline.auraltune.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import com.coreline.audio.AudioEngine
import java.nio.ByteBuffer
import java.nio.ByteOrder

@UnstableApi
class AuralTuneAudioProcessor(
    private val engine: AudioEngine,
    private val analyzer: SpectrumAnalyzer? = null,
    private val onFormatChanged: ((sampleRateHz: Int, bitDepth: Int) -> Unit)? = null,
) : BaseAudioProcessor() {

    /** True only for stereo input — the engine's interleaved-stereo contract. */
    private var applyEngine = false

    /** Reusable direct float buffer for the engine (16-bit path). */
    private var floatScratch: ByteBuffer? = null

    private fun scratch(bytes: Int): ByteBuffer {
        val cur = floatScratch
        val s = if (cur != null && cur.capacity() >= bytes) {
            cur
        } else {
            ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder()).also { floatScratch = it }
        }
        s.clear()
        return s
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
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
        analyzer?.setSampleRate(inputAudioFormat.sampleRate) // 스펙트럼 주파수 매핑용
        onFormatChanged?.invoke(inputAudioFormat.sampleRate, bitDepthOf(inputAudioFormat.encoding))

        // ALWAYS output 16-bit PCM regardless of input encoding. DefaultAudioSink's
        // downstream processors (SilenceSkipping/Sonic) reject PCM_FLOAT, so emitting
        // float — even when the input is already float — breaks playback.
        return AudioProcessor.AudioFormat(
            inputAudioFormat.sampleRate,
            inputAudioFormat.channelCount,
            C.ENCODING_PCM_16BIT,
        )
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val position = inputBuffer.position()
        val limit = inputBuffer.limit()
        val byteCount = limit - position
        if (byteCount == 0) return

        val channels = inputAudioFormat.channelCount
        val is16 = inputAudioFormat.encoding == C.ENCODING_PCM_16BIT
        val frames = if (is16) byteCount / (2 * channels) else byteCount / (4 * channels)
        val samples = frames * channels

        // Gather input into a direct float scratch buffer for the engine.
        val fs = scratch(samples * 4)
        if (is16) {
            var i = position
            while (i < limit) {
                fs.putFloat(inputBuffer.getShort(i) / 32768.0f)
                i += 2
            }
        } else {
            var i = position
            while (i < limit) {
                fs.putFloat(inputBuffer.getFloat(i))
                i += 4
            }
        }
        inputBuffer.position(limit)

        if (applyEngine && frames > 0) {
            engine.process(fs, frames) // in-place on base address
        }

        // post-EQ 시각화 탭(RT-safe: 복사만). 절대 인덱스 읽기라 아래 출력 루프의 rewind에 영향 없음.
        if (frames > 0) analyzer?.feed(fs, frames, channels)

        // Always write 16-bit output.
        val out = replaceOutputBuffer(samples * 2)
        fs.rewind()
        repeat(samples) {
            val v = fs.float * 32768.0f
            val clamped = if (v > 32767.0f) 32767.0f else if (v < -32768.0f) -32768.0f else v
            out.putShort(clamped.toInt().toShort())
        }
        out.flip()
    }

    override fun onReset() {
        floatScratch = null
    }

    private fun bitDepthOf(encoding: Int): Int = when (encoding) {
        C.ENCODING_PCM_16BIT -> 16
        C.ENCODING_PCM_FLOAT -> 32
        else -> 0
    }
}
