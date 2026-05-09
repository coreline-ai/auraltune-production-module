package com.coreline.audio

import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #4 — Coverage for the `check(it.get() != 0L)` defensive guard inside
 * [AudioEngine]'s constructor. The regular [ShadowAudioEngine] hands back a
 * non-zero stub handle and so cannot exercise the failure path; this test
 * uses [ShadowAudioEngineFailingCreate] which always returns `0L` to
 * simulate a native allocation failure.
 *
 * The contract being pinned: when `nativeCreate` returns 0 (no engine
 * allocated), constructing an [AudioEngine] MUST throw [IllegalStateException]
 * — handing back a half-constructed instance is a worse contract for
 * source-level integrators (every method would later trip
 * `require(handle != 0L)` with a misleading "AudioEngine is closed" error).
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    manifest = Config.NONE,
    sdk = [33],
    shadows = [ShadowAudioEngineFailingCreate::class],
)
class AudioEngineNativeCreateFailureTest {

    @Test
    fun `constructor throws IllegalStateException when nativeCreate returns 0`() {
        val ex = assertThrows(IllegalStateException::class.java) {
            AudioEngine(48_000)
        }
        // Sanity: the message must be diagnostic enough to triage in production.
        // It should mention the offending sampleRate.
        assertTrue(
            "message should mention sampleRate, was '${ex.message}'",
            ex.message?.contains("48000") == true,
        )
        assertTrue(
            "message should mention nativeCreate, was '${ex.message}'",
            ex.message?.contains("nativeCreate", ignoreCase = true) == true,
        )
    }

    @Test
    fun `constructor with valid range still throws (range check passes, native fails)`() {
        // Even MIN/MAX boundary values must throw — proves the failure happens
        // AFTER the range gate (i.e. nativeCreate is what's failing) rather than
        // some earlier mistakenly-broadened check.
        assertThrows(IllegalStateException::class.java) {
            AudioEngine(AudioEngine.MIN_SAMPLE_RATE_HZ)
        }
        assertThrows(IllegalStateException::class.java) {
            AudioEngine(AudioEngine.MAX_SAMPLE_RATE_HZ)
        }
    }

    @Test
    fun `constructor still rejects out-of-range BEFORE reaching nativeCreate`() {
        // With the failing shadow, IF we ever called nativeCreate we'd get
        // IllegalStateException. With the range check first, we get
        // IllegalArgumentException — proving the gate runs before the
        // native call. (#3: this ordering is enforced lexically inside
        // the single `run { }` expression in AudioEngine.)
        assertThrows(IllegalArgumentException::class.java) {
            AudioEngine(7_999)
        }
        assertThrows(IllegalArgumentException::class.java) {
            AudioEngine(384_001)
        }
    }
}
