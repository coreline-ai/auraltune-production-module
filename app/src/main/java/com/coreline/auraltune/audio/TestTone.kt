// TestTone.kt
// Phase-continuous 1 kHz stereo sine generator at -12 dBFS. Used by AudioPlayerService
// as the MVP sample provider so users can hear EQ changes on first launch without needing
// any external audio source.
package com.coreline.auraltune.audio

import kotlin.math.PI
import kotlin.math.sin

/**
 * Generates a 1 kHz stereo sine wave at -12 dBFS, interleaved Float32.
 *
 * Phase is preserved across [fill] calls so successive buffers stitch without clicks.
 * Not thread-safe by itself — caller (AudioPlayerService) holds it from a single audio thread.
 */
class TestTone(
    private val sampleRate: Int = 48_000,
    private val frequencyHz: Double = 1_000.0,
    /** Linear amplitude. -12 dBFS ≈ 0.2512f. */
    private val amplitude: Float = 0.2512f,
) {
    private var phase: Double = 0.0
    private val phaseIncrement: Double = 2.0 * PI * frequencyHz / sampleRate

    /**
     * Fill [out] with `numFrames` of stereo interleaved samples (length = numFrames * 2).
     * Returns numFrames written so the call site can match the [AudioPlayerService] contract.
     */
    fun fill(out: FloatArray, numFrames: Int): Int {
        val needed = numFrames * 2
        require(out.size >= needed) { "buffer too small: ${out.size} < $needed" }
        var p = phase
        var i = 0
        while (i < needed) {
            val s = (sin(p) * amplitude).toFloat()
            out[i] = s        // left
            out[i + 1] = s    // right
            p += phaseIncrement
            // Wrap to keep numerical precision over long runs.
            if (p >= TWO_PI) p -= TWO_PI
            i += 2
        }
        phase = p
        return numFrames
    }

    /** Reset oscillator phase. Call when starting a fresh session. */
    fun reset() { phase = 0.0 }

    private companion object {
        const val TWO_PI = 2.0 * PI
    }
}
