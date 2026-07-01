// AuralTuneApp.kt
// Top-level Composable. Bottom-nav 3-tab shell:
//   ① 플레이어(홈) — full player + queue   ② AutoEQ   ③ OPRA
// The single playback session (MusicPlayerController) is shared: a docked MINI PLAYER stays
// visible on the AutoEQ/OPRA tabs while audio plays, and tapping it returns to the Player tab.
// AutoEQ/OPRA tabs reuse the same correction UI (status · compare · search · graphic EQ · preamp
// · diagnostics · about), differing only in the search source.
@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.coreline.auraltune.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.Player
import com.coreline.autoeq.model.CatalogState
import com.coreline.auraltune.opra.model.OpraCatalogEntry
import com.coreline.auraltune.AuralTuneApplication
import com.coreline.auraltune.BuildConfig
import com.coreline.auraltune.R
import com.coreline.auraltune.audio.AlbumArtCache
import com.coreline.auraltune.audio.PlaybackUiState
import com.coreline.auraltune.audio.TrackInfo
import com.coreline.auraltune.audio.eq.EqMode
import com.coreline.auraltune.audio.eq.GraphicEqBands
import kotlinx.coroutines.flow.StateFlow

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
        LaunchedEffect(playback.playbackError) {
            val msg = playback.playbackError
            if (msg != null) {
                snackbarHostState.showSnackbar(msg)
                musicController.consumePlaybackError()
            }
        }
        val opraDetail by vm.opraDetail.collectAsState()
        val opraSyncState by vm.opraSyncState.collectAsState()
        // '현재 사용중'(실제 엔진 적용) 프로파일 = 활성 provider의 선택. 플레이어·미니 배지에 사용.
        val activeProfile by vm.activeProfile.collectAsState()
        val correctionProvider by vm.correctionProvider.collectAsState()
        val correctionSource: String? = activeProfile?.let {
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
        val playerPicker = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenMultipleDocuments(),
        ) { uris -> if (!uris.isNullOrEmpty()) musicController.addToQueue(uris) }
        var bluetoothPermissionGranted by remember {
            mutableStateOf(isBluetoothConnectGranted(context))
        }
        var bluetoothPermissionDismissed by rememberSaveable { mutableStateOf(false) }
        val bluetoothPermissionLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestPermission(),
        ) { granted ->
            bluetoothPermissionGranted = granted
            bluetoothPermissionDismissed = true
        }
        // 알림 권한(API 33+): 미디어 재생 알림(잠금화면/셰이드 컨트롤)이 보이도록 최초 1회 요청.
        // 거부해도 재생 자체는 정상 동작한다.
        val notificationPermissionLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestPermission(),
        ) { /* no-op: 재생은 권한과 무관하게 동작 */ }
        if (Build.VERSION.SDK_INT >= 33) {
            LaunchedEffect(Unit) {
                val granted = context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED
                if (!granted) notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        val showBluetoothPermissionNotice =
            Build.VERSION.SDK_INT >= 31 && !bluetoothPermissionGranted && !bluetoothPermissionDismissed
        val openPlayerPicker: () -> Unit = {
            try {
                playerPicker.launch(arrayOf("audio/*"))
            } catch (e: android.content.ActivityNotFoundException) {
                if (BuildConfig.DEBUG) android.util.Log.w("MusicPlay", "no SAF picker", e)
                android.widget.Toast.makeText(
                    context, context.getString(R.string.saf_unavailable), android.widget.Toast.LENGTH_LONG,
                ).show()
            }
        }

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
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                CenterAlignedTopAppBar(
                    colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                        titleContentColor = MaterialTheme.colorScheme.primary,
                    ),
                    title = {
                    Text(
                        when (selectedTab) {
                            AppTab.PLAYER -> stringResource(R.string.app_name)
                            AppTab.AUTOEQ -> stringResource(
                                R.string.app_title_with_source,
                                stringResource(R.string.app_name),
                                stringResource(R.string.tab_autoeq),
                            )
                            AppTab.OPRA -> stringResource(
                                R.string.app_title_with_source,
                                stringResource(R.string.app_name),
                                stringResource(R.string.tab_opra),
                            )
                        },
                    )
                    },
                )
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
                    val navColors = NavigationBarItemDefaults.colors(
                        selectedIconColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        selectedTextColor = MaterialTheme.colorScheme.secondaryContainer,
                        indicatorColor = MaterialTheme.colorScheme.secondaryContainer,
                        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    NavigationBar(
                        containerColor = MaterialTheme.colorScheme.background,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                    ) {
                        NavigationBarItem(
                            selected = selectedTab == AppTab.PLAYER,
                            onClick = { selectedTab = AppTab.PLAYER },
                            icon = {
                                Icon(
                                    Icons.Default.LibraryMusic,
                                    contentDescription = stringResource(R.string.tab_player),
                                )
                            },
                            label = { Text(stringResource(R.string.tab_player)) },
                            colors = navColors,
                        )
                        NavigationBarItem(
                            selected = selectedTab == AppTab.AUTOEQ,
                            onClick = { selectedTab = AppTab.AUTOEQ },
                            icon = {
                                // 현재 적용 중인 보정 소스 탭에만 점 배지를 표시 — '서 있는 탭'이 아니라
                                // '실제 적용 중인 소스'를 한눈에 알 수 있게 한다.
                                BadgedBox(badge = { if (correctionSource == "AutoEQ") Badge() }) {
                                    Icon(
                                        Icons.Default.GraphicEq,
                                        contentDescription = stringResource(R.string.tab_autoeq),
                                    )
                                }
                            },
                            label = { Text(stringResource(R.string.tab_autoeq)) },
                            colors = navColors,
                        )
                        NavigationBarItem(
                            selected = selectedTab == AppTab.OPRA,
                            onClick = { selectedTab = AppTab.OPRA },
                            icon = {
                                BadgedBox(badge = { if (correctionSource == "OPRA") Badge() }) {
                                    Icon(
                                        Icons.Default.Tune,
                                        contentDescription = stringResource(R.string.tab_opra),
                                    )
                                }
                            },
                            label = { Text(stringResource(R.string.tab_opra)) },
                            colors = navColors,
                        )
                    }
                }
            },
            snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        ) { padding ->
            when (selectedTab) {
                AppTab.PLAYER -> PlayerScreen(
                    vm = vm,
                    state = playback,
                    contentPadding = padding,
                    listState = playerListState,
                    correctionSource = correctionSource,
                    correctionName = activeProfile?.name,
                    onAddFiles = openPlayerPicker,
                    showBluetoothPermissionNotice = showBluetoothPermissionNotice,
                    onRequestBluetoothPermission = {
                        if (Build.VERSION.SDK_INT >= 31) {
                            bluetoothPermissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
                        }
                    },
                    onDismissBluetoothPermissionNotice = { bluetoothPermissionDismissed = true },
                )
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
    onAddFiles: () -> Unit,
    showBluetoothPermissionNotice: Boolean,
    onRequestBluetoothPermission: () -> Unit,
    onDismissBluetoothPermissionNotice: () -> Unit,
) {
    val musicController = vm.musicController
    val listenMode by vm.listenMode.collectAsState()
    val preampEnabled by vm.preampEnabled.collectAsState()
    // 재생곡 커버에서 뽑은 강조색 — 슬라이더·트랜스포트에 반영(곡마다 변함, 없으면 테마색).
    val accent = rememberArtworkAccent(state.artwork, MaterialTheme.colorScheme.secondaryContainer)
    val onAccent = contentColorOn(accent)
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(contentPadding),
        state = listState,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Now-playing + seek + transport.
        item {
            AuralTunePanel(elevated = true) {
                // 재생곡 앨범아트를 블러 처리한 배경(없으면 기본 커버) 위에 Now-playing 콘텐츠를 얹는다.
                BlurredArtBackground(artwork = state.artwork, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    SpectrumVisualizer(
                        spectrum = musicController.spectrum,
                        formatLabel = state.audioFormatLabel(),
                        accent = accent,
                    )
                    Spacer(Modifier.height(16.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AlbumArtThumbnail(artwork = state.artwork, modifier = Modifier.size(64.dp))
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (state.hasMedia) state.title else stringResource(R.string.player_no_media),
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 2,
                            )
                            // 파일명 아래 가수 · 앨범(태그). 없으면 줄 숨김, 길면 말줄임.
                            formatArtistAlbum(state.artist, state.album)?.let { sub ->
                                Text(
                                    text = sub,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
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
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    val dur = state.durationMs
                    Slider(
                        value = if (dur > 0) (state.positionMs.toFloat() / dur).coerceIn(0f, 1f) else 0f,
                        onValueChange = { f -> if (dur > 0) musicController.seekTo((f * dur).toLong()) },
                        enabled = state.hasMedia && dur > 0,
                        colors = SliderDefaults.colors(
                            thumbColor = accent,
                            activeTrackColor = accent,
                            inactiveTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                            disabledThumbColor = MaterialTheme.colorScheme.outlineVariant,
                            disabledActiveTrackColor = MaterialTheme.colorScheme.outlineVariant,
                            disabledInactiveTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        ),
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
                        // 셔플(랜덤) — 이전곡 왼쪽
                        IconButton(onClick = musicController::toggleShuffle, enabled = state.hasMedia) {
                            Icon(
                                Icons.Default.Shuffle,
                                contentDescription = stringResource(
                                    if (state.shuffleEnabled) R.string.player_shuffle_on else R.string.player_shuffle_off,
                                ),
                                tint = if (state.shuffleEnabled) accent
                                       else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        IconButton(onClick = musicController::previous, enabled = state.hasMedia) {
                            Icon(Icons.Default.SkipPrevious, contentDescription = stringResource(R.string.player_prev))
                        }
                        Spacer(Modifier.width(12.dp))
                        FilledIconButton(
                            onClick = musicController::togglePlayPause,
                            enabled = state.hasMedia,
                            modifier = Modifier.size(64.dp),
                            colors = IconButtonDefaults.filledIconButtonColors(
                                containerColor = accent,
                                contentColor = onAccent,
                            ),
                        ) {
                            Icon(
                                if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = stringResource(if (state.isPlaying) R.string.player_pause else R.string.player_play),
                                modifier = Modifier.size(34.dp),
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        IconButton(onClick = musicController::next, enabled = state.hasMedia) {
                            Icon(Icons.Default.SkipNext, contentDescription = stringResource(R.string.player_next))
                        }
                        Spacer(Modifier.width(8.dp))
                        // 반복(한번만/전체/1곡) — 다음곡 오른쪽. 탭마다 OFF→ALL→ONE 순환.
                        IconButton(onClick = musicController::cycleRepeatMode, enabled = state.hasMedia) {
                            Icon(
                                if (state.repeatMode == Player.REPEAT_MODE_ONE) Icons.Default.RepeatOne
                                else Icons.Default.Repeat,
                                contentDescription = stringResource(
                                    when (state.repeatMode) {
                                        Player.REPEAT_MODE_ONE -> R.string.player_repeat_one
                                        Player.REPEAT_MODE_ALL -> R.string.player_repeat_all
                                        else -> R.string.player_repeat_off
                                    },
                                ),
                                tint = if (state.repeatMode == Player.REPEAT_MODE_OFF)
                                       MaterialTheme.colorScheme.onSurfaceVariant
                                       else accent,
                            )
                        }
                    }
                }
                }
            }
        }

        if (showBluetoothPermissionNotice) {
            item {
                BluetoothPermissionNotice(
                    onRequest = onRequestBluetoothPermission,
                    onDismiss = onDismissBluetoothPermissionNotice,
                )
            }
        }

        // Player correction controls.
        item {
            PlayerCorrectionControls(
                mode = listenMode,
                preampEnabled = preampEnabled,
                onModeSelect = vm::setListenMode,
                onTogglePreamp = vm::togglePreamp,
            )
        }
        // Queue / playlist.
        item {
            PlaylistHeader(
                count = state.queue.size,
                onAddFiles = onAddFiles,
            )
        }
        if (state.queue.isEmpty()) {
            item { EmptyStateMessage(stringResource(R.string.player_queue_empty)) }
        } else {
            itemsIndexed(state.queue, key = { i, t -> "$i:${t.uri}" }) { index, track ->
                QueueRow(
                    track = track,
                    isCurrent = index == state.currentIndex,
                    isPlaying = state.isPlaying,
                    artCache = vm.albumArtCache,
                    onClick = { musicController.playIndex(index) },
                    onRemove = { musicController.removeFromQueue(index) },
                )
            }
        }
    }
}

/** 재생 중 음원의 실시간 주파수 스펙트럼을 막대로 표시(아래→위). post-EQ 실제 값. */
@Composable
private fun SpectrumVisualizer(
    spectrum: StateFlow<FloatArray>,
    formatLabel: String?,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    val levels by spectrum.collectAsState()
    val slot = MaterialTheme.colorScheme.surfaceContainerLowest
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(156.dp)
            .clip(MaterialTheme.shapes.medium)
            .background(slot),
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 14.dp),
        ) {
            val n = levels.size
            if (n == 0) return@Canvas
            val gap = 3.dp.toPx()
            val barW = ((size.width - gap * (n - 1)) / n).coerceAtLeast(1f)
            val minH = 2.dp.toPx()
            val radius = CornerRadius(barW / 2f, barW / 2f)
            for (i in 0 until n) {
                val lvl = levels[i].coerceIn(0f, 1f)
                val h = (size.height * lvl).coerceIn(minH, size.height)
                val x = i * (barW + gap)
                drawRoundRect(
                    color = accent.copy(alpha = 0.40f + 0.60f * lvl),
                    topLeft = Offset(x, size.height - h),
                    size = Size(barW, h),
                    cornerRadius = radius,
                )
            }
        }
        formatLabel?.let {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(10.dp)
                    .clip(MaterialTheme.shapes.small)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.88f))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelSmall,
                    color = accent,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

private fun PlaybackUiState.audioFormatLabel(): String? {
    val bitDepth = audioBitDepth ?: return null
    val sampleRate = audioSampleRateHz ?: return null
    if (!hasMedia || bitDepth <= 0 || sampleRate <= 0) return null
    return "$bitDepth/$sampleRate"
}

/** "가수 · 앨범" 보조 줄 텍스트(둘 다 없으면 null). now-playing 헤더 + 큐 행 공용. */
private fun formatArtistAlbum(artist: String?, album: String?): String? = when {
    !artist.isNullOrBlank() && !album.isNullOrBlank() -> "$artist · $album"
    !artist.isNullOrBlank() -> artist
    !album.isNullOrBlank() -> album
    else -> null
}

@Composable
private fun PlayerCorrectionControls(
    mode: ListenMode,
    preampEnabled: Boolean,
    onModeSelect: (ListenMode) -> Unit,
    onTogglePreamp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AuralTunePanel(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // 외곽 컨테이너 트랙 제거 — 그래픽/파라메트릭 토글과 동일한 칩(GainLimitButton)으로 통일.
            GainLimitButton(
                label = stringResource(R.string.player_mode_original),
                selected = mode == ListenMode.ORIGINAL,
                onClick = { onModeSelect(ListenMode.ORIGINAL) },
                modifier = Modifier.weight(1f),
            )
            GainLimitButton(
                label = stringResource(R.string.player_mode_eq_applied),
                selected = mode == ListenMode.AUTOEQ,
                onClick = { onModeSelect(ListenMode.AUTOEQ) },
                modifier = Modifier.weight(1f),
            )
            GainLimitButton(
                label = stringResource(R.string.player_mode_custom),
                selected = mode == ListenMode.USER,
                onClick = { onModeSelect(ListenMode.USER) },
                modifier = Modifier.weight(1f),
            )
            // 프리앰프는 독립 토글이라 칩과 구분되는 자체 스타일 유지(높이만 칩과 맞춤).
            PlayerPreampButton(
                enabled = preampEnabled,
                onClick = onTogglePreamp,
                modifier = Modifier.width(96.dp),
            )
        }
    }
}

@Composable
private fun PlayerPreampButton(
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.height(38.dp),
        shape = MaterialTheme.shapes.small,
        border = BorderStroke(
            if (enabled) 2.dp else 1.dp,
            if (enabled) MaterialTheme.colorScheme.secondaryContainer
            else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
        ),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = if (enabled) {
                MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.22f)
            } else {
                MaterialTheme.colorScheme.surfaceContainerLow
            },
            contentColor = if (enabled) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        ),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
    ) {
        Text(
            text = stringResource(R.string.player_preamp),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (enabled) FontWeight.Bold else FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun BluetoothPermissionNotice(
    onRequest: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AuralTunePanel(modifier = modifier) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = stringResource(R.string.bt_permission_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.secondaryContainer,
            )
            Text(
                text = stringResource(R.string.bt_permission_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = onRequest,
                    modifier = Modifier.weight(1f),
                    shape = MaterialTheme.shapes.medium,
                    colors = sourceFilledButtonColors(),
                ) {
                    Text(stringResource(R.string.bt_permission_allow))
                }
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                    shape = MaterialTheme.shapes.medium,
                    colors = sourceOutlinedButtonColors(),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.58f)),
                ) {
                    Text(stringResource(R.string.bt_permission_later))
                }
            }
        }
    }
}

