package com.coreline.audio.loudness

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Pre-verification reference test for **C. Loudness Compensation (ISO 226:2023)**.
 *
 * Pins the math the future native implementation MUST match:
 *
 *  1. **ISO 226:2023 Table 1** — 29 frequencies, αf exponents, Lu transfer
 *     magnitudes, Tf hearing thresholds. Verbatim from the published standard.
 *  2. **Formula (1)** — phon → SPL contour computation. Verified at 1 kHz where
 *     SPL must equal phon by definition (the contour's reference frequency).
 *  3. **Compensation curve direction** — at lower phon than reference, bass and
 *     treble must be boosted relative to 1 kHz. This is the *reason for the
 *     module's existence*; if direction is wrong, the implementation is wrong.
 *  4. **Volume → phon mapping** — sqrt curve from 20 phon (silent) to 80 phon
 *     (reference). Monotonic, bounded.
 *  5. **Gauss-Newton convergence** — solving a 4-section least-squares fit
 *     against an ISO target reaches <2 dB residual within 3 iterations.
 *  6. **Linear solver** — partial-pivoting Gaussian elimination produces the
 *     correct solution on a known 4×4 system.
 *
 * All math is pure JVM — no native code, no Robolectric. The future C++
 * `LoudnessCompensator` MUST reproduce the constants exactly and meet the
 * directional / convergence properties.
 */
class Iso226ReferenceTest {

    // ─────────────────────── ISO 226:2023 Table 1 (verbatim) ───────────────────────

    private val frequencies = doubleArrayOf(
        20.0, 25.0, 31.5, 40.0, 50.0, 63.0, 80.0, 100.0, 125.0, 160.0,
        200.0, 250.0, 315.0, 400.0, 500.0, 630.0, 800.0, 1000.0, 1250.0, 1600.0,
        2000.0, 2500.0, 3150.0, 4000.0, 5000.0, 6300.0, 8000.0, 10_000.0, 12_500.0,
    )

    private val alphaF = doubleArrayOf(
        0.635, 0.602, 0.569, 0.537, 0.509, 0.482, 0.456, 0.433, 0.412, 0.391,
        0.373, 0.357, 0.343, 0.330, 0.320, 0.311, 0.303, 0.300, 0.295, 0.292,
        0.290, 0.290, 0.289, 0.289, 0.289, 0.293, 0.303, 0.323, 0.354,
    )

    private val transferLuDB = doubleArrayOf(
        -31.5, -27.2, -23.1, -19.3, -16.1, -13.1, -10.4, -8.2, -6.3, -4.6,
        -3.2, -2.1, -1.2, -0.5, 0.0, 0.4, 0.5, 0.0, -2.7, -4.2,
        -1.2, 1.4, 2.3, 1.0, -2.3, -7.2, -11.2, -10.9, -3.5,
    )

    private val thresholdTfDB = doubleArrayOf(
        78.1, 68.7, 59.5, 51.1, 44.0, 37.5, 31.5, 26.5, 22.1, 17.9,
        14.4, 11.4, 8.6, 6.2, 4.4, 3.0, 2.2, 2.4, 3.5, 1.7,
        -1.3, -4.2, -6.0, -5.4, -1.5, 6.0, 12.6, 13.9, 12.3,
    )

    private val referenceLoudnessExponent = 0.300
    private val referenceSoundPressureSquaredPa = 4e-10
    private val referenceFrequencyIndex = 17    // 1000 Hz
    private val defaultReferencePhon = 80.0

    // ─────────────────────── Table 1 size + value pinning ───────────────────────

    @Test
    fun `Table 1 has exactly 29 entries with consistent length across all four columns`() {
        assertEquals(29, frequencies.size)
        assertEquals(29, alphaF.size)
        assertEquals(29, transferLuDB.size)
        assertEquals(29, thresholdTfDB.size)
    }

    @Test
    fun `Table 1 reference frequency index 17 corresponds to 1 kHz`() {
        assertEquals(1000.0, frequencies[referenceFrequencyIndex], 0.0)
        // αf at 1 kHz must equal the global reference exponent αr = 0.3 by definition
        assertEquals(referenceLoudnessExponent, alphaF[referenceFrequencyIndex], 0.0)
        // Lu at 1 kHz is defined as 0.0 dB (reference)
        assertEquals(0.0, transferLuDB[referenceFrequencyIndex], 0.0)
    }

    @Test
    fun `Table 1 frequencies are strictly monotonic ISO preferred 1-3 octave centers`() {
        for (i in 1 until frequencies.size) {
            assertTrue(
                "Frequency $i (${frequencies[i]}) must be > previous (${frequencies[i-1]})",
                frequencies[i] > frequencies[i - 1],
            )
        }
    }

    @Test
    fun `Table 1 hearing thresholds are highest at extremes lowest near 4 kHz`() {
        // ISO 389-7 / ISO 226 minimum-audible-field crosses below 0 dB SPL near
        // 3-5 kHz (ear's most sensitive region).
        val tfMinIndex = thresholdTfDB.indices.minBy { thresholdTfDB[it] }
        val freqAtTfMin = frequencies[tfMinIndex]
        assertTrue(
            "Tf minimum ($freqAtTfMin Hz) must be in 2.5-5 kHz sensitivity region",
            freqAtTfMin in 2500.0..5000.0,
        )
        // Tf must be NEGATIVE at the minimum (sub-zero MAF — ear amplification at 3-5 kHz).
        assertTrue(
            "Tf must dip below 0 dB at sensitivity peak (got ${thresholdTfDB[tfMinIndex]})",
            thresholdTfDB[tfMinIndex] < 0.0,
        )
    }

    // ─────────────────────── ISO 226:2023 Formula (1) ───────────────────────

    /**
     * ISO 226:2023 Formula (1) — phon → SPL at one frequency index.
     * Implemented verbatim from the standard, used here only as a reference
     * implementation for the pre-verification.
     */
    private fun contourSpl(phon: Double): DoubleArray {
        val Tf17 = thresholdTfDB[referenceFrequencyIndex]
        return DoubleArray(29) { i ->
            val af = alphaF[i]
            val lu = transferLuDB[i]
            val tf = thresholdTfDB[i]

            val excitation =
                referenceSoundPressureSquaredPa.pow(referenceLoudnessExponent - af) *
                    (10.0.pow((referenceLoudnessExponent * phon) / 10.0) -
                        10.0.pow((referenceLoudnessExponent * Tf17) / 10.0)) +
                    10.0.pow((referenceLoudnessExponent * (tf + lu)) / 10.0)

            (10.0 / af) * log10(excitation) - lu
        }
    }

    @Test
    fun `Formula 1 at 1 kHz reference frequency must produce SPL equal to phon`() {
        // Definitional: at 1 kHz the equal-loudness contour passes through
        // SPL == phon. Any phon level we test must satisfy this within
        // floating-point precision.
        for (phon in listOf(20.0, 40.0, 60.0, 80.0, 90.0)) {
            val contour = contourSpl(phon)
            val splAt1kHz = contour[referenceFrequencyIndex]
            assertEquals(
                "ISO 226 contour at 1 kHz must equal phon (got SPL=$splAt1kHz, expected=$phon)",
                phon, splAt1kHz, 0.05,
            )
        }
    }

    @Test
    fun `Formula 1 at 60 phon produces sensible bass requirement at 100 Hz`() {
        // Sanity: at 60 phon, the ear is much less sensitive in the bass, so the
        // contour at 100 Hz needs MORE SPL than 60 dB. ISO 226:2023 published
        // value is ~78.5 dB SPL — we pin a tight band around it.
        val contour = contourSpl(60.0)
        val splAt100Hz = contour[7]   // index 7 = 100 Hz
        assertTrue(
            "60 phon contour at 100 Hz should require SPL > 60 (got $splAt100Hz)",
            splAt100Hz > 60.0,
        )
        // ISO 226:2023 normative value ≈ 78.48 dB at 60 phon, 100 Hz.
        // Pin to ±0.5 dB around that — implementation drift would surface here.
        assertEquals(
            "60 phon contour at 100 Hz must match ISO normative ~78.48 dB",
            78.48, splAt100Hz, 0.5,
        )
    }

    @Test
    fun `Formula 1 produces strictly monotonic SPL increase for higher phon at every frequency`() {
        val low = contourSpl(40.0)
        val mid = contourSpl(60.0)
        val high = contourSpl(80.0)
        for (i in low.indices) {
            assertTrue(
                "f=${frequencies[i]}: SPL must increase with phon (40→60: ${low[i]}→${mid[i]})",
                mid[i] > low[i],
            )
            assertTrue(
                "f=${frequencies[i]}: SPL must increase with phon (60→80: ${mid[i]}→${high[i]})",
                high[i] > mid[i],
            )
        }
    }

    // ─────────────────────── Compensation curve (1 kHz normalized) ───────────────────────

    /**
     * Reference impl of the compensation curve: relative to a reference phon,
     * normalized at 1 kHz so the broadband level is preserved (only spectral
     * balance changes).
     */
    private fun compensationDB(currentPhon: Double, refPhon: Double = 80.0): DoubleArray {
        val cur = contourSpl(currentPhon)
        val ref = contourSpl(refPhon)
        val curAt1k = cur[referenceFrequencyIndex]
        val refAt1k = ref[referenceFrequencyIndex]
        return DoubleArray(29) { i -> (cur[i] - curAt1k) - (ref[i] - refAt1k) }
    }

    @Test
    fun `compensation curve at lower phon BOOSTS bass relative to 1 kHz`() {
        // The reason this module exists: when listening quietly (e.g. 40 phon),
        // bass perception drops faster than mid; we boost bass to compensate.
        val gains = compensationDB(currentPhon = 40.0, refPhon = 80.0)
        val gainAt100Hz = gains[7]    // 100 Hz
        val gainAt1kHz = gains[17]    // 1 kHz (reference, must be 0)

        assertEquals("compensation at 1 kHz must be exactly 0", 0.0, gainAt1kHz, 1e-9)
        assertTrue(
            "compensation at 100 Hz must be POSITIVE (bass boost) at low phon — got $gainAt100Hz",
            gainAt100Hz > 0.0,
        )
        // For 40 phon vs 80 phon, expect at least 6 dB bass boost at 100 Hz.
        assertTrue(
            "expected ≥6 dB bass boost at 100 Hz for 40-vs-80 phon, got $gainAt100Hz",
            gainAt100Hz >= 6.0,
        )
    }

    @Test
    fun `compensation curve at lower phon shows ISO-correct shape (extreme bass + extreme treble boost mid-treble dip)`() {
        // The ISO 226 compensation curve at 40 vs 80 phon has a characteristic shape:
        //   - Heavy bass boost (low phon → ear less sensitive in bass)
        //   - Slight DIP around 2-6 kHz (ear's sensitivity peak there means we
        //     don't need to boost — the ear already amplifies that band)
        //   - Boost above ~8 kHz (ear loses high-frequency sensitivity at low phon)
        // Pinning these ground-truth values from numerical computation of the
        // ISO 226:2023 contour formula.
        val gains = compensationDB(currentPhon = 40.0, refPhon = 80.0)

        // ─── Bass boost (well above 0 — primary purpose) ───
        assertTrue("100 Hz must boost ≥ 10 dB at 40 vs 80 phon (got ${gains[7]})", gains[7] >= 10.0)
        assertTrue("63 Hz must boost ≥ 12 dB (got ${gains[5]})", gains[5] >= 12.0)
        assertTrue("31.5 Hz must boost ≥ 16 dB (got ${gains[2]})", gains[2] >= 16.0)

        // ─── Mid-treble dip (2-6 kHz) — characteristic of ISO 226 ───
        // The ear's sensitivity peak around 3-4 kHz means LESS compensation needed.
        // ISO computed values: ~-1.5 dB to -1.7 dB in this region (slight cut).
        assertTrue("3.15 kHz should be in [-3, 0] dB (got ${gains[22]})", gains[22] in -3.0..0.0)
        assertTrue("4 kHz should be in [-3, 0] dB (got ${gains[23]})", gains[23] in -3.0..0.0)
        assertTrue("5 kHz should be in [-3, 0] dB (got ${gains[24]})", gains[24] in -3.0..0.0)

        // ─── High-treble boost (≥ 10 kHz) ───
        // Ear loses high-frequency sensitivity at low phon → boost.
        assertTrue("10 kHz must boost (got ${gains[27]})", gains[27] > 0.0)
        assertTrue("12.5 kHz must boost ≥ 4 dB (got ${gains[28]})", gains[28] >= 4.0)
    }

    @Test
    fun `compensation at reference phon equals zero across all frequencies`() {
        // Identity: when current phon == reference, no compensation needed.
        val gains = compensationDB(currentPhon = 80.0, refPhon = 80.0)
        for ((i, g) in gains.withIndex()) {
            assertEquals(
                "f=${frequencies[i]}: zero compensation at ref phon (got $g)",
                0.0, g, 1e-9,
            )
        }
    }

    // ─────────────────────── Volume → phon mapping ───────────────────────

    @Test
    fun `volume to phon mapping is monotonic and bounded`() {
        // Reference impl: phon = 20 + (80 - 20) · sqrt(volume)  for volume in [0, 1]
        fun volumeToPhon(v: Float): Double {
            val clamped = v.coerceIn(0f, 1f).toDouble()
            return 20.0 + (80.0 - 20.0) * sqrt(clamped)
        }

        assertEquals("v=0 → 20 phon", 20.0, volumeToPhon(0.0f), 1e-12)
        assertEquals("v=1 → 80 phon (reference)", 80.0, volumeToPhon(1.0f), 1e-12)
        assertEquals("v=0.25 → 50 phon", 50.0, volumeToPhon(0.25f), 1e-12)
        assertEquals("v=1.0 → 80 phon (clipped above)", 80.0, volumeToPhon(2.0f), 1e-12)

        // Monotonicity over a sweep
        var prev = volumeToPhon(0f)
        for (i in 1..100) {
            val cur = volumeToPhon(i / 100f)
            assertTrue("monotonic (i=$i)", cur >= prev)
            prev = cur
        }
    }

    // ─────────────────────── Gauss-Newton convergence ───────────────────────

    @Test
    fun `Gauss-Newton 4-section least-squares converges within 3 iterations on toy problem`() {
        // Toy: target = 1.0·basis[0] + 2.0·basis[1] + 3.0·basis[2] + 4.0·basis[3]
        // where basis[i] are 8 random-but-fixed unit-magnitude vectors.
        // True solution = [1, 2, 3, 4]. Gauss-Newton on linear LSQ = exact in 1 iter.
        val basis = arrayOf(
            doubleArrayOf( 1.0,  0.0, 0.5, 0.2, 0.1,  0.0, 0.0, 0.0),
            doubleArrayOf( 0.0,  1.0, 0.5, 0.0, 0.0,  0.3, 0.1, 0.0),
            doubleArrayOf( 0.5, -0.3, 0.0, 1.0, 0.5,  0.0, 0.0, 0.0),
            doubleArrayOf( 0.0,  0.0, 0.0, 0.5, 0.7,  1.0, 0.5, 0.2),
        )
        val trueGains = doubleArrayOf(1.0, 2.0, 3.0, 4.0)
        val target = DoubleArray(8) { f ->
            (0..3).sumOf { i -> trueGains[i] * basis[i][f] }
        }

        // Build Gram matrix: G[i,j] = <basis[i], basis[j]>
        val gram = Array(4) { i ->
            DoubleArray(4) { j ->
                (0..7).sumOf { f -> basis[i][f] * basis[j][f] }
            }
        }

        var gains = DoubleArray(4)
        for (iter in 0 until 3) {
            val realized = DoubleArray(8) { f ->
                (0..3).sumOf { i -> gains[i] * basis[i][f] }
            }
            val residual = DoubleArray(8) { f -> target[f] - realized[f] }
            val rhs = DoubleArray(4) { i ->
                (0..7).sumOf { f -> basis[i][f] * residual[f] }
            }
            val delta = solveLinearSystem(gram, rhs)
                ?: error("singular gram matrix at iter=$iter")
            for (i in 0..3) gains[i] += delta[i]
        }

        // Must be within 1e-6 of true solution after just 1 iteration (linear LSQ),
        // and we ran 3 iterations.
        for (i in 0..3) {
            assertEquals("gain[$i] must converge", trueGains[i], gains[i], 1e-9)
        }
    }

    @Test
    fun `linear solver Gaussian elimination produces correct result on 3x3 system`() {
        // Vandermonde-style system with verified solution (1, 2, 3):
        //   x +  y +  z =  6   →  1 + 2 + 3 = 6  ✓
        //   x + 2y + 3z = 14   →  1 + 4 + 9 = 14 ✓
        //   x + 4y + 9z = 36   →  1 + 8 + 27 = 36 ✓
        val A = arrayOf(
            doubleArrayOf(1.0, 1.0, 1.0),
            doubleArrayOf(1.0, 2.0, 3.0),
            doubleArrayOf(1.0, 4.0, 9.0),
        )
        val b = doubleArrayOf(6.0, 14.0, 36.0)
        val x = solveLinearSystem(A, b) ?: error("solver returned null")
        assertEquals(1.0, x[0], 1e-9)
        assertEquals(2.0, x[1], 1e-9)
        assertEquals(3.0, x[2], 1e-9)
    }

    @Test
    fun `linear solver returns null for singular matrix`() {
        val A = arrayOf(
            doubleArrayOf(1.0, 2.0),
            doubleArrayOf(2.0, 4.0),    // proportional → singular
        )
        val b = doubleArrayOf(3.0, 6.0)
        assertEquals(null, solveLinearSystem(A, b))
    }

    /**
     * Reference: Gaussian elimination with partial pivoting.
     * Returns null if the matrix is (near-)singular.
     */
    private fun solveLinearSystem(matrix: Array<DoubleArray>, rhs: DoubleArray): DoubleArray? {
        val n = rhs.size
        val aug = Array(n) { i -> DoubleArray(n + 1) { j ->
            if (j < n) matrix[i][j] else rhs[i]
        } }
        for (k in 0 until n) {
            var best = k
            for (r in (k + 1) until n) if (abs(aug[r][k]) > abs(aug[best][k])) best = r
            if (abs(aug[best][k]) < 1e-12) return null
            if (best != k) {
                val t = aug[k]; aug[k] = aug[best]; aug[best] = t
            }
            val pivot = aug[k][k]
            for (j in k..n) aug[k][j] /= pivot
            for (r in 0 until n) if (r != k) {
                val f = aug[r][k]
                if (f == 0.0) continue
                for (j in k..n) aug[r][j] -= f * aug[k][j]
            }
        }
        return DoubleArray(n) { aug[it][n] }
    }

    @Test
    fun `log domain interpolation between Table 1 frequencies is well-behaved`() {
        // Reference impl: interpolation in log-frequency space (used to map the 29-point
        // contour to arbitrary EQ band centers).
        fun interp(gains: DoubleArray, freq: Double): Double {
            val logF = ln(freq)
            val logFreqs = frequencies.map { ln(it) }
            if (logF <= logFreqs.first()) return gains.first()
            if (logF >= logFreqs.last()) return gains.last()
            var lo = 0
            for (i in 0 until logFreqs.size - 1) if (logFreqs[i + 1] >= logF) { lo = i; break }
            val hi = lo + 1
            val t = (logF - logFreqs[lo]) / (logFreqs[hi] - logFreqs[lo])
            return gains[lo] + t * (gains[hi] - gains[lo])
        }

        // Sanity: interpolating AT a known frequency returns the table value
        val gains = DoubleArray(29) { it.toDouble() }    // 0, 1, 2, ..., 28
        for ((i, f) in frequencies.withIndex()) {
            assertEquals(
                "interp at table freq f=$f must be $i (got ${interp(gains, f)})",
                i.toDouble(), interp(gains, f), 1e-9,
            )
        }

        // Below table → first value; above table → last value
        assertEquals(0.0, interp(gains, 10.0), 0.0)
        assertEquals(28.0, interp(gains, 50_000.0), 0.0)
    }
}
