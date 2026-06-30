// ParametricEqView.kt
// Stage 3 — full parametric EQ with a graph-drag editor.
//   • Each band is a draggable point on the response graph: X = frequency, Y = gain.
//   • The selected band exposes a type dropdown (Peaking/LowShelf/HighShelf/HighPass)
//     and a Q slider. Bands can be added / removed (cap = ParametricBand.MAX_BANDS).
// The composite curve (parametric bands + AutoEQ profile) is computed in Kotlin via
// BiquadResponse, matching the native engine.
package com.coreline.auraltune.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.coreline.auraltune.R
import com.coreline.auraltune.audio.eq.BiquadResponse
import com.coreline.auraltune.audio.eq.EqMode
import com.coreline.auraltune.data.ParametricBand
import com.coreline.auraltune.data.ParametricEqPreset
import com.coreline.auraltune.data.ParametricPresetSource
import com.coreline.autoeq.model.AutoEqFilter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.log10
import kotlin.math.pow

private const val P_CURVE_POINTS = 180
private const val TYPE_HIGH_PASS = 3

/** Format a raw frequency for the readout: "120 Hz" / "1.5 kHz" (locale-stable). */
private fun formatHz(hz: Float): String =
    if (hz >= 1000f) String.format(Locale.US, "%.1f kHz", hz / 1000f)
    else String.format(Locale.US, "%d Hz", hz.toInt())

