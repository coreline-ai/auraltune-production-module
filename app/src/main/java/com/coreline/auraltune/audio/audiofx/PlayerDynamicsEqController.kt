package com.coreline.auraltune.audio.audiofx

import android.os.Handler
import android.os.Looper
import com.coreline.auraltune.audio.eq.BiquadSpec
import com.coreline.auraltune.data.PlaybackProcessingMode
import java.io.Closeable
import kotlin.math.ceil
import kotlin.math.max

data class PlayerDynamicsEqStatus(
    val active: Boolean,
    val backend: String?,
    val bandCount: Int,
    val audioSessionId: Int?,
    val headroomDb: Float,
    val rmsErrorDb: Double?,
    val maxErrorDb: Double?,
    val message: String?,
) {
    companion object {
        fun idle(): PlayerDynamicsEqStatus = PlayerDynamicsEqStatus(
            active = false,
            backend = null,
            bandCount = 0,
            audioSessionId = null,
            headroomDb = 0f,
            rmsErrorDb = null,
            maxErrorDb = null,
            message = null,
        )
    }
}

/**
 * Attaches a DynamicsProcessing PostEQ to the app's own ExoPlayer audio session.
 *
 * This controller deliberately ignores external audio-effect broadcasts and session 0. It is a
 * player-only fallback backend: AuralTune native processing and this OS effect are mutually
 * exclusive through [PlaybackProcessingMode].
 */
