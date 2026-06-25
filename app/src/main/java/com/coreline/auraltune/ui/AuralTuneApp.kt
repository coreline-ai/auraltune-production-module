// AuralTuneApp.kt
// Top-level Composable. Bottom-nav 3-tab shell:
//   ① 플레이어(홈) — full player + queue   ② AutoEQ   ③ OPRA
// The single playback session (MusicPlayerController) is shared: a docked MINI PLAYER stays
// visible on the AutoEQ/OPRA tabs while audio plays, and tapping it returns to the Player tab.
// AutoEQ/OPRA tabs reuse the same correction UI (status · compare · search · graphic EQ · preamp
// · diagnostics · about), differing only in the search source.
@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.coreline.auraltune.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.coreline.autoeq.model.CatalogState
import com.coreline.auraltune.opra.model.OpraCatalogEntry
import com.coreline.auraltune.AuralTuneApplication
import com.coreline.auraltune.BuildConfig
import com.coreline.auraltune.R
import com.coreline.auraltune.audio.PlaybackUiState
import com.coreline.auraltune.audio.TrackInfo
import com.coreline.auraltune.audio.eq.GraphicEqBands

/** Bottom-nav destinations. Player is home. */
private enum class AppTab { PLAYER, AUTOEQ, OPRA }

/**
 * Top-level AuralTune Composable. Hosts [AuralTuneTheme] + a bottom-nav [Scaffold] with the docked
 * mini-player. The [AutoEqViewModel] (owns the shared audio stack) is retained across tab switches.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AuralTuneApp() {
    AuralTuneTheme {
        val context = LocalContext.current
        val app = context.applicationContext as AuralTuneApplication
        val vm: AutoEqViewModel = viewModel(factory = AutoEqViewModelFactory(app))
        val musicController = vm.musicController

        val snackbarHostState = remember { SnackbarHostState() }
        val importMsg by vm.importMessage.collectAsState()
        LaunchedEffect(importMsg) {
            val msg = importMsg
            if (msg != null) {
                snackbarHostState.showSnackbar(msg)
                vm.consumeImportMessage()
            }
        }

        var selectedTab by rememberSaveable { mutableStateOf(AppTab.PLAYER) }

        val playback by musicController.state.collectAsState()
        val opraDetail by vm.opraDetail.collectAsState()
        val opraSyncState by vm.opraSyncState.collectAsState()
        // 적용된 보정의 소스 배지(AutoEQ/OPRA) — 프로파일이 있을 때만. 상태카드·플레이어·미니에 공통 사용.
        val selectedProfile by vm.selectedProfile.collectAsState()
        val correctionProvider by vm.correctionProvider.collectAsState()
        val correctionSource: String? = selectedProfile?.let {
            if (correctionProvider == "OPRA") "OPRA" else "AutoEQ"
        }

        // 외부 링크(라이선스/측정 출처) 열기 — ActivityNotFound 가드 + Toast.
        val linkCtx = LocalContext.current
        val openUrl: (String) -> Unit = { url ->
            try {
                linkCtx.startActivity(
                    android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            } catch (e: android.content.ActivityNotFoundException) {
                if (BuildConfig.DEBUG) android.util.Log.w("AboutLink", "no browser for $url", e)
                android.widget.Toast.makeText(
                    linkCtx, linkCtx.getString(R.string.opra_link_open_failed), android.widget.Toast.LENGTH_SHORT,
                ).show()
            }
        }

        // 탭 전환에도 스크롤 위치 보존 — AuralTuneApp 스코프에서 hoist(탭 콘텐츠만 dispose됨).
        val playerListState = rememberLazyListState()
        val autoEqListState = rememberLazyListState()
        val opraListState = rememberLazyListState()

        // OPRA 행 탭 → 상세 시트(어느 탭 위에서도 오버레이).
        opraDetail?.let { detail ->
            OpraProfileDetailDialog(
                profile = detail,
                onApply = { vm.applyOpraDetail() },
                onOpenUrl = openUrl,
                onDismiss = { vm.dismissOpraDetail() },
            )
        }

        Scaffold(
            topBar = {
                TopAppBar(title = {
                    Text(
                        when (selectedTab) {
                            AppTab.PLAYER -> stringResource(R.string.app_name)
                            AppTab.AUTOEQ -> stringResource(R.string.app_name) + " · AutoEQ"
                            AppTab.OPRA -> stringResource(R.string.app_name) + " · OPRA"
                        },
                    )
                })
            },
            bottomBar = {
                Column {
                    // 미니 플레이어: 플레이어 탭 밖 + 재생 세션 존재 시에만 상주. 탭하면 플레이어로 확장.
                    if (selectedTab != AppTab.PLAYER && playback.hasMedia) {
                        MiniPlayer(
                            state = playback,
                            correctionSource = correctionSource,
                            onPlayPause = musicController::togglePlayPause,
                            onNext = musicController::next,
                            onExpand = { selectedTab = AppTab.PLAYER },
                        )
                    }
                    NavigationBar {
                        NavigationBarItem(
                            selected = selectedTab == AppTab.PLAYER,
                            onClick = { selectedTab = AppTab.PLAYER },
                            icon = { Icon(Icons.Default.LibraryMusic, contentDescription = null) },
                            label = { Text(stringResource(R.string.tab_player)) },
                        )
                        NavigationBarItem(
                            selected = selectedTab == AppTab.AUTOEQ,
                            onClick = { selectedTab = AppTab.AUTOEQ },
                            icon = { Icon(Icons.Default.GraphicEq, contentDescription = null) },
                            label = { Text("AutoEQ") },
                        )
                        NavigationBarItem(
                            selected = selectedTab == AppTab.OPRA,
                            onClick = { selectedTab = AppTab.OPRA },
                            icon = { Icon(Icons.Default.Tune, contentDescription = null) },
                            label = { Text("OPRA") },
                        )
                    }
                }
            },
            snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        ) { padding ->
            when (selectedTab) {
                AppTab.PLAYER -> PlayerScreen(vm, playback, padding, playerListState, correctionSource, selectedProfile?.name)
                AppTab.AUTOEQ -> CorrectionScreen(vm, isOpra = false, contentPadding = padding, listState = autoEqListState, openUrl = openUrl, opraSyncState = opraSyncState, sourceLabel = correctionSource)
                AppTab.OPRA -> CorrectionScreen(vm, isOpra = true, contentPadding = padding, listState = opraListState, openUrl = openUrl, opraSyncState = opraSyncState, sourceLabel = correctionSource)
            }
        }
    }
}

// ── Player tab (home) — full transport + queue ─────────────────────────────────

@Composable
private fun PlayerScreen(
    vm: AutoEqViewModel,
    state: PlaybackUiState,
    contentPadding: PaddingValues,
    listState: LazyListState,
    correctionSource: String?,
    correctionName: String?,
) {
    val musicController = vm.musicController
    val ctx = LocalContext.current
    // 멀티 파일 선택 → 큐에 추가(첫 추가면 자동 재생).
    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris -> if (!uris.isNullOrEmpty()) musicController.addToQueue(uris) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(contentPadding),
        state = listState,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Now-playing + seek + transport.
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = if (state.hasMedia) state.title else stringResource(R.string.player_no_media),
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 2,
                    )
                    // 재생 중인 음원에 걸린 보정(소스 배지 + 프로파일명). 보정 없으면 숨김.
                    if (correctionSource != null) {
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            SourceBadge(correctionSource)
                            correctionName?.let {
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = it,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    val dur = state.durationMs
                    Slider(
                        value = if (dur > 0) (state.positionMs.toFloat() / dur).coerceIn(0f, 1f) else 0f,
                        onValueChange = { f -> if (dur > 0) musicController.seekTo((f * dur).toLong()) },
                        enabled = state.hasMedia && dur > 0,
                    )
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Text(formatTime(state.positionMs), style = MaterialTheme.typography.labelSmall)
                        Spacer(Modifier.weight(1f))
                        Text(formatTime(state.durationMs), style = MaterialTheme.typography.labelSmall)
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = musicController::previous, enabled = state.hasMedia) {
                            Icon(Icons.Default.SkipPrevious, contentDescription = stringResource(R.string.player_prev))
                        }
                        Spacer(Modifier.width(16.dp))
                        FilledIconButton(
                            onClick = musicController::togglePlayPause,
                            enabled = state.hasMedia,
                            modifier = Modifier.size(64.dp),
                        ) {
                            Icon(
                                if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = stringResource(if (state.isPlaying) R.string.player_pause else R.string.player_play),
                            )
                        }
                        Spacer(Modifier.width(16.dp))
                        IconButton(onClick = musicController::next, enabled = state.hasMedia) {
                            Icon(Icons.Default.SkipNext, contentDescription = stringResource(R.string.player_next))
                        }
                    }
                }
            }
        }

        // File actions.
        item {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        try {
                            picker.launch(arrayOf("audio/*"))
                        } catch (e: android.content.ActivityNotFoundException) {
                            if (BuildConfig.DEBUG) android.util.Log.w("MusicPlay", "no SAF picker", e)
                            android.widget.Toast.makeText(
                                ctx, ctx.getString(R.string.saf_unavailable), android.widget.Toast.LENGTH_LONG,
                            ).show()
                        }
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.player_add_files))
                }
                OutlinedButton(
                    onClick = { musicController.clearQueue() },
                    enabled = state.hasMedia,
                    modifier = Modifier.weight(1f),
                ) { Text(stringResource(R.string.player_clear_queue)) }
                // 디버그 전용: MediaStore 첫 곡을 큐에 추가(자동 테스트용).
                if (BuildConfig.DEBUG) {
                    OutlinedButton(onClick = {
                        DebugSupport.firstPlayableUri(ctx)?.let { musicController.addToQueue(listOf(it)) }
                    }) { Text("첫곡") }
                }
            }
        }

        // Queue / playlist.
        if (state.queue.isEmpty()) {
            item { EmptyStateMessage(stringResource(R.string.player_queue_empty)) }
        } else {
            item {
                Text(
                    text = stringResource(R.string.player_queue_title, state.queue.size),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            itemsIndexed(state.queue, key = { i, t -> "$i:${t.uri}" }) { index, track ->
                QueueRow(
                    track = track,
                    isCurrent = index == state.currentIndex,
                    isPlaying = state.isPlaying,
                    onClick = { musicController.playIndex(index) },
                    onRemove = { musicController.removeFromQueue(index) },
                )
            }
        }
    }
}

/** One queue row. Current track is highlighted; the X removes it. */
@Composable
private fun QueueRow(
    track: TrackInfo,
    isCurrent: Boolean,
    isPlaying: Boolean,
    onClick: () -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = when {
                isCurrent && isPlaying -> Icons.Default.Pause
                isCurrent -> Icons.Default.PlayArrow
                else -> Icons.Default.MusicNote
            },
            contentDescription = null,
            tint = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = track.title,
            style = MaterialTheme.typography.bodyMedium,
            color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onRemove) {
            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.player_remove))
        }
    }
}

