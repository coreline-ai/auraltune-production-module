package com.coreline.audio

import androidx.annotation.Keep
import java.io.Closeable
import java.nio.ByteBuffer

/**
 * Kotlin wrapper for the native AutoEQ + Manual EQ DSP engine.
 *
 * Phase 1 contract: handle-based lifetime, validated JNI boundary, RT-safe config publish.
 * Phase 2 contract: Manual chain (≤20 sections) and AutoEQ chain (≤10 sections) are independent.
 *                   Sample-rate changes recompute all coefficients with pre-warp.
 *
 * Thread ownership:
 *  - All public methods MUST be called from the main / control thread.
 *  - process() runs on the audio thread; never call native lifetime methods from there.
 *
 * **Deliberate `handle` access asymmetry (#1).** [process] and [recordXrun] take a
 * local snapshot of [handleRef] (`val h = handleRef.get(); if (h == 0L) return ...`)
 * because they are called from the audio thread and CAN race with [close] running
 * on the control thread; the native engine retire-queue (500 ms grace, see
 * `kRetireGraceMs` in C++) keeps the snapshotted pointer valid long enough for the
 * audio callback to finish.
 *
 * The control-thread methods ([updateSampleRate], [updateAutoEq], [updateManualEq],
 * the `set*Enabled` family, [clearAutoEq]) deliberately **do NOT** snapshot. They
 * re-read [handle] (the property getter) on each access. This is safe because:
 *
 *   1. The thread-ownership contract above guarantees [close] never races with them.
 *   2. If a caller violates that contract, re-reading observes [handle] == 0 after
 *      [close], and JNI's `fromHandle(0)` returns nullptr → silent no-op. With a
 *      local snapshot we'd instead pass a *stale non-zero* handle into JNI, which
 *      would be a UAF outside the retire-queue's protection — i.e. a contract
 *      violation that gracefully no-ops today would crash the app instead.
 *
 * In short: snapshot where there's a known race + retire-queue protection;
 * re-read where there's no race + no protection.
 */
@Keep
class AudioEngine(sampleRate: Int) : Closeable {

    /**
     * Native handle, atomic to defend against post-close UAF (P0-3 fix). Once the
     * audio thread has been joined, [close] CAS-resets this to 0 and frees the native
     * engine. Subsequent calls — including any in-flight [process] from a thread that
     * outlives our join window — observe handle==0 and return -1 without dereferencing.
     *
     * C2 / #3: range validation, [nativeCreate], and the null-handle check live in a
     * single `run { }` expression so the ordering is **lexically obvious**: range
     * gate → native alloc → handle non-zero check → atomic wrap. Using a separate
     * `init { require(...) }` block ahead of the property worked but depended on
     * declaration order — a future refactor reordering members could silently route
     * an out-of-range value into JNI before the `require`. The single-expression form
     * makes that impossible to break without rewriting this exact block.
     */
    private val handleRef: java.util.concurrent.atomic.AtomicLong = run {
        require(sampleRate in MIN_SAMPLE_RATE_HZ..MAX_SAMPLE_RATE_HZ) {
            "sampleRate $sampleRate out of range [$MIN_SAMPLE_RATE_HZ, $MAX_SAMPLE_RATE_HZ]"
        }
        val h = nativeCreate(sampleRate)
        check(h != 0L) {
            "nativeCreate returned null handle for sampleRate=$sampleRate"
        }
        java.util.concurrent.atomic.AtomicLong(h)
    }

    private val handle: Long
        get() = handleRef.get()

    /** Currently active sample rate (Hz). */
    var sampleRate: Int = sampleRate
        private set

