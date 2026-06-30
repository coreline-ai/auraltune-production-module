package com.coreline.auraltune.data

import android.content.Context
import androidx.annotation.StringRes
import com.coreline.auraltune.R

/**
 * Built-in tone (Bass/Mid/Treble) starting-point presets. Ship in BOTH debug and release; live in
 * code (never persisted) and are merged ahead of user presets by the ViewModel. The [PREFIX] id
 * marks them so the UI hides the delete action and the ViewModel rejects delete/save for them.
 * Each preset = 3 gains [bass, mid, treble]; clamped to the live limit at load time.
 */
object ToneEqPresetCatalog {
    const val PREFIX = "builtin:tone:"

    private data class Template(
        val id: String,
        @StringRes val nameRes: Int,
        val gains: List<Float>,
    )

    private val templates: List<Template> = listOf(
        Template("bass", R.string.tone_preset_bass, listOf(5f, 0f, 0f)),
        Template("vocal", R.string.tone_preset_vocal, listOf(0f, 3f, -1f)),
        Template("bright", R.string.tone_preset_bright, listOf(0f, 0f, 5f)),
        Template("vshape", R.string.tone_preset_vshape, listOf(5f, -2f, 5f)),
        Template("warm", R.string.tone_preset_warm, listOf(4f, 0f, -3f)),
    )

    fun isBuiltInId(id: String): Boolean = id.startsWith(PREFIX)

    /** Number of built-in tone presets (context-free, for tests / sizing). */
    val count: Int get() = templates.size

    fun builtIns(context: Context): List<ToneEqPreset> =
        templates.map { t ->
            ToneEqPreset(
                id = PREFIX + t.id,
                name = context.getString(t.nameRes),
                gainsDb = t.gains,
                createdAtMs = 0L,
                updatedAtMs = 0L,
            )
        }
}