/** Compact mini-player docked above the bottom nav on the AutoEQ/OPRA tabs. Tap to expand. */
@Composable
private fun MiniPlayer(
    state: PlaybackUiState,
    correctionSource: String?,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onExpand: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().clickable(onClick = onExpand)) {
        HorizontalDivider()
        // 진행바(얇게) — durationMs 있을 때만.
        if (state.durationMs > 0) {
            val frac = (state.positionMs.toFloat() / state.durationMs).coerceIn(0f, 1f)
            Box(modifier = Modifier.fillMaxWidth().height(2.dp)) {
                Box(modifier = Modifier.fillMaxWidth(frac).height(2.dp))
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.MusicNote, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(10.dp))
            // 현재 보정 소스 배지(있을 때) — 듣는 중에도 무엇이 걸렸는지 한눈에.
            correctionSource?.let {
                SourceBadge(it)
                Spacer(Modifier.width(8.dp))
            }
            Text(
                text = state.title.ifBlank { stringResource(R.string.player_no_media) },
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onPlayPause) {
                Icon(
                    if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = stringResource(if (state.isPlaying) R.string.player_pause else R.string.player_play),
                )
            }
            IconButton(onClick = onNext) {
                Icon(Icons.Default.SkipNext, contentDescription = stringResource(R.string.player_next))
            }
        }
    }
}

