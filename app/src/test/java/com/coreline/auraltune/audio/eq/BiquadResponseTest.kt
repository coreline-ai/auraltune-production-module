package com.coreline.auraltune.audio.eq

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phase G0 self-test — Kotlin freqz must match engine RBJ behavior. */
class BiquadResponseTest {

    private val fs = 48_000.0

    @Test
    fun flatWhenNoFilters() {
        val r = BiquadResponse.compositeDb(doubleArrayOf(100.0, 1000.0, 10000.0), emptyList(), fs)
        r.forEach { assertEquals(0.0, it, 1e-9) }
    }

    @Test
    fun peakingPeakEqualsGain() {
        // RBJ peaking magnitude at the center frequency equals the gain in dB.
        val r = BiquadResponse.compositeDb(
            doubleArrayOf(1000.0),
            listOf(BiquadSpec(BiquadType.PEAKING, 1000.0, 6.0, 1.0)),
            fs,
        )
        assertEquals(6.0, r[0], 0.15)
    }

    @Test
    fun lowShelfBoostsBassNotTreble() {
        val r = BiquadResponse.compositeDb(
            doubleArrayOf(30.0, 12000.0),
            listOf(BiquadSpec(BiquadType.LOW_SHELF, 105.0, 6.0, 0.7)),
            fs,
        )
        assertEquals(6.0, r[0], 0.6)   // bass region ≈ shelf gain
        assertEquals(0.0, r[1], 0.6)   // treble barely affected
    }

    @Test
    fun highShelfBoostsTrebleNotBass() {
        val r = BiquadResponse.compositeDb(
            doubleArrayOf(50.0, 16000.0),
            listOf(BiquadSpec(BiquadType.HIGH_SHELF, 8000.0, 6.0, 0.7)),
            fs,
        )
        assertEquals(0.0, r[0], 0.6)   // bass barely affected
        assertTrue("treble should be boosted", r[1] > 3.0)
    }

    @Test
    fun compositeAddsBands() {
        val filters = listOf(
            BiquadSpec(BiquadType.PEAKING, 100.0, 4.0, 3.0),
            BiquadSpec(BiquadType.PEAKING, 10000.0, -4.0, 3.0),
        )
        val r = BiquadResponse.compositeDb(doubleArrayOf(100.0, 10000.0), filters, fs)
        assertEquals(4.0, r[0], 0.6)
        assertEquals(-4.0, r[1], 0.6)
    }

    @Test
    fun twentyBandsCoverRange() {
        assertEquals(20, GraphicEqBands.COUNT)
        assertEquals(20.0, GraphicEqBands.frequencies.first(), 0.5)
        assertEquals(20_000.0, GraphicEqBands.frequencies.last(), 1.0)
        assertTrue("Q must be positive and sensible", GraphicEqBands.q in 1.0..5.0)
    }
}
