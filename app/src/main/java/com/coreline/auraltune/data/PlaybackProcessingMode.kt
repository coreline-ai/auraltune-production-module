package com.coreline.auraltune.data

/**
 * Mutually-exclusive playback processing backend.
 *
 * AURAL_TUNE uses the in-app native DSP engine. ANDROID_DYNAMICS bypasses the
 * native DSP and attaches Android framework DynamicsProcessing to the player
 * audio session.
 */
enum class PlaybackProcessingMode(val key: String) {
    AURAL_TUNE("AURAL_TUNE"),
    ANDROID_DYNAMICS("ANDROID_DYNAMICS");

    companion object {
        fun fromKey(key: String?): PlaybackProcessingMode =
            entries.firstOrNull { it.key == key } ?: AURAL_TUNE
    }
}
