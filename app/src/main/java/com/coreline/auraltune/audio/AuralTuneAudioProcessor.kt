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
import com.coreline.auraltune.BuildConfig
import com.coreline.audio.AudioEngine
import com.coreline.audio.PcmFormat
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

@UnstableApi
class AuralTuneAudioProcessor(
    private val engine: AudioEngine,
    private val analyzer: SpectrumAnalyzer? = null,
    private val processingState: PlaybackProcessingState? = null,
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
        if (!isSupportedPcmEncoding(inputAudioFormat.encoding)) {
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
        val bytesPerSample = pcmBytesPerSample(inputAudioFormat.encoding)
        val frames = byteCount / (bytesPerSample * channels)
        val samples = frames * channels
        val nativeEngineActive = applyEngine && processingState?.useNativeEngine() != false

        if (nativeEngineActive && frames > 0 && processNativeFastPath(inputBuffer, position, limit, frames, channels)) {
            return
        }

        if (!nativeEngineActive &&
            inputAudioFormat.encoding == C.ENCODING_PCM_16BIT &&
            frames > 0 &&
            copyPcm16Passthrough(inputBuffer, position, limit, frames, channels)
        ) {
            return
        }

        // Gather input into a direct float scratch buffer for the engine.
        val fs = scratch(samples * 4)
        var i = position
        repeat(samples) {
            fs.putFloat(pcmSampleToFloat(inputBuffer, i, inputAudioFormat.encoding))
            i += bytesPerSample
        }
        inputBuffer.position(limit)

        if (nativeEngineActive && frames > 0) {
            engine.process(fs, frames) // in-place on base address
        }

        // post-EQ 시각화 탭(RT-safe: 복사만). 절대 인덱스 읽기라 아래 출력 루프의 rewind에 영향 없음.
        if (frames > 0 && analyzer?.hasSubscribers() == true) analyzer.feed(fs, frames, channels)

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

    private fun processNativeFastPath(
        inputBuffer: ByteBuffer,
        position: Int,
        limit: Int,
        frames: Int,
        channels: Int,
    ): Boolean {
        val inputFormat = pcmFormatOf(inputAudioFormat.encoding) ?: run {
            AuralTuneAudioProcessorDiagnostics.recordFastPathMiss()
            return false
        }
        val byteCount = limit - position
        if (!inputBuffer.isDirect) {
            AuralTuneAudioProcessorDiagnostics.recordFastPathMiss()
            return false
        }
        val input = if (position == 0 && inputBuffer.isDirect) {
            inputBuffer
        } else {
            inputBuffer.duplicate().apply {
                this.position(position)
                this.limit(limit)
            }.slice().order(ByteOrder.nativeOrder())
        }
        if (input.capacity() < byteCount) {
            AuralTuneAudioProcessorDiagnostics.recordFastPathMiss()
            return false
        }

        val out = replaceOutputBuffer(frames * channels * PcmFormat.S16.bytesPerSample)
        val rc = engine.processFormatted(
            input = input,
            inputFormat = inputFormat,
            output = out,
            outputFormat = PcmFormat.S16,
            numFrames = frames,
        )
        if (rc != 0) {
            AuralTuneAudioProcessorDiagnostics.recordFastPathNativeReject(rc)
            return false
        }

        inputBuffer.position(limit)
        AuralTuneAudioProcessorDiagnostics.recordFastPathHit(frames)
        if (analyzer?.hasSubscribers() == true) analyzer.feedPcm16(out, frames, channels)
        out.position(frames * channels * PcmFormat.S16.bytesPerSample)
        out.flip()
        return true
    }

    private fun copyPcm16Passthrough(
        inputBuffer: ByteBuffer,
        position: Int,
        limit: Int,
        frames: Int,
        channels: Int,
    ): Boolean {
        val byteCount = limit - position
        val src = inputBuffer.duplicate().apply {
            this.position(position)
            this.limit(limit)
        }.slice()
        val out = replaceOutputBuffer(byteCount)
        out.put(src)
        inputBuffer.position(limit)
        if (analyzer?.hasSubscribers() == true) analyzer.feedPcm16(out, frames, channels)
        out.flip()
        return true
    }

    override fun onReset() {
        floatScratch = null
    }

    private fun bitDepthOf(encoding: Int): Int = when (encoding) {
        C.ENCODING_PCM_16BIT -> 16
        C.ENCODING_PCM_24BIT -> 24
        C.ENCODING_PCM_32BIT -> 32
        C.ENCODING_PCM_FLOAT -> 32
        else -> 0
    }
}

private fun pcmFormatOf(encoding: Int): PcmFormat? = when (encoding) {
    C.ENCODING_PCM_16BIT -> PcmFormat.S16
    C.ENCODING_PCM_24BIT -> PcmFormat.S24
    C.ENCODING_PCM_32BIT -> PcmFormat.S32
    C.ENCODING_PCM_FLOAT -> PcmFormat.F32
    else -> null
}

internal object AuralTuneAudioProcessorDiagnostics {
    data class Snapshot(
        val fastPathHits: Long,
        val fastPathMisses: Long,
        val nativeRejects: Long,
        val maxFrames: Long,
        val lastNativeRc: Int,
    )

    private val fastPathHits = AtomicLong()
    private val fastPathMisses = AtomicLong()
    private val nativeRejects = AtomicLong()
    private val maxFrames = AtomicLong()
    private val lastNativeRc = AtomicInteger()

    fun snapshot(): Snapshot = Snapshot(
        fastPathHits = fastPathHits.get(),
        fastPathMisses = fastPathMisses.get(),
        nativeRejects = nativeRejects.get(),
        maxFrames = maxFrames.get(),
        lastNativeRc = lastNativeRc.get(),
    )

    fun reset() {
        fastPathHits.set(0)
        fastPathMisses.set(0)
        nativeRejects.set(0)
        maxFrames.set(0)
        lastNativeRc.set(0)
    }

    internal fun recordFastPathHit(frames: Int) {
        if (!BuildConfig.DEBUG) return
        fastPathHits.incrementAndGet()
        updateMaxFrames(frames.toLong())
    }

    internal fun recordFastPathMiss() {
        if (!BuildConfig.DEBUG) return
        fastPathMisses.incrementAndGet()
    }

    internal fun recordFastPathNativeReject(rc: Int) {
        if (!BuildConfig.DEBUG) return
        nativeRejects.incrementAndGet()
        fastPathMisses.incrementAndGet()
        lastNativeRc.set(rc)
    }

    private fun updateMaxFrames(frames: Long) {
        var cur = maxFrames.get()
        while (frames > cur && !maxFrames.compareAndSet(cur, frames)) {
            cur = maxFrames.get()
        }
    }
}

private fun isSupportedPcmEncoding(encoding: Int): Boolean =
    encoding == C.ENCODING_PCM_16BIT ||
        encoding == C.ENCODING_PCM_24BIT ||
        encoding == C.ENCODING_PCM_32BIT ||
        encoding == C.ENCODING_PCM_FLOAT

private fun pcmBytesPerSample(encoding: Int): Int = when (encoding) {
    C.ENCODING_PCM_16BIT -> 2
    C.ENCODING_PCM_24BIT -> 3
    C.ENCODING_PCM_32BIT,
    C.ENCODING_PCM_FLOAT -> 4
    else -> error("unsupported PCM encoding=$encoding")
}

internal fun pcmSampleToFloat(input: ByteBuffer, offset: Int, encoding: Int): Float = when (encoding) {
    C.ENCODING_PCM_16BIT -> {
        val lo = input.get(offset).toInt() and 0xFF
        val hi = input.get(offset + 1).toInt()
        (lo or (hi shl 8)).toShort().toInt() / 32768.0f
    }
    C.ENCODING_PCM_24BIT -> {
        val b0 = input.get(offset).toInt() and 0xFF
        val b1 = input.get(offset + 1).toInt() and 0xFF
        val b2 = input.get(offset + 2).toInt() and 0xFF
        val raw = b0 or (b1 shl 8) or (b2 shl 16)
        val signed = if ((raw and 0x00800000) != 0) raw or -0x01000000 else raw
        signed / 8388608.0f
    }
    C.ENCODING_PCM_32BIT -> {
        val b0 = input.get(offset).toInt() and 0xFF
        val b1 = input.get(offset + 1).toInt() and 0xFF
        val b2 = input.get(offset + 2).toInt() and 0xFF
        val b3 = input.get(offset + 3).toInt()
        val signed = b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
        signed / 2147483648.0f
    }
    C.ENCODING_PCM_FLOAT -> input.duplicate()
        .order(ByteOrder.LITTLE_ENDIAN)
        .getFloat(offset)
    else -> error("unsupported PCM encoding=$encoding")
}
