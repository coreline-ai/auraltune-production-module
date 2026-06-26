// Components.kt
// Small, self-contained Compose Material3 building blocks used by the AuralTune MVP screen.
package com.coreline.auraltune.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.coreline.audio.AudioEngine
import com.coreline.autoeq.model.AutoEqCatalogEntry
import com.coreline.autoeq.model.AutoEqProfile
import com.coreline.auraltune.R
import com.coreline.auraltune.opra.model.OpraEqProfile

/**
 * Small pill showing which data source the active correction came from. AutoEQ uses the primary
 * container color, OPRA the tertiary container — distinct at a glance.
 */
@Composable
fun AuralTunePanel(
    modifier: Modifier = Modifier,
    elevated: Boolean = false,
    containerColor: Color? = null,
    borderColor: Color? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = containerColor ?: MaterialTheme.colorScheme.surfaceContainer,
        ),
        border = BorderStroke(
            1.dp,
            borderColor ?: MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.58f),
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = if (elevated) 6.dp else 0.dp),
    ) {
        Column(content = content)
    }
}

@Composable
fun SourceBadge(label: String, modifier: Modifier = Modifier) {
    val isOpra = label.equals("OPRA", ignoreCase = true)
    val accent = if (isOpra) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.secondaryContainer
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(50),
        color = accent.copy(alpha = 0.13f),
        contentColor = accent,
        border = BorderStroke(1.dp, accent.copy(alpha = 0.45f)),
    ) {
        Text(
            text = label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

/**
 * Card showing the currently active correction profile (if any) + its [sourceLabel] badge
 * (AutoEQ / OPRA). Use empty-state copy from strings.xml when [profile] is null.
 */
@Composable
fun StatusCard(
    profile: AutoEqProfile?,
    sourceLabel: String?,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
    inUse: Boolean = false,
    onUse: (() -> Unit)? = null,
) {
    val canUseByCardTap = profile != null && !inUse && onUse != null
    val sourceAccent = if (sourceLabel.equals("OPRA", ignoreCase = true)) {
        MaterialTheme.colorScheme.tertiary
    } else {
        MaterialTheme.colorScheme.secondaryContainer
    }
    val cardModifier = modifier.then(
        if (canUseByCardTap) Modifier.clickable(onClick = onUse!!) else Modifier,
    )

    AuralTunePanel(
        modifier = cardModifier,
        elevated = profile != null,
        containerColor = if (inUse) {
            MaterialTheme.colorScheme.surfaceContainerHigh
        } else {
            null
        },
        borderColor = if (inUse) sourceAccent.copy(alpha = 0.55f) else null,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            if (profile == null) {
                Text(
                    text = stringResource(R.string.no_correction_active),
                    style = MaterialTheme.typography.titleMedium,
                )
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceContainerLowest),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Default.GraphicEq,
                            contentDescription = stringResource(R.string.status_profile_icon),
                            tint = MaterialTheme.colorScheme.secondaryContainer,
                        )
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        sourceLabel?.let {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                SourceBadge(it)
                                if (inUse) InUseBadge()
                            }
                            Spacer(Modifier.height(6.dp))
                        }
                        Text(
                            text = profile.name,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
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

@Composable
private fun InUseBadge() {
    val accent = MaterialTheme.colorScheme.secondaryContainer
    Surface(
        shape = RoundedCornerShape(50),
        color = accent.copy(alpha = 0.24f),
        contentColor = accent,
        border = BorderStroke(1.dp, accent.copy(alpha = 0.62f)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(5.dp)
                    .clip(CircleShape)
                    .background(accent),
            )
            Spacer(Modifier.width(5.dp))
            Text(
                text = stringResource(R.string.in_use_badge),
                style = MaterialTheme.typography.labelSmall,
            )
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
    AuralTunePanel(modifier = modifier) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = stringResource(R.string.listen_mode_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.outline,
            )
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                GainLimitButton(
                    label = stringResource(R.string.listen_mode_original),
                    selected = mode == ListenMode.ORIGINAL,
                    onClick = { onSelect(ListenMode.ORIGINAL) },
                    modifier = Modifier.weight(1f),
                )
                GainLimitButton(
                    label = stringResource(R.string.listen_mode_autoeq),
                    selected = mode == ListenMode.AUTOEQ,
                    onClick = { onSelect(ListenMode.AUTOEQ) },
                    modifier = Modifier.weight(1f),
                )
                GainLimitButton(
                    label = stringResource(R.string.listen_mode_user),
                    selected = mode == ListenMode.USER,
                    onClick = { onSelect(ListenMode.USER) },
                    modifier = Modifier.weight(1f),
                )
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

/** AutoEQ preamp toggle. Directly above the Graphic EQ (correction on/off moved to [ListenModeBar]). */
@Composable
fun AutoEqPreampCard(
    preampEnabled: Boolean,
    onTogglePreamp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AuralTunePanel(modifier = modifier) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
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
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = if (isSelected) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(
            1.dp,
            if (isSelected) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f)
            else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.24f),
        ),
    ) {
        Row(
            modifier = Modifier
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.name,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (isSelected) MaterialTheme.colorScheme.secondaryContainer
                    else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                entry.measuredBy.takeIf { it.isNotBlank() }?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            IconButton(onClick = onToggleFavorite) {
                Icon(
                    imageVector = if (isFavorite) Icons.Default.Star else Icons.Outlined.Star,
                    contentDescription = stringResource(R.string.favorite_toggle),
                    tint = if (isFavorite) MaterialTheme.colorScheme.secondaryContainer
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
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
    AuralTunePanel(modifier = modifier) {
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
                    contentDescription = stringResource(
                        if (expanded) R.string.diagnostics_collapse else R.string.diagnostics_expand,
                    ),
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
                    DiagnosticsRow(
                        stringResource(R.string.diagnostics_autoeq_active_filters),
                        diagnostics.autoEqActiveCount.toLong(),
                    )
                    DiagnosticsRow(
                        stringResource(R.string.diagnostics_applied_generation),
                        diagnostics.appliedGeneration,
                    )
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
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = MaterialTheme.colorScheme.secondaryContainer,
                checkedTrackColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.22f),
                checkedBorderColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f),
            ),
        )
    }
}

/**
 * Empty-state placeholder used when there is no query / no match / catalog offline.
 * Phase 6 calls for distinguishing the three cases at this surface.
 */
@Composable
fun EmptyStateMessage(message: String) {
    AuralTunePanel {
        Row(
            modifier = Modifier.fillMaxWidth().padding(24.dp),
            horizontalArrangement = Arrangement.Center,
        ) {
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
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
    AuralTunePanel(modifier = modifier) {
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
                    contentDescription = stringResource(
                        if (expanded) R.string.about_collapse else R.string.about_expand,
                    ),
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
