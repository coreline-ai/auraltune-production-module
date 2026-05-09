package com.coreline.audio

/**
 * Mirror of the native EqFilterType enum.
 *
 * `nativeId` is the wire value passed to AudioEngine.updateAutoEq(filterTypes=...).
 * Keep these ordinals in sync with auraltune::audio::EqFilterType in C++.
 */
enum class EqFilterType(val nativeId: Int) {
    PEAKING(0),
    LOW_SHELF(1),
    HIGH_SHELF(2);

    companion object {
        /**
         * Parse the EqualizerAPO / AutoEq token (PK / PEQ / LS / LSC / HS / HSC).
         * Returns null for unsupported tokens (e.g. legacy "Bell", "BP", "NO").
         */
        fun fromString(s: String): EqFilterType? = when (s.uppercase()) {
            "PK", "PEQ" -> PEAKING
            "LS", "LSC" -> LOW_SHELF
            "HS", "HSC" -> HIGH_SHELF
            else -> null
        }
    }
}
