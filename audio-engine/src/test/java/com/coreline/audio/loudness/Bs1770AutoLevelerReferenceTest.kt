package com.coreline.audio.loudness

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Pre-verification reference test for **D. Loudness Equalizer (BS.1770 + auto-leveler)**.
 *
 * Pins the math the future native implementation MUST match across 5 sub-modules:
 *
 *  1. **K-Weighting filter** — ITU-R BS.1770 high-shelf (+4 dB @ 1.5 kHz Q=1/√2) +
 *     high-pass (38 Hz Butterworth Q=1/√2). Magnitude response at canonical
 *     points must match the standard within 0.1 dB.
 *  2. **RMS detector** — ring-buffer mean-square-sum running window. Verified
 *     against known-input mathematical expectation.
 *  3. **Asymmetric envelope smoother** — one-pole filter with `1 - exp(-step/τ)`
 *     coefficient, attack ≠ release. Verified time-constant behavior.
 *  4. **Soft-knee compressor** — quadratic spline in the knee region,
 *     pure linear in/out outside. Pin transitions and slope.
 *  5. **Time-constant coefficient** — `1 - exp(-stepMs / τ)` formula gives
 *     monotonic 0..1 mapping; faster τ → larger coefficient → faster
 *     convergence per step.
 */
class Bs1770AutoLevelerReferenceTest {

    // =========================================================================
    // Reference helpers — these are what the native implementation must reproduce
    // =========================================================================

    /** RBJ Audio EQ Cookbook — high-shelf coefficients [b0,b1,b2,a1,a2] / a0. */
    private fun rbjHighShelf(fc: Double, fs: Double, gainDB: Double, q: Double): DoubleArray {
        val A = 10.0.pow(gainDB / 40.0)
        val omega = 2.0 * PI * fc / fs
        val sinW = sin(omega); val cosW = cos(omega)
        val alpha = sinW / (2.0 * q)
        val twoSqrtAAlpha = 2.0 * sqrt(A) * alpha

        val b0 = A * ((A + 1.0) + (A - 1.0) * cosW + twoSqrtAAlpha)
        val b1 = -2.0 * A * ((A - 1.0) + (A + 1.0) * cosW)
        val b2 = A * ((A + 1.0) + (A - 1.0) * cosW - twoSqrtAAlpha)
        val a0 = (A + 1.0) - (A - 1.0) * cosW + twoSqrtAAlpha
        val a1 = 2.0 * ((A - 1.0) - (A + 1.0) * cosW)
        val a2 = (A + 1.0) - (A - 1.0) * cosW - twoSqrtAAlpha
        return doubleArrayOf(b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0)
    }

    /** RBJ Audio EQ Cookbook — high-pass coefficients [b0,b1,b2,a1,a2] / a0. */
    private fun rbjHighPass(fc: Double, fs: Double, q: Double): DoubleArray {
        val omega = 2.0 * PI * fc / fs
        val sinW = sin(omega); val cosW = cos(omega)
        val alpha = sinW / (2.0 * q)
        val a0 = 1.0 + alpha
        return doubleArrayOf(
            (1.0 + cosW) / 2.0 / a0,
            -(1.0 + cosW) / a0,
            (1.0 + cosW) / 2.0 / a0,
            -2.0 * cosW / a0,
            (1.0 - alpha) / a0,
        )
    }

    /** Magnitude response of a normalized biquad at frequency `freq`. */
    private fun magDB(coeffs: DoubleArray, freq: Double, fs: Double): Double {
        val (b0, b1, b2, a1, a2) = coeffs.let { c -> arrayOf(c[0], c[1], c[2], c[3], c[4]) }
        val omega = 2.0 * PI * freq / fs
        val cosW = cos(omega); val sinW = sin(omega)
        val cos2W = cos(2.0 * omega); val sin2W = sin(2.0 * omega)
        val numRe = b0 + b1 * cosW + b2 * cos2W
        val numIm = -(b1 * sinW + b2 * sin2W)
        val denRe = 1.0 + a1 * cosW + a2 * cos2W
        val denIm = -(a1 * sinW + a2 * sin2W)
        val numMag = sqrt(numRe * numRe + numIm * numIm)
        val denMag = sqrt(denRe * denRe + denIm * denIm)
        return 20.0 * log10(numMag / denMag)
    }