@Composable
private fun PlaylistHeader(
    count: Int,
    onAddFiles: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.player_queue_title, count),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.weight(1f),
        )
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(MaterialTheme.shapes.medium)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .clickable(onClick = onAddFiles),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Default.Add,
                contentDescription = stringResource(R.string.player_add_files),
                tint = MaterialTheme.colorScheme.secondaryContainer,
            )
        }
    }
}

/** One queue row. Current track is highlighted; the X removes it. */
@Composable
private fun QueueRow(
    track: TrackInfo,
    isCurrent: Boolean,
    isPlaying: Boolean,
    artCache: AlbumArtCache,
    onClick: () -> Unit,
    onRemove: () -> Unit,
) {
    val meta = rememberTrackMeta(artCache, track.uri)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(if (isCurrent) MaterialTheme.colorScheme.surfaceContainerHighest else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 트랙별 커버 썸네일(없으면 기본커버). 현재 곡은 위에 재생/일시정지 오버레이.
        Box(modifier = Modifier.size(40.dp)) {
            AlbumArtThumbnail(
                artwork = meta.artwork,
                modifier = Modifier.matchParentSize(),
                cornerRadius = 6.dp,
            )
            if (isCurrent) {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color.Black.copy(alpha = 0.4f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = stringResource(
                            if (isPlaying) R.string.player_queue_item_playing
                            else R.string.player_queue_item_selected,
                        ),
                        tint = Color.White,
                    )
                }
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = track.title,
                style = MaterialTheme.typography.bodyMedium,
                color = if (isCurrent) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // 파일명 아래 가수 · 앨범(태그). 없으면 숨김, 길면 말줄임.
            formatArtistAlbum(meta.artist, meta.album)?.let { sub ->
                Text(
                    text = sub,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
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
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .clickable(onClick = onExpand),
    ) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        // 진행바(얇게) — durationMs 있을 때만.
        if (state.durationMs > 0) {
            val frac = (state.positionMs.toFloat() / state.durationMs).coerceIn(0f, 1f)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp)
                    .background(MaterialTheme.colorScheme.surfaceContainerLowest),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(frac)
                        .height(2.dp)
                        .background(MaterialTheme.colorScheme.secondaryContainer),
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AlbumArtThumbnail(
                artwork = state.artwork,
                modifier = Modifier.size(40.dp),
                cornerRadius = 6.dp,
            )
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
                overflow = TextOverflow.Ellipsis,
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
private fun sourceTextFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = MaterialTheme.colorScheme.onSurface,
    unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
    focusedPlaceholderColor = MaterialTheme.colorScheme.outline,
    unfocusedPlaceholderColor = MaterialTheme.colorScheme.outline,
    focusedTrailingIconColor = MaterialTheme.colorScheme.secondaryContainer,
    unfocusedTrailingIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
    cursorColor = MaterialTheme.colorScheme.secondaryContainer,
    focusedBorderColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.72f),
    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.58f),
    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
)

@Composable
private fun sourceFilledButtonColors() = ButtonDefaults.buttonColors(
    containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.18f),
    contentColor = MaterialTheme.colorScheme.secondaryContainer,
    disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
)

