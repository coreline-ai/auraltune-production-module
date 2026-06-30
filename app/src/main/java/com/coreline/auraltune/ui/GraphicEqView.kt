// GraphicEqView.kt
// Phase G1 — 20-band graphic EQ UI (vertical sliders, horizontal scroll).
// Each slider drives one Manual-chain band via AutoEqViewModel.setBandGain.
package com.coreline.auraltune.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.coreline.auraltune.audio.eq.GraphicEqBands
import com.coreline.auraltune.data.GraphicEqPreset
import com.coreline.auraltune.R
import com.coreline.autoeq.model.AutoEqFilter

@Composable
fun GraphicEqCard(
    bandGains: FloatArray,
    autoEqFilters: List<AutoEqFilter>,
    presets: List<GraphicEqPreset>,
    selectedPresetId: String?,
    gainLimitDb: Float,
    qScale: Float,
    sampleRate: Double,
    preampDb: Float,
    showPreamp: Boolean,
    preampApplied: Boolean,
    onBandChange: (Int, Float) -> Unit,
    onGainLimitChange: (Float) -> Unit,
    onQScaleChange: (Float) -> Unit,
    onToggleShowPreamp: (Boolean) -> Unit,
    onReset: () -> Unit,
    onSavePreset: (String) -> Unit,
    onLoadPreset: (String) -> Unit,
    onDeletePreset: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showSaveDialog by remember { mutableStateOf(false) }
    var presetMenuOpen by remember { mutableStateOf(false) }

    AuralTunePanel(modifier = modifier, elevated = true) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.graphic_eq_title),
                    modifier = Modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            Spacer(Modifier.height(8.dp))

            // 프리셋 행: 저장 + 불러오기 메뉴(현재 선택 표시).
            val selectedName = presets.firstOrNull { it.id == selectedPresetId }?.name
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = { showSaveDialog = true },
                    modifier = Modifier.weight(1f).height(40.dp),
                    shape = MaterialTheme.shapes.medium,
                    colors = eqSaveButtonColors(),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Save,
                        contentDescription = stringResource(R.string.graphic_eq_save),
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.graphic_eq_save), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Box(modifier = Modifier.weight(1f)) {
                    OutlinedButton(
                        onClick = { presetMenuOpen = true },
                        modifier = Modifier.fillMaxWidth().height(40.dp),
                        shape = MaterialTheme.shapes.medium,
                        border = eqButtonBorder(selected = selectedName != null),
                        colors = if (selectedName != null) eqSelectedButtonColors() else eqOutlinedButtonColors(),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
                    ) {
                        Text(
                            selectedName ?: stringResource(R.string.graphic_eq_load_count, presets.size),
                            modifier = Modifier.weight(1f, fill = false),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.width(6.dp))
                        Icon(
                            imageVector = Icons.Default.ExpandMore,
                            contentDescription = stringResource(R.string.graphic_eq_load_menu),
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    DropdownMenu(expanded = presetMenuOpen, onDismissRequest = { presetMenuOpen = false }) {
                        if (presets.isEmpty()) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.graphic_eq_empty)) },
                                onClick = {},
                                enabled = false,
                            )
                        }
                        presets.forEach { p ->
                            val builtIn = com.coreline.auraltune.data.GraphicEqPresetCatalog.isBuiltInId(p.id)
                            DropdownMenuItem(
                                text = { Text(p.name) },
                                onClick = { presetMenuOpen = false; onLoadPreset(p.id) },
                                trailingIcon = {
                                    // 내장 기본 프리셋은 삭제 버튼 없음(삭제 불가).
                                    if (!builtIn) {
                                        TextButton(onClick = { onDeletePreset(p.id) }) {
                                            Text(stringResource(R.string.graphic_eq_delete))
                                        }
                                    }
                                },
                            )
                        }
                    }
                }
            }

            if (showSaveDialog) {
                SavePresetDialog(
                    onConfirm = { name -> showSaveDialog = false; onSavePreset(name) },
                    onDismiss = { showSaveDialog = false },
                )
            }

            Spacer(Modifier.height(10.dp))
            // 상단: 합성 응답 그래프(Manual + AutoEQ) + preamp 기준선(점선). 전역 Q 배율 반영.
            val manualSpecs = remember(bandGains, qScale) { GraphicEqBands.toSpecs(bandGains, qScale) }
            EqGraphView(
                manualSpecs = manualSpecs,
                autoEqFilters = autoEqFilters,
                sampleRate = sampleRate,
                preampDb = preampDb,
                showPreamp = showPreamp,
                preampApplied = preampApplied,
            )
            // 프리앰프 표시 토글(주황 점선). 활성 프로파일 preamp가 있을 때만 의미 있어 그 외엔 비활성.
            val hasPreamp = kotlin.math.abs(preampDb) > 0.05f
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(
                    checked = showPreamp,
                    onCheckedChange = onToggleShowPreamp,
                    enabled = hasPreamp,
                    colors = CheckboxDefaults.colors(
                        checkedColor = MaterialTheme.colorScheme.secondaryContainer,
                        checkmarkColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    ),
                )
                Text(
                    if (hasPreamp) {
                        stringResource(R.string.graphic_eq_preamp_format, String.format(java.util.Locale.US, "%+.1f", preampDb))
                    } else {
                        stringResource(R.string.graphic_eq_preamp_none)
                    },
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                )
                EqResetButton(onClick = onReset)
            }
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                for (i in 0 until GraphicEqBands.COUNT) {
                    VerticalEqBand(
                        gainDb = bandGains.getOrElse(i) { 0f },
                        gainLimitDb = gainLimitDb,
                        label = GraphicEqBands.label(GraphicEqBands.frequencies[i]),
                        onChange = { onBandChange(i, it) },
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            GainLimitSelector(
                gainLimitDb = gainLimitDb,
                onGainLimitChange = onGainLimitChange,
            )
            Spacer(Modifier.height(12.dp))
            QScaleSelector(
                qScale = qScale,
                onQScaleChange = onQScaleChange,
            )
        }
    }
}

