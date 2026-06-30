// ToneEqView.kt
// Simple 3-band tone control (Bass low-shelf / Mid peak / Treble high-shelf) — the TONE EqMode.
// Reuses the shared composite response graph (EqGraphView), the graphic preset row helpers
// (eqSaveButtonColors / eqButtonBorder / SavePresetDialog), and the same Manual-chain apply path;
// only the authoring UI (3 horizontal sliders + tone presets) differs from graphic/parametric.
package com.coreline.auraltune.ui

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.coreline.auraltune.R
import com.coreline.auraltune.audio.eq.BiquadSpec
import com.coreline.auraltune.data.ToneEqPreset
import com.coreline.auraltune.data.ToneEqPresetCatalog
import com.coreline.autoeq.model.AutoEqFilter
import java.util.Locale

/**
 * Tone EQ card: preset row (save + load) · composite response graph (profile + tone) · 3 horizontal
 * sliders. [toneGains] = [bass, mid, treble] dB. [manualSpecs] is the live tone chain (mode == TONE),
 * drawn by the shared [EqGraphView]. Slider ranges share the graphic/parametric gain limit.
 */
@Composable
fun ToneEqCard(
    toneGains: FloatArray,
    manualSpecs: List<BiquadSpec>,
    autoEqFilters: List<AutoEqFilter>,
    presets: List<ToneEqPreset>,
    selectedPresetId: String?,
    gainLimitDb: Float,
    sampleRate: Double,
    preampDb: Float,
    showPreamp: Boolean,
    preampApplied: Boolean,
    onToneChange: (Int, Float) -> Unit,
    onSavePreset: (String) -> Unit,
    onLoadPreset: (String) -> Unit,
    onDeletePreset: (String) -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showSaveDialog by remember { mutableStateOf(false) }
    var presetMenuOpen by remember { mutableStateOf(false) }

    AuralTunePanel(modifier = modifier.fillMaxWidth(), elevated = true) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.tone_eq_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onReset) { Text(stringResource(R.string.graphic_eq_reset)) }
            }
            Spacer(Modifier.height(4.dp))

            // 프리셋 행: 저장 + 불러오기(현재 선택 표시). 그래픽 프리셋 행과 동일 패턴.
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
                    Icon(Icons.Default.Save, contentDescription = stringResource(R.string.graphic_eq_save), modifier = Modifier.size(18.dp))
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
                        Icon(Icons.Default.ExpandMore, contentDescription = stringResource(R.string.graphic_eq_load_menu), modifier = Modifier.size(18.dp))
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
                            val builtIn = ToneEqPresetCatalog.isBuiltInId(p.id)
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

            Spacer(Modifier.height(8.dp))
            EqGraphView(
                manualSpecs = manualSpecs,
                autoEqFilters = autoEqFilters,
                sampleRate = sampleRate,
                preampDb = preampDb,
                showPreamp = showPreamp,
                preampApplied = preampApplied,
            )
            Spacer(Modifier.height(12.dp))
            ToneSlider(R.string.tone_bass, "100 Hz", toneGains.getOrElse(0) { 0f }, gainLimitDb) { onToneChange(0, it) }
            Spacer(Modifier.height(8.dp))
            ToneSlider(R.string.tone_mid, "1 kHz", toneGains.getOrElse(1) { 0f }, gainLimitDb) { onToneChange(1, it) }
            Spacer(Modifier.height(8.dp))
            ToneSlider(R.string.tone_treble, "10 kHz", toneGains.getOrElse(2) { 0f }, gainLimitDb) { onToneChange(2, it) }
        }
    }
}

@Composable
private fun ToneSlider(
    labelRes: Int,
    freqLabel: String,
    value: Float,
    limit: Float,
    onChange: (Float) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(labelRes),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Text(
                freqLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(10.dp))
            Text(
                formatDb(value),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.End,
                modifier = Modifier.width(68.dp),
            )
        }
        Slider(
            value = value.coerceIn(-limit, limit),
            onValueChange = onChange,
            valueRange = -limit..limit,
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.secondaryContainer,
                activeTrackColor = MaterialTheme.colorScheme.secondaryContainer,
                inactiveTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            ),
        )
    }
}

private fun formatDb(v: Float): String =
    if (kotlin.math.abs(v) < 0.05f) "0.0 dB" else String.format(Locale.US, "%+.1f dB", v)
