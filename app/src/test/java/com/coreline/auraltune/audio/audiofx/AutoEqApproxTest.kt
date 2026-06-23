package com.coreline.auraltune.audio.audiofx

import com.coreline.auraltune.audio.eq.BiquadSpec
import com.coreline.auraltune.audio.eq.BiquadType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phase 6 self-test — OS-band approximation fit + error quantification. Pure math. */
class AutoEqApproxTest {

    private val bands = doubleArrayOf(31.5, 63.0, 125.0, 250.0, 500.0, 1000.0, 2000.0, 4000.0, 8000.0, 16000.0)

    @Test
    fun `flat target yields zero gains and zero error`() {
        val r = AutoEqApprox.fit(emptyList(), bands)
        r.bands.forEach { assertEquals(0.0, it.gainDb, 1e-9) }
        assertEquals(0.0, r.rmsErrorDb, 1e-9)
        assertEquals(0.0, r.maxErrorDb, 1e-9)
    }

    @Test
    fun `band gain samples the target at its center frequency`() {
        // A +6 dB peak at 1 kHz → the 1 kHz band should pick up ≈ +6 dB.
        val target = listOf(BiquadSpec(BiquadType.PEAKING, 1000.0, 6.0, 1.0))
        val r = AutoEqApprox.fit(target, bands)
        val b1k = r.bands.first { it.centerHz == 1000.0 }
        assertEquals(6.0, b1k.gainDb, 0.2)
        // Bands far from 1 kHz are barely touched.
        assertEquals(0.0, r.bands.first { it.centerHz == 63.0 }.gainDb, 0.6)
    }

    @Test
    fun `error metrics are finite and max greater-or-equal rms`() {
        val target = listOf(
            BiquadSpec(BiquadType.PEAKING, 120.0, 5.0, 2.0),
            BiquadSpec(BiquadType.PEAKING, 3000.0, -4.0, 2.0),
        )
        val r = AutoEqApprox.fit(target, bands)
        assertTrue(r.rmsErrorDb.isFinite() && r.rmsErrorDb >= 0.0)
        assertTrue(r.maxErrorDb >= r.rmsErrorDb)
    }

    @Test
    fun `gains are clamped to the OS range`() {
        // A huge +30 dB peak must clamp to the backend max (default 15 dB).
        val target = listOf(BiquadSpec(BiquadType.PEAKING, 1000.0, 30.0, 1.0))
        val r = AutoEqApprox.fit(target, bands, maxGainDb = 15.0)
        assertTrue(r.bands.all { it.gainDb <= 15.0 + 1e-9 && it.gainDb >= -15.0 - 1e-9 })
    }

    @Test
    fun `empty band centers is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            AutoEqApprox.fit(emptyList(), doubleArrayOf())
        }
    }

    // --- QA adversarial additions (Phase 6 review) -------------------------------------------

    /**
     * ADVERSARIAL (C1): gridPoints=1 makes logGrid divide by (n-1)=0 → grid[0]=NaN → the error
     * metrics become NaN for any non-flat target. The reported "approximation error" is then a
     * lie (NaN, not a number). fit() has no require(gridPoints >= 2) guard. This test asserts the
     * CONTRACT we want (finite metrics) so it goes RED, documenting the defect.
     */
    @Test
    fun `gridPoints=1 must still report finite error`() {
        val target = listOf(BiquadSpec(BiquadType.PEAKING, 1000.0, 6.0, 1.0))
        val r = AutoEqApprox.fit(target, bands, gridPoints = 1)
        assertTrue(
            "rmsErrorDb should be finite but was ${r.rmsErrorDb}",
            r.rmsErrorDb.isFinite(),
        )
        assertTrue(
            "maxErrorDb should be finite but was ${r.maxErrorDb}",
            r.maxErrorDb.isFinite(),
        )
    }

    /**
     * ADVERSARIAL (C2): a target far beyond the OS clamp (±15 dB) must be REPORTED as a large
     * error, because the realized (clamped) curve cannot reach it. This is the honesty contract:
     * clamping must not silently hide the loss. A +40 dB peak clamped to +15 dB should leave a
     * max error of at least ~20 dB near the peak. Expected GREEN guard.
     */
    @Test
    fun `target beyond OS clamp reports large max error`() {
        val target = listOf(BiquadSpec(BiquadType.PEAKING, 1000.0, 40.0, 4.0))
        val r = AutoEqApprox.fit(target, bands, maxGainDb = 15.0)
        assertTrue(r.bands.all { it.gainDb <= 15.0 + 1e-9 })
        assertTrue(
            "clamped fit must surface the loss; maxErrorDb=${r.maxErrorDb}",
            r.maxErrorDb >= 20.0,
        )
    }
}
