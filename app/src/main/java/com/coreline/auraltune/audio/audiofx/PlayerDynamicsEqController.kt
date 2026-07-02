package com.coreline.auraltune.audio.audiofx

import com.coreline.auraltune.audio.eq.BiquadSpec
import com.coreline.auraltune.data.PlaybackProcessingMode
import java.io.Closeable

data class PlayerDynamicsEqStatus(
    val active: Boolean,
    val backend: String?,
    val bandCount: Int,
    val audioSessionId: Int?,
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
) : Closeable {
    private var mode = PlaybackProcessingMode.AURAL_TUNE
    private var audioSessionId: Int? = null
    private var backend: OsEffectBackend? = null
    private var backendSessionId: Int? = null
    private var targetSpecs: List<BiquadSpec> = emptyList()

    fun setMode(mode: PlaybackProcessingMode) {
        this.mode = mode
        reconcile()
    }

    fun onAudioSessionIdChanged(audioSessionId: Int) {
        if (this.audioSessionId == audioSessionId) return
        releaseBackend()
        this.audioSessionId = audioSessionId.takeIf { it > 0 }
        reconcile()
    }

    fun setTargetSpecs(specs: List<BiquadSpec>) {
        targetSpecs = specs
        reconcile()
    }

    private fun reconcile() {
        if (mode != PlaybackProcessingMode.ANDROID_DYNAMICS) {
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

        if (targetSpecs.isEmpty()) {
            releaseBackend()
            statusSink(inactive("적용할 프로파일 없음", session))
            return
        }

        val activeBackend = ensureBackend(session) ?: return
        val fit = AutoEqApprox.fit(
            targetFilters = targetSpecs,
            bandCenters = activeBackend.bandCenters(),
            sampleRate = sampleRateProvider().coerceAtLeast(1.0),
        )
        if (!activeBackend.apply(fit.bands)) {
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
            rmsErrorDb = null,
            maxErrorDb = null,
            message = message,
        )

    private fun releaseBackend() {
        backend?.release()
        backend = null
        backendSessionId = null
    }

    override fun close() {
        releaseBackend()
        statusSink(PlayerDynamicsEqStatus.idle())
    }
}
