// AuralTuneApp.kt
// Top-level Composable. Wires the AutoEqViewModel into a single-screen Scaffold and hosts
// the Phase 0 / Phase 6 MVP layout: status card, search section, diagnostics card,
// test-tone toggle, and AutoEq attribution.
@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.coreline.auraltune.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.coreline.autoeq.model.CatalogState
import com.coreline.auraltune.AuralTuneApplication
import com.coreline.auraltune.BuildConfig
import com.coreline.auraltune.R
import com.coreline.auraltune.audio.MusicPlayerController

/**
 * Top-level AuralTune Composable. Hosts the [AuralTuneTheme] + [Scaffold] and binds the
 * [AutoEqViewModel] for the only screen of the MVP.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AuralTuneApp() {
    AuralTuneTheme {
        val context = LocalContext.current
        val app = context.applicationContext as AuralTuneApplication

        // Phase 2: the ViewModel owns the audio stack (engine / musicController /
        // deviceManager) and closes it in onCleared(), so it survives rotation. The
        // Composable no longer creates or disposes any of it.
        val vm: AutoEqViewModel = viewModel(factory = AutoEqViewModelFactory(app))
        val musicController = vm.musicController

        val snackbarHostState = remember { SnackbarHostState() }

        // P3-A: surface the latest import message as a one-shot Snackbar.
        val importMsg by vm.importMessage.collectAsState()
        LaunchedEffect(importMsg) {
            val msg = importMsg
            if (msg != null) {
                snackbarHostState.showSnackbar(msg)
                vm.consumeImportMessage()
            }
        }

        Scaffold(
            topBar = {
                TopAppBar(title = { Text(stringResource(R.string.app_name)) })
            },
            snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        ) { padding ->
            AuralTuneScreen(
                vm = vm,
                musicController = musicController,
                contentPadding = padding,
            )
        }
    }
}

@Composable
private fun AuralTuneScreen(
    vm: AutoEqViewModel,
    musicController: MusicPlayerController,
    contentPadding: PaddingValues,
) {
    val query by vm.query.collectAsState()
    val results by vm.results.collectAsState()
    val catalog by vm.catalogState.collectAsState()
    val selected by vm.selectedProfile.collectAsState()
    val correctionEnabled by vm.isCorrectionEnabled.collectAsState()
    val preampEnabled by vm.preampEnabled.collectAsState()
    val favorites by vm.favoriteIds.collectAsState()
    val diag by vm.diagnostics.collectAsState()
    val bandGains by vm.bandGains.collectAsState()
    val eqPresets by vm.graphicEqPresets.collectAsState()
    val selectedPresetId by vm.selectedGraphicEqPresetId.collectAsState()
    val gainLimit by vm.gainLimitDb.collectAsState()
    val showPreamp by vm.showPreampOnGraph.collectAsState()
    val recents by vm.recentProfiles.collectAsState()

    // Phase 2 PoC(외부앱 effect-control-session 측정)는 DebugSupport.AudioFxProbeCard 안으로
    // 완전히 이동했다(src/debug=실제, src/release=no-op). main은 probe를 전혀 참조하지 않는다.

    // 음악 파일 선택 launcher — 본문에서 remember (LazyColumn item 안에 두면
    // 스크롤로 item이 detach될 때 ActivityResult 콜백이 유실될 수 있음).
    val audioPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (BuildConfig.DEBUG) android.util.Log.i("MusicPlay", "picked uri=$uri")
        if (uri != null) {
            musicController.play(uri)
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // 로컬 음악 재생 (T1) — launcher는 본문에서 remember됨.
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // TODO(i18n): 문자열 리소스화
                val playCtx = LocalContext.current
                // 항상 SAF 파일 선택기를 띄워 사용자가 곡을 고르고 바꿀 수 있게 한다(debug/release 동일).
                // SAF 핸들러(DocumentsUI)가 없거나 차단된 일부 기기(전용 DAP 등)에서는 launch()가
                // ActivityNotFoundException을 던질 수 있어 무반응처럼 보인다 → 가드 + Toast 피드백.
                Button(
                    onClick = {
                        try {
                            audioPicker.launch(arrayOf("audio/*"))
                        } catch (e: android.content.ActivityNotFoundException) {
                            if (BuildConfig.DEBUG) android.util.Log.w("MusicPlay", "no SAF picker", e)
                            android.widget.Toast.makeText(
                                playCtx,
                                "이 기기에서 파일 선택기를 열 수 없습니다 (SAF 미지원)",
                                android.widget.Toast.LENGTH_LONG,
                            ).show()
                        }
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("음악 파일 선택") }
                OutlinedButton(
                    onClick = { musicController.stop() },
                    modifier = Modifier.weight(1f),
                ) { Text("정지") }
                // 디버그 전용 빠른 재생(자동 테스트용): MediaStore 첫 곡 즉시 재생. release엔 없음.
                if (BuildConfig.DEBUG) {
                    OutlinedButton(onClick = {
                        DebugSupport.firstPlayableUri(playCtx)?.let { musicController.play(it) }
                    }) { Text("첫곡") }
                }
            }
        }

        // Kill switch — top zone (moved up from below the results).
        item {
            val killed by vm.killSwitchEngaged.collectAsState()
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                stringResource(R.string.kill_switch_label),
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                stringResource(R.string.kill_switch_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(checked = killed, onCheckedChange = vm::setKillSwitchEngaged)
                    }
                }
            }
        }

        // Search field
        item {
            OutlinedTextField(
                value = query,
                onValueChange = vm::onQueryChanged,
                placeholder = { Text(stringResource(R.string.search_placeholder)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                modifier = Modifier.fillMaxWidth(),
            )
        }

        // 최근/빠른 선택 프로파일 스피너 — 이전 선택 10개(최초 1회 큐레이션 pre-seed).
        if (recents.isNotEmpty()) {
            item {
                var expanded by remember { mutableStateOf(false) }
                Box(modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = { expanded = true },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = selected?.name?.let { "프로파일: $it" } ?: "프로파일 빠른 선택 ▾",
                        )
                    }
                    DropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false },
                    ) {
                        recents.forEach { entry ->
                            DropdownMenuItem(
                                text = { Text(entry.name) },
                                onClick = {
                                    expanded = false
                                    vm.selectProfile(entry)
                                },
                            )
                        }
                    }
                }
            }
        }

        // P3-A: SAF picker + clear cache.
        item {
            val context = LocalContext.current
            val importLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.OpenDocument(),
            ) { uri ->
                if (uri != null) {
                    val displayName = queryDisplayName(context, uri)
                    vm.importFromUri(uri, displayName)
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = { importLauncher.launch(arrayOf("text/plain", "application/octet-stream", "*/*")) },
                    modifier = Modifier.weight(1f),
                ) { Text(stringResource(R.string.import_button)) }
                OutlinedButton(
                    onClick = vm::clearNetworkCache,
                    modifier = Modifier.weight(1f),
                ) { Text(stringResource(R.string.clear_cache_button)) }
            }
        }

        // Catalog state / results
        when (val state = catalog) {
            CatalogState.Loading -> item {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.catalog_loading),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            is CatalogState.Error -> item {
                EmptyStateMessage(state.message)
            }
            CatalogState.Idle -> item {
                EmptyStateMessage(stringResource(R.string.catalog_offline))
            }
            is CatalogState.Loaded -> {
                if (results.entries.isEmpty()) {
                    // 검색어가 없으면 "결과 없음"이 아니라 검색 안내를 띄운다(카탈로그는 정상 로드됨).
                    val emptyMsg = if (query.isBlank()) R.string.search_prompt else R.string.no_results
                    item { EmptyStateMessage(stringResource(emptyMsg)) }
                } else {
                    items(results.entries, key = { it.id }) { entry ->
                        CatalogEntryRow(
                            entry = entry,
                            isSelected = selected?.id == entry.id,
                            isFavorite = entry.id in favorites,
                            onClick = { vm.selectProfile(entry) },
                            onToggleFavorite = { vm.toggleFavorite(entry.id) },
                        )
                    }
                }
            }
        }

        // 상태 카드(프로파일명 + 닫기) — AutoEQ 토글 바로 위로 이동(그래픽 EQ 위에 프로파일+토글을 묶음).
        item {
            StatusCard(
                profile = selected,
                onClear = vm::clearProfile,
            )
        }

        // AutoEQ correction + preamp 토글 — 그래픽 EQ 바로 위로 이동.
        item {
            AutoEqToggleCard(
                isEnabled = correctionEnabled,
                preampEnabled = preampEnabled,
                onToggleCorrection = vm::toggleCorrection,
                onTogglePreamp = vm::togglePreamp,
            )
        }

        // 그래픽 EQ (20밴드, Manual chain) — AutoEQ 프로파일과 독립·합성.
        item {
            GraphicEqCard(
                bandGains = bandGains,
                // 그래프엔 correction이 켜졌을 때만 AutoEQ 곡선/preamp를 합성한다(실제 신호와 일치).
                autoEqFilters = if (correctionEnabled) selected?.filters.orEmpty() else emptyList(),
                presets = eqPresets,
                selectedPresetId = selectedPresetId,
                gainLimitDb = gainLimit,
                preampDb = if (correctionEnabled) selected?.preampDB ?: 0f else 0f,
                showPreamp = showPreamp,
                // 실제 출력 반영: correction+preamp 둘 다 켜졌을 때만 곡선을 preampDb만큼 하강.
                preampApplied = correctionEnabled && preampEnabled,
                onBandChange = vm::setBandGain,
                onGainLimitChange = vm::setGainLimit,
                onToggleShowPreamp = vm::setShowPreampOnGraph,
                onReset = vm::resetGraphicEq,
                onSavePreset = vm::saveGraphicEqPreset,
                onLoadPreset = vm::loadGraphicEqPreset,
                onDeletePreset = vm::deleteGraphicEqPreset,
            )
        }

        // Diagnostics card (collapsed by default).
        // P3-C: surface device hash + current sample rate, not just engine counters.
        item {
            DiagnosticsCard(
                diagnostics = diag,
                currentSampleRate = vm.engineSampleRate(),
                deviceHash = vm.currentDeviceHash(),
            )
        }

        // Phase 2 PoC — 외부앱 AudioFx 신호 측정 카드. debug=실제 측정 UI / release=no-op(빈 컴포저블).
        // BuildConfig.DEBUG로 item 자체를 감싸 release에선 빈 슬롯/간격도 생기지 않게 한다.
        if (BuildConfig.DEBUG) {
            item { DebugSupport.AudioFxProbeCard() }
        }

        // Attribution footer
        item {
            Text(
                text = stringResource(R.string.autoeq_attribution),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
            )
        }
    }
}

/**
 * Resolve the SAF picker's display name into a human-readable profile name.
 * Falls back to the URI's last path segment when DISPLAY_NAME isn't reported
 * (e.g. some pickers return null cursor for the column).
 */
private fun queryDisplayName(context: android.content.Context, uri: android.net.Uri): String {
    return runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
        }
    }.getOrNull() ?: uri.lastPathSegment ?: "Imported profile"
}
