// OpraEngineAdapter.kt
// Phase 4 — the ONLY bridge between the two isolated data modules. Lives in :app (which depends on
// both :opra-data and :autoeq-data); neither data module depends on the other. Converts an OPRA
// profile into the engine's existing input model (AutoEqProfile), so OPRA presets reuse the exact
// shared DSP apply path (DeviceAutoEqManager) AND the response graph (EqGraphView) unchanged.
package com.coreline.auraltune.opra

import com.coreline.autoeq.model.AutoEqFilter
import com.coreline.autoeq.model.AutoEqProfile
import com.coreline.autoeq.model.AutoEqSource
import com.coreline.auraltune.opra.model.OpraEqProfile

/**
 * Convert to the engine-input [AutoEqProfile], or null when the profile is not applicable
 * (unsupported filter type, >MAX_FILTERS bands, or invalid values — see [OpraEqProfile.isSupported]).
 * No partial conversion: callers must treat null as "cannot apply".
 */
fun OpraEqProfile.toAutoEqProfile(): AutoEqProfile? {
    if (!isSupported) return null
    val engineFilters = filters.map { f ->
        val engineType = f.type.toEngine() ?: return null // defensive; isSupported already checked
        AutoEqFilter(
            type = engineType,
            frequency = f.frequencyHz,
            gainDB = f.gainDb.toFloat(),
            q = f.q,
        )
    }
    return AutoEqProfile(
        id = id,
        name = profileName,
        source = AutoEqSource.FETCHED,
        measuredBy = author,
        preampDB = preampDb,
        filters = engineFilters,
    ).validated()
}