class PlayerDynamicsEqController(
    private val statusSink: (PlayerDynamicsEqStatus) -> Unit,
    private val attachBackend: (Int) -> Pair<OsEffectBackend?, AttachResult> = { session ->
        OsEffectBackend.attachDynamics(session)
    },
    private val sampleRateProvider: () -> Double = { 48_000.0 },
    private val scheduleRampStep: (delayMs: Long, step: () -> Unit) -> Unit = { delayMs, step ->
        Handler(Looper.getMainLooper()).postDelayed(step, delayMs)
    },
) : Closeable {
    private var mode = PlaybackProcessingMode.AURAL_TUNE
    private var audioSessionId: Int? = null
    private var backend: OsEffectBackend? = null
    private var backendSessionId: Int? = null
    private var targetSpecs: List<BiquadSpec> = emptyList()
    private var targetHeadroomDb: Float = 0f
    private var lastAppliedBands: List<AutoEqApprox.BandGain> = emptyList()
    private var lastAppliedHeadroomDb: Float = 0f
    private var rampToken: Long = 0L

    fun setMode(mode: PlaybackProcessingMode) {
        if (this.mode == mode) return
        this.mode = mode
        reconcile()
    }

    fun onAudioSessionIdChanged(audioSessionId: Int) {
        if (this.audioSessionId == audioSessionId) return
        releaseBackend()
        this.audioSessionId = audioSessionId.takeIf { it > 0 }
        reconcile()
    }

    fun setTargetSpecs(specs: List<BiquadSpec>, headroomDb: Float = 0f) {
        targetSpecs = specs
        targetHeadroomDb = sanitizeHeadroomDb(headroomDb)
        reconcile()
    }

    private fun reconcile() {
        if (mode != PlaybackProcessingMode.ANDROID_DYNAMICS) {
            neutralizeBackendBeforeRelease()
            releaseBackend()
            statusSink(PlayerDynamicsEqStatus.idle())
            return
        }

        val session = audioSessionId
        if (session == null) {
            releaseBackend()
            statusSink(inactive("audio session 대기 중"))
            return
        }

        val activeBackend = ensureBackend(session) ?: return
        if (targetSpecs.isEmpty()) {
            if (!applyTarget(activeBackend, emptyList(), 0f)) {
                releaseBackend()
                statusSink(inactive("DynamicsProcessing 초기화 실패", session))
                return
            }
            statusSink(inactive("적용할 프로파일 없음", session))
            return
        }

        val fit = AutoEqApprox.fit(
            targetFilters = targetSpecs,
            bandCenters = activeBackend.bandCenters(),
            sampleRate = sampleRateProvider().coerceAtLeast(1.0),
        )
        if (!applyTarget(activeBackend, fit.bands, targetHeadroomDb)) {
            releaseBackend()
            statusSink(inactive("DynamicsProcessing 적용 실패", session))
            return
        }

        statusSink(
            PlayerDynamicsEqStatus(
                active = true,
                backend = activeBackend.name,
                bandCount = fit.bands.size,
                audioSessionId = session,
                headroomDb = targetHeadroomDb,
                rmsErrorDb = fit.rmsErrorDb,
                maxErrorDb = fit.maxErrorDb,
                message = null,
            ),
        )
    }

    private fun ensureBackend(session: Int): OsEffectBackend? {
        backend?.takeIf { backendSessionId == session }?.let { return it }
        releaseBackend()
        val (created, result) = attachBackend(session)
        if (created == null || result !is AttachResult.Attached) {
            statusSink(inactive(attachFailureMessage(result), session))
            return null
        }
        backend = created
        backendSessionId = session
        lastAppliedBands = neutralBands(created)
        lastAppliedHeadroomDb = 0f
        return created
    }

    private fun attachFailureMessage(result: AttachResult): String = when (result) {
        is AttachResult.Attached -> "DynamicsProcessing 연결됨"
        AttachResult.Unsupported -> "DynamicsProcessing 미지원"
        is AttachResult.Failed -> result.reason
    }

    private fun inactive(message: String, session: Int? = audioSessionId): PlayerDynamicsEqStatus =
        PlayerDynamicsEqStatus(
            active = false,
            backend = null,
            bandCount = 0,
            audioSessionId = session,
            headroomDb = 0f,
            rmsErrorDb = null,
            maxErrorDb = null,
            message = message,
        )

    private fun sanitizeHeadroomDb(value: Float): Float = when {
        !value.isFinite() -> 0f
        value > 0f -> 0f
        value < MIN_HEADROOM_DB -> MIN_HEADROOM_DB
        else -> value
    }

    private fun releaseBackend() {
        backend?.release()
        backend = null
        backendSessionId = null
        lastAppliedBands = emptyList()
        lastAppliedHeadroomDb = 0f
        rampToken++
    }

    override fun close() {
        neutralizeBackendBeforeRelease()
        releaseBackend()
        statusSink(PlayerDynamicsEqStatus.idle())
    }

    private fun applyTarget(
        activeBackend: OsEffectBackend,
        targetBands: List<AutoEqApprox.BandGain>,
        targetHeadroomDb: Float,
        immediate: Boolean = false,
    ): Boolean {
        val normalizedTarget = targetBands.ifEmpty { neutralBands(activeBackend) }
        val startBands = if (lastAppliedBands.size == normalizedTarget.size) {
            lastAppliedBands
        } else {
            neutralBands(activeBackend)
        }
        val fromHeadroomDb = lastAppliedHeadroomDb
        val steps = if (immediate) {
            1
        } else {
            rampSteps(
                fromBands = startBands,
                toBands = normalizedTarget,
                fromHeadroomDb = fromHeadroomDb,
                toHeadroomDb = targetHeadroomDb,
            )
        }
        val token = ++rampToken

        if (immediate || steps == 1) {
            return applyInterpolatedStep(
                activeBackend = activeBackend,
                fromBands = startBands,
                toBands = normalizedTarget,
                fromHeadroomDb = fromHeadroomDb,
                toHeadroomDb = targetHeadroomDb,
                t = 1.0,
            )
        }

        for (step in 1..steps) {
            val delayMs = ((RAMP_DURATION_MS * step) / steps).coerceAtLeast(1)
            scheduleRampStep(delayMs) {
                if (rampToken != token || backend !== activeBackend) return@scheduleRampStep
                val ok = applyInterpolatedStep(
                    activeBackend = activeBackend,
                    fromBands = startBands,
                    toBands = normalizedTarget,
                    fromHeadroomDb = fromHeadroomDb,
                    toHeadroomDb = targetHeadroomDb,
                    t = step.toDouble() / steps.toDouble(),
                )
                if (!ok && rampToken == token) {
                    releaseBackend()
                    statusSink(inactive("DynamicsProcessing 적용 실패", audioSessionId))
                }
            }
        }
        return true
    }

    private fun neutralizeBackendBeforeRelease() {
        val activeBackend = backend ?: return
        runCatching { applyTarget(activeBackend, emptyList(), 0f, immediate = true) }
    }

    private fun applyInterpolatedStep(
        activeBackend: OsEffectBackend,
        fromBands: List<AutoEqApprox.BandGain>,
        toBands: List<AutoEqApprox.BandGain>,
        fromHeadroomDb: Float,
        toHeadroomDb: Float,
        t: Double,
    ): Boolean {
        val bands = interpolateBands(fromBands, toBands, t)
        val headroom = interpolate(fromHeadroomDb, toHeadroomDb, t)
        if (!activeBackend.apply(bands, headroom)) return false
        lastAppliedBands = bands
        lastAppliedHeadroomDb = headroom
        return true
    }

    private fun neutralBands(activeBackend: OsEffectBackend): List<AutoEqApprox.BandGain> =
        activeBackend.bandCenters().map { center ->
            AutoEqApprox.BandGain(centerHz = center, gainDb = 0.0)
        }

    private fun rampSteps(
        fromBands: List<AutoEqApprox.BandGain>,
        toBands: List<AutoEqApprox.BandGain>,
        fromHeadroomDb: Float,
        toHeadroomDb: Float,
    ): Int {
        val maxBandDelta = fromBands.zip(toBands)
            .maxOfOrNull { (from, to) -> kotlin.math.abs(from.gainDb - to.gainDb) }
            ?: 0.0
        val headroomDelta = kotlin.math.abs((fromHeadroomDb - toHeadroomDb).toDouble())
        val maxDelta = max(maxBandDelta, headroomDelta)
        return ceil(maxDelta / DB_PER_RAMP_STEP).toInt().coerceIn(1, MAX_RAMP_STEPS)
    }

    private fun interpolateBands(
        fromBands: List<AutoEqApprox.BandGain>,
        toBands: List<AutoEqApprox.BandGain>,
        t: Double,
    ): List<AutoEqApprox.BandGain> =
        fromBands.zip(toBands).map { (from, to) ->
            AutoEqApprox.BandGain(
                centerHz = interpolate(from.centerHz, to.centerHz, t),
                gainDb = interpolate(from.gainDb, to.gainDb, t),
            )
        }

    private fun interpolate(from: Double, to: Double, t: Double): Double =
        from + (to - from) * t

    private fun interpolate(from: Float, to: Float, t: Double): Float =
        (from + (to - from) * t).toFloat()

    private companion object {
        const val MIN_HEADROOM_DB = -30f
        const val DB_PER_RAMP_STEP = 1.5
        const val MAX_RAMP_STEPS = 8
        const val RAMP_DURATION_MS = 48L
    }
}
