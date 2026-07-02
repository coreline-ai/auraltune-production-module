package com.coreline.auraltune.audio

import android.os.Handler
import android.os.Looper
import com.coreline.auraltune.audio.audiofx.PlayerDynamicsEqStatus
import com.coreline.auraltune.audio.eq.BiquadSpec
import com.coreline.auraltune.data.PlaybackProcessingMode
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide switchboard for the playback processing backend.
 *
 * The media service owns the player/audio session, while the UI owns user choices and selected
 * profiles. This small shared state keeps those two lifecycles synchronized without binding the
 * service to a retained ViewModel instance.
 */
class PlaybackProcessingState(
    private val scheduleNativeGateChange: (delayMs: Long, action: () -> Unit) -> Unit = { delayMs, action ->
        Handler(Looper.getMainLooper()).postDelayed(action, delayMs)
    },
) {
    private val nativeEngineEnabled = AtomicBoolean(true)
    private var nativeGateToken = 0L

    private val _mode = MutableStateFlow(PlaybackProcessingMode.AURAL_TUNE)
    val mode: StateFlow<PlaybackProcessingMode> = _mode.asStateFlow()

    private val _targetSpecs = MutableStateFlow<List<BiquadSpec>>(emptyList())
    val targetSpecs: StateFlow<List<BiquadSpec>> = _targetSpecs.asStateFlow()

    private val _targetHeadroomDb = MutableStateFlow(0f)
    val targetHeadroomDb: StateFlow<Float> = _targetHeadroomDb.asStateFlow()

    private val _dynamicsStatus = MutableStateFlow(PlayerDynamicsEqStatus.idle())
    val dynamicsStatus: StateFlow<PlayerDynamicsEqStatus> = _dynamicsStatus.asStateFlow()

    fun setMode(mode: PlaybackProcessingMode) {
        _mode.value = mode
        val token = ++nativeGateToken
        if (mode == PlaybackProcessingMode.AURAL_TUNE) {
            nativeEngineEnabled.set(true)
        } else {
            scheduleNativeGateChange(NATIVE_GATE_FADE_OUT_MS) {
                if (nativeGateToken == token && _mode.value != PlaybackProcessingMode.AURAL_TUNE) {
                    nativeEngineEnabled.set(false)
                }
            }
        }
    }

    fun useNativeEngine(): Boolean = nativeEngineEnabled.get()

    fun setTargetSpecs(specs: List<BiquadSpec>, headroomDb: Float = 0f) {
        _targetSpecs.value = specs
        _targetHeadroomDb.value = headroomDb
    }

    fun setDynamicsStatus(status: PlayerDynamicsEqStatus) {
        _dynamicsStatus.value = status
    }

    private companion object {
        const val NATIVE_GATE_FADE_OUT_MS = 520L
    }
}