    /**
     * Update the AutoEQ chain (≤10 biquad sections).
     * Pass empty arrays to clear the chain.
     *
     * @param preampDB AutoEQ chain preamp in dB. Range [-30, +30].
     * @param enableLimiter Whether the post-cascade soft limiter is active.
     * @param profileOptimizedRate Sample rate the profile was optimized for (Hz). Default 48000.
     * @param filterTypes 0=PEAKING, 1=LOW_SHELF, 2=HIGH_SHELF (one per filter).
     * @param frequencies Center/cutoff frequencies in Hz.
     * @param gainsDB Per-filter gains in dB.
     * @param qFactors Per-filter Q factors.
     * @return 0 on success, negative on validation failure.
     */
    fun updateAutoEq(
        preampDB: Float,
        enableLimiter: Boolean,
        profileOptimizedRate: Double,
        filterTypes: IntArray,
        frequencies: FloatArray,
        gainsDB: FloatArray,
        qFactors: FloatArray,
    ): Int {
        require(handle != 0L) { "AudioEngine is closed" }
        require(filterTypes.size == frequencies.size) { "filterTypes/frequencies size mismatch" }
        require(filterTypes.size == gainsDB.size) { "filterTypes/gains size mismatch" }
        require(filterTypes.size == qFactors.size) { "filterTypes/qFactors size mismatch" }
        require(filterTypes.size <= MAX_AUTOEQ_FILTERS) { "AutoEQ supports max $MAX_AUTOEQ_FILTERS filters" }
        return nativeUpdateAutoEq(
            handle, preampDB, enableLimiter, profileOptimizedRate,
            filterTypes, frequencies, gainsDB, qFactors,
        )
    }

    /**
     * Clear the AutoEQ chain (equivalent to passing empty arrays).
     */
    fun clearAutoEq() {
        require(handle != 0L) { "AudioEngine is closed" }
        nativeUpdateAutoEq(
            handle, 0f, false, 48000.0,
            IntArray(0), FloatArray(0), FloatArray(0), FloatArray(0),
        )
    }

    /**
     * Update the Manual EQ chain (≤20 biquad sections).
     * Phase 2: Manual preamp is forced to 0 dB; user gain is managed by limiter.
     */
    fun updateManualEq(
        frequencies: FloatArray,
        gainsDB: FloatArray,
        qFactors: FloatArray,
    ): Int {
        require(handle != 0L) { "AudioEngine is closed" }
        require(frequencies.size == gainsDB.size) { "size mismatch" }
        require(frequencies.size == qFactors.size) { "size mismatch" }
        require(frequencies.size <= MAX_MANUAL_FILTERS) { "Manual EQ supports max $MAX_MANUAL_FILTERS filters" }
        return nativeUpdateManualEq(handle, frequencies, gainsDB, qFactors)
    }

    fun setAutoEqEnabled(enabled: Boolean) {
        require(handle != 0L)
        nativeSetAutoEqEnabled(handle, enabled)
    }

    fun setManualEqEnabled(enabled: Boolean) {
        require(handle != 0L)
        nativeSetManualEqEnabled(handle, enabled)
    }

    fun setAutoEqPreampEnabled(enabled: Boolean) {
        require(handle != 0L)
        nativeSetAutoEqPreampEnabled(handle, enabled)
    }

    /**
     * Notify the engine that the device sample rate has changed.
     * Triggers full coefficient recomputation with pre-warp + delay buffer reset.
     *
     * C1: range-check BEFORE calling JNI, and only update [sampleRate] AFTER the
     * native call succeeds. Without the early `require` the wrapper's `sampleRate`
     * could drift from the engine's actual rate — a subtle source-level
     * integration footgun.
     *
     * #2: the JNI returns 0 on success and -1 on rejection (engine null, or rate
     * outside the native range). With the Kotlin range check above and the C++
     * range check sharing the same constants, the -1 branch is unreachable in
     * normal operation — but if the two ever drift, this throws immediately
     * rather than silently advancing [sampleRate] past a value the engine
     * never accepted.
     */
    fun updateSampleRate(newRate: Int) {
        require(handle != 0L) { "AudioEngine is closed" }
        require(newRate in MIN_SAMPLE_RATE_HZ..MAX_SAMPLE_RATE_HZ) {
            "newRate $newRate out of range [$MIN_SAMPLE_RATE_HZ, $MAX_SAMPLE_RATE_HZ]"
        }
        if (newRate == sampleRate) return
        val status = nativeUpdateSampleRate(handle, newRate)
        check(status == 0) {
            "nativeUpdateSampleRate rejected newRate=$newRate (status=$status). " +
                "This indicates Kotlin's MIN/MAX_SAMPLE_RATE_HZ has drifted from " +
                "the native AuralTuneEQEngine::kMin/kMaxSampleRateHz; both sides " +
                "must be updated in lockstep."
        }
        sampleRate = newRate
    }

