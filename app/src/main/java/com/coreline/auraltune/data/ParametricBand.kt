// ParametricBand.kt
// Persistence model for ONE parametric-EQ band (PARAMETRIC EqMode).
//
// Unlike the graphic EQ (fixed 20-band grid → persist only gains), a parametric band is
// fully free-form, so we persist all four DSP parameters plus a stable id for the editor.
// [type] is the native filter-type id (matches the C++ enum & EqFilterType.nativeId) so the
// stored value is independent of any Kotlin enum ordinal churn.
//
// Stored as a JSON list in SettingsStore. Covered by the `com.coreline.auraltune.data.**`
// ProGuard keep rule (do NOT move out of this package without updating proguard-rules.pro).
package com.coreline.auraltune.data

import com.coreline.auraltune.audio.eq.BiquadSpec
import com.coreline.auraltune.audio.eq.biquadTypeFromNativeId
import kotlinx.serialization.Serializable
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * @property id     Stable unique id (UUID) — identifies the band in the graph-drag editor.
 * @property type   Native filter-type id: 0=PEAKING, 1=LOW_SHELF, 2=HIGH_SHELF, 3=HIGH_PASS.
 * @property freqHz Center / cutoff frequency in Hz.
 * @property gainDb Gain in dB (ignored by the engine for HIGH_PASS, but still persisted).
 * @property q      Q factor (resonance / bandwidth).
 */
@Serializable
data class ParametricBand(
    val id: String,
    val type: Int,
    val freqHz: Float,
    val gainDb: Float,
    val q: Float,
) {
    /** Render/engine spec for this band (type id → BiquadType). */
    fun toBiquadSpec(): BiquadSpec = BiquadSpec(
        type = biquadTypeFromNativeId(type),
        freqHz = freqHz.toDouble(),
        gainDb = gainDb.toDouble(),
        q = q.toDouble(),
    )

    /** Clamp every field into a valid, engine-safe range. */
    fun normalized(): ParametricBand = copy(
        // Unknown/corrupt type ids fall back to PEAKING (a no-op at 0 dB), matching
        // biquadTypeFromNativeId. coerceIn(0,3) would map e.g. 99 -> HIGH_PASS, silently
        // cutting bass — never the safe default for unrecognized data.
        type = if (type in 0..3) type else 0,
        freqHz = if (freqHz.isFinite()) freqHz.coerceIn(MIN_FREQ_HZ, MAX_FREQ_HZ) else DEFAULT_FREQ_HZ,
        gainDb = if (gainDb.isFinite()) gainDb.coerceIn(-MAX_GAIN_DB, MAX_GAIN_DB) else 0f,
        q = if (q.isFinite()) q.coerceIn(MIN_Q, MAX_Q) else DEFAULT_Q,
    )

    companion object {
        /** Max parametric bands the engine Manual chain caps at 20, but UX caps lower. */
        const val MAX_BANDS = 8

        const val MIN_FREQ_HZ = 20f
        const val MAX_FREQ_HZ = 20_000f
        const val DEFAULT_FREQ_HZ = 1_000f

        const val MIN_Q = 0.2f
        const val MAX_Q = 10f
        const val DEFAULT_Q = 1.0f

        /** Storage sanity ceiling for gain (matches the graphic-EQ absolute ceiling). */
        const val MAX_GAIN_DB = 20f

        /** A fresh default band (peaking, 1 kHz, flat) at the given [id]. */
        fun default(id: String): ParametricBand =
            ParametricBand(id = id, type = 0, freqHz = DEFAULT_FREQ_HZ, gainDb = 0f, q = DEFAULT_Q)

        /**
         * Default frequency for a newly added band: the geometric center (log-midpoint) of the
         * widest frequency gap among the existing [bands] plus the spectrum edges (20 Hz / 20 kHz).
         * This guarantees a new band never lands exactly on top of an existing one (which would
         * make multiple handles overlap into a single visible dot). Empty list → [DEFAULT_FREQ_HZ].
         */
        fun nextFreqHz(bands: List<ParametricBand>): Float {
            if (bands.isEmpty()) return DEFAULT_FREQ_HZ
            val pts = (listOf(MIN_FREQ_HZ.toDouble(), MAX_FREQ_HZ.toDouble()) +
                bands.map { it.freqHz.toDouble() }).sorted()
            var bestMid = DEFAULT_FREQ_HZ.toDouble()
            var bestGap = -1.0
            for (i in 0 until pts.size - 1) {
                val a = pts[i]; val b = pts[i + 1]
                val gap = ln(b) - ln(a)
                if (gap > bestGap) { bestGap = gap; bestMid = sqrt(a * b) }
            }
            return bestMid.toFloat().coerceIn(MIN_FREQ_HZ, MAX_FREQ_HZ)
        }
    }
}
