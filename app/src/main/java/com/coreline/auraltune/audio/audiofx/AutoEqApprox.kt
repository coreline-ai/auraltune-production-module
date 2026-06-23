// AutoEqApprox.kt
// Phase 6 (T2-OS, dev-plan 110525): approximate a target EQ curve (our engine's AutoEQ
// correction) with the fixed/limited bands of an OS AudioEffect (Equalizer / DynamicsProcessing),
// and QUANTIFY the approximation error versus our own freqz. This is the honesty layer: T2 is an
// approximation, so we measure exactly how far the OS-band fit is from the precise engine curve.
//
// Pure math — reuses the Kotlin freqz (BiquadResponse). No android.media.audiofx dependency, so
// it is fully unit-testable off-device.
package com.coreline.auraltune.audio.audiofx

import com.coreline.auraltune.audio.eq.BiquadResponse
import com.coreline.auraltune.audio.eq.BiquadSpec
import com.coreline.auraltune.audio.eq.BiquadType
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

object AutoEqApprox {

    /** One OS equalizer band: its center frequency and the gain we will set on it. */
    data class BandGain(val centerHz: Double, val gainDb: Double)

    /**
     * @property bands per-band gains to push to the OS effect.
     * @property rmsErrorDb RMS of (approx − target) across the eval grid — overall fidelity.
     * @property maxErrorDb worst-case |approx − target| — surfaced in UI as "approximation".
     */
    data class FitResult(
        val bands: List<BandGain>,
        val rmsErrorDb: Double,
        val maxErrorDb: Double,
    )

    /**
     * Fit OS-effect [bandCenters] gains to approximate the response of [targetFilters]
     * (an AutoEQ profile, as [BiquadSpec]s).
     *
     * Strategy = direct sampling: each band gain is the target response at the band's center
     * frequency. We then RECONSTRUCT the realized curve by modeling each band as a peaking
     * filter ([bandQ]) and summing — this captures the inter-band interaction the OS effect
     * actually produces — and measure RMS/max error against the precise target on a dense
     * log grid. Clamps to [maxGainDb] (most OS equalizers cap at ±15 dB).
     */
    fun fit(
        targetFilters: List<BiquadSpec>,
        bandCenters: DoubleArray,
        sampleRate: Double = 48_000.0,
        bandQ: Double = 1.41,
        maxGainDb: Double = 15.0,
        gridPoints: Int = 256,
    ): FitResult {
        require(bandCenters.isNotEmpty()) { "bandCenters must not be empty" }

        // >=2 so the log grid never divides by (n-1)=0 (would yield NaN error metrics).
        val grid = logGrid(gridPoints.coerceAtLeast(2))
        val target = BiquadResponse.compositeDb(grid, targetFilters, sampleRate)

        // Band gains = target response sampled at each band center, clamped to the OS range.
        val sampled = BiquadResponse.compositeDb(bandCenters, targetFilters, sampleRate)
        val bands = bandCenters.indices.map { i ->
            BandGain(bandCenters[i], sampled[i].coerceIn(-maxGainDb, maxGainDb))
        }

        // Reconstruct what those bands actually produce (peaking model) and measure error.
        val approxFilters = bands.map { BiquadSpec(BiquadType.PEAKING, it.centerHz, it.gainDb, bandQ) }
        val approx = BiquadResponse.compositeDb(grid, approxFilters, sampleRate)

        var sumSq = 0.0
        var maxErr = 0.0
        for (i in grid.indices) {
            val e = approx[i] - target[i]
            sumSq += e * e
            if (abs(e) > maxErr) maxErr = abs(e)
        }
        return FitResult(
            bands = bands,
            rmsErrorDb = sqrt(sumSq / grid.size),
            maxErrorDb = maxErr,
        )
    }

    /** Dense log-spaced eval grid, 20 Hz – 20 kHz. */
    private fun logGrid(n: Int): DoubleArray =
        DoubleArray(n) { 20.0 * (20_000.0 / 20.0).pow(it.toDouble() / (n - 1)) }
}
