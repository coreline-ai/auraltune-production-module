package com.coreline.audio.loudness

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Pre-verification reference test for **A. High-pass filter (RBJ)**.
 *
 * This test pins the *math* the future native [BiquadFilter::highPass] implementation
 * MUST match. It is NOT a test of any production code — it is a pure-Kotlin
 * computation of the RBJ Audio EQ Cookbook formula, verified against:
 *
 *  1. **Coefficient golden values** for canonical (fc=1000 Hz, fs=48000 Hz, Q=1/√2)
 *     hand-computed from the cookbook.
 *  2. **−3 dB at fc** property — Butterworth defining characteristic.
 *  3. **−12 dB/octave low-frequency rolloff** — 2nd-order HPF asymptotic slope.
 *  4. **Stopband DC blocking** — magnitude approaches 0 as ω → 0.
 *
 * Future implementation (`BiquadFilter::highPass()` in C++) MUST reproduce these
 * coefficients to floating-point precision.
 */
class RbjHighPassReferenceTest {

    /**
     * RBJ Audio EQ Cookbook — high-pass biquad coefficients.
     * Returns [b0, b1, b2, a1, a2] normalized by a0 (vDSP_biquad / native engine layout).
     *
     * Reference: Robert Bristow-Johnson, "Cookbook formulae for audio EQ biquad
     * filter coefficients", section "HPF".
     *
     *   b0 =  (1 + cos w0) / 2
     *   b1 = -(1 + cos w0)
     *   b2 =  (1 + cos w0) / 2
     *   a0 =   1 + alpha
     *   a1 =  -2·cos w0
     *   a2 =   1 - alpha
     *
     *   alpha = sin w0 / (2·Q)
     *   w0    = 2π·fc/fs
     */
    private fun rbjHighPass(fc: Double, fs: Double, q: Double): DoubleArray {
        val omega = 2.0 * PI * fc / fs
        val sinW = sin(omega)
        val cosW = cos(omega)
        val alpha = sinW / (2.0 * q)

        val b0 = (1.0 + cosW) / 2.0
        val b1 = -(1.0 + cosW)
        val b2 = (1.0 + cosW) / 2.0
        val a0 = 1.0 + alpha
        val a1 = -2.0 * cosW
        val a2 = 1.0 - alpha

        return doubleArrayOf(b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0)
    }

    /**
     * Magnitude response |H(e^jω)| for a biquad in normalized form
     *   y[n] = b0·x[n] + b1·x[n-1] + b2·x[n-2] - a1·y[n-1] - a2·y[n-2]
     */
    private fun magnitudeAtFreq(coeffs: DoubleArray, freq: Double, fs: Double): Double {
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
        return numMag / denMag
    }

    private fun magnitudeDB(coeffs: DoubleArray, freq: Double, fs: Double): Double =
        20.0 * log10(magnitudeAtFreq(coeffs, freq, fs))

    // ─────────────────────────── Golden coefficient pin ───────────────────────────

    @Test
    fun `print computed RBJ HPF coefficients for golden capture`() {
        // Diagnostic — captures the actual values produced by our RBJ formula on this JVM.
        // Use these to update the golden test below if the formula is corrected.
        val coeffs = rbjHighPass(fc = 1000.0, fs = 48_000.0, q = 1.0 / sqrt(2.0))
        println("RBJ HPF coeffs at fc=1000 fs=48000 Q=1/√2:")
        for ((i, v) in coeffs.withIndex()) println("  c[$i] = $v")
    }

    @Test
    fun `RBJ HPF coefficients at fc=1000Hz fs=48000Hz Q=0707 are stable golden values`() {
        val coeffs = rbjHighPass(fc = 1000.0, fs = 48_000.0, q = 1.0 / sqrt(2.0))

        // Golden values produced by JVM `kotlin.math` `sin`/`cos` on the formula:
        //   omega = 2π · 1000/48000
        //   alpha = sin(omega) / (2 · 1/√2)
        //   b0 = b2 = (1+cosω)/2 / (1+α);  b1 = -(1+cosω) / (1+α)
        //   a1 = -2·cosω / (1+α);          a2 = (1-α) / (1+α)
        //
        // These pin the future C++ implementation to the same RBJ formula. Any drift
        // in the C++ trig path or the formula itself fails this test. To regenerate
        // the values: run this test, copy the actual values from the failure message.
        val tolerance = 1e-12
        assertEquals( 0.9115866680128315, coeffs[0], tolerance)
        assertEquals(-1.823173336025663,  coeffs[1], tolerance)
        assertEquals( 0.9115866680128315, coeffs[2], tolerance)
        assertEquals(-1.815341082704568,  coeffs[3], tolerance)
        assertEquals( 0.8310055893467576, coeffs[4], tolerance)

        // Symmetry: b0 == b2 by construction
        assertEquals("RBJ HPF: b0 must equal b2", coeffs[0], coeffs[2], 0.0)
        // Symmetry: b1 == -2·b0 by construction
        assertEquals("RBJ HPF: b1 must equal -2·b0", -2.0 * coeffs[0], coeffs[1], 1e-12)
    }

