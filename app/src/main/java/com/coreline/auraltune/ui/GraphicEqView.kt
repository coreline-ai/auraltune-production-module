// GraphicEqView.kt
// Phase G1 — 20-band graphic EQ UI (vertical sliders, horizontal scroll).
// Each slider drives one Manual-chain band via AutoEqViewModel.setBandGain.
package com.coreline.auraltune.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.coreline.auraltune.audio.eq.GraphicEqBands
import com.coreline.auraltune.data.GraphicEqPreset
import com.coreline.autoeq.model.AutoEqFilter

@Composable
fun GraphicEqCard(
    bandGains: FloatArray,
    autoEqFilters: List<AutoEqFilter>,
    presets: List<GraphicEqPreset>,
    selectedPresetId: String?,
    gainLimitDb: Float,
    preampDb: Float,
    showPreamp: Boolean,
    preampApplied: Boolean,
    onBandChange: (Int, Float) -> Unit,
    onGainLimitChange: (Float) -> Unit,
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
                // TODO(i18n): 문자열 리소스화
                Text(
                    "그래픽 EQ (20밴드)",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                )
                TextButton(onClick = onReset) { Text("리셋") }
            }

            // 게인 한계(리미터) 세그먼트 칩: ±6/±12/±15/±20 중 선택.
            // 최근접 옵션을 선택 표시 — 어떤 경로로든 정확히 1개만 선택됨(스냅 미보장 값에도 견고).
            val selectedLimit = GraphicEqBands.GAIN_LIMIT_OPTIONS
                .minByOrNull { kotlin.math.abs(it - gainLimitDb) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("한계", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.width(8.dp))
                for (opt in GraphicEqBands.GAIN_LIMIT_OPTIONS) {
                    FilterChip(
                        selected = opt == selectedLimit,
                        onClick = { onGainLimitChange(opt) },
                        label = { Text("±${opt.toInt()}") },
                    )
                    Spacer(Modifier.width(6.dp))
                }
            }

            // 프리셋 행: 저장 + 불러오기 메뉴(현재 선택 표시).
            val selectedName = presets.firstOrNull { it.id == selectedPresetId }?.name
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { showSaveDialog = true }) { Text("프리셋 저장") }
                Spacer(Modifier.width(8.dp))
                Box {
                    TextButton(onClick = { presetMenuOpen = true }) {
                        Text(selectedName ?: "프리셋 불러오기 (${presets.size})")
                    }
                    DropdownMenu(expanded = presetMenuOpen, onDismissRequest = { presetMenuOpen = false }) {
                        if (presets.isEmpty()) {
                            DropdownMenuItem(text = { Text("저장된 프리셋 없음") }, onClick = {}, enabled = false)
                        }
                        presets.forEach { p ->
                            DropdownMenuItem(
                                text = { Text(p.name) },
                                onClick = { presetMenuOpen = false; onLoadPreset(p.id) },
                                trailingIcon = {
                                    TextButton(onClick = { onDeletePreset(p.id) }) { Text("삭제") }
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

            Spacer(Modifier.height(14.dp))
            // 상단: 합성 응답 그래프(Manual + AutoEQ) + preamp 기준선(점선).
            EqGraphView(
                bandGains = bandGains,
                autoEqFilters = autoEqFilters,
                preampDb = preampDb,
                showPreamp = showPreamp,
                preampApplied = preampApplied,
            )
            // 프리앰프 표시 토글(주황 점선). 활성 프로파일 preamp가 있을 때만 의미 있어 그 외엔 비활성.
            val hasPreamp = kotlin.math.abs(preampDb) > 0.05f
            Row(verticalAlignment = Alignment.CenterVertically) {
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
                    if (hasPreamp) "프리앰프 표시 (${String.format("%+.1f", preampDb)} dB)"
                    else "프리앰프 표시 (프로파일 없음)",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Spacer(Modifier.height(8.dp))
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
        }
    }
}

@Composable
private fun SavePresetDialog(
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("프리셋 저장") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                label = { Text("이름") },
            )
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(name) },
                enabled = name.isNotBlank(),
            ) { Text("저장") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
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
            String.format("%+.0f", gainDb),
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
