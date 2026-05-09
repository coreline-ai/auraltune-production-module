package com.coreline.audio

import android.media.AudioTrack
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Host-JVM unit tests for [AudioEngineBuilder].
 *
 * The Robolectric runner is used purely so that [AudioEngine]'s companion
 * `init { System.loadLibrary("auraltune_audio") }` is short-circuited inside
 * Robolectric's sandbox. The companion [ShadowAudioEngine] additionally
 * stubs the native methods so a real `AudioEngine(rate)` instance can be
 * constructed on the host JVM without the .so being present (the .so only
 * exists in the on-device APK).
 *
 * mockk is used to stand up [AudioTrack] fixtures, since AudioTrack cannot
 * be constructed against the Android-stub jar.
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    manifest = Config.NONE,
    sdk = [33],
    shadows = [ShadowAudioEngine::class],
)
class AudioEngineBuilderTest {

    @Test
    fun `forSampleRate 48000 build returns non-null engine`() {
        val engine = AudioEngineBuilder()
            .forSampleRate(48_000)
            .build()
        try {
            assertNotNull(engine)
        } finally {
            engine.close()
        }
    }

    @Test
    fun `forSampleRate below MIN throws IllegalArgumentException`() {
        val ex = assertThrows(IllegalArgumentException::class.java) {
            AudioEngineBuilder().forSampleRate(7_999)
        }
        // Sanity-check the message includes the offending value so production
        // log triage isn't a guessing game.
        assertEquals(true, ex.message?.contains("7999"))
    }

    @Test
    fun `forSampleRate above MAX throws IllegalArgumentException`() {
        val ex = assertThrows(IllegalArgumentException::class.java) {
            AudioEngineBuilder().forSampleRate(384_001)
        }
        assertEquals(true, ex.message?.contains("384001"))
    }

    @Test
    fun `build without setting rate throws IllegalStateException`() {
        assertThrows(IllegalStateException::class.java) {
            AudioEngineBuilder().build()
        }
    }

    @Test
    fun `forAudioTrack with uninitialized track throws IllegalStateException`() {
        val track = mockk<AudioTrack>()
        every { track.state } returns AudioTrack.STATE_UNINITIALIZED

        assertThrows(IllegalStateException::class.java) {
            AudioEngineBuilder().forAudioTrack(track)
        }
    }

    @Test
    @Suppress("DEPRECATION")
    fun `deprecated builder constants stay in lockstep with the canonical AudioEngine constants`() {
        // #8 contract: the deprecated MIN/MAX_SAMPLE_RATE_HZ on AudioEngineBuilder
        // exist purely for source/binary compat with downstream code that
        // imported them before they moved to AudioEngine. They MUST never drift
        // from the canonical values, otherwise downstream callers using the
        // alias would get a different validation range than the engine itself.
        assertEquals(
            AudioEngine.MIN_SAMPLE_RATE_HZ,
            AudioEngineBuilder.MIN_SAMPLE_RATE_HZ,
        )
        assertEquals(
            AudioEngine.MAX_SAMPLE_RATE_HZ,
            AudioEngineBuilder.MAX_SAMPLE_RATE_HZ,
        )
    }

    @Test
    fun `forAudioTrack with initialized track reads sampleRate and builds`() {
        val track = mockk<AudioTrack>()
        every { track.state } returns AudioTrack.STATE_INITIALIZED
        every { track.sampleRate } returns 44_100

        val engine = AudioEngineBuilder()
            .forAudioTrack(track)
            .build()
        try {
            assertNotNull(engine)
        } finally {
            engine.close()
        }
    }
}
