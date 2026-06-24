// OpraFilterType.kt
// OPRA parametric-EQ filter types, normalized from the raw OPRA "eq" band tokens
// (e.g. "PK", "LSC", "HSC"). [toEngine] maps to the DSP engine's supported set; types the
// engine cannot realize map to null so the profile-level "unsupported -> exclude" policy can
// drop them (Phase 2). The engine itself is NOT extended for OPRA (shared, unchanged).
package com.coreline.auraltune.opra.model

import com.coreline.audio.EqFilterType

enum class OpraFilterType {
    PEAKING,
    LOW_SHELF,
    HIGH_SHELF,
    HIGH_PASS,
    LOW_PASS,
    NOTCH,
    BAND_PASS,
    UNKNOWN,
    ;

    /** Engine equivalent, or null if the engine does not support this type. */
    fun toEngine(): EqFilterType? = when (this) {
        PEAKING -> EqFilterType.PEAKING
        LOW_SHELF -> EqFilterType.LOW_SHELF
        HIGH_SHELF -> EqFilterType.HIGH_SHELF
        HIGH_PASS -> EqFilterType.HIGH_PASS
        LOW_PASS, NOTCH, BAND_PASS, UNKNOWN -> null
    }

    /** True when this filter can be applied by the shared DSP engine. */
    val isSupported: Boolean get() = toEngine() != null

    companion object {
        /** Parse an OPRA band token (case-insensitive). Common AutoEq/OPRA tokens incl. PK/LSC/HSC. */
        fun fromToken(token: String?): OpraFilterType = when (token?.trim()?.uppercase()) {
            "PK", "PEAKING", "PEQ" -> PEAKING
            "LS", "LSC", "LSQ", "LOWSHELF", "LOW_SHELF" -> LOW_SHELF
            "HS", "HSC", "HSQ", "HIGHSHELF", "HIGH_SHELF" -> HIGH_SHELF
            "HP", "HPQ", "HIGHPASS", "HIGH_PASS" -> HIGH_PASS
            "LP", "LPQ", "LOWPASS", "LOW_PASS" -> LOW_PASS
            "NO", "NOTCH" -> NOTCH
            "BP", "BANDPASS", "BAND_PASS" -> BAND_PASS
            else -> UNKNOWN
        }
    }
}
