package com.coreline.audio

import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

/**
 * Robolectric shadow that stubs out the JNI surface of [AudioEngine] for host-JVM
 * unit tests. The real native library `libauraltune_audio.so` is only built for
 * Android ABIs (arm64-v8a / x86_64) — there is no host-JVM .dylib/.so to load,
 * so the companion `init { System.loadLibrary(...) }` would fail without this
 * shadow short-circuiting [nativeCreate] et al.
 *
 * Robolectric routes the static `init`-block `System.loadLibrary` call to a
 * no-op inside its sandbox, and `@Implementation` methods below replace the
 * `external fun` JNI bindings with deterministic Kotlin returns. We hand back
 * non-zero handles from [nativeCreate] so that AudioEngine's `require(handle != 0L)`
 * lifecycle assertion passes — though the Builder tests don't exercise any
 * post-construction methods.
 *
 * This shadow is scoped via `@Config(shadows = [...])` on `AudioEngineBuilderTest`;
 * it is NOT applied globally and does not affect on-device behavior.
 */
@Implements(AudioEngine::class)
class ShadowAudioEngine {

    @Implementation
    fun nativeCreate(@Suppress("UNUSED_PARAMETER") sampleRate: Int): Long = STUB_HANDLE

    @Implementation
    fun nativeDestroy(@Suppress("UNUSED_PARAMETER") handle: Long) {
        // no-op
    }

    companion object {
        // Any non-zero value satisfies AudioEngine's `require(handle != 0L)` checks.
        private const val STUB_HANDLE: Long = 0x5AFEL
    }
}