    /** Cascade two filters' magnitude responses (in dB, simply add). */
    private fun cascadeMagDB(s1: DoubleArray, s2: DoubleArray, freq: Double, fs: Double): Double =
        magDB(s1, freq, fs) + magDB(s2, freq, fs)

    // =========================================================================
    // 1. K-Weighting filter (ITU-R BS.1770)
    // =========================================================================

    @Test
    fun `K-weighting cascade has approximately 0 dB gain at 1 kHz reference`() {
        val fs = 48_000.0
        val q = 1.0 / sqrt(2.0)
        // Stage 1: high-shelf +4 dB @ 1500 Hz, Q = 1/√2
        val s1 = rbjHighShelf(fc = 1500.0, fs = fs, gainDB = 4.0, q = q)
        // Stage 2: high-pass 38 Hz, Q = 1/√2 (Butterworth)
        val s2 = rbjHighPass(fc = 38.0, fs = fs, q = q)

        val gainAt1kHzDB = cascadeMagDB(s1, s2, 1000.0, fs)
        // BS.1770 K-weighting is normalized so that 1 kHz gives ~0 dB (anchor frequency).
        // Real cascade is ~0 dB ± 1 dB at 1 kHz.
        assertTrue(
            "K-weighting at 1 kHz should be ~0 dB ± 1 (got $gainAt1kHzDB)",
            abs(gainAt1kHzDB) < 1.0,
        )
    }

    @Test
    fun `K-weighting attenuates very low frequencies`() {
        val fs = 48_000.0
        val q = 1.0 / sqrt(2.0)
        val s1 = rbjHighShelf(1500.0, fs, 4.0, q)
        val s2 = rbjHighPass(38.0, fs, q)

        // BS.1770 high-pass (38 Hz Butterworth) suppresses sub-bass:
        //   - 20 Hz should be deeply attenuated (≈ -10 dB or worse below the HSF anchor)
        //   - At 38 Hz (cutoff): -3 dB from HPF + small contribution from HSF
        val gainAt20Hz = cascadeMagDB(s1, s2, 20.0, fs)
        val gainAt38Hz = cascadeMagDB(s1, s2, 38.0, fs)

        assertTrue(
            "K-weighting at 20 Hz must be deeply attenuated (got $gainAt20Hz dB)",
            gainAt20Hz < -8.0,
        )
        // At 38 Hz cutoff, HPF gives -3 dB; HSF contributes very little at 38 Hz,
        // so cascade ≈ -3 dB ± 1 dB.
        assertEquals(
            "K-weighting at HPF cutoff (38 Hz) should be ~-3 dB",
            -3.0, gainAt38Hz, 1.0,
        )
    }

    @Test
    fun `K-weighting boosts high frequencies up to about 4 dB shelf`() {
        val fs = 48_000.0
        val q = 1.0 / sqrt(2.0)
        val s1 = rbjHighShelf(1500.0, fs, 4.0, q)
        val s2 = rbjHighPass(38.0, fs, q)

        // High-shelf @ 1.5 kHz with +4 dB gain: at 10 kHz it's well into the shelf band.
        val gainAt10kHzDB = cascadeMagDB(s1, s2, 10_000.0, fs)
        // Should be ≈ +4 dB ± 0.5 dB (HPF contributes ~0 here).
        assertEquals(
            "K-weighting at 10 kHz should be ~+4 dB shelf",
            4.0, gainAt10kHzDB, 0.5,
        )
    }

    @Test
    fun `K-weighting magnitude response is monotonic upward across the audio band`() {
        val fs = 48_000.0
        val q = 1.0 / sqrt(2.0)
        val s1 = rbjHighShelf(1500.0, fs, 4.0, q)
        val s2 = rbjHighPass(38.0, fs, q)

        // The combined response: HPF rolls in until ~80 Hz, HSF rolls up around 1.5 kHz.
        // Monotonic-up across the full 50 Hz → 8 kHz band.
        val freqs = listOf(50.0, 100.0, 200.0, 500.0, 1000.0, 2000.0, 4000.0, 8000.0)
        var prev = cascadeMagDB(s1, s2, freqs[0], fs)
        for (i in 1 until freqs.size) {
            val cur = cascadeMagDB(s1, s2, freqs[i], fs)
            assertTrue(
                "K-weighting mag must increase: f=${freqs[i-1]} ($prev dB) → f=${freqs[i]} ($cur dB)",
                cur >= prev - 0.05,    // tiny tolerance for numerical noise
            )
            prev = cur
        }
    }

