package com.coreline.auraltune.audio

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
class PlaybackProcessingState {
    private val nativeEngineEnabled = AtomicBoolean(true)

    private val _mode = MutableStateFlow(PlaybackProcessingMode.AURAL_TUNE)
    val mode: StateFlow<PlaybackProcessingMode> = _mode.asStateFlow()

    private val _targetSpecs = MutableStateFlow<List<BiquadSpec>>(emptyList())
    val targetSpecs: StateFlow<List<BiquadSpec>> = _targetSpecs.asStateFlow()

    private val _dynamicsStatus = MutableStateFlow(PlayerDynamicsEqStatus.idle())
    val dynamicsStatus: StateFlow<PlayerDynamicsEqStatus> = _dynamicsStatus.asStateFlow()

    fun setMode(mode: PlaybackProcessingMode) {
        _mode.value = mode
        nativeEngineEnabled.set(mode == PlaybackProcessingMode.AURAL_TUNE)
    }

    fun useNativeEngine(): Boolean = nativeEngineEnabled.get()

    fun setTargetSpecs(specs: List<BiquadSpec>) {
        _targetSpecs.value = specs
    }

    fun setDynamicsStatus(status: PlayerDynamicsEqStatus) {
        _dynamicsStatus.value = status
    }
}
