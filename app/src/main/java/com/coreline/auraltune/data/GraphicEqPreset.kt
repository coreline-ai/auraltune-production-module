// GraphicEqPreset.kt
// Phase 3 (dev-plan 110525): persistence model for the user's 20-band graphic EQ.
//
// We persist ONLY the raw band gains (dB) — never the derived BiquadSpec, native coeffs, or
// AutoEQ profile filters. The 20-band frequency/Q grid is fixed (GraphicEqBands), so a preset
// is fully described by its gains. Stored as JSON in SettingsStore (NOT the AutoEQ catalog DB).
package com.coreline.auraltune.data

import kotlinx.serialization.Serializable

/**
 * A named graphic-EQ preset.
 *
 * @property id Stable unique id (UUID-like string).
 * @property name User-facing name.
 * @property gainsDb Per-band gain in dB. Expected size = [com.coreline.auraltune.audio.eq.GraphicEqBands.COUNT];
 *   callers must defensively normalize length/NaN (see [SettingsStore]).
 * @property createdAtMs / [updatedAtMs] epoch millis (System.currentTimeMillis()).
 * @property version schema version for forward migration.
 */
@Serializable
data class GraphicEqPreset(
    val id: String,
    val name: String,
    val gainsDb: List<Float>,
    val createdAtMs: Long,
    val updatedAtMs: Long,
    val version: Int = 1,
)
