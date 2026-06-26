package com.coreline.auraltune.audio.eq

import com.coreline.audio.EqFilterType
import com.coreline.auraltune.data.ParametricBand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** Stage 2 self-test — parametric model + graphic global-Q + type-id ABI alignment. */
class ParametricEqTest {

    private val fs = 48_000.0

    // The single highest-value guard: the Kotlin BiquadType <-> native id mapping MUST match
    // com.coreline.audio.EqFilterType.nativeId (and the C++ enum). A drift here silently applies
    // the WRONG filter shape (e.g. a high-pass where the user asked for a peak).
    @Test
    fun typeIdsMatchNativeEqFilterType() {
        assertEquals(EqFilterType.PEAKING.nativeId, BiquadType.PEAKING.toNativeId())
        assertEquals(EqFilterType.LOW_SHELF.nativeId, BiquadType.LOW_SHELF.toNativeId())
        assertEquals(EqFilterType.HIGH_SHELF.nativeId, BiquadType.HIGH_SHELF.toNativeId())
        assertEquals(EqFilterType.HIGH_PASS.nativeId, BiquadType.HIGH_PASS.toNativeId())
    }

    @Test
    fun typeIdRoundTrips() {
        for (t in BiquadType.entries) {
            assertEquals(t, biquadTypeFromNativeId(t.toNativeId()))
        }
        // Out-of-range / legacy ids fall back to PEAKING (never crash).
        assertEquals(BiquadType.PEAKING, biquadTypeFromNativeId(99))
        assertEquals(BiquadType.PEAKING, biquadTypeFromNativeId(-1))
    }

    @Test
    fun eqModeFromKeyIsTolerant() {
        assertEquals(EqMode.GRAPHIC, EqMode.fromKey("GRAPHIC"))
        assertEquals(EqMode.PARAMETRIC, EqMode.fromKey("PARAMETRIC"))
        assertEquals(EqMode.GRAPHIC, EqMode.fromKey(null))
        assertEquals(EqMode.GRAPHIC, EqMode.fromKey("bogus"))
    }

    // Wider scale → lower Q → broader bell → less peak attenuation of the neighbour gap;
    // narrower scale → higher Q. We assert the effective Q ordering through the rendered curve:
    // a narrow bell has a higher peak-to-shoulder contrast than a wide one for the same gain.
    @Test
    fun graphicQScaleChangesBandwidth() {
        val gains = FloatArray(GraphicEqBands.COUNT)
        val center = GraphicEqBands.COUNT / 2
        gains[center] = 6f
        val f = GraphicEqBands.frequencies[center]
        // a shoulder ~2/3 octave away from the center
        val shoulder = f * 1.5

        fun shoulderDbAt(qScale: Float): Double {
            val specs = GraphicEqBands.toSpecs(gains, qScale)
            return BiquadResponse.compositeDb(doubleArrayOf(shoulder), specs, fs)[0]
        }

        val wide = shoulderDbAt(0.5f)   // low Q → broad → shoulder still lifted
        val normal = shoulderDbAt(1.0f)
        val narrow = shoulderDbAt(2.0f) // high Q → narrow → shoulder near 0
        assertTrue("wider Q-scale must lift the shoulder more than narrow", wide > normal)
        assertTrue("narrower Q-scale must lift the shoulder less than normal", normal > narrow)
    }

    @Test
    fun snapQScaleSnapsToOptions() {
        assertEquals(0.5f, GraphicEqBands.snapQScale(0.4f))
        assertEquals(1.0f, GraphicEqBands.snapQScale(0.9f))
        assertEquals(2.0f, GraphicEqBands.snapQScale(5.0f))
    }

    @Test
    fun parametricBandNormalizesOutOfRangeFields() {
        val b = ParametricBand(
            id = "x", type = 7, freqHz = 99_999f, gainDb = 999f, q = 999f,
        ).normalized()
        assertEquals(3, b.type)                                  // clamped to HIGH_PASS id
        assertEquals(ParametricBand.MAX_FREQ_HZ, b.freqHz)
        assertEquals(ParametricBand.MAX_GAIN_DB, b.gainDb)
        assertEquals(ParametricBand.MAX_Q, b.q)

        val nan = ParametricBand("y", 0, Float.NaN, Float.NaN, Float.NaN).normalized()
        assertEquals(ParametricBand.DEFAULT_FREQ_HZ, nan.freqHz)
        assertEquals(0f, nan.gainDb)
        assertEquals(ParametricBand.DEFAULT_Q, nan.q)
    }

    @Test
    fun parametricBandToBiquadSpecMapsType() {
        val hp = ParametricBand("z", type = 3, freqHz = 80f, gainDb = 0f, q = 0.7f).toBiquadSpec()
        assertSame(BiquadType.HIGH_PASS, hp.type)
        assertEquals(80.0, hp.freqHz, 1e-9)

        val def = ParametricBand.default("d")
        assertEquals(0, def.type)
        assertEquals(ParametricBand.DEFAULT_FREQ_HZ, def.freqHz)
        assertEquals(0f, def.gainDb)
    }

    @Test
    fun highPassActuallyAttenuatesBelowCutoff() {
        val specs = listOf(ParametricBand("hp", 3, 200f, 0f, 0.707f).toBiquadSpec())
        val r = BiquadResponse.compositeDb(doubleArrayOf(40.0, 2000.0), specs, fs)
        assertTrue("sub-cutoff must be cut", r[0] < -6.0)
        assertEquals("passband ~flat", 0.0, r[1], 0.6)
    }

    @Test
    fun nextFreqSpreadsBandsApart() {
        // Empty → default 1 kHz.
        assertEquals(ParametricBand.DEFAULT_FREQ_HZ, ParametricBand.nextFreqHz(emptyList()))

        // First band at 1 kHz → next lands in the widest gap, never on top of it.
        val b1 = ParametricBand.default("a") // 1000 Hz
        val f2 = ParametricBand.nextFreqHz(listOf(b1))
        assertTrue("next freq must differ from existing band", abs(f2 - b1.freqHz) > 1f)
        assertTrue("next freq within spectrum", f2 in ParametricBand.MIN_FREQ_HZ..ParametricBand.MAX_FREQ_HZ)

        // Adding repeatedly must keep producing DISTINCT, non-colliding frequencies.
        val bands = ArrayList<ParametricBand>()
        repeat(ParametricBand.MAX_BANDS) { i ->
            val f = ParametricBand.nextFreqHz(bands)
            bands.forEach { existing ->
                assertTrue("band $i collided with an existing band at $f", abs(existing.freqHz - f) > 0.5f)
            }
            bands.add(ParametricBand.default("b$i").copy(freqHz = f))
        }
        assertEquals(ParametricBand.MAX_BANDS, bands.map { it.freqHz }.toSet().size)
    }

    @Test
    fun maxBandsIsSane() {
        // Engine Manual chain caps at 20; UX cap must stay within that.
        assertTrue(ParametricBand.MAX_BANDS in 1..20)
        assertEquals(EqMode.GRAPHIC, EqMode.fromKey("")) // empty string is not a valid name
    }
}