/** [그래픽 | 파라메트릭] 편집 모드 토글(2-세그먼트) + "내 EQ는 모든 소스 공통" 안내. */
@Composable
fun EqModeToggle(
    mode: EqMode,
    onSelect: (EqMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Text(
            stringResource(R.string.eq_mode_label),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.outline,
        )
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            GainLimitButton(
                label = stringResource(R.string.eq_mode_graphic),
                selected = mode == EqMode.GRAPHIC,
                onClick = { onSelect(EqMode.GRAPHIC) },
                modifier = Modifier.weight(1f),
            )
            GainLimitButton(
                label = stringResource(R.string.eq_mode_parametric),
                selected = mode == EqMode.PARAMETRIC,
                onClick = { onSelect(EqMode.PARAMETRIC) },
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            stringResource(R.string.eq_mode_global_caption),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
fun ParametricEqCard(
    bands: List<ParametricBand>,
    selectedId: String?,
    presets: List<ParametricEqPreset>,
    selectedPresetId: String?,
    selectedPresetSource: ParametricPresetSource?,
    presetDirty: Boolean,
    autoEqFilters: List<AutoEqFilter>,
    gainLimitDb: Float,
    sampleRate: Double,
    onApplyPreset: (String, ParametricPresetSource) -> Unit,
    onSavePreset: (String) -> Unit,
    onDeleteUserPreset: (String) -> Unit,
    onAddBand: () -> Unit,
    onSelectBand: (String?) -> Unit,
    onDragBand: (String, Float, Float) -> Unit,
    onChangeType: (String, Int) -> Unit,
    onChangeQ: (String, Float) -> Unit,
    onRemoveBand: (String) -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showSaveDialog by remember { mutableStateOf(false) }
    AuralTunePanel(modifier = modifier, elevated = true) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.parametric_eq_title),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    stringResource(R.string.parametric_eq_count, bands.size, ParametricBand.MAX_BANDS),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(10.dp))

            ParametricPresetPicker(
                presets = presets,
                selectedPresetId = selectedPresetId,
                selectedPresetSource = selectedPresetSource,
                presetDirty = presetDirty,
                hasBands = bands.isNotEmpty(),
                onApplyPreset = onApplyPreset,
                onSavePreset = { showSaveDialog = true },
                onDeleteUserPreset = onDeleteUserPreset,
            )

            if (showSaveDialog) {
                SaveParametricPresetDialog(
                    onConfirm = { name ->
                        showSaveDialog = false
                        onSavePreset(name)
                    },
                    onDismiss = { showSaveDialog = false },
                )
            }

            Spacer(Modifier.height(10.dp))

            ParametricGraphEditor(
                bands = bands,
                selectedId = selectedId,
                autoEqFilters = autoEqFilters,
                yHalfDb = gainLimitDb,
                sampleRate = sampleRate,
                onSelectBand = onSelectBand,
                onDragBand = onDragBand,
            )

            Spacer(Modifier.height(6.dp))
            GraphLegend(hasProfile = autoEqFilters.isNotEmpty())

            Spacer(Modifier.height(8.dp))
            // 선택 컨트롤 영역 높이를 고정해, 밴드를 선택/해제할 때 아래 [추가|리셋] 줄이 점프하지 않게.
            Box(modifier = Modifier.fillMaxWidth().heightIn(min = 128.dp)) {
                val selected = bands.firstOrNull { it.id == selectedId }
                if (selected != null) {
                    SelectedBandControls(
                        band = selected,
                        onChangeType = onChangeType,
                        onChangeQ = onChangeQ,
                        onRemove = onRemoveBand,
                    )
                } else {
                    Text(
                        stringResource(
                            if (bands.isEmpty()) R.string.parametric_eq_empty
                            else R.string.parametric_eq_select_hint,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = onAddBand,
                    enabled = bands.size < ParametricBand.MAX_BANDS,
                    modifier = Modifier.weight(1f).height(40.dp),
                    shape = MaterialTheme.shapes.medium,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    ),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
                ) {
                    Icon(
                        Icons.Default.Add,
                        contentDescription = stringResource(R.string.parametric_eq_add),
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.parametric_eq_add), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                OutlinedButton(
                    onClick = onReset,
                    enabled = bands.isNotEmpty(),
                    modifier = Modifier.height(40.dp),
                    shape = MaterialTheme.shapes.medium,
                    border = eqButtonBorder(selected = false),
                    colors = eqOutlinedButtonColors(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp),
                ) {
                    Text(stringResource(R.string.graphic_eq_reset), style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

@Composable
private fun ParametricPresetPicker(
    presets: List<ParametricEqPreset>,
    selectedPresetId: String?,
    selectedPresetSource: ParametricPresetSource?,
    presetDirty: Boolean,
    hasBands: Boolean,
    onApplyPreset: (String, ParametricPresetSource) -> Unit,
    onSavePreset: () -> Unit,
    onDeleteUserPreset: (String) -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    var selectedCategory by remember(presets) { mutableStateOf<String?>(null) }
    val categories = remember(presets) { presets.map { it.category }.distinct() }
    val selectedPreset = presets.firstOrNull {
        it.id == selectedPresetId && it.source == selectedPresetSource
    }
    val statusLabel = when {
        selectedPreset != null && presetDirty ->
            stringResource(R.string.parametric_preset_status_modified, selectedPreset.name)
        selectedPreset != null -> selectedPreset.name
        hasBands -> stringResource(R.string.parametric_preset_status_custom)
        else -> stringResource(R.string.parametric_preset_pick_placeholder)
    }
    val filtered = if (selectedCategory == null) presets else presets.filter { it.category == selectedCategory }

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            stringResource(R.string.parametric_preset_starting_point),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.outline,
        )
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            GainLimitButton(
                label = stringResource(R.string.parametric_preset_category_recommended),
                selected = selectedCategory == null,
                onClick = { selectedCategory = null },
            )
            categories.forEach { category ->
                GainLimitButton(
                    label = category,
                    selected = selectedCategory == category,
                    onClick = { selectedCategory = category },
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(modifier = Modifier.weight(1f)) {
                OutlinedButton(
                    onClick = { menuOpen = true },
                    modifier = Modifier.fillMaxWidth().height(40.dp),
                    shape = MaterialTheme.shapes.medium,
                    border = eqButtonBorder(selected = selectedPreset != null && !presetDirty),
                    colors = if (selectedPreset != null && !presetDirty) {
                        eqSelectedButtonColors()
                    } else {
                        eqOutlinedButtonColors()
                    },
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
                ) {
                    Text(
                        statusLabel,
                        modifier = Modifier.weight(1f, fill = false),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.width(6.dp))
                    Icon(
                        imageVector = Icons.Default.ExpandMore,
                        contentDescription = stringResource(R.string.parametric_preset_open),
                        modifier = Modifier.size(18.dp),
                    )
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    if (filtered.isEmpty()) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.parametric_preset_empty)) },
                            onClick = {},
                            enabled = false,
                        )
                    }
                    filtered.forEach { preset ->
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(preset.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        if (preset.source == ParametricPresetSource.BUILT_IN) {
                                            stringResource(R.string.parametric_preset_source_builtin)
                                        } else {
                                            stringResource(R.string.parametric_preset_source_user)
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            },
                            onClick = {
                                menuOpen = false
                                onApplyPreset(preset.id, preset.source)
                            },
                            trailingIcon = if (preset.source == ParametricPresetSource.USER) {
                                {
                                    TextButton(onClick = { onDeleteUserPreset(preset.id) }) {
                                        Text(stringResource(R.string.graphic_eq_delete))
                                    }
                                }
                            } else {
                                null
                            },
                        )
                    }
                }
            }
            Button(
                onClick = onSavePreset,
                enabled = hasBands,
                modifier = Modifier.height(40.dp),
                shape = MaterialTheme.shapes.medium,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                ),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Save,
                    contentDescription = stringResource(R.string.parametric_preset_save),
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.graphic_eq_save), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(R.string.parametric_preset_caption),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SaveParametricPresetDialog(
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.parametric_preset_save_title)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                label = { Text(stringResource(R.string.graphic_eq_preset_name)) },
            )
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(name) },
                enabled = name.isNotBlank(),
            ) { Text(stringResource(R.string.graphic_eq_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}

/** Small legend under the graph: solid = 내 EQ, dashed = 프로파일(있을 때만), dot = 밴드. */
@Composable
private fun GraphLegend(hasProfile: Boolean) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LegendSwatch(stringResource(R.string.legend_my_eq)) {
            drawLine(cs.secondaryContainer, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), strokeWidth = 4f)
        }
        if (hasProfile) {
            LegendSwatch(stringResource(R.string.legend_profile)) {
                drawLine(
                    cs.onSurfaceVariant.copy(alpha = 0.72f),
                    Offset(0f, size.height / 2), Offset(size.width, size.height / 2),
                    strokeWidth = 3f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 5f)),
                )
            }
        }
        LegendSwatch(stringResource(R.string.legend_band)) {
            drawCircle(cs.primary, radius = size.height / 2.4f, center = Offset(size.width / 2, size.height / 2))
        }
    }
}