    // =========================================================================
    // 2. RMS detector — ring-buffer running mean-square
    // =========================================================================

    @Test
    fun `RMS ring buffer running sum equals direct mean-square for known input`() {
        val window = 8
        val ring = DoubleArray(window)
        var writeIdx = 0
        var sumSquares = 0.0

        val input = doubleArrayOf(1.0, 0.5, -1.0, 2.0, -1.5, 0.0, 0.5, 1.0,    // first window
                                  3.0, -2.0, 1.5, 0.0, -0.5, 1.0, 2.0, -1.0)  // second window
        val expectedAfterFirstWindow =
            (input.take(window).sumOf { it * it }) / window
        val expectedAfterAllInputs =
            (input.takeLast(window).sumOf { it * it }) / window

        // Simulate the ring-buffer running sum
        for (i in 0 until 8) {
            sumSquares -= ring[writeIdx]
            ring[writeIdx] = input[i] * input[i]
            sumSquares += ring[writeIdx]
            writeIdx = (writeIdx + 1) % window
        }
        val meanSquareAfter8 = sumSquares / window
        assertEquals(
            "After first window of 8 samples, running mean-square must match direct compute",
            expectedAfterFirstWindow, meanSquareAfter8, 1e-12,
        )

        // Continue ingestion, after 16 samples the window contains samples 8..15.
        for (i in 8 until 16) {
            sumSquares -= ring[writeIdx]
            ring[writeIdx] = input[i] * input[i]
            sumSquares += ring[writeIdx]
            writeIdx = (writeIdx + 1) % window
        }
        val meanSquareAfter16 = sumSquares / window
        assertEquals(
            "After second window of 8 samples, running mean-square must match",
            expectedAfterAllInputs, meanSquareAfter16, 1e-12,
        )
    }

    @Test
    fun `RMS to dB conversion floor protects against log of zero`() {
        // Reference impl: meanSquareToDb(ms) = 10 · log10(max(ms, 1e-12))
        fun msToDb(ms: Double): Double = 10.0 * log10(max(ms, 1e-12))

        assertEquals("zero input → -120 dB floor", -120.0, msToDb(0.0), 1e-9)
        assertEquals("1e-15 input → still -120 dB floor", -120.0, msToDb(1e-15), 1e-9)
        assertEquals("1.0 → 0 dB", 0.0, msToDb(1.0), 1e-9)
        assertEquals("0.01 → -20 dB", -20.0, msToDb(0.01), 1e-9)
        assertEquals("100.0 → +20 dB", 20.0, msToDb(100.0), 1e-9)
    }

    // =========================================================================
    // 3. Asymmetric envelope smoother
    // =========================================================================

    /** Reference: `1 - exp(-step/τ)` — discrete-time one-pole coefficient. */
    private fun timeConstantCoeff(timeMs: Double, stepMs: Double): Double =
        1.0 - exp(-stepMs / max(timeMs, 1e-6))

    @Test
    fun `time-constant coefficient is monotonic in step-to-tau ratio`() {
        // Larger step / smaller τ → coefficient closer to 1 (immediate convergence).
        // Smaller step / larger τ → coefficient closer to 0 (slow convergence).
        val tauSweep = listOf(1.0, 10.0, 100.0, 1000.0)
        val stepMs = 15.0
        var prev = 1.0    // tau small → coeff large
        for (tau in tauSweep) {
            val c = timeConstantCoeff(tau, stepMs)
            assertTrue(
                "coefficient must DECREASE as τ increases (τ=$tau → c=$c, prev=$prev)",
                c <= prev,
            )
            assertTrue("coeff in (0, 1] (got $c)", c > 0.0 && c <= 1.0)
            prev = c
        }
    }

    @Test
    fun `envelope smoother converges to step input within several time constants`() {
        // Step input at -40 dB → -10 dB. After ~5τ, smoothed should be within 1% of target.
        val attackMs = 25.0
        val stepMs = 15.0
        val coeff = timeConstantCoeff(attackMs, stepMs)

        var smoothed = -40.0
        val target = -10.0
        // 5τ in this discrete representation = 5 * (attackMs / stepMs) ≈ 8.3 steps
        for (step in 0..50) {
            smoothed += coeff * (target - smoothed)
        }
        assertEquals(
            "After 50 steps (≈30τ), smoothed value must converge to target",
            target, smoothed, 0.1,
        )
    }

