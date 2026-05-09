// AuralTuneApp.kt
// Top-level Composable. Wires the AutoEqViewModel into a single-screen Scaffold and hosts
// the Phase 0 / Phase 6 MVP layout: status card, search section, diagnostics card,
// test-tone toggle, and AutoEq attribution.
package com.coreline.auraltune.ui

import androidx.compose.foundation.layout.Arrangement
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
import com.coreline.auraltune.R
import com.coreline.auraltune.audio.AudioPlayerService
import com.coreline.auraltune.audio.DeviceAutoEqManager
import com.coreline.auraltune.audio.TestTone

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
        val locator = app.serviceLocator

        // Per-activity AudioEngine + AudioPlayerService. The engine is closed alongside
        // the AudioPlayerService when the composable leaves composition.
        val engine = remember { locator.createAudioEngine() }
        val player = remember { AudioPlayerService(engine, sampleRate = engine.sampleRate) }
        val tone = remember { TestTone(engine.sampleRate) }
        val managerScope = androidx.compose.runtime.rememberCoroutineScope()
        val deviceManager = remember {
            DeviceAutoEqManager(
                context = context,
                engine = engine,
                api = locator.autoEqApi,
                settings = locator.settingsStore,
                coroutineScope = managerScope,
            )
        }
        DisposableEffectClose(player, engine, deviceManager)

        val vm: AutoEqViewModel = viewModel(
            factory = AutoEqViewModelFactory(
                locator.autoEqApi,
                locator.settingsStore,
                engine,
                deviceManager,
            ),
        )

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
                player = player,
                tone = tone,
                contentPadding = padding,
            )
        }
    }
}

@Composable
private fun AuralTuneScreen(
    vm: AutoEqViewModel,
    player: AudioPlayerService,
    tone: TestTone,
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

    var testToneOn by remember { mutableStateOf(false) }
    LaunchedEffect(testToneOn) {
        if (testToneOn) {
            tone.reset()
            player.start { out, n -> tone.fill(out, n) }
        } else {
            player.stop()
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Test tone toggle
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.test_tone_label),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = if (testToneOn) stringResource(R.string.test_tone_on)
                    else stringResource(R.string.test_tone_off),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(0.dp))
                Switch(checked = testToneOn, onCheckedChange = { testToneOn = it })
            }
        }

        // Status card
        item {
            StatusCard(
                profile = selected,
                isEnabled = correctionEnabled,
                preampEnabled = preampEnabled,
                onClear = vm::clearProfile,
                onToggleCorrection = vm::toggleCorrection,
                onTogglePreamp = vm::togglePreamp,
            )
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
                    item { EmptyStateMessage(stringResource(R.string.no_results)) }
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

        // P2-E kill switch UI.
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

        // Diagnostics card (collapsed by default).
        // P3-C: surface device hash + current sample rate, not just engine counters.
        item {
            DiagnosticsCard(
                diagnostics = diag,
                currentSampleRate = vm.engineSampleRate(),
                deviceHash = vm.currentDeviceHash(),
            )
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

/**
 * Closes the audio player and engine when the host Composable leaves composition. Kept as a
 * dedicated helper so the call-site is one line and the cleanup order (stop player → close
 * engine) is enforced.
 */
@Composable
private fun DisposableEffectClose(
    player: AudioPlayerService,
    engine: com.coreline.audio.AudioEngine,
    deviceManager: DeviceAutoEqManager,
) {
    androidx.compose.runtime.DisposableEffect(Unit) {
        // Start the device-route manager immediately so the engine sees the correct
        // sample rate AND restores any saved per-device profile before the user
        // hits "Test tone" / playback. Stop ordering on dispose:
        //   1. deviceManager.close() — no more native updateSampleRate / updateAutoEq.
        //   2. player.close()        — joins the audio thread (no more engine.process).
        //   3. engine.close()        — frees the native handle. Lifecycle-safe per P0-3.
        deviceManager.start()
        onDispose {
            deviceManager.close()
            player.close()
            engine.close()
        }
    }
}