@Composable
private fun LegendSwatch(label: String, draw: DrawScope.() -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Canvas(Modifier.size(width = 18.dp, height = 10.dp)) { draw() }
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Per-band controls for the currently selected band: type dropdown + Q slider + delete. */
@Composable
private fun SelectedBandControls(
    band: ParametricBand,
    onChangeType: (String, Int) -> Unit,
    onChangeQ: (String, Float) -> Unit,
    onRemove: (String) -> Unit,
) {
    var typeMenuOpen by remember { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxWidth()) {
        // Readout: freq · gain (gain hidden for high-pass). Raw Hz/kHz, locale-stable.
        val readout = if (band.type == TYPE_HIGH_PASS) {
            formatHz(band.freqHz)
        } else {
            formatHz(band.freqHz) + "  ·  " + String.format(Locale.US, "%+.1f dB", band.gainDb)
        }
        Text(readout, style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(modifier = Modifier.weight(1f)) {
                OutlinedButton(
                    onClick = { typeMenuOpen = true },
                    modifier = Modifier.fillMaxWidth().height(40.dp),
                    shape = MaterialTheme.shapes.medium,
                    border = eqButtonBorder(selected = true),
                    colors = eqSelectedButtonColors(),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
                ) {
                    Text(
                        filterTypeLabel(band.type),
                        modifier = Modifier.weight(1f, fill = false),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.width(6.dp))
                    Icon(Icons.Default.ExpandMore, contentDescription = stringResource(R.string.parametric_eq_type), modifier = Modifier.size(18.dp))
                }
                DropdownMenu(expanded = typeMenuOpen, onDismissRequest = { typeMenuOpen = false }) {
                    for (t in 0..3) {
                        DropdownMenuItem(
                            text = { Text(filterTypeLabel(t)) },
                            onClick = { typeMenuOpen = false; onChangeType(band.id, t) },
                        )
                    }
                }
            }
            OutlinedButton(
                onClick = { onRemove(band.id) },
                modifier = Modifier.height(40.dp),
                shape = MaterialTheme.shapes.medium,
                border = eqButtonBorder(selected = false),
                colors = eqOutlinedButtonColors(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp),
            ) {
                Text(stringResource(R.string.graphic_eq_delete), style = MaterialTheme.typography.labelMedium)
            }
        }

        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                String.format(Locale.US, "Q %.2f", band.q),
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.width(72.dp),
            )
            Slider(
                value = band.q,
                onValueChange = { onChangeQ(band.id, it) },
                valueRange = ParametricBand.MIN_Q..ParametricBand.MAX_Q,
                colors = SliderDefaults.colors(
                    thumbColor = MaterialTheme.colorScheme.secondaryContainer,
                    activeTrackColor = MaterialTheme.colorScheme.secondaryContainer,
                    inactiveTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                ),
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun filterTypeLabel(type: Int): String = stringResource(
    when (type) {
        1 -> R.string.filter_type_low_shelf
        2 -> R.string.filter_type_high_shelf
        3 -> R.string.filter_type_high_pass
        else -> R.string.filter_type_peaking
    },
)

/**
 * The interactive response graph. Draggable handles map (freqHz, gainDb) ↔ (x, y).
 * High-pass handles snap to the 0 dB line (gain not applicable) and only move in X.
 *
 * Gesture: a single [awaitEachGesture] that only claims (consumes) the drag when the touch
 * STARTS on a handle — otherwise it leaves the event unconsumed so the parent LazyColumn can
 * scroll (empty-space drags no longer get swallowed). A clean tap on empty space deselects.
 */
@Composable
private fun ParametricGraphEditor(
    bands: List<ParametricBand>,
    selectedId: String?,
    autoEqFilters: List<AutoEqFilter>,
    yHalfDb: Float,
    sampleRate: Double,
    onSelectBand: (String?) -> Unit,
    onDragBand: (String, Float, Float) -> Unit,
) {
    val freqs = remember {
        DoubleArray(P_CURVE_POINTS) { i ->
            GRAPH_MIN_HZ * (GRAPH_MAX_HZ / GRAPH_MIN_HZ).pow(i.toDouble() / (P_CURVE_POINTS - 1))
        }
    }
    val autoSpecs = remember(autoEqFilters) { autoEqFilters.map { it.toBiquadSpec() } }
    val manualSpecs = remember(bands) { bands.map { it.toBiquadSpec() } }
    val composite = remember(manualSpecs, autoSpecs, sampleRate) {
        BiquadResponse.compositeDb(freqs, manualSpecs + autoSpecs, sampleRate)
    }
    val autoCurve = remember(autoSpecs, sampleRate) {
        if (autoSpecs.isEmpty()) null else BiquadResponse.compositeDb(freqs, autoSpecs, sampleRate)
    }

    // gainLimit = the user's chosen ± limit (also the drag clamp). displayHalf = the Y axis range
    // actually drawn — auto-expanded (5 dB steps) so a big composite boost isn't flat-topped/hidden,
    // while a single band's handle can still never be dragged past ±gainLimit.
    val gainLimit = yHalfDb.toDouble().coerceAtLeast(6.0)
    val curveMaxAbs = maxOf(
        composite.maxOfOrNull { abs(it) } ?: 0.0,
        autoCurve?.maxOfOrNull { abs(it) } ?: 0.0,
    )
    val displayHalf = maxOf(gainLimit, ceil((curveMaxAbs + 1.5) / 5.0) * 5.0)

    val colorScheme = MaterialTheme.colorScheme
    val graphBackground = colorScheme.surfaceContainerLowest
    val gridColor = colorScheme.outlineVariant.copy(alpha = 0.36f)
    val zeroColor = colorScheme.outline.copy(alpha = 0.55f)
    val autoColor = colorScheme.onSurfaceVariant.copy(alpha = 0.72f)
    val compositeColor = colorScheme.secondaryContainer
    val handleColor = colorScheme.primary
    val selectedHandleColor = colorScheme.tertiary
    val handleRingColor = colorScheme.surfaceContainerLowest
    val labelColor = colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
    val textMeasurer = rememberTextMeasurer()
    val axisStyle = remember(labelColor) { TextStyle(color = labelColor, fontSize = 9.sp) }

    // Read latest values inside the gesture WITHOUT re-keying pointerInput (re-keying mid-drag
    // cancels the gesture). The pointerInput is keyed on Unit; everything dynamic is read via state.
    val latestBands by rememberUpdatedState(bands)
    val latestSelectedId by rememberUpdatedState(selectedId)
    val latestDisplayHalf by rememberUpdatedState(displayHalf)
    val latestGainLimit by rememberUpdatedState(gainLimit)
    val latestOnDrag by rememberUpdatedState(onDragBand)
    val latestOnSelect by rememberUpdatedState(onSelectBand)

    // Plot padding (px). Left/bottom reserve room for axis labels. Must match draw + gesture.
    val leftPad = 52f; val rightPad = 12f; val topPad = 14f; val bottomPad = 34f

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(240.dp)
            .padding(horizontal = 4.dp)
            .pointerInput(Unit) {
                val radius = 26.dp.toPx()
                val slop = viewConfiguration.touchSlop
                fun pl() = leftPad
                fun pr() = (size.width - rightPad).coerceAtLeast(leftPad + 1f)
                fun pt() = topPad
                fun pb() = (size.height - bottomPad).coerceAtLeast(topPad + 1f)
                fun handleGain(b: ParametricBand) = if (b.type == TYPE_HIGH_PASS) 0.0 else b.gainDb.toDouble()
                fun posOf(b: ParametricBand) = Offset(
                    xOfFreq(b.freqHz.toDouble(), pl(), pr()),
                    yOfGain(handleGain(b), pt(), pb(), latestDisplayHalf),
                )
                // Among handles within [radius], pick the nearest; if that is the already-selected
                // band and others overlap, cycle to the next so stacked handles are reachable.
                fun pick(at: Offset): ParametricBand? {
                    val within = latestBands
                        .filter { (posOf(it) - at).getDistance() <= radius }
                        .sortedBy { (posOf(it) - at).getDistanceSquared() }
                    if (within.isEmpty()) return null
                    if (within.size == 1) return within[0]
                    val idx = within.indexOfFirst { it.id == latestSelectedId }
                    return if (idx >= 0) within[(idx + 1) % within.size] else within[0]
                }
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val hit = pick(down.position)
                    if (hit == null) {
                        // Empty space: do NOT consume → parent list can scroll. Clean tap deselects.
                        var moved = false
                        while (true) {
                            val ev = awaitPointerEvent()
                            val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                            if ((ch.position - down.position).getDistance() > slop) moved = true
                            if (ch.changedToUp()) {
                                if (!moved) latestOnSelect(null)
                                break
                            }
                            if (!ch.pressed) break
                        }
                        return@awaitEachGesture
                    }
                    val id = hit.id
                    latestOnSelect(id)
                    while (true) {
                        val ev = awaitPointerEvent()
                        val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                        if (ch.changedToUp()) { ch.consume(); break }
                        val b = latestBands.firstOrNull { it.id == id }
                        if (b != null) {
                            val f = freqOfX(ch.position.x.toDouble(), pl(), pr())
                                .coerceIn(GRAPH_MIN_HZ, GRAPH_MAX_HZ).toFloat()
                            val g = if (b.type == TYPE_HIGH_PASS) {
                                b.gainDb
                            } else {
                                gainOfY(ch.position.y.toDouble(), pt(), pb(), latestDisplayHalf)
                                    .coerceIn(-latestGainLimit, latestGainLimit).toFloat()
                            }
                            latestOnDrag(id, f, g)
                        }
                        ch.consume() // claim the drag from the parent so the list doesn't scroll
                        if (!ch.pressed) break
                    }
                }
            },
    ) {
        val plotLeft = leftPad
        val plotRight = (size.width - rightPad).coerceAtLeast(plotLeft + 1f)
        val plotTop = topPad
        val plotBottom = (size.height - bottomPad).coerceAtLeast(plotTop + 1f)

        drawRect(graphBackground)

        // Vertical grid (decade-ish marks).
        for (f in listOf(50.0, 100.0, 200.0, 500.0, 1000.0, 2000.0, 5000.0, 10000.0)) {
            val x = xOfFreq(f, plotLeft, plotRight)
            drawLine(gridColor, Offset(x, plotTop), Offset(x, plotBottom), strokeWidth = 1f)
        }
        // Horizontal dB grid every 5 dB.
        val maxGrid = displayHalf.toInt()
        for (db in (-maxGrid..maxGrid step 5)) {
            if (db == 0) continue
            val y = yOfGain(db.toDouble(), plotTop, plotBottom, displayHalf)
            drawLine(gridColor, Offset(plotLeft, y), Offset(plotRight, y), strokeWidth = 1f)
        }
        // 0 dB baseline.
        val zeroY = yOfGain(0.0, plotTop, plotBottom, displayHalf)
        drawLine(zeroColor, Offset(plotLeft, zeroY), Offset(plotRight, zeroY), strokeWidth = 2f)

        // Axis labels: dB on the left (top / 0 / bottom), frequency along the bottom.
        val dh = displayHalf.toInt()
        fun dbLabel(value: Int, y: Float) {
            val s = if (value > 0) "+$value" else value.toString()
            val l = textMeasurer.measure(s, axisStyle)
            drawText(l, topLeft = Offset(plotLeft - 6f - l.size.width, y - l.size.height / 2f))
        }
        dbLabel(dh, yOfGain(dh.toDouble(), plotTop, plotBottom, displayHalf))
        dbLabel(0, zeroY)
        dbLabel(-dh, yOfGain(-dh.toDouble(), plotTop, plotBottom, displayHalf))
        for ((f, s) in listOf(100.0 to "100", 1000.0 to "1k", 10000.0 to "10k")) {
            val l = textMeasurer.measure(s, axisStyle)
            val x = (xOfFreq(f, plotLeft, plotRight) - l.size.width / 2f)
                .coerceIn(plotLeft, plotRight - l.size.width)
            drawText(l, topLeft = Offset(x, plotBottom + 5f))
        }

        // AutoEQ-only dashed reference.
        autoCurve?.let { curve ->
            val path = Path()
            for (i in freqs.indices) {
                val x = xOfFreq(freqs[i], plotLeft, plotRight)
                val y = yOfGain(curve[i], plotTop, plotBottom, displayHalf)
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, autoColor, style = Stroke(width = 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f))))
        }
        // Composite curve (solid).
        run {
            val path = Path()
            for (i in freqs.indices) {
                val x = xOfFreq(freqs[i], plotLeft, plotRight)
                val y = yOfGain(composite[i], plotTop, plotBottom, displayHalf)
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, compositeColor, style = Stroke(width = 3f))
        }

        // Draggable handles.
        for (b in bands) {
            val gain = if (b.type == TYPE_HIGH_PASS) 0.0 else b.gainDb.toDouble()
            val cx = xOfFreq(b.freqHz.toDouble(), plotLeft, plotRight)
            val cy = yOfGain(gain, plotTop, plotBottom, displayHalf)
            val isSel = b.id == selectedId
            val r = if (isSel) 9f else 7f
            drawCircle(handleRingColor, radius = r + 3f, center = Offset(cx, cy))
            drawCircle(if (isSel) selectedHandleColor else handleColor, radius = r, center = Offset(cx, cy))
            if (isSel) {
                drawLine(
                    selectedHandleColor.copy(alpha = 0.4f),
                    Offset(cx, plotTop), Offset(cx, plotBottom),
                    strokeWidth = 1f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)),
                )
            }
        }
    }
}