    /**
     * Process stereo interleaved Float32 audio in place.
     * Must be called from the audio thread. RT-safe: no allocations, locks, or Java callbacks.
     *
     * @param buffer Direct ByteBuffer (nativeOrder, capacity ≥ numFrames * 2 * 4 bytes).
     * @param numFrames Number of stereo frames (samples = numFrames * 2).
     * @return 0 on success, negative on error.
     */
    fun process(buffer: ByteBuffer, numFrames: Int): Int {
        // Snapshot once — even if close() happens between this load and the JNI call,
        // native side is required to handle a stale handle being passed in. We rely on
        // the lifecycle invariant in AudioPlayerService.stop() to never let close() run
        // before the audio thread is joined; this is a defense-in-depth no-op fast path.
        val h = handleRef.get()
        if (h == 0L) return -1
        return nativeProcessDirectBuffer(h, buffer, numFrames)
    }

    /**
     * Increment the native xrun (underrun) counter by [deltaUnderrunFrames]. Called by
     * [com.coreline.auraltune.audio.AudioPlayerService] after reading
     * `AudioTrack.getUnderrunCount()`. RT-safe (atomic counter increment only).
     */
    fun recordXrun(deltaUnderrunFrames: Long) {
        val h = handleRef.get()
        if (h == 0L || deltaUnderrunFrames <= 0L) return
        nativeRecordXrun(h, deltaUnderrunFrames)
    }

    /**
     * Read native diagnostic counters (atomic read, RT-safe).
     *
     * The native side returns a `LongArray` of length 7, in this exact order:
     *   [0] xrunCount
     *   [1] nonFiniteResetCount
     *   [2] sampleRateChangeCount
     *   [3] configSwapCount
     *   [4] totalProcessedFrames
     *   [5] appliedGeneration              (Tier B-2)
     *   [6] autoEqActiveCount (as Long; narrowed to Int)  (Tier B-2)
     *
     * `appliedGeneration` increases by 1 per successful publish (`updateAutoEq`,
     * `updateManualEq`, `setAutoEqEnabled` transitions, sample-rate change, …).
     * The host UI compares it against the generation it expected from its own
     * `selectProfile` call to decide "is the profile I selected actually applied?".
     *
     * `autoEqActiveCount` is the number of biquads currently in the AutoEQ chain
     * — sourced from the published snapshot. Reported as 0 when the AutoEQ
     * master switch is off (no correction is reaching the user even if N
     * coefficients are cached).
     */
    fun readDiagnostics(): Diagnostics {
        if (handle == 0L) return Diagnostics(0, 0, 0, 0, 0, 0, 0)
        val arr = nativeGetDiagnostics(handle)
        return Diagnostics(
            xrunCount = arr.getOrElse(0) { 0L },
            nonFiniteResetCount = arr.getOrElse(1) { 0L },
            sampleRateChangeCount = arr.getOrElse(2) { 0L },
            configSwapCount = arr.getOrElse(3) { 0L },
            totalProcessedFrames = arr.getOrElse(4) { 0L },
            appliedGeneration = arr.getOrElse(5) { 0L },
            autoEqActiveCount = arr.getOrElse(6) { 0L }.toInt(),
        )
    }

    /**
     * Read a coherent snapshot of "what's actually applied right now?". Backs
     * the host UI's "X applied" vs "X selected (waiting)" decision — see the
     * Tier B-2 contract.
     *
     * All fields are sourced from the same currently-published native snapshot,
     * so they are mutually consistent (no torn reads across fields).
     */
    fun readAppliedSnapshot(): AppliedSnapshot {
        if (handle == 0L) {
            return AppliedSnapshot(
                generation = 0L,
                autoEqEnabled = false,
                autoEqFilterCount = 0,
                manualEnabled = false,
                preampEnabled = false,
                preampLinearGain = 1.0f,
            )
        }
        val arr = nativeGetAppliedSnapshot(handle)
        // Layout: [generation, autoOn?, count, manualOn?, preampOn?, gainBits]
        // gainBits is the float bit pattern of preampLinearGain, packed into a
        // Long on the native side (memcpy of float into uint32_t, widened to
        // jlong). Decode by truncating to Int and using Float.fromBits.
        val gainBits = arr.getOrElse(5) { java.lang.Float.floatToRawIntBits(1.0f).toLong() }
        return AppliedSnapshot(
            generation = arr.getOrElse(0) { 0L },
            autoEqEnabled = arr.getOrElse(1) { 0L } != 0L,
            autoEqFilterCount = arr.getOrElse(2) { 0L }.toInt(),
            manualEnabled = arr.getOrElse(3) { 0L } != 0L,
            preampEnabled = arr.getOrElse(4) { 0L } != 0L,
            preampLinearGain = Float.fromBits(gainBits.toInt()),
        )
    }