/** 전역 Q 배율 선택(넓게/보통/좁게) — 모든 밴드의 종 폭을 동시에 조절. */
@Composable
private fun QScaleSelector(
    qScale: Float,
    onQScaleChange: (Float) -> Unit,
) {
    val selected = GraphicEqBands.snapQScale(qScale)
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            stringResource(R.string.graphic_eq_q_scale),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.outline,
        )
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for (opt in GraphicEqBands.Q_SCALE_OPTIONS) {
                GainLimitButton(
                    label = qScaleLabel(opt),
                    selected = opt == selected,
                    onClick = { onQScaleChange(opt) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** 0.5 → 넓게, 1.0 → 보통, 2.0 → 좁게. */
@Composable
private fun qScaleLabel(scale: Float): String = stringResource(
    when {
        scale < 0.75f -> R.string.graphic_eq_q_wide
        scale > 1.5f -> R.string.graphic_eq_q_narrow
        else -> R.string.graphic_eq_q_normal
    },
)

@Composable
private fun EqResetButton(onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.height(34.dp),
        shape = MaterialTheme.shapes.small,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.tertiary.copy(alpha = 0.70f)),
        colors = eqResetButtonColors(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
    ) {
        Text(
            text = stringResource(R.string.graphic_eq_reset),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
internal fun GainLimitButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.height(38.dp),
        shape = MaterialTheme.shapes.small,
        border = eqButtonBorder(selected),
        colors = if (selected) eqSelectedButtonColors() else eqOutlinedButtonColors(),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
    ) {
        Text(
            text = label,
            style = if (selected) MaterialTheme.typography.labelMedium else MaterialTheme.typography.labelSmall,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
        )
    }
}

@Composable
private fun GainLimitSelector(
    gainLimitDb: Float,
    onGainLimitChange: (Float) -> Unit,
) {
    val selectedLimit = GraphicEqBands.GAIN_LIMIT_OPTIONS
        .minByOrNull { kotlin.math.abs(it - gainLimitDb) }

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            stringResource(R.string.graphic_eq_limit),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.outline,
        )
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for (opt in GraphicEqBands.GAIN_LIMIT_OPTIONS) {
                GainLimitButton(
                    label = "±${opt.toInt()} dB",
                    selected = opt == selectedLimit,
                    onClick = { onGainLimitChange(opt) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
internal fun eqButtonBorder(selected: Boolean) = BorderStroke(
    width = if (selected) 2.dp else 1.dp,
    color = if (selected) {
        MaterialTheme.colorScheme.secondaryContainer
    } else {
        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)
    },
)

@Composable
internal fun eqSelectedButtonColors() = ButtonDefaults.outlinedButtonColors(
    containerColor = MaterialTheme.colorScheme.secondaryContainer,
    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
)

@Composable
internal fun eqSaveButtonColors() = ButtonDefaults.buttonColors(
    containerColor = MaterialTheme.colorScheme.secondaryContainer,
    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
)

@Composable
private fun eqResetButtonColors() = ButtonDefaults.outlinedButtonColors(
    containerColor = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.12f),
    contentColor = MaterialTheme.colorScheme.tertiary,
)

@Composable
internal fun eqOutlinedButtonColors() = ButtonDefaults.outlinedButtonColors(
    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
)

@Composable
internal fun SavePresetDialog(
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.graphic_eq_save_dialog_title)) },
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

@Composable
private fun VerticalEqBand(
    gainDb: Float,
    gainLimitDb: Float,
    label: String,
    onChange: (Float) -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(34.dp),
    ) {
        Text(
            String.format(java.util.Locale.US, "%+.0f", gainDb),
            style = MaterialTheme.typography.labelSmall,
        )
        Box(
            modifier = Modifier
                .height(150.dp)
                .width(40.dp),
            contentAlignment = Alignment.Center,
        ) {
            // Rotate a horizontal Slider 270° to make a vertical fader.
            Slider(
                value = gainDb,
                onValueChange = onChange,
                valueRange = -gainLimitDb..gainLimitDb,
                colors = SliderDefaults.colors(
                    thumbColor = MaterialTheme.colorScheme.secondaryContainer,
                    activeTrackColor = MaterialTheme.colorScheme.secondaryContainer,
                    inactiveTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                ),
                modifier = Modifier
                    .graphicsLayer {
                        rotationZ = 270f
                        transformOrigin = TransformOrigin(0f, 0f)
                    }
                    .layout { measurable, constraints ->
                        val placeable = measurable.measure(
                            Constraints(
                                minWidth = constraints.minHeight,
                                maxWidth = constraints.maxHeight,
                                minHeight = constraints.minWidth,
                                maxHeight = constraints.maxWidth,
                            ),
                        )
                        layout(placeable.height, placeable.width) {
                            placeable.place(-placeable.width, 0)
                        }
                    }
                    .width(150.dp),
            )
        }
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}