// ── Shared log-freq / linear-gain transforms (graph coordinates) ────────────────
private fun xOfFreq(freqHz: Double, plotLeft: Float, plotRight: Float): Float {
    val t = (log10(freqHz) - log10(GRAPH_MIN_HZ)) / (log10(GRAPH_MAX_HZ) - log10(GRAPH_MIN_HZ))
    return plotLeft + (t * (plotRight - plotLeft)).toFloat()
}

private fun freqOfX(x: Double, plotLeft: Float, plotRight: Float): Double {
    val t = ((x - plotLeft) / (plotRight - plotLeft)).coerceIn(0.0, 1.0)
    return 10.0.pow(log10(GRAPH_MIN_HZ) + t * (log10(GRAPH_MAX_HZ) - log10(GRAPH_MIN_HZ)))
}

private fun yOfGain(db: Double, plotTop: Float, plotBottom: Float, yHalf: Double): Float {
    val clamped = db.coerceIn(-yHalf, yHalf)
    val h = plotBottom - plotTop
    return plotTop + (h * (0.5 - clamped / (2 * yHalf))).toFloat()
}

private fun gainOfY(y: Double, plotTop: Float, plotBottom: Float, yHalf: Double): Double {
    val h = (plotBottom - plotTop).toDouble()
    val frac = ((y - plotTop) / h).coerceIn(0.0, 1.0)
    return (0.5 - frac) * (2 * yHalf)
}