    /**
     * Destroy the native engine. Idempotent across concurrent calls (CAS-then-free).
     *
     * IMPORTANT: this method is **only** safe to call after the audio thread has been
     * joined. The Kotlin-side `handleRef` short-circuits subsequent calls into
     * [process] / [readDiagnostics] (returning -1 / zeros) before they reach JNI, so the
     * native engine is never re-entered after `close()`. **However, the underlying JNI
     * does not separately validate that the handle is still alive — passing a freed
     * native handle into JNI would dereference freed memory.** The whole-program
     * invariant is:
     *
     *   `AudioPlayerService.stop()`  →  audio thread joined  →  `engine.close()`.
     *
     * See `AudioPlayerService.stop()` for the lifecycle contract that callers MUST
     * uphold. Violating that ordering is undefined behavior.
     */
    override fun close() {
        val h = handleRef.getAndSet(0L)
        if (h != 0L) {
            nativeDestroy(h)
        }
    }

    /**
     * Run [block] with this engine, ensuring the lifecycle ordering
     *   audio thread stop → audio thread join (via [audioThreadJoiner]) → engine.close
     * is honored even if [block] throws or [audioThreadJoiner] throws.
     *
     * The host adapter passes a no-arg lambda that performs whatever it does to
     * stop and join its audio thread. Common implementations:
     *
     * ```kotlin
     * // AudioTrack-style host
     * engine.useInAudioSession(audioThreadJoiner = { player.stop() }) { eng ->
     *     // safe to call eng.process(...) from the audio thread inside this block
     *     ...
     * }
     *
     * // Oboe-style host
     * engine.useInAudioSession(audioThreadJoiner = {
     *     stream.requestStop()
     *     stream.close()  // Oboe's close blocks until the callback thread joins
     * }) { eng -> ... }
     * ```
     *
     * Guarantees:
     * 1. [audioThreadJoiner] is called before [close], even if [block] throws.
     * 2. [close] is called even if [audioThreadJoiner] throws — the joiner's
     *    exception is propagated, but the native handle is still freed (close()
     *    is idempotent and CAS-guarded).
     * 3. The return value of [block] is propagated when both succeed.
     *
     * Exception precedence (consistent with `kotlin.io.use { }`): if more than
     * one of {block, joiner, close} throws, the **block** exception wins, then
     * the **joiner** exception, then the **close** exception. Suppressed
     * exceptions are attached via [Throwable.addSuppressed] so they remain
     * visible in stacktraces.
     *
     * Thread safety: invoke from the control thread only (same as [close]).
     * The engine MUST NOT already have been closed.
     */
    /**
     * Internal accessor for [useInAudioSession]. Inline functions cannot read
     * the `private val handle`, so we expose a non-mutating snapshot through
     * `@PublishedApi internal` — kept out of the public API but visible to the
     * inlined call site.
     */
    @PublishedApi
    internal fun isOpen(): Boolean = handleRef.get() != 0L

