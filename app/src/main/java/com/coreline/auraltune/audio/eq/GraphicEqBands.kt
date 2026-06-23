// GraphicEqBands.kt
// Phase G0 — 20-band graphic EQ definition (frequencies + Q).
//
// Bands are log-spaced 20Hz..20kHz (≈1/2-octave). Each band is a peaking filter
// (Manual chain is peaking-only). Q is chosen so adjacent bands cross near −3dB,
// giving smooth overlap (the "bell tails" that fill the gaps between sliders).
package com.coreline.auraltune.audio.eq

import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt

object GraphicEqBands {
    const val COUNT = 20
    const val MIN_HZ = 20.0
    const val MAX_HZ = 20_000.0

    /** Default slider gain limit in dB (±). User may change it; see [GAIN_LIMIT_OPTIONS]. */
    const val MAX_GAIN_DB = 12.0f

    /** Absolute ceiling for any selectable limit — storage sanity clamp uses this, not the live limit. */
    const val MAX_GAIN_LIMIT_DB = 20.0f

    /** User-selectable gain limits (segmented chips). [MAX_GAIN_DB] is the default. */
    val GAIN_LIMIT_OPTIONS = floatArrayOf(6.0f, 12.0f, 15.0f, 20.0f)

    /** Log-spaced center frequencies: f_i = 20 * 1000^(i/(N-1)). */
    val frequencies: DoubleArray = DoubleArray(COUNT) { i ->
        MIN_HZ * (MAX_HZ / MIN_HZ).pow(i.toDouble() / (COUNT - 1))
    }

    /**
     * Per-band Q for ~−3dB crossover at the neighbour.
     * Band spacing = log2(1000)/(N-1) octaves; Q = √f / (f − 1), f = 2^bw.
     */
    val q: Double = run {
        val bwOct = (ln(MAX_HZ / MIN_HZ) / ln(2.0)) / (COUNT - 1)
        val f = 2.0.pow(bwOct)
        sqrt(f) / (f - 1.0)
    }

    /** Human-readable label for a band center (e.g. "1.0k", "16k", "63"). */
    fun label(freqHz: Double): String = when {
        freqHz >= 1000.0 -> {
            val k = freqHz / 1000.0
            if (k >= 10.0) "${k.toInt()}k" else String.format("%.1fk", k)
        }
        else -> freqHz.toInt().toString()
    }

    /** Build BiquadSpec list from current band gains (skip ~0dB bands). */
    fun toSpecs(bandGainsDb: FloatArray): List<BiquadSpec> {
        val out = ArrayList<BiquadSpec>(COUNT)
        for (i in 0 until COUNT) {
            val g = bandGainsDb.getOrElse(i) { 0f }.toDouble()
            if (kotlin.math.abs(g) < 0.05) continue // near-flat → skip
            out.add(BiquadSpec(BiquadType.PEAKING, frequencies[i], g, q))
        }
        return out
    }
}
