// OsEffectBackend.kt
// Phase 6 (T2-OS): thin wrappers over the OS AudioEffect equalizers we can attach to an external
// app's audio session. DynamicsProcessing (API 28+) is preferred (configurable multi-band EQ);
// Equalizer is the API 26–27 fallback (fixed bands). All android.media.audiofx coupling lives
// here so AutoEqApprox stays pure/testable. Device-verified (not unit-testable).
package com.coreline.auraltune.audio.audiofx

import android.media.audiofx.DynamicsProcessing
import android.media.audiofx.Equalizer
import android.os.Build
import android.util.Log
import com.coreline.auraltune.BuildConfig

/** Result of trying to attach an OS effect to a session. */
sealed interface AttachResult {
    data class Attached(val backend: String, val bandCount: Int) : AttachResult
    data object Unsupported : AttachResult
    data class Failed(val reason: String) : AttachResult
}

/**
 * An OS equalizer effect bound to one audio session. Lifecycle: [attach] → [apply]* → [release].
 * Implementations are NOT thread-safe; the controller owns one per session on a single thread.
 */
interface OsEffectBackend {
    val name: String

    /** Center frequencies (Hz) of this backend's bands — feed these to [AutoEqApprox.fit]. */
    fun bandCenters(): DoubleArray

    /** Push per-band gains (dB). Returns false on any OS error (caller releases). */
    fun apply(bands: List<AutoEqApprox.BandGain>): Boolean

    fun release()

    companion object {
        private const val TAG = "OsEffectBackend"

        /**
         * Attach the best available backend to [audioSessionId]. Prefers DynamicsProcessing on
         * API 28+, falls back to Equalizer. Returns the backend + an [AttachResult] describing
         * what happened (for UI / coverage logging).
         */
        fun attach(audioSessionId: Int): Pair<OsEffectBackend?, AttachResult> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                runCatching { DynamicsProcessingBackend(audioSessionId) }
                    .onSuccess { return it to AttachResult.Attached(it.name, it.bandCenters().size) }
                    .onFailure { if (BuildConfig.DEBUG) Log.w(TAG, "DynamicsProcessing attach failed: ${it.message}") }
            }
            runCatching { EqualizerBackend(audioSessionId) }
                .onSuccess { return it to AttachResult.Attached(it.name, it.bandCenters().size) }
                .onFailure { return null to AttachResult.Failed(it.message ?: "Equalizer attach failed") }
            return null to AttachResult.Unsupported
        }
    }
}

/** API 26+ fixed-band [Equalizer] fallback. */
private class EqualizerBackend(audioSessionId: Int) : OsEffectBackend {
    override val name = "Equalizer"
    private val eq = Equalizer(EFFECT_PRIORITY, audioSessionId)
    private val numBands: Short
    private val range: ShortArray // millibels [min, max]

    init {
        // Release the native effect if any post-allocation configuration throws (no leak).
        try {
            eq.enabled = true
            numBands = eq.numberOfBands
            range = eq.bandLevelRange
        } catch (t: Throwable) {
            runCatching { eq.release() }
            throw t
        }
    }

    override fun bandCenters(): DoubleArray =
        DoubleArray(numBands.toInt()) { eq.getCenterFreq(it.toShort()) / 1000.0 } // mHz → Hz

    override fun apply(bands: List<AutoEqApprox.BandGain>): Boolean = runCatching {
        val n = minOf(numBands.toInt(), bands.size)
        for (i in 0 until n) {
            val mb = (bands[i].gainDb * 100.0).toInt().toShort() // dB → millibels
            eq.setBandLevel(i.toShort(), mb.coerceIn(range[0], range[1]))
        }
        true
    }.getOrElse { false }

    override fun release() = runCatching { eq.release() }.getOrDefault(Unit)

    private companion object { const val EFFECT_PRIORITY = 0 }
}

/** API 28+ configurable multi-band [DynamicsProcessing] EQ (post-EQ stage only). */
private class DynamicsProcessingBackend(audioSessionId: Int) : OsEffectBackend {
    override val name = "DynamicsProcessing"
    private val bandCount = DEFAULT_BANDS
    private val centers = logCenters(bandCount)

    private val dp: DynamicsProcessing = run {
        val eqCfg = DynamicsProcessing.Eq(true, true, bandCount)
        for (i in 0 until bandCount) {
            eqCfg.getBand(i).apply {
                cutoffFrequency = centers[i].toFloat()
                gain = 0f
                isEnabled = true
            }
        }
        val config = DynamicsProcessing.Config.Builder(
            DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
            /* channelCount = */ 2,
            /* preEqInUse = */ false, 0,
            /* mbcInUse = */ false, 0,
            /* postEqInUse = */ true, bandCount,
            /* limiterInUse = */ false,
        ).setPostEqAllChannelsTo(eqCfg).build()
        val d = DynamicsProcessing(EFFECT_PRIORITY, audioSessionId, config)
        // Release if enabling throws (don't leak the native effect).
        try {
            d.enabled = true
        } catch (t: Throwable) {
            runCatching { d.release() }
            throw t
        }
        d
    }

    override fun bandCenters(): DoubleArray = centers.copyOf()

    override fun apply(bands: List<AutoEqApprox.BandGain>): Boolean = runCatching {
        val eqCfg = dp.getPostEqByChannelIndex(0)
        val n = minOf(bandCount, bands.size)
        for (i in 0 until n) {
            eqCfg.getBand(i).apply {
                cutoffFrequency = bands[i].centerHz.toFloat()
                gain = bands[i].gainDb.toFloat()
            }
        }
        dp.setPostEqAllChannelsTo(eqCfg) // applies to all channels in one call
        true
    }.getOrElse { false }

    override fun release() = runCatching { dp.release() }.getOrDefault(Unit)

    private companion object {
        const val EFFECT_PRIORITY = 0
        const val DEFAULT_BANDS = 12
        fun logCenters(n: Int): DoubleArray =
            DoubleArray(n) { 31.5 * Math.pow(20_000.0 / 31.5, it.toDouble() / (n - 1)) }
    }
}