@Composable
private fun sourceOutlinedButtonColors() = ButtonDefaults.outlinedButtonColors(
    containerColor = MaterialTheme.colorScheme.surfaceContainer,
    contentColor = MaterialTheme.colorScheme.onSurface,
    disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
)

@Composable
private fun ProfilePickerButtonContent(
    title: String,
    selectedName: String?,
    placeholder: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.secondaryContainer,
            )
            Text(
                text = selectedName ?: placeholder,
                style = MaterialTheme.typography.labelLarge,
                color = if (selectedName != null) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(10.dp))
        Icon(
            imageVector = Icons.Default.ExpandMore,
            contentDescription = stringResource(R.string.profile_picker_expand),
            tint = MaterialTheme.colorScheme.secondaryContainer,
        )
    }
}

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
    // 탭별 독립 선택: 이 탭(isOpra)의 선택만 표시·그래프에 사용.
    val selectedAutoEq by vm.selectedAutoEqProfile.collectAsState()
    val selectedOpra by vm.selectedOpraProfile.collectAsState()
    val selected = if (isOpra) selectedOpra else selectedAutoEq
    val recentOpra by vm.recentOpraProfiles.collectAsState()
    val listenMode by vm.listenMode.collectAsState()
    val autoEqAudible = listenMode != ListenMode.ORIGINAL
    val preampEnabled by vm.preampEnabled.collectAsState()
    val bandGains by vm.bandGains.collectAsState()
    val toneGains by vm.toneGains.collectAsState()
    val toneEqPresets by vm.toneEqPresets.collectAsState()
    val selectedTonePresetId by vm.selectedToneEqPresetId.collectAsState()
    val eqPresets by vm.graphicEqPresets.collectAsState()
    val selectedPresetId by vm.selectedGraphicEqPresetId.collectAsState()
    val gainLimit by vm.gainLimitDb.collectAsState()
    val showPreamp by vm.showPreampOnGraph.collectAsState()
    val eqMode by vm.eqMode.collectAsState()
    val qScale by vm.graphicQScale.collectAsState()
    val parametricBands by vm.parametricBands.collectAsState()
    val selectedParametricBandId by vm.selectedParametricBandId.collectAsState()
    val parametricPresets by vm.parametricPresets.collectAsState()
    val selectedParametricPresetId by vm.selectedParametricEqPresetId.collectAsState()
    val selectedParametricPresetSource by vm.selectedParametricEqPresetSource.collectAsState()
    val parametricPresetDirty by vm.parametricEqPresetDirty.collectAsState()
    val manualSpecs by vm.manualResponseSpecs.collectAsState()
    val engineRateHz by vm.engineSampleRateHz.collectAsState()
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
                    shape = MaterialTheme.shapes.medium,
                    colors = sourceTextFieldColors(),
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
                        OutlinedButton(
                            onClick = { expanded = true },
                            modifier = Modifier.fillMaxWidth(),
                            shape = MaterialTheme.shapes.medium,
                            border = BorderStroke(
                                1.dp,
                                if (selected != null) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.54f)
                                else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.58f),
                            ),
                            colors = sourceOutlinedButtonColors(),
                        ) {
                            ProfilePickerButtonContent(
                                title = stringResource(R.string.profile_picker_title),
                                selectedName = selected?.name,
                                placeholder = stringResource(R.string.profile_picker_placeholder),
                            )
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
                        shape = MaterialTheme.shapes.medium,
                        colors = sourceFilledButtonColors(),
                    ) { Text(stringResource(R.string.import_button)) }
                    OutlinedButton(
                        onClick = vm::clearNetworkCache,
                        modifier = Modifier.weight(1f),
                        shape = MaterialTheme.shapes.medium,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.58f)),
                        colors = sourceOutlinedButtonColors(),
                    ) {
                        Text(stringResource(R.string.clear_cache_button))
                    }
                }
            }
            item {
                OutlinedButton(
                    onClick = { vm.checkProfileUpdates() },
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.35f)),
                    colors = sourceOutlinedButtonColors(),
                ) {
                    Text(stringResource(R.string.profile_update_check))
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
                    placeholder = { Text(stringResource(R.string.opra_search_placeholder)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    shape = MaterialTheme.shapes.medium,
                    colors = sourceTextFieldColors(),
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
                    shape = MaterialTheme.shapes.medium,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.35f)),
                    colors = sourceOutlinedButtonColors(),
                ) {
                    Text(
                        if (opraRefreshing) stringResource(R.string.opra_refreshing)
                        else stringResource(R.string.opra_refresh),
                    )
                }
            }
            if (recentOpra.isNotEmpty()) {
                item {
                    var expanded by remember { mutableStateOf(false) }
                    Box(modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(
                            onClick = { expanded = true },
                            modifier = Modifier.fillMaxWidth(),
                            shape = MaterialTheme.shapes.medium,
                            border = BorderStroke(
                                1.dp,
                                if (selectedOpra != null) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.54f)
                                else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.58f),
                            ),
                            colors = sourceOutlinedButtonColors(),
                        ) {
                            ProfilePickerButtonContent(
                                title = stringResource(R.string.opra_profile_picker_title),
                                selectedName = selectedOpra?.name,
                                placeholder = stringResource(R.string.opra_profile_picker_placeholder),
                            )
                        }
                        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                            recentOpra.forEach { recent ->
                                DropdownMenuItem(
                                    text = { Text(recent.name) },
                                    onClick = { expanded = false; vm.selectOpraRecent(recent) },
                                )
                            }
                        }
                    }
                }
            }
            if (opraResults.isEmpty()) {
                item {
                    EmptyStateMessage(
                        if (opraRefreshing) stringResource(R.string.opra_empty_loading)
                        else stringResource(R.string.opra_empty_prompt),
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

        // ── 탭별 선택 프로파일 + 비교 모드 (검색/결과 아래·그래픽 EQ 바로 위) ──
        item {
            val thisTabSource = if (isOpra) "OPRA" else "AutoEQ"
            // 이 탭의 선택이 곧 '현재 사용중'(엔진 적용)인가 = 활성 소스가 이 탭인가.
            val isInUse = sourceLabel == thisTabSource
            StatusCard(
                profile = selected,
                sourceLabel = if (selected != null) thisTabSource else null,
                onClear = if (isOpra) vm::clearOpraSelection else vm::clearAutoEqSelection,
                inUse = isInUse,
                onUse = if (isOpra) vm::useOpraSelection else vm::useAutoEqSelection,
            )
        }
        item {
            // 사용자 EQ가 실제로 소리를 바꾸는지(비평탄)는 현재 모드의 적용 스펙으로 판단.
            val userBands = manualSpecs.isNotEmpty()
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
            EqModeToggle(mode = eqMode, onSelect = vm::setEqMode)
        }
        item {
            val autoFilters = if (autoEqAudible) selected?.filters.orEmpty() else emptyList()
            val preamp = if (autoEqAudible) selected?.preampDB ?: 0f else 0f
            when (eqMode) {
                EqMode.GRAPHIC -> GraphicEqCard(
                    bandGains = bandGains,
                    autoEqFilters = autoFilters,
                    presets = eqPresets,
                    selectedPresetId = selectedPresetId,
                    gainLimitDb = gainLimit,
                    qScale = qScale,
                    sampleRate = engineRateHz.toDouble(),
                    preampDb = preamp,
                    showPreamp = showPreamp,
                    preampApplied = autoEqAudible && preampEnabled,
                    onBandChange = vm::setBandGain,
                    onGainLimitChange = vm::setGainLimit,
                    onQScaleChange = vm::setGraphicQScale,
                    onToggleShowPreamp = vm::setShowPreampOnGraph,
                    onReset = vm::resetGraphicEq,
                    onSavePreset = vm::saveGraphicEqPreset,
                    onLoadPreset = vm::loadGraphicEqPreset,
                    onDeletePreset = vm::deleteGraphicEqPreset,
                )
                EqMode.PARAMETRIC -> ParametricEqCard(
                    bands = parametricBands,
                    selectedId = selectedParametricBandId,
                    presets = parametricPresets,
                    selectedPresetId = selectedParametricPresetId,
                    selectedPresetSource = selectedParametricPresetSource,
                    presetDirty = parametricPresetDirty,
                    autoEqFilters = autoFilters,
                    gainLimitDb = gainLimit,
                    sampleRate = engineRateHz.toDouble(),
                    onApplyPreset = vm::applyParametricPreset,
                    onSavePreset = vm::saveCurrentParametricPreset,
                    onDeleteUserPreset = vm::deleteUserParametricPreset,
                    onAddBand = vm::addParametricBand,
                    onSelectBand = vm::selectParametricBand,
                    onDragBand = { id, f, g -> vm.updateParametricBand(id, freqHz = f, gainDb = g) },
                    onChangeType = { id, t -> vm.updateParametricBand(id, type = t) },
                    onChangeQ = { id, q -> vm.updateParametricBand(id, q = q) },
                    onRemoveBand = vm::removeParametricBand,
                    onReset = vm::resetParametricEq,
                )
                EqMode.TONE -> ToneEqCard(
                    toneGains = toneGains,
                    manualSpecs = manualSpecs,
                    autoEqFilters = autoFilters,
                    presets = toneEqPresets,
                    selectedPresetId = selectedTonePresetId,
                    gainLimitDb = gainLimit,
                    sampleRate = engineRateHz.toDouble(),
                    preampDb = preamp,
                    showPreamp = showPreamp,
                    preampApplied = autoEqAudible && preampEnabled,
                    onToneChange = vm::setToneGain,
                    onSavePreset = vm::saveToneEqPreset,
                    onLoadPreset = vm::loadToneEqPreset,
                    onDeletePreset = vm::deleteToneEqPreset,
                    onReset = vm::resetTone,
                )
            }
        }
        item { AutoEqPreampCard(preampEnabled = preampEnabled, onTogglePreamp = vm::togglePreamp) }
        // 진단 정보 + 외부앱 신호 측정(PoC)은 개발/QA 전용 — 릴리스 UI에선 숨긴다.
        if (BuildConfig.DEBUG) {
            item {
                DiagnosticsCard(
                    diagnostics = diag,
                    currentSampleRate = vm.engineSampleRate(),
                    deviceHash = vm.currentDeviceHash(),
                )
            }
            item { DebugSupport.AudioFxProbeCard() }
        }
        item {
            AboutCard(
                appVersion = BuildConfig.VERSION_NAME,
                isOpra = isOpra,
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
                    isSelected -> MaterialTheme.colorScheme.secondaryContainer
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
                text = entry.license + (if (!entry.isSupported) stringResource(R.string.opra_unsupported_suffix) else ""),
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
    }.getOrNull() ?: uri.lastPathSegment ?: context.getString(R.string.imported_profile_fallback)
}

private fun isBluetoothConnectGranted(context: android.content.Context): Boolean {
    if (Build.VERSION.SDK_INT < 31) return true
    return context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) ==
        PackageManager.PERMISSION_GRANTED
}