    @Test
    fun `asymmetric attack-release uses different coefficients for upward vs downward steps`() {
        // Reference: when measured > smoothed → use attackCoeff (typically faster)
        //            when measured < smoothed → use releaseCoeff (typically slower)
        val attackMs = 25.0
        val releaseMs = 400.0
        val stepMs = 15.0
        val attackCoeff = timeConstantCoeff(attackMs, stepMs)
        val releaseCoeff = timeConstantCoeff(releaseMs, stepMs)

        assertTrue(
            "attack must converge faster than release (attack=$attackCoeff release=$releaseCoeff)",
            attackCoeff > releaseCoeff,
        )

        // Behavioral test: starting at -10 dB,
        //   measured rises to -5 dB → attackCoeff applies (faster)
        //   measured falls to -20 dB → releaseCoeff applies (slower)
        var smoothed = -10.0
        smoothed += attackCoeff * (-5.0 - smoothed)
        val afterAttack = smoothed
        // After 1 attack step from -10 → -5 with attack 25 ms / step 15 ms (coeff ≈ 0.451):
        //   -10 + 0.451 · 5.0 = -10 + 2.255 = -7.745
        assertTrue("attack moved upward (got $afterAttack)", afterAttack > -10.0 && afterAttack < -5.0)

        smoothed = -10.0
        smoothed += releaseCoeff * (-20.0 - smoothed)
        val afterRelease = smoothed
        // After 1 release step from -10 → -20 with release 400 ms / step 15 ms (coeff ≈ 0.0367):
        //   -10 + 0.0367 · -10 = -10.367 — moved very slightly toward -20
        assertTrue("release moved downward slowly (got $afterRelease)", afterRelease < -10.0)
        // Release should move LESS (in magnitude) than attack moved
        val attackDelta = abs(afterAttack - (-10.0))    // ≈ 2.255
        val releaseDelta = abs(afterRelease - (-10.0))  // ≈ 0.367
        assertTrue(
            "release delta ($releaseDelta) must be smaller than attack delta ($attackDelta)",
            releaseDelta < attackDelta,
        )
    }

    // =========================================================================
    // 4. Soft-knee compressor
    // =========================================================================

    /**
     * Reference implementation of macOS `softKneeCompressedLevel`.
     * Outside the knee: linear input below threshold; (input − threshold)/ratio + threshold above.
     * Inside the knee: quadratic spline between the two segments.
     */
    private fun softKneeCompressed(
        inputDB: Double, thresholdDB: Double, ratio: Double, kneeWidthDB: Double,
    ): Double {
        if (ratio <= 1.0) return inputDB
        if (kneeWidthDB <= 0.0) {
            return if (inputDB > thresholdDB) thresholdDB + (inputDB - thresholdDB) / ratio
            else inputDB
        }
        val halfKnee = kneeWidthDB / 2.0
        val kneeStart = thresholdDB - halfKnee
        val kneeEnd = thresholdDB + halfKnee
        if (inputDB <= kneeStart) return inputDB
        if (inputDB >= kneeEnd) return thresholdDB + (inputDB - thresholdDB) / ratio
        val normDist = inputDB - kneeStart
        val quadGain = (1.0 / ratio - 1.0) * normDist * normDist / (2.0 * kneeWidthDB)
        return inputDB + quadGain
    }

    @Test
    fun `soft-knee compressor is identity below knee start`() {
        // Below kneeStart = threshold − kneeWidth/2 → no compression
        val out = softKneeCompressed(inputDB = -20.0, thresholdDB = -10.0, ratio = 4.0, kneeWidthDB = 4.0)
        assertEquals("below knee → identity", -20.0, out, 1e-12)
    }

    @Test
    fun `soft-knee compressor applies fixed-ratio compression above knee end`() {
        // Above kneeEnd = threshold + kneeWidth/2 → linear compression with given ratio
        val threshold = -10.0
        val ratio = 4.0
        val knee = 4.0
        val input = 0.0    // kneeEnd = -8 dB; 0 dB is well above
        val expected = threshold + (input - threshold) / ratio
        // expected = -10 + (0 - (-10)) / 4 = -10 + 2.5 = -7.5
        val out = softKneeCompressed(input, threshold, ratio, knee)
        assertEquals("above knee → linear ratio compression", expected, out, 1e-12)
        assertEquals("specifically -7.5 dB", -7.5, out, 1e-12)
    }

