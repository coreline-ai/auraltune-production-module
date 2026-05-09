package com.coreline.audio

import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

/**
 * Companion to [ShadowAudioEngine] that simulates the failure mode of
 * `nativeCreate` returning 0 (e.g. native heap exhaustion). Used by
 * [AudioEngineNativeCreateFailureTest] to exercise the C2 / #4 contract:
 * the constructor must throw immediately rather than build a "zombie"
 * instance whose every public method later trips `require(handle != 0L)`.
 *
 * Scoped via `@Config(shadows = [ShadowAudioEngineFailingCreate::class])`
 * — does NOT replace the regular [ShadowAudioEngine] used by the rest of
 * the host-JVM tests.
 */
@Implements(AudioEngine::class)
class ShadowAudioEngineFailingCreate {

    @Implementation
    fun nativeCreate(@Suppress("UNUSED_PARAMETER") sampleRate: Int): Long = 0L

    @Implementation
    fun nativeDestroy(@Suppress("UNUSED_PARAMETER") handle: Long) {
        // no-op (would never be called given nativeCreate returns 0)
    }
}
