package com.coreline.auraltune.audio.eq

import com.coreline.auraltune.data.ParametricBand

/**
 * Pure update rule for the parametric editor.
 *
 * Type edits preserve gain/Q/frequency, but a type change back into a gain-bearing filter must
 * re-clamp the hidden gain that may have been stored while the band was a high-pass.
 */
internal fun updateParametricBandModel(
    band: ParametricBand,
    type: Int? = null,
    freqHz: Float? = null,
    gainDb: Float? = null,
    q: Float? = null,
    gainLimitDb: Float,
): ParametricBand =
    band.copy(
        type = type ?: band.type,
        freqHz = freqHz ?: band.freqHz,
        gainDb = when {
            gainDb != null -> gainDb.coerceIn(-gainLimitDb, gainLimitDb)
            type != null && type != band.type -> band.gainDb.coerceIn(-gainLimitDb, gainLimitDb)
            else -> band.gainDb
        },
        q = q ?: band.q,
    ).normalized()
