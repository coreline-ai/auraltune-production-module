package com.coreline.auraltune.data

import kotlinx.serialization.Serializable

/**
 * Source of a parametric EQ preset.
 *
 * BUILT_IN presets are AuralTune "starting points" bundled with the app. USER
 * presets are user-saved edits. Built-ins must never be deleted or overwritten.
 */
@Serializable
enum class ParametricPresetSource {
    BUILT_IN,
    USER;

    companion object {
        fun fromKey(key: String?): ParametricPresetSource? =
            key?.let { k -> entries.firstOrNull { it.name == k } }
    }
}

/** One persisted band inside a parametric preset, without an editor handle id. */
@Serializable
data class ParametricPresetBand(
    val type: Int,
    val freqHz: Float,
    val gainDb: Float,
    val q: Float,
) {
    fun normalized(): ParametricPresetBand {
        val band = ParametricBand(
            id = "preset",
            type = type,
            freqHz = freqHz,
            gainDb = gainDb,
            q = q,
        ).normalized()
        return ParametricPresetBand(
            type = band.type,
            freqHz = band.freqHz,
            gainDb = band.gainDb,
            q = band.q,
        )
    }

    fun toParametricBand(id: String): ParametricBand =
        ParametricBand(
            id = id,
            type = type,
            freqHz = freqHz,
            gainDb = gainDb,
            q = q,
        ).normalized()

    companion object {
        fun fromBand(band: ParametricBand): ParametricPresetBand =
            ParametricPresetBand(
                type = band.type,
                freqHz = band.freqHz,
                gainDb = band.gainDb,
                q = band.q,
            ).normalized()
    }
}

/**
 * A named parametric EQ preset.
 *
 * Built-in presets are shipped as "starting points"; they are not OPRA-derived
 * data and are not presented as verified/certified corrections.
 */
@Serializable
data class ParametricEqPreset(
    val id: String,
    val name: String,
    val category: String,
    val source: ParametricPresetSource,
    val bands: List<ParametricPresetBand>,
    val createdAtMs: Long,
    val updatedAtMs: Long,
    val version: Int = 1,
) {
    fun normalized(): ParametricEqPreset =
        copy(
            name = name.trim().ifBlank { "시작점" },
            category = category.trim().ifBlank { "추천" },
            bands = bands
                .map { it.normalized() }
                .take(ParametricBand.MAX_BANDS),
        )

    fun toParametricBands(idFactory: () -> String): List<ParametricBand> =
        normalized().bands.map { it.toParametricBand(idFactory()) }

    fun sameBandsAs(currentBands: List<ParametricBand>): Boolean {
        val presetBands = normalized().bands
        val current = currentBands
            .map { ParametricPresetBand.fromBand(it) }
            .take(ParametricBand.MAX_BANDS)
        return presetBands == current
    }
}
