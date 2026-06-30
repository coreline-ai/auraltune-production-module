package com.coreline.auraltune.data

import android.content.Context
import androidx.annotation.StringRes
import com.coreline.auraltune.R
import com.coreline.auraltune.audio.eq.GraphicEqBands

/**
 * Built-in 20-band graphic EQ "기본 프로파일" presets.
 *
 * These ship in BOTH debug and release (they replace the former debug-only `seedTestPresets`
 * fixtures). They live in code — never persisted to DataStore — and are merged ahead of the
 * user's saved presets by the ViewModel. The [PREFIX] id marks them so the UI hides the delete
 * action and the ViewModel rejects delete requests for them (defaults are non-deletable).
 *
 * A preset is fully described by its 20-band gains (the freq/Q grid is fixed by [GraphicEqBands]);
 * gains are clamped to the live limit at load time (same path as user presets).
 */
object GraphicEqPresetCatalog {
    const val PREFIX = "builtin:graphic:"

    private data class Template(
        val id: String,
        @StringRes val nameRes: Int,
        val gains: List<Float>,
    )

    /** Build a length-[GraphicEqBands.COUNT] gain list, zero-padding any unspecified trailing bands. */
    private fun gains(vararg v: Float): List<Float> =
        FloatArray(GraphicEqBands.COUNT) { i -> v.getOrElse(i) { 0f } }.toList()

    private val templates: List<Template> = listOf(
        Template(
            id = "bass_boost",
            nameRes = R.string.graphic_preset_bass_boost,
            gains = gains(8f, 8f, 7f, 6f, 5f, 4f, 3f, 2f, 1f),
        ),
        Template(
            id = "v_shape",
            nameRes = R.string.graphic_preset_v_shape,
            gains = gains(6f, 6f, 5f, 4f, 2f, 0f, -2f, -3f, -4f, -4f, -3f, -2f, 0f, 2f, 3f, 4f, 5f, 6f, 6f, 6f),
        ),
        Template(
            id = "treble_cut",
            nameRes = R.string.graphic_preset_treble_cut,
            gains = gains(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, -1f, -2f, -3f, -4f, -5f, -6f, -7f, -8f, -8f),
        ),
    )

    fun isBuiltInId(id: String): Boolean = id.startsWith(PREFIX)

    /** Number of built-in presets (context-free, for tests / sizing). */
    val count: Int get() = templates.size

    fun builtIns(context: Context): List<GraphicEqPreset> =
        templates.map { t ->
            GraphicEqPreset(
                id = PREFIX + t.id,
                name = context.getString(t.nameRes),
                gainsDb = t.gains,
                createdAtMs = 0L,
                updatedAtMs = 0L,
            )
        }
}