    inline fun <R> useInAudioSession(
        audioThreadJoiner: () -> Unit,
        block: (AudioEngine) -> R,
    ): R {
        require(isOpen()) { "AudioEngine already closed" }
        var blockThrew: Throwable? = null
        var result: R? = null
        try {
            result = block(this)
        } catch (t: Throwable) {
            blockThrew = t
        }
        var joinerThrew: Throwable? = null
        try {
            audioThreadJoiner()
        } catch (t: Throwable) {
            joinerThrew = t
        }
        var closeThrew: Throwable? = null
        try {
            close()
        } catch (t: Throwable) {
            closeThrew = t
        }
        // Precedence: block > joiner > close. Lower-priority exceptions are
        // attached as suppressed on the winner so debug info isn't lost.
        if (blockThrew != null) {
            joinerThrew?.let { blockThrew.addSuppressed(it) }
            closeThrew?.let { blockThrew.addSuppressed(it) }
            throw blockThrew
        }
        if (joinerThrew != null) {
            closeThrew?.let { joinerThrew.addSuppressed(it) }
            throw joinerThrew
        }
        if (closeThrew != null) throw closeThrew
        @Suppress("UNCHECKED_CAST")
        return result as R
    }

    data class Diagnostics(
        val xrunCount: Long,
        val nonFiniteResetCount: Long,
        val sampleRateChangeCount: Long,
        val configSwapCount: Long,
        val totalProcessedFrames: Long,
        // Tier B-2: "selected vs applied" telemetry.
        val appliedGeneration: Long,
        val autoEqActiveCount: Int,
    )

    /**
     * Tier B-2 — consolidated "is profile X currently applied?" snapshot.
     *
     * The host UI computes:
     *   `applied = (generation == expectedGeneration) && (autoEqFilterCount > 0)`
     *
     * where `expectedGeneration` is whatever it observed when it last called
     * `updateAutoEq`. This avoids being fooled by:
     *  - a partial publish (snapshot atomicity gives end-to-end consistency)
     *  - the user toggling the AutoEQ master switch off (autoEqEnabled=false
     *    forces autoEqFilterCount=0 in the diagnostics view).
     *
     * `preampLinearGain == 1.0f` ⇒ no preamp attenuation (either preamp is
     * disabled, or it's enabled with 0 dB).
     */
    data class AppliedSnapshot(
        val generation: Long,
        val autoEqEnabled: Boolean,
        val autoEqFilterCount: Int,
        val manualEnabled: Boolean,
        val preampEnabled: Boolean,
        val preampLinearGain: Float,
    )

    // ---------------- Native methods ----------------

    private external fun nativeCreate(sampleRate: Int): Long
    private external fun nativeDestroy(handle: Long)
    private external fun nativeUpdateAutoEq(
        handle: Long,
        preampDB: Float,
        enableLimiter: Boolean,
        profileOptimizedRate: Double,
        filterTypes: IntArray,
        frequencies: FloatArray,
        gainsDB: FloatArray,
        qFactors: FloatArray,
    ): Int
    private external fun nativeUpdateManualEq(
        handle: Long,
        frequencies: FloatArray,
        gainsDB: FloatArray,
        qFactors: FloatArray,
    ): Int
    private external fun nativeSetAutoEqEnabled(handle: Long, enabled: Boolean)
    private external fun nativeSetManualEqEnabled(handle: Long, enabled: Boolean)
    private external fun nativeSetAutoEqPreampEnabled(handle: Long, enabled: Boolean)
    private external fun nativeUpdateSampleRate(handle: Long, newRate: Int): Int
    private external fun nativeProcessDirectBuffer(handle: Long, buffer: ByteBuffer, numFrames: Int): Int
    private external fun nativeGetDiagnostics(handle: Long): LongArray
    private external fun nativeGetAppliedSnapshot(handle: Long): LongArray
    private external fun nativeRecordXrun(handle: Long, deltaUnderrunFrames: Long)

    companion object {
        const val MAX_AUTOEQ_FILTERS = 10
        const val MAX_MANUAL_FILTERS = 20
        const val DEFAULT_PROFILE_OPTIMIZED_RATE: Double = 48000.0

        /**
         * Inclusive lower / upper bound on the sample rate accepted by the native
         * engine. Mirrors `AuralTuneEQEngine::kMinSampleRateHz` / `kMaxSampleRateHz`
         * in C++. The constructor and [updateSampleRate] both validate against this
         * range so wrapper state cannot diverge from native state.
         */
        const val MIN_SAMPLE_RATE_HZ: Int = 8_000
        const val MAX_SAMPLE_RATE_HZ: Int = 384_000

        init {
            System.loadLibrary("auraltune_audio")
        }
    }
}
