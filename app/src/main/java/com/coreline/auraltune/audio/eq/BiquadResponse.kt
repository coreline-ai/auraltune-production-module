// BiquadResponse.kt
// Phase G0 — Kotlin freqz (magnitude response) for the graphic-EQ graph.
//
// Mirrors the engine's RBJ coefficient formulas (AuralTuneEQEngine::peakingCoeffs /
// lowShelfCoeffs / highShelfCoeffs / highPassCoeffs) so the on-screen curve matches
// what the native engine actually applies. Pure Kotlin — no native dependency.
package com.coreline.auraltune.audio.eq

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

enum class BiquadType { PEAKING, LOW_SHELF, HIGH_SHELF, HIGH_PASS }

/** One biquad section. gainDb is ignored for HIGH_PASS. */
data class BiquadSpec(
    val type: BiquadType,
    val freqHz: Double,
    val gainDb: Double,
    val q: Double,
)

object BiquadResponse {

    /** Normalized biquad coefficients (a0 divided out), matching the engine. */
    private data class Coeffs(
        val b0: Double, val b1: Double, val b2: Double,
        val a1: Double, val a2: Double,
    )

    private fun coeffs(s: BiquadSpec, sampleRate: Double): Coeffs {
        val a = 10.0.pow(s.gainDb / 40.0)        // A
        val w = 2.0 * PI * s.freqHz / sampleRate // omega
        val sinW = sin(w)
        val cosW = cos(w)
        val alpha = sinW / (2.0 * s.q)
        return when (s.type) {
            BiquadType.PEAKING -> {
                val a0 = 1.0 + alpha / a
                Coeffs(
                    (1.0 + alpha * a) / a0,
                    (-2.0 * cosW) / a0,
                    (1.0 - alpha * a) / a0,
                    (-2.0 * cosW) / a0,
                    (1.0 - alpha / a) / a0,
                )
            }
            BiquadType.LOW_SHELF -> {
                val ts = 2.0 * sqrt(a) * alpha
                val a0 = (a + 1.0) + (a - 1.0) * cosW + ts
                Coeffs(
                    a * ((a + 1.0) - (a - 1.0) * cosW + ts) / a0,
                    2.0 * a * ((a - 1.0) - (a + 1.0) * cosW) / a0,
                    a * ((a + 1.0) - (a - 1.0) * cosW - ts) / a0,
                    -2.0 * ((a - 1.0) + (a + 1.0) * cosW) / a0,
                    ((a + 1.0) + (a - 1.0) * cosW - ts) / a0,
                )
            }
            BiquadType.HIGH_SHELF -> {
                val ts = 2.0 * sqrt(a) * alpha
                val a0 = (a + 1.0) - (a - 1.0) * cosW + ts
                Coeffs(
                    a * ((a + 1.0) + (a - 1.0) * cosW + ts) / a0,
                    -2.0 * a * ((a - 1.0) + (a + 1.0) * cosW) / a0,
                    a * ((a + 1.0) + (a - 1.0) * cosW - ts) / a0,
                    2.0 * ((a - 1.0) - (a + 1.0) * cosW) / a0,
                    ((a + 1.0) - (a - 1.0) * cosW - ts) / a0,
                )
            }
            BiquadType.HIGH_PASS -> {
                val a0 = 1.0 + alpha
                Coeffs(
                    (1.0 + cosW) / 2.0 / a0,
                    -(1.0 + cosW) / a0,
                    (1.0 + cosW) / 2.0 / a0,
                    (-2.0 * cosW) / a0,
                    (1.0 - alpha) / a0,
                )
            }
        }
    }

    /** Magnitude (dB) of one biquad at [freqHz]. */
    private fun magnitudeDb(c: Coeffs, freqHz: Double, sampleRate: Double): Double {
        val w = 2.0 * PI * freqHz / sampleRate
        val cw = cos(w); val c2w = cos(2.0 * w)
        val sw = sin(w); val s2w = sin(2.0 * w)
        val numRe = c.b0 + c.b1 * cw + c.b2 * c2w
        val numIm = -(c.b1 * sw + c.b2 * s2w)
        val denRe = 1.0 + c.a1 * cw + c.a2 * c2w
        val denIm = -(c.a1 * sw + c.a2 * s2w)
        val num = sqrt(numRe * numRe + numIm * numIm)
        val den = sqrt(denRe * denRe + denIm * denIm)
        if (den <= 0.0 || num <= 0.0) return 0.0
        return 20.0 * log10(num / den)
    }

    /**
     * Composite magnitude (dB) at each frequency = sum of all filters' dB responses
     * (cascade in series → multiply linear = add dB).
     */
    fun compositeDb(
        freqsHz: DoubleArray,
        filters: List<BiquadSpec>,
        sampleRate: Double,
    ): DoubleArray {
        if (filters.isEmpty()) return DoubleArray(freqsHz.size)
        val cs = filters.map { coeffs(it, sampleRate) }
        return DoubleArray(freqsHz.size) { i ->
            var sum = 0.0
            for (c in cs) sum += magnitudeDb(c, freqsHz[i], sampleRate)
            sum
        }
    }
}