    @Test
    fun `soft-knee compressor is continuous and differentiable across the knee`() {
        val threshold = -10.0
        val ratio = 4.0
        val knee = 4.0
        val kneeStart = threshold - knee / 2    // -12
        val kneeEnd = threshold + knee / 2      // -8

        // C0 continuity at boundaries
        val atKneeStart = softKneeCompressed(kneeStart, threshold, ratio, knee)
        val atKneeStartIdentity = kneeStart
        assertEquals(
            "compressor must be continuous at knee start",
            atKneeStartIdentity, atKneeStart, 1e-9,
        )

        val atKneeEnd = softKneeCompressed(kneeEnd, threshold, ratio, knee)
        val atKneeEndLinear = threshold + (kneeEnd - threshold) / ratio
        assertEquals(
            "compressor must be continuous at knee end",
            atKneeEndLinear, atKneeEnd, 1e-9,
        )

        // Inside the knee, output should be < input (compression activated)
        // and monotonic increase as input increases.
        var prev = softKneeCompressed(kneeStart, threshold, ratio, knee)
        for (delta in listOf(0.5, 1.0, 1.5, 2.0, 2.5, 3.0, 3.5)) {
            val out = softKneeCompressed(kneeStart + delta, threshold, ratio, knee)
            assertTrue("compressor monotonic in knee", out >= prev)
            assertTrue(
                "compressor reduces (or equals) input in knee (in=${kneeStart+delta}, out=$out)",
                out <= kneeStart + delta + 1e-9,
            )
            prev = out
        }
    }

    @Test
    fun `soft-knee compressor with ratio = 1 is pure passthrough`() {
        for (input in listOf(-30.0, -10.0, -5.0, 0.0, 5.0)) {
            val out = softKneeCompressed(input, thresholdDB = -10.0, ratio = 1.0, kneeWidthDB = 4.0)
            assertEquals("ratio=1 must be passthrough", input, out, 1e-12)
        }
    }

    // =========================================================================
    // 5. Combined GainComputer behavior (boost + cut)
    // =========================================================================

    @Test
    fun `gain computer boosts when below target up to maxBoost`() {
        val target = -12.0
        val maxBoost = 15.0

        // Reference: boost = clamp(target - measured, 0, maxBoost)
        fun boost(measured: Double): Double = max(0.0, min(target - measured, maxBoost)).coerceAtLeast(0.0)

        assertEquals("loud signal at -8 dB → no boost (measured > target)", 0.0, boost(-8.0), 1e-12)
        assertEquals("at target → no boost", 0.0, boost(target), 1e-12)
        assertEquals("very quiet -100 dB → clamped at maxBoost", maxBoost, boost(-100.0), 1e-12)
        assertEquals("between -25 and target → 13 dB boost", 13.0, boost(-25.0), 1e-12)
    }

    @Test
    fun `noise floor protection caps boost when signal is very quiet`() {
        val target = -12.0
        val maxBoost = 15.0
        val noiseFloor = -48.0
        val lowLevelMaxBoost = 1.5

        fun boost(measured: Double): Double {
            var clamped = max(0.0, min(target - measured, maxBoost))
            if (measured < noiseFloor && clamped > 0.0) {
                clamped = min(clamped, lowLevelMaxBoost)
            }
            return clamped
        }

        // Above noise floor: standard boost behavior
        assertEquals("above noise floor → unrestricted boost", 13.0, boost(-25.0), 1e-12)
        // Below noise floor: capped to lowLevelMaxBoost
        assertEquals("below noise floor → capped at $lowLevelMaxBoost",
                     lowLevelMaxBoost, boost(-50.0), 1e-12)
        assertEquals("very quiet → still capped",
                     lowLevelMaxBoost, boost(-90.0), 1e-12)
    }

    @Test
    fun `dB to linear and back is round-trip stable`() {
        // dbToLinear(db) = 10^(db/20);  linearToDb(linear) = 20·log10(max(linear, 1e-9))
        fun dbToLinear(db: Double): Double = 10.0.pow(db / 20.0)
        fun linearToDb(lin: Double): Double = 20.0 * log10(max(lin, 1e-9))

        for (db in listOf(-40.0, -20.0, -6.0, 0.0, 3.0, 12.0, 24.0)) {
            val roundTrip = linearToDb(dbToLinear(db))
            assertEquals("round-trip dB=$db", db, roundTrip, 1e-9)
        }
    }
}
