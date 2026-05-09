package com.coreline.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Contract tests for the C1 / C2 hardening round.
 *
 * - C1: [AudioEngine.updateSampleRate] must reject out-of-range values BEFORE
 *       calling JNI, and only flip the wrapper's [AudioEngine.sampleRate]
 *       property when the underlying native call has been issued.
 * - C2: The [AudioEngine] constructor must throw on out-of-range sample rates
 *       (and would throw on a `nativeCreate` 0-handle) rather than handing back
 *       a "zombie" instance that explodes on first use.
 *
 * Uses [ShadowAudioEngine] so the shadow's non-zero stub handle keeps the
 * happy-path init { check(...) } guard green; the C2 failure path
 * (nativeCreate → 0) is handled by the `require` range check that runs
 * BEFORE nativeCreate, so it's exercised here without a custom shadow.
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    manifest = Config.NONE,
    sdk = [33],
    shadows = [ShadowAudioEngine::class],
)
class AudioEngineRangeContractTest {

    // -------------------- C2: constructor range gate --------------------

    @Test
    fun `constructor rejects sampleRate below MIN with IllegalArgumentException`() {
        val ex = assertThrows(IllegalArgumentException::class.java) {
            AudioEngine(7_999)
        }
        assertTrue(
            "message should include the offending rate, was '${ex.message}'",
            ex.message?.contains("7999") == true,
        )
    }

    @Test
    fun `constructor rejects sampleRate above MAX with IllegalArgumentException`() {
        val ex = assertThrows(IllegalArgumentException::class.java) {
            AudioEngine(384_001)
        }
        assertTrue(
            "message should include the offending rate, was '${ex.message}'",
            ex.message?.contains("384001") == true,
        )
    }

    @Test
    fun `constructor accepts MIN_SAMPLE_RATE_HZ exactly`() {
        val engine = AudioEngine(AudioEngine.MIN_SAMPLE_RATE_HZ)
        try {
            assertEquals(AudioEngine.MIN_SAMPLE_RATE_HZ, engine.sampleRate)
        } finally {
            engine.close()
        }
    }

    @Test
    fun `constructor accepts MAX_SAMPLE_RATE_HZ exactly`() {
        val engine = AudioEngine(AudioEngine.MAX_SAMPLE_RATE_HZ)
        try {
            assertEquals(AudioEngine.MAX_SAMPLE_RATE_HZ, engine.sampleRate)
        } finally {
            engine.close()
        }
    }

    // -------------------- C1: updateSampleRate range gate --------------------

    @Test
    fun `updateSampleRate rejects newRate below MIN`() {
        val engine = AudioEngine(48_000)
        try {
            val ex = assertThrows(IllegalArgumentException::class.java) {
                engine.updateSampleRate(7_999)
            }
            assertTrue(
                "message should include the offending rate, was '${ex.message}'",
                ex.message?.contains("7999") == true,
            )
            // Wrapper state must NOT have advanced past the rejected call.
            assertEquals(48_000, engine.sampleRate)
        } finally {
            engine.close()
        }
    }

    @Test
    fun `updateSampleRate rejects newRate above MAX without state drift`() {
        val engine = AudioEngine(48_000)
        try {
            assertThrows(IllegalArgumentException::class.java) {
                engine.updateSampleRate(384_001)
            }
            assertEquals(48_000, engine.sampleRate)
        } finally {
            engine.close()
        }
    }

    @Test
    fun `updateSampleRate to same rate is a no-op early return`() {
        val engine = AudioEngine(48_000)
        try {
            engine.updateSampleRate(48_000)
            assertEquals(48_000, engine.sampleRate)
        } finally {
            engine.close()
        }
    }

    @Test
    fun `updateSampleRate to a valid different rate advances sampleRate`() {
        val engine = AudioEngine(48_000)
        try {
            engine.updateSampleRate(44_100)
            assertEquals(44_100, engine.sampleRate)
        } finally {
            engine.close()
        }
    }

    @Test
    fun `updateSampleRate after close throws IllegalArgumentException`() {
        val engine = AudioEngine(48_000)
        engine.close()
        assertThrows(IllegalArgumentException::class.java) {
            engine.updateSampleRate(44_100)
        }
    }

    // -------------------- companion constants pinning --------------------

    @Test
    fun `companion exposes the canonical sample-rate range`() {
        // These values are mirrored in C++ as
        //   AuralTuneEQEngine::kMinSampleRateHz / kMaxSampleRateHz
        // — keep them in lockstep when changing either side.
        assertEquals(8_000, AudioEngine.MIN_SAMPLE_RATE_HZ)
        assertEquals(384_000, AudioEngine.MAX_SAMPLE_RATE_HZ)
    }
}
