package com.coreline.audio

import android.media.AudioTrack

/**
 * Fluent builder for [AudioEngine]. Use this in preference to the direct
 * constructor when wiring an audio pipeline — the builder enforces that the
 * engine's coefficient pre-warp targets the same sample rate as the PCM you
 * intend to feed into [AudioEngine.process].
 *
 * The most common mistake without this builder is creating an AudioTrack at
 * 44.1 kHz and an AudioEngine at the default 48 kHz. The cascade still runs
 * and audio still flows, but every AutoEQ filter's center frequency drifts by
 * the rate ratio (about 8% off at 44.1k vs 48k profile). The result is a
 * subtly-wrong tonal balance that A/B listening reveals.
 *
 * ### Sample rate enforcement
 *
 * The builder rejects rates outside
 * [AudioEngine.MIN_SAMPLE_RATE_HZ] .. [AudioEngine.MAX_SAMPLE_RATE_HZ]
 * with [IllegalArgumentException]. This range covers everything from telephony
 * (8 kHz) to audiophile DSD-to-PCM (384 kHz) and rejects garbage inputs early.
 *
 * ### Usage
 *
 * ```kotlin
 * // From an existing AudioTrack — most common, zero rate-mismatch risk:
 * val track = AudioTrack.Builder().setAudioFormat(...).build()
 * val engine = AudioEngine.Builder()
 *     .forAudioTrack(track)
 *     .build()
 *
 * // From an explicit rate — when constructing AudioTrack and engine in the
 * // same factory:
 * val engine = AudioEngine.Builder()
 *     .forSampleRate(48_000)
 *     .build()
 * ```
 *
 * The [AudioEngine] direct constructor is still public (can't break existing
 * call sites) but its KDoc steers you here.
 */
class AudioEngineBuilder {

    private var sampleRate: Int? = null

    /**
     * Set the engine sample rate from an existing [AudioTrack]. Reads
     * [AudioTrack.getSampleRate] so the rate is provably the same as the PCM
     * stream the engine will process.
     *
     * @throws IllegalStateException if the AudioTrack has not yet reached
     *         [AudioTrack.STATE_INITIALIZED] (its sampleRate may be unstable).
     */
    fun forAudioTrack(track: AudioTrack): AudioEngineBuilder {
        check(track.state == AudioTrack.STATE_INITIALIZED) {
            "AudioTrack must be STATE_INITIALIZED before calling forAudioTrack(); got state=${track.state}"
        }
        return forSampleRate(track.sampleRate)
    }

    /**
     * Set the engine sample rate explicitly. Prefer [forAudioTrack] when an
     * AudioTrack already exists.
     *
     * @throws IllegalArgumentException if [rate] is outside
     *         [AudioEngine.MIN_SAMPLE_RATE_HZ] .. [AudioEngine.MAX_SAMPLE_RATE_HZ].
     */
    fun forSampleRate(rate: Int): AudioEngineBuilder {
        require(rate in AudioEngine.MIN_SAMPLE_RATE_HZ..AudioEngine.MAX_SAMPLE_RATE_HZ) {
            "sample rate $rate out of range " +
                "[${AudioEngine.MIN_SAMPLE_RATE_HZ}, ${AudioEngine.MAX_SAMPLE_RATE_HZ}]"
        }
        this.sampleRate = rate
        return this
    }

    /**
     * Build the engine. The caller takes ownership of the returned instance and
     * MUST call [AudioEngine.close] before the audio thread is destroyed (see
     * AudioEngine KDoc for the lifecycle ordering contract).
     *
     * @throws IllegalStateException if neither [forAudioTrack] nor
     *         [forSampleRate] was called.
     */
    fun build(): AudioEngine {
        val rate = sampleRate
            ?: error("Must call forAudioTrack(track) or forSampleRate(rate) before build()")
        return AudioEngine(rate)
    }

    companion object {
        /**
         * Deprecated alias — retained for binary/source compatibility with
         * downstream code that imported these constants from the Builder before
         * they moved to [AudioEngine.Companion]. New code should prefer
         * [AudioEngine.MIN_SAMPLE_RATE_HZ].
         *
         * Pinned to the same literal as the canonical
         * `AudioEngine.MIN_SAMPLE_RATE_HZ` so that any future divergence is
         * caught by [AudioEngineBuilderTest]'s boundary tests
         * ([forSampleRate below MIN throws]).
         */
        @Deprecated(
            "Use AudioEngine.MIN_SAMPLE_RATE_HZ",
            ReplaceWith("AudioEngine.MIN_SAMPLE_RATE_HZ", "com.coreline.audio.AudioEngine"),
            DeprecationLevel.WARNING,
        )
        const val MIN_SAMPLE_RATE_HZ: Int = 8_000

        @Deprecated(
            "Use AudioEngine.MAX_SAMPLE_RATE_HZ",
            ReplaceWith("AudioEngine.MAX_SAMPLE_RATE_HZ", "com.coreline.audio.AudioEngine"),
            DeprecationLevel.WARNING,
        )
        const val MAX_SAMPLE_RATE_HZ: Int = 384_000
    }
}

/**
 * Convenience extension so call sites read `AudioEngine.Builder()` rather than
 * `AudioEngineBuilder()`. Keeps the namespace tidy and matches the JVM idiom
 * where Builders nest under the type they construct.
 *
 * Equivalent of:
 * ```kotlin
 * companion object {
 *     fun Builder() = AudioEngineBuilder()
 * }
 * ```
 * but lives outside the AudioEngine source file (which we are not allowed to
 * modify in this change).
 */
@Suppress("FunctionName")
fun AudioEngine.Companion.Builder(): AudioEngineBuilder = AudioEngineBuilder()
