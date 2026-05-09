package com.coreline.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #2 — Contract test for the new int-return signature on
 * `nativeUpdateSampleRate`. The Kotlin wrapper now treats any non-zero
 * status as a hard error and throws [IllegalStateException] without
 * advancing its `sampleRate` property.
 *
 * This is defense-in-depth against future drift between Kotlin's
 * `MIN/MAX_SAMPLE_RATE_HZ` and native's `kMin/kMaxSampleRateHz`. With the
 * constants currently in lockstep and the Kotlin-side range check running
 * first, the rejection branch is unreachable in normal operation — so we
 * use [ShadowAudioEngineRejectingUpdate] (always returns -1) to exercise it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    manifest = Config.NONE,
    sdk = [33],
    shadows = [ShadowAudioEngineRejectingUpdate::class],
)
class AudioEngineUpdateRateRejectionTest {

    @Test
    fun `non-zero JNI status throws IllegalStateException`() {
        val engine = AudioEngine(48_000)
        try {
            val ex = assertThrows(IllegalStateException::class.java) {
                engine.updateSampleRate(44_100)
            }
            // Diagnostic message must mention the offending rate AND the
            // most likely cause (constant drift), so production triage
            // doesn't have to grep for the line.
            assertTrue(
                "message should mention rate, was '${ex.message}'",
                ex.message?.contains("44100") == true,
            )
            assertTrue(
                "message should call out constant drift, was '${ex.message}'",
                ex.message?.contains("drift", ignoreCase = true) == true ||
                    ex.message?.contains("lockstep", ignoreCase = true) == true,
            )
        } finally {
            engine.close()
        }
    }

    @Test
    fun `wrapper sampleRate property does NOT advance when native rejects`() {
        // The whole point of #2: wrapper state must stay coherent with the
        // engine's actual state. If native rejected the rate, the wrapper
        // must not advance its `sampleRate` property.
        val engine = AudioEngine(48_000)
        try {
            assertThrows(IllegalStateException::class.java) {
                engine.updateSampleRate(44_100)
            }
            assertEquals(
                "sampleRate must NOT advance past a rejected JNI call",
                48_000, engine.sampleRate,
            )
        } finally {
            engine.close()
        }
    }

    @Test
    fun `same-rate no-op short-circuits BEFORE hitting JNI`() {
        // The rejection shadow returns -1 unconditionally; if the wrapper's
        // same-rate fast path didn't short-circuit, this call would throw.
        // It must not — proving the no-op check sits in front of the JNI call.
        val engine = AudioEngine(48_000)
        try {
            engine.updateSampleRate(48_000) // must NOT throw
            assertEquals(48_000, engine.sampleRate)
        } finally {
            engine.close()
        }
    }
}
