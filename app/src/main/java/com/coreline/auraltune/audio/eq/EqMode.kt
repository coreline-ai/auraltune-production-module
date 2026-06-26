// EqMode.kt
// User EQ editing mode for the Manual chain.
//   GRAPHIC    — fixed 20-band graphic EQ (peaking only, shared global Q scale).
//   PARAMETRIC — free-form parametric bands (per-band type/freq/gain/Q, graph-drag editor).
// Persisted as the enum name (see SettingsStore.eqMode). Both modes drive the SAME native
// Manual chain — only the way the user authors the band set differs.
package com.coreline.auraltune.audio.eq

enum class EqMode {
    GRAPHIC,
    PARAMETRIC;

    companion object {
        /** Tolerant parse for the persisted string (unknown/legacy → GRAPHIC). */
        fun fromKey(key: String?): EqMode =
            key?.let { k -> entries.firstOrNull { it.name == k } } ?: GRAPHIC
    }
}
