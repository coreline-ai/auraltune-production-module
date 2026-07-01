// PlaybackTelemetry.kt
// Bridges post-decode audio format (sample rate / bit depth) from the render-thread
// AuralTuneAudioProcessor — which now lives inside AuralTuneMediaService — to the UI-facing
// MusicPlayerController. Both sides reference the SAME instance via ServiceLocator, so the
// format telemetry never has to cross the MediaController/MediaSession (binder) boundary.
package com.coreline.auraltune.audio

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Process-scoped holder for the current playback audio format (shown in the player UI). */
class PlaybackTelemetry {

    /** Null fields = unknown (no media / not yet decoded). */
    data class Format(val sampleRateHz: Int? = null, val bitDepth: Int? = null)

    private val _format = MutableStateFlow(Format())
    val format: StateFlow<Format> = _format.asStateFlow()

    /** Called from the audio processor's format callback (render thread → StateFlow). */
    fun update(sampleRateHz: Int?, bitDepth: Int?) {
        _format.value = Format(sampleRateHz, bitDepth)
    }

    fun clear() {
        _format.value = Format()
    }
}