// ── AutoEQ / OPRA correction tab (shared; differs only in the search source) ───────

@Composable
private fun CorrectionScreen(
    vm: AutoEqViewModel,
    isOpra: Boolean,
    contentPadding: PaddingValues,
    listState: LazyListState,
    openUrl: (String) -> Unit,
    opraSyncState: com.coreline.auraltune.opra.model.OpraSyncState?,
    sourceLabel: String?,
) {
    val selected by vm.selectedProfile.collectAsState()
    val listenMode by vm.listenMode.collectAsState()
    val autoEqAudible = listenMode != ListenMode.ORIGINAL
    val preampEnabled by vm.preampEnabled.collectAsState()
    val bandGains by vm.bandGains.collectAsState()
    val eqPresets by vm.graphicEqPresets.collectAsState()
    val selectedPresetId by vm.selectedGraphicEqPresetId.collectAsState()
    val gainLimit by vm.gainLimitDb.collectAsState()
    val showPreamp by vm.showPreampOnGraph.collectAsState()
    val diag by vm.diagnostics.collectAsState()

    // AutoEQ source
    val query by vm.query.collectAsState()
    val results by vm.results.collectAsState()
    val catalog by vm.catalogState.collectAsState()
    val favorites by vm.favoriteIds.collectAsState()
    val recents by vm.recentProfiles.collectAsState()
    // OPRA source
    val opraResults by vm.opraResults.collectAsState()
    val opraQuery by vm.opraQuery.collectAsState()
    val opraRefreshing by vm.opraRefreshing.collectAsState()

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(contentPadding),
        state = listState,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (!isOpra) {
            // ── AutoEQ 소스 ──
            item {
                OutlinedTextField(
                    value = query,
                    onValueChange = vm::onQueryChanged,
                    placeholder = { Text(stringResource(R.string.search_placeholder)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { vm.onQueryChanged("") }) {
                                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.clear_search))
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (recents.isNotEmpty()) {
                item {
                    var expanded by remember { mutableStateOf(false) }
                    Box(modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
                            Text(selected?.name?.let { "프로파일: $it" } ?: "프로파일 빠른 선택 ▾")
                        }
                        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                            recents.forEach { entry ->
                                DropdownMenuItem(
                                    text = { Text(entry.name) },
                                    onClick = { expanded = false; vm.selectProfile(entry) },
                                )
                            }
                        }
                    }
                }
            }
            item {
                val context = LocalContext.current
                val importLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.OpenDocument(),
                ) { uri -> if (uri != null) vm.importFromUri(uri, queryDisplayName(context, uri)) }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { importLauncher.launch(arrayOf("text/plain", "application/octet-stream", "*/*")) },
                        modifier = Modifier.weight(1f),
                    ) { Text(stringResource(R.string.import_button)) }
                    OutlinedButton(onClick = vm::clearNetworkCache, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.clear_cache_button))
                    }
                }
            }
            item {
                OutlinedButton(onClick = { vm.checkProfileUpdates() }, modifier = Modifier.fillMaxWidth()) {
                    Text("프로파일 업데이트 확인")
                }
            }
            when (val state = catalog) {
                CatalogState.Loading -> item {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(8.dp))
                        Text(stringResource(R.string.catalog_loading), style = MaterialTheme.typography.bodySmall)
                    }
                }
                is CatalogState.Error -> item { EmptyStateMessage(state.message) }
                CatalogState.Idle -> item { EmptyStateMessage(stringResource(R.string.catalog_offline)) }
                is CatalogState.Loaded -> {
                    if (results.entries.isEmpty()) {
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
        } else {
            // ── OPRA 소스 ──
            item {
                OutlinedTextField(
                    value = opraQuery,
                    onValueChange = vm::onOpraQueryChanged,
                    placeholder = { Text("OPRA 헤드폰 검색…") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    trailingIcon = {
                        if (opraQuery.isNotEmpty()) {
                            IconButton(onClick = { vm.onOpraQueryChanged("") }) {
                                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.clear_search))
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                OutlinedButton(
                    onClick = { vm.refreshOpra() },
                    enabled = !opraRefreshing,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (opraRefreshing) "OPRA 갱신 중…" else "OPRA 데이터 갱신") }
            }
            if (opraResults.isEmpty()) {
                item {
                    EmptyStateMessage(
                        if (opraRefreshing) "OPRA 데이터를 불러오는 중…"
                        else "OPRA 헤드폰을 검색하거나 'OPRA 데이터 갱신'을 누르세요",
                    )
                }
            } else {
                items(opraResults, key = { it.id }) { entry ->
                    OpraCatalogRow(
                        entry = entry,
                        isSelected = selected?.id == entry.id,
                        onClick = { vm.openOpraDetail(entry) },
                    )
                }
            }
            item {
                Text(
                    text = stringResource(R.string.opra_tab_footer),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }

        // ── 공유 보정 영역 ── (선택 프로파일 + 비교 모드를 검색/결과 아래·그래픽 EQ 바로 위에 배치)
        item { StatusCard(profile = selected, sourceLabel = sourceLabel, onClear = vm::clearProfile) }
        item {
            val userBands = GraphicEqBands.toSpecs(bandGains).isNotEmpty()
            val subtitle = when (listenMode) {
                ListenMode.ORIGINAL -> stringResource(R.string.listen_mode_hint_original)
                ListenMode.AUTOEQ -> stringResource(R.string.listen_mode_hint_autoeq)
                ListenMode.USER ->
                    if (userBands) stringResource(R.string.listen_mode_hint_user)
                    else stringResource(R.string.listen_mode_hint_user_flat)
            }
            ListenModeBar(mode = listenMode, subtitle = subtitle, onSelect = vm::setListenMode)
        }
        item {
            GraphicEqCard(
                bandGains = bandGains,
                autoEqFilters = if (autoEqAudible) selected?.filters.orEmpty() else emptyList(),
                presets = eqPresets,
                selectedPresetId = selectedPresetId,
                gainLimitDb = gainLimit,
                preampDb = if (autoEqAudible) selected?.preampDB ?: 0f else 0f,
                showPreamp = showPreamp,
                preampApplied = autoEqAudible && preampEnabled,
                onBandChange = vm::setBandGain,
                onGainLimitChange = vm::setGainLimit,
                onToggleShowPreamp = vm::setShowPreampOnGraph,
                onReset = vm::resetGraphicEq,
                onSavePreset = vm::saveGraphicEqPreset,
                onLoadPreset = vm::loadGraphicEqPreset,
                onDeletePreset = vm::deleteGraphicEqPreset,
            )
        }
        item { AutoEqPreampCard(preampEnabled = preampEnabled, onTogglePreamp = vm::togglePreamp) }
        item {
            DiagnosticsCard(
                diagnostics = diag,
                currentSampleRate = vm.engineSampleRate(),
                deviceHash = vm.currentDeviceHash(),
            )
        }
        if (BuildConfig.DEBUG) {
            item { DebugSupport.AudioFxProbeCard() }
        }
        item {
            AboutCard(
                appVersion = BuildConfig.VERSION_NAME,
                opraSnapshotCommit = opraSyncState?.opraCommit ?: opraSyncState?.snapshotVersion,
                opraSourceUrl = opraSyncState?.sourceUrl,
                onOpenUrl = openUrl,
            )
        }
    }
}

/** mm:ss formatter for playback times. */
private fun formatTime(ms: Long): String {
    val totalSec = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(totalSec / 60, totalSec % 60)
}

/** One OPRA catalog row: product + vendor • author + license. Unsupported rows are dimmed/disabled. */
@Composable
private fun OpraCatalogRow(
    entry: OpraCatalogEntry,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = entry.isSupported, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.productName,
                style = MaterialTheme.typography.bodyLarge,
                color = when {
                    isSelected -> MaterialTheme.colorScheme.primary
                    !entry.isSupported -> MaterialTheme.colorScheme.onSurfaceVariant
                    else -> MaterialTheme.colorScheme.onSurface
                },
            )
            Text(
                text = entry.vendorName + (entry.author?.let { " • $it" } ?: ""),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = entry.license + (if (!entry.isSupported) " • 적용 불가" else ""),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Resolve the SAF picker's display name into a human-readable profile name.
 * Falls back to the URI's last path segment when DISPLAY_NAME isn't reported.
 */
private fun queryDisplayName(context: android.content.Context, uri: android.net.Uri): String {
    return runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
        }
    }.getOrNull() ?: uri.lastPathSegment ?: "Imported profile"
}
