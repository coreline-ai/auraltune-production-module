// ToneEqPreset.kt
// Persistence model for a tone-EQ preset (TONE EqMode). A tone preset is fully described by its
// 3 gains [bass, mid, treble] — the freq/Q grid is fixed by the tone control. Built-in defaults
// use a "builtin:tone:" id (see ToneEqPresetCatalog) and are non-deletable; user presets use a
// random UUID. Length/range normalization happens in SettingsStore on encode/decode.
//
// Covered by the `com.coreline.auraltune.data.**` ProGuard keep rule.
package com.coreline.auraltune.data

import kotlinx.serialization.Serializable

@Serializable
data class ToneEqPreset(
    val id: String,
    val name: String,
    /** [bass, mid, treble] in dB — expected length 3. */
    val gainsDb: List<Float>,
    val createdAtMs: Long,
    val updatedAtMs: Long,
)
