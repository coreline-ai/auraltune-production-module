// Components.kt
// Small, self-contained Compose Material3 building blocks used by the AuralTune MVP screen.
package com.coreline.auraltune.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
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
import androidx.compose.ui.unit.dp
import com.coreline.audio.AudioEngine
import com.coreline.autoeq.model.AutoEqCatalogEntry
import com.coreline.autoeq.model.AutoEqProfile
import com.coreline.auraltune.R

/**
 * Card showing the currently active AutoEQ profile (if any) plus correction / preamp toggles.
 * Use empty-state copy from strings.xml when [profile] is null.
 */
@Composable
fun StatusCard(
    profile: AutoEqProfile?,
    isEnabled: Boolean,
    preampEnabled: Boolean,
    onClear: () -> Unit,
    onToggleCorrection: () -> Unit,
    onTogglePreamp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            if (profile == null) {
                Text(
                    text = stringResource(R.string.no_correction_active),
                    style = MaterialTheme.typography.titleMedium,
                )
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = profile.name,
                            style = MaterialTheme.typography.titleMedium,
                        )
                        profile.measuredBy?.takeIf { it.isNotBlank() }?.let { source ->
                            Text(
                                text = source,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    IconButton(onClick = onClear) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = stringResource(R.string.clear_selection),
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            ToggleRow(
                label = stringResource(R.string.correction_toggle),
                checked = isEnabled,
                onCheckedChange = { onToggleCorrection() },
            )
            ToggleRow(
                label = stringResource(R.string.preamp_toggle),
                checked = preampEnabled,
                onCheckedChange = { onTogglePreamp() },
            )
        }
    }
}

/**
 * Single search-result row. Tapping the row body selects; the star toggles favorite.
 */
@Composable
fun CatalogEntryRow(
    entry: AutoEqCatalogEntry,
    isSelected: Boolean,
    onClick: () -> Unit,
    onToggleFavorite: () -> Unit,
    isFavorite: Boolean,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.name,
                style = MaterialTheme.typography.bodyLarge,
                color = if (isSelected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurface,
            )
            entry.measuredBy.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        IconButton(onClick = onToggleFavorite) {
            Icon(
                imageVector = if (isFavorite) Icons.Default.Star else Icons.Outlined.Star,
                contentDescription = stringResource(R.string.favorite_toggle),
                tint = if (isFavorite) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    HorizontalDivider()
}

/**
 * Collapsible diagnostic counters card. Defaults to collapsed so it does not pull focus
 * from the primary search/selection flow.
 */
@Composable
fun DiagnosticsCard(
    diagnostics: AudioEngine.Diagnostics,
    currentSampleRate: Int,
    deviceHash: String?,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    Card(modifier = modifier.fillMaxWidth()) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.diagnostics_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                )
            }
            if (expanded) {
                Column(
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    // P3-C: device + sample rate at the top so the rest of the diag
                    // makes sense in context.
                    DiagnosticsTextRow(
                        label = stringResource(R.string.diagnostics_device),
                        value = deviceHash ?: stringResource(R.string.diagnostics_no_device),
                    )
                    DiagnosticsTextRow(
                        label = stringResource(R.string.diagnostics_sample_rate_now),
                        value = "$currentSampleRate Hz",
                    )
                    DiagnosticsRow(stringResource(R.string.diagnostics_xrun), diagnostics.xrunCount)
                    DiagnosticsRow(stringResource(R.string.diagnostics_non_finite), diagnostics.nonFiniteResetCount)
                    DiagnosticsRow(stringResource(R.string.diagnostics_config_swap), diagnostics.configSwapCount)
                    DiagnosticsRow(stringResource(R.string.diagnostics_sample_rate), diagnostics.sampleRateChangeCount)
                    DiagnosticsRow(stringResource(R.string.diagnostics_total_frames), diagnostics.totalProcessedFrames)
                }
            }
        }
    }
}

@Composable
private fun DiagnosticsTextRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun DiagnosticsRow(label: String, value: Long) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(value.toString(), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ToggleRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/**
 * Empty-state placeholder used when there is no query / no match / catalog offline.
 * Phase 6 calls for distinguishing the three cases at this surface.
 */
@Composable
fun EmptyStateMessage(message: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(24.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        Text(message, style = MaterialTheme.typography.bodyMedium)
    }
}

/** Simple text-button used for "Clear cache" affordance in the overflow area. */
@Composable
fun TextLinkButton(text: String, onClick: () -> Unit) {
    TextButton(onClick = onClick) { Text(text) }
}
