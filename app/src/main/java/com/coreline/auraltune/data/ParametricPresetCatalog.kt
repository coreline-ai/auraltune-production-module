package com.coreline.auraltune.data

import android.content.Context
import androidx.annotation.StringRes
import com.coreline.audio.EqFilterType
import com.coreline.auraltune.R

data class BuiltInParametricPresetTemplate(
    val id: String,
    @StringRes val nameRes: Int,
    @StringRes val categoryRes: Int,
    val bands: List<ParametricPresetBand>,
) {
    fun toPreset(context: Context): ParametricEqPreset =
        ParametricEqPreset(
            id = id,
            name = context.getString(nameRes),
            category = context.getString(categoryRes),
            source = ParametricPresetSource.BUILT_IN,
            bands = bands,
            createdAtMs = 0L,
            updatedAtMs = 0L,
        ).normalized()
}

object ParametricPresetCatalog {
    private const val PREFIX = "builtin:parametric:"

    val templates: List<BuiltInParametricPresetTemplate> = listOf(
        template(
            id = "bass_boost",
            nameRes = R.string.parametric_preset_bass_boost,
            categoryRes = R.string.parametric_preset_category_bass,
            lowShelf(80f, 3.0f, 0.70f),
        ),
        template(
            id = "bass_cleanup",
            nameRes = R.string.parametric_preset_bass_cleanup,
            categoryRes = R.string.parametric_preset_category_bass,
            peaking(250f, -2.5f, 1.00f),
        ),
        template(
            id = "warm_tone",
            nameRes = R.string.parametric_preset_warm_tone,
            categoryRes = R.string.parametric_preset_category_tone,
            lowShelf(120f, 2.0f, 0.70f),
            highShelf(8_000f, -1.5f, 0.70f),
        ),
        template(
            id = "clarity_boost",
            nameRes = R.string.parametric_preset_clarity_boost,
            categoryRes = R.string.parametric_preset_category_clarity,
            highShelf(7_000f, 2.0f, 0.70f),
        ),
        template(
            id = "vocal_forward",
            nameRes = R.string.parametric_preset_vocal_forward,
            categoryRes = R.string.parametric_preset_category_vocal,
            peaking(1_500f, 2.0f, 1.00f),
            peaking(4_000f, 1.5f, 1.20f),
        ),
        template(
            id = "vocal_back",
            nameRes = R.string.parametric_preset_vocal_back,
            categoryRes = R.string.parametric_preset_category_vocal,
            peaking(2_500f, -2.0f, 1.00f),
        ),
        template(
            id = "sibilance_reduction",
            nameRes = R.string.parametric_preset_sibilance_reduction,
            categoryRes = R.string.parametric_preset_category_fix,
            peaking(7_200f, -3.0f, 3.00f),
        ),
        template(
            id = "fatigue_reduction",
            nameRes = R.string.parametric_preset_fatigue_reduction,
            categoryRes = R.string.parametric_preset_category_fix,
            highShelf(6_000f, -2.0f, 0.80f),
            peaking(3_500f, -1.5f, 1.50f),
        ),
        template(
            id = "de_muffle",
            nameRes = R.string.parametric_preset_de_muffle,
            categoryRes = R.string.parametric_preset_category_fix,
            peaking(300f, -2.0f, 1.00f),
            peaking(3_000f, 1.5f, 1.00f),
        ),
        template(
            id = "movie_dialogue",
            nameRes = R.string.parametric_preset_movie_dialogue,
            categoryRes = R.string.parametric_preset_category_content,
            peaking(180f, -1.5f, 1.00f),
            peaking(2_000f, 2.0f, 1.00f),
            peaking(4_000f, 1.0f, 1.20f),
        ),
        template(
            id = "fps_footsteps",
            nameRes = R.string.parametric_preset_fps_footsteps,
            categoryRes = R.string.parametric_preset_category_game,
            peaking(250f, -2.0f, 1.00f),
            peaking(2_500f, 3.0f, 1.20f),
            peaking(7_000f, 1.5f, 1.00f),
        ),
        template(
            id = "live_space",
            nameRes = R.string.parametric_preset_live_space,
            categoryRes = R.string.parametric_preset_category_space,
            lowShelf(100f, 1.5f, 0.70f),
            peaking(500f, -1.0f, 1.00f),
            peaking(2_500f, -1.0f, 1.00f),
            highShelf(9_000f, 1.5f, 0.70f),
        ),
        // 공간감을 더 확실히: 저역 토대 + 로우미드 정리 + 미드 스쿱(거리감) +
        // 프레즌스 후퇴 + 강한 에어. live_space보다 스쿱/에어를 키워 무대가 넓고
        // 멀게 들리도록 한다(주파수 셰이핑 기반 — 실제 스테레오 확장은 아님).
        template(
            id = "wide_space",
            nameRes = R.string.parametric_preset_wide_space,
            categoryRes = R.string.parametric_preset_category_space,
            lowShelf(110f, 2.5f, 0.70f),
            peaking(350f, -2.5f, 1.10f),
            peaking(1_600f, -2.5f, 1.20f),
            peaking(4_000f, -2.0f, 1.40f),
            highShelf(11_000f, 3.5f, 0.70f),
        ),
    )

    fun builtIns(context: Context): List<ParametricEqPreset> =
        templates.map { it.toPreset(context) }

    fun isBuiltInId(id: String): Boolean = id.startsWith(PREFIX)

    private fun template(
        id: String,
        @StringRes nameRes: Int,
        @StringRes categoryRes: Int,
        vararg bands: ParametricPresetBand,
    ): BuiltInParametricPresetTemplate =
        BuiltInParametricPresetTemplate(
            id = PREFIX + id,
            nameRes = nameRes,
            categoryRes = categoryRes,
            bands = bands.toList(),
        )

    private fun peaking(freqHz: Float, gainDb: Float, q: Float): ParametricPresetBand =
        band(EqFilterType.PEAKING.nativeId, freqHz, gainDb, q)

    private fun lowShelf(freqHz: Float, gainDb: Float, q: Float): ParametricPresetBand =
        band(EqFilterType.LOW_SHELF.nativeId, freqHz, gainDb, q)

    private fun highShelf(freqHz: Float, gainDb: Float, q: Float): ParametricPresetBand =
        band(EqFilterType.HIGH_SHELF.nativeId, freqHz, gainDb, q)

    private fun band(type: Int, freqHz: Float, gainDb: Float, q: Float): ParametricPresetBand =
        ParametricPresetBand(type = type, freqHz = freqHz, gainDb = gainDb, q = q).normalized()
}
