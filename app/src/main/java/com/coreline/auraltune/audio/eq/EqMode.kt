// EqMode.kt
// User EQ editing mode for the Manual chain.
//   TONE       — simple 3-band tone control (Bass low-shelf / Mid peak / Treble high-shelf).
//   GRAPHIC    — fixed 20-band graphic EQ (peaking only, shared global Q scale).
//   PARAMETRIC — free-form parametric bands (per-band type/freq/gain/Q, graph-drag editor).
// Persisted as the enum name (see SettingsStore.eqMode). All modes drive the SAME native
// Manual chain (mutually exclusive) — only the way the user authors the band set differs.
package com.coreline.auraltune.audio.eq

enum class EqMode {
    TONE,
    GRAPHIC,
    PARAMETRIC;

    companion object {
        /** Tolerant parse for the persisted string (unknown/legacy → GRAPHIC). */
        fun fromKey(key: String?): EqMode =
            key?.let { k -> entries.firstOrNull { it.name == k } } ?: GRAPHIC
    }
}
