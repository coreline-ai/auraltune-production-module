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
    HIGH_SHELF(2),

    /**
     * 2nd-order Butterworth high-pass filter (RBJ cookbook). The `gainDB`
     * argument is **ignored** for this filter type — pass 0.0f. Q controls
     * shape: 1/√2 ≈ 0.707 = standard Butterworth (-3 dB at fc).
     *
     * Note: AutoEq catalog profiles never use HPF — the parser explicitly
     * rejects "HP" / "LP" tokens as legacy REW format. This type is exposed
     * for direct callers and as a building block for internal stages
     * (e.g. K-weighting in the loudness modules).
     */
    HIGH_PASS(3);

    companion object {
        /**
         * Parse the EqualizerAPO / AutoEq token (PK / PEQ / LS / LSC / HS / HSC).
         * Returns null for unsupported tokens (e.g. legacy "Bell", "BP", "NO", "HP").
         * `HIGH_PASS` is intentionally NOT parseable here — see KDoc.
         */
        fun fromString(s: String): EqFilterType? = when (s.uppercase()) {
            "PK", "PEQ" -> PEAKING
            "LS", "LSC" -> LOW_SHELF
            "HS", "HSC" -> HIGH_SHELF
            else -> null
        }
    }
}
