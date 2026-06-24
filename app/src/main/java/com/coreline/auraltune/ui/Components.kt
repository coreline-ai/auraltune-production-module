// Components.kt
// Small, self-contained Compose Material3 building blocks used by the AuralTune MVP screen.
package com.coreline.auraltune.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.coreline.audio.AudioEngine
import com.coreline.autoeq.model.AutoEqCatalogEntry
import com.coreline.autoeq.model.AutoEqProfile
import com.coreline.auraltune.R
import com.coreline.auraltune.opra.model.OpraEqProfile

/**
 * Card showing the currently active AutoEQ profile (if any). The correction / preamp
 * toggles live in [AutoEqToggleCard] (placed above the Graphic EQ).
 * Use empty-state copy from strings.xml when [profile] is null.
 */
@Composable
fun StatusCard(
    profile: AutoEqProfile?,
    onClear: () -> Unit,
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
        }
    }
}

/**
 * 3-way 청취 비교 스위치(원음 / AutoEQ / 내 설정). 기존 킬스위치 + correction 토글을 대체한다.
 * 선택된 모드는 채워진 버튼, 나머지는 외곽선. 하단 한 줄로 현재 모드를 설명한다.
 */
@Composable
fun ListenModeBar(
    mode: ListenMode,
    subtitle: String,
    onSelect: (ListenMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.listen_mode_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ModeButton(stringResource(R.string.listen_mode_original), mode == ListenMode.ORIGINAL) { onSelect(ListenMode.ORIGINAL) }
                ModeButton(stringResource(R.string.listen_mode_autoeq), mode == ListenMode.AUTOEQ) { onSelect(ListenMode.AUTOEQ) }
                ModeButton(stringResource(R.string.listen_mode_user), mode == ListenMode.USER) { onSelect(ListenMode.USER) }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** One segment of [ListenModeBar]: filled when selected, outlined otherwise. */
@Composable
private fun RowScope.ModeButton(label: String, selected: Boolean, onClick: () -> Unit) {
    if (selected) {
        Button(onClick = onClick, modifier = Modifier.weight(1f)) { Text(label) }
    } else {
        OutlinedButton(onClick = onClick, modifier = Modifier.weight(1f)) { Text(label) }
    }
}

/** AutoEQ preamp toggle. Directly above the Graphic EQ (correction on/off moved to [ListenModeBar]). */
@Composable
fun AutoEqPreampCard(
    preampEnabled: Boolean,
    onTogglePreamp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
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
                    // 보정이 실제 엔진에 걸렸는지 객관 확인용 — active filters > 0 이면 EQ 적용 중.
                    DiagnosticsRow("AutoEQ active filters", diagnostics.autoEqActiveCount.toLong())
                    DiagnosticsRow("Applied generation", diagnostics.appliedGeneration)
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

/** Underlined, primary-colored, clickable text — used for license/source web links. */
@Composable
private fun WebLink(text: String, url: String, onOpenUrl: (String) -> Unit) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.primary,
        textDecoration = TextDecoration.Underline,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpenUrl(url) }
            .padding(vertical = 6.dp),
    )
}

/**
 * Collapsible "About & licenses" card (Phase 5). Surfaces the AutoEq + OPRA attributions, the
 * CC BY-SA 4.0 license link, the OPRA project link, the loaded snapshot commit, and the
 * no-endorsement / rights-not-restricted notices required for commercial distribution.
 */
@Composable
fun AboutCard(
    appVersion: String,
    opraSnapshotCommit: String?,
    opraSourceUrl: String?,
    onOpenUrl: (String) -> Unit,
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
                    text = stringResource(R.string.about_title),
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
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = stringResource(R.string.about_app_version, appVersion),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    HorizontalDivider()
                    // AutoEq
                    Text(
                        text = stringResource(R.string.autoeq_attribution),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    HorizontalDivider()
                    // OPRA
                    Text(
                        text = stringResource(R.string.opra_attribution),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = stringResource(R.string.opra_license_label),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    WebLink(
                        text = stringResource(R.string.opra_license_link_label),
                        url = OpraEqProfile.LICENSE_URL,
                        onOpenUrl = onOpenUrl,
                    )
                    WebLink(
                        text = stringResource(R.string.opra_project_link_label),
                        url = OPRA_PROJECT_URL,
                        onOpenUrl = onOpenUrl,
                    )
                    // 데이터 출처(원본 데이터셋) 링크 — attribution 요구사항(원본 링크 제공).
                    opraSourceUrl?.takeIf { it.isNotBlank() }?.let { src ->
                        WebLink(
                            text = stringResource(R.string.opra_source_data_link_label),
                            url = src,
                            onOpenUrl = onOpenUrl,
                        )
                    }
                    Text(
                        text = opraSnapshotCommit
                            ?.let { stringResource(R.string.opra_snapshot_commit_format, it.take(8)) }
                            ?: stringResource(R.string.opra_snapshot_none),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    HorizontalDivider()
                    // 변경 사실 표시(CC BY-SA 4.0 attribution 요구) — 형식 변환만 함을 명시.
                    Text(
                        text = stringResource(R.string.opra_changes_notice),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = stringResource(R.string.opra_no_endorsement),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = stringResource(R.string.opra_license_not_restricted),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * OPRA profile detail sheet (Phase 5): shows author / measurement source / license with clickable
 * links and an explicit Apply action. Unsupported profiles show the reason and disable Apply
 * (the "no partial apply" policy).
 */
@Composable
fun OpraProfileDetailDialog(
    profile: OpraEqProfile,
    onApply: () -> Unit,
    onOpenUrl: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(profile.profileName) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = profile.author
                        ?.let { stringResource(R.string.opra_detail_author_format, it) }
                        ?: stringResource(R.string.opra_detail_author_unknown),
                    style = MaterialTheme.typography.bodyMedium,
                )
                profile.details?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                profile.link?.takeIf { it.isNotBlank() }?.let {
                    WebLink(stringResource(R.string.opra_detail_source_link), it, onOpenUrl)
                }
                WebLink(
                    text = stringResource(R.string.opra_detail_license_link),
                    url = OpraEqProfile.LICENSE_URL,
                    onOpenUrl = onOpenUrl,
                )
                if (!profile.isSupported) {
                    Text(
                        text = stringResource(
                            R.string.opra_detail_unsupported_format,
                            profile.unsupportedReason ?: "",
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                HorizontalDivider()
                // CC BY-SA 4.0 고지(EQ 상세): 변경 사실(형식 변환/OPRA-derived) + 비제휴.
                Text(
                    text = stringResource(R.string.opra_detail_notice),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Button(onClick = onApply, enabled = profile.isSupported) {
                Text(stringResource(R.string.opra_detail_apply))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.opra_detail_close)) }
        },
    )
}

/** OPRA upstream project (CC BY-SA 4.0 data / MIT code). */
private const val OPRA_PROJECT_URL = "https://github.com/opra-project/OPRA"