    // ─────────────────────────── Butterworth properties ───────────────────────────

    @Test
    fun `magnitude at cutoff is -3 dB for Butterworth Q=1 over root2`() {
        // 2nd-order Butterworth HPF (Q=1/√2) MUST cross −3.01 dB at fc by definition.
        val fs = 48_000.0
        val q = 1.0 / sqrt(2.0)
        val testFcs = listOf(50.0, 100.0, 500.0, 1000.0, 5000.0)
        for (fc in testFcs) {
            val coeffs = rbjHighPass(fc, fs, q)
            val magDB = magnitudeDB(coeffs, fc, fs)
            assertEquals(
                "Butterworth HPF at fc=${fc}Hz must be -3.01 dB ± 0.05",
                -3.0103, magDB, 0.05,
            )
        }
    }

    @Test
    fun `magnitude well below cutoff approaches -12 dB per octave rolloff`() {
        // 2nd-order HPF asymptotic slope = +40 dB/decade in stopband.
        // Equivalently: doubling frequency (one octave below fc) recovers ~12 dB.
        val fs = 48_000.0
        val fc = 1000.0
        val q = 1.0 / sqrt(2.0)
        val coeffs = rbjHighPass(fc, fs, q)

        val magAtFcOver8 = magnitudeDB(coeffs, fc / 8.0, fs)   // 3 octaves below
        val magAtFcOver4 = magnitudeDB(coeffs, fc / 4.0, fs)   // 2 octaves below
        val magAtFcOver2 = magnitudeDB(coeffs, fc / 2.0, fs)   // 1 octave below

        // Each octave should add ~+12 dB as we move toward fc (less attenuation).
        // Bilinear distortion near fc means we accept ±1.5 dB tolerance per octave step.
        val deltaPerOctave1 = magAtFcOver4 - magAtFcOver8
        val deltaPerOctave2 = magAtFcOver2 - magAtFcOver4
        assertEquals("rolloff per octave (3→2 below fc)", 12.0, deltaPerOctave1, 1.5)
        assertEquals("rolloff per octave (2→1 below fc)", 12.0, deltaPerOctave2, 1.5)
    }

    @Test
    fun `magnitude at DC is essentially zero (DC blocking)`() {
        val fs = 48_000.0
        val coeffs = rbjHighPass(fc = 100.0, fs = fs, q = 1.0 / sqrt(2.0))
        // At ω=0, numerator |1+cosW|·... → 2·... and denominator's a-coefficients give
        // a finite value. Math: (1 + a1 + a2) is the DC denominator term; for HPF the
        // numerator at DC is (b0 + b1 + b2) which equals 0 by RBJ HPF design.
        val numAtDc = coeffs[0] + coeffs[1] + coeffs[2]
        assertEquals("HPF must have zero gain at DC by symmetry", 0.0, numAtDc, 1e-12)

        val magDcDB = magnitudeDB(coeffs, 0.001, fs)
        assertTrue(
            "DC magnitude ($magDcDB dB) must be deeply attenuated (< -80 dB)",
            magDcDB < -80.0,
        )
    }

    @Test
    fun `magnitude well above cutoff approaches unity gain`() {
        val fs = 48_000.0
        val fc = 100.0
        val q = 1.0 / sqrt(2.0)
        val coeffs = rbjHighPass(fc, fs, q)

        // Test at fc * 32 (5 octaves above) — should be very close to 0 dB
        // but stays slightly under because of the bilinear pre-warp near Nyquist.
        val magWellAboveDB = magnitudeDB(coeffs, fc * 32.0, fs)
        assertTrue(
            "Far passband mag ($magWellAboveDB dB) must approach 0 dB (allow ±0.5)",
            abs(magWellAboveDB) < 0.5,
        )
    }

    // ─────────────────────────── Range / sanity ───────────────────────────

    @Test
    fun `coefficients remain finite and stable for typical parameter sweep`() {
        val fs = 48_000.0
        val qSweep = listOf(0.5, 1.0 / sqrt(2.0), 1.0, 2.0, 5.0, 10.0)
        val fcSweep = listOf(20.0, 80.0, 200.0, 1_000.0, 5_000.0, 15_000.0)

        for (q in qSweep) for (fc in fcSweep) {
            val c = rbjHighPass(fc, fs, q)
            for ((i, v) in c.withIndex()) {
                assertTrue("coeff[$i] at fc=$fc Q=$q is non-finite: $v", v.isFinite())
            }
            // Stability: poles inside unit circle ⇒ |a2| < 1 AND |a1| < 1 + a2
            val a1 = c[3]; val a2 = c[4]
            assertTrue("|a2|=$a2 must be < 1 for stability (fc=$fc Q=$q)", abs(a2) < 1.0)
            assertTrue(
                "|a1|=$a1 must be < 1+a2=${1+a2} for stability (fc=$fc Q=$q)",
                abs(a1) < 1.0 + a2 + 1e-12,
            )
        }
    }
}
