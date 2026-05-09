package com.coreline.audio

import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

/**
 * Companion to [ShadowAudioEngine] that simulates the failure mode of
 * `nativeUpdateSampleRate` returning -1 (the JNI rejected the call —
 * either because the engine pointer was null or, in the contract-divergence
 * case we're protecting against, because the rate fell outside the native
 * range despite passing the Kotlin range check).
 *
 * Used by [AudioEngineUpdateRateRejectionTest] to exercise the #2 contract:
 * if the JNI returns a non-zero status the wrapper must throw
 * [IllegalStateException] WITHOUT advancing its `sampleRate` property —
 * otherwise wrapper state would silently diverge from the engine's actual rate.
 */
@Implements(AudioEngine::class)
class ShadowAudioEngineRejectingUpdate {

    @Implementation
    fun nativeCreate(@Suppress("UNUSED_PARAMETER") sampleRate: Int): Long = STUB_HANDLE

    @Implementation
    fun nativeDestroy(@Suppress("UNUSED_PARAMETER") handle: Long) {
        // no-op
    }

    @Implementation
    fun nativeUpdateSampleRate(
        @Suppress("UNUSED_PARAMETER") handle: Long,
        @Suppress("UNUSED_PARAMETER") newRate: Int,
    ): Int = -1

    companion object {
        private const val STUB_HANDLE: Long = 0x5AFEL
    }
}
