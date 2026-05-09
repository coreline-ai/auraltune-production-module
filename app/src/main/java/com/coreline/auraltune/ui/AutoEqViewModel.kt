// AutoEqViewModel.kt
// MVP screen state holder. Bridges AutoEqApi + SettingsStore + AudioEngine into Compose-readable
// StateFlows. All state mutation goes through this class so the UI stays declarative.
package com.coreline.auraltune.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.coreline.audio.AudioEngine
import com.coreline.autoeq.AutoEqApi
import com.coreline.autoeq.model.AutoEqCatalogEntry
import com.coreline.autoeq.model.AutoEqProfile
import com.coreline.autoeq.model.CatalogState
import com.coreline.autoeq.search.AutoEqSearchEngine
import com.coreline.auraltune.data.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Single ViewModel for the MVP screen. Public state is exposed as [StateFlow] so Compose
 * can collect it via `collectAsStateWithLifecycle`. All side effects (engine updates,
 * persistence, telemetry) run inside [viewModelScope].
 */
@OptIn(FlowPreview::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AutoEqViewModel(
    private val api: AutoEqApi,
    private val settings: SettingsStore,
    private val engine: AudioEngine,
    private val deviceManager: com.coreline.auraltune.audio.DeviceAutoEqManager,
) : ViewModel() {

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    /** Catalog availability state (idle / loading / loaded / error). */
    val catalogState: StateFlow<CatalogState> =
        api.observe(viewModelScope)
            .stateIn(viewModelScope, SharingStarted.Eagerly, CatalogState.Idle)

    /**
     * Search results, debounced to match the Swift AuralTune feel.
     *
     * P1-1: search runs the 6k-entry 3-tier scoring loop synchronously, which
     * costs ~50-200ms on mid-tier devices. We dispatch to [Dispatchers.Default]
     * so the main thread is never blocked while typing.
     */
    val results: StateFlow<AutoEqSearchEngine.Result> =
        _query
            .debounce(DEBOUNCE_MS)
            .flatMapLatest { q ->
                flow {
                    emit(withContext(Dispatchers.Default) { api.search(q) })
                }
            }
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(SUBSCRIBE_TIMEOUT_MS),
                AutoEqSearchEngine.Result(emptyList(), 0),
            )

    private val _selectedProfile = MutableStateFlow<AutoEqProfile?>(null)
    val selectedProfile: StateFlow<AutoEqProfile?> = _selectedProfile.asStateFlow()

    val isCorrectionEnabled: StateFlow<Boolean> =
        settings.autoEqEnabled.stateIn(
            viewModelScope, SharingStarted.Eagerly, SettingsStore.DEFAULT_AUTOEQ_ENABLED,
        )

    val preampEnabled: StateFlow<Boolean> =
        settings.preampEnabled.stateIn(
            viewModelScope, SharingStarted.Eagerly, SettingsStore.DEFAULT_PREAMP_ENABLED,
        )

    val favoriteIds: StateFlow<Set<String>> =
        settings.favoriteProfileIds.stateIn(
            viewModelScope, SharingStarted.Eagerly, emptySet(),
        )

    /** Polled native diagnostics. RT-safe: only [AudioEngine.readDiagnostics] is called. */
    val diagnostics: StateFlow<AudioEngine.Diagnostics> =
        flow {
            while (true) {
                emit(engine.readDiagnostics())
                kotlinx.coroutines.delay(DIAG_POLL_MS)
            }
        }.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(SUBSCRIBE_TIMEOUT_MS),
            AudioEngine.Diagnostics(
                xrunCount = 0,
                nonFiniteResetCount = 0,
                sampleRateChangeCount = 0,
                configSwapCount = 0,
                totalProcessedFrames = 0,
                appliedGeneration = 0,
                autoEqActiveCount = 0,
            ),
        )

    /**
     * Phase 7 — observe local kill-switch state. Declared BEFORE [init] so the
     * init-block coroutines that read it see a non-null StateFlow.
     */
    val killSwitchEngaged: StateFlow<Boolean> =
        settings.killSwitchEngaged.stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            SettingsStore.DEFAULT_KILL_SWITCH_ENGAGED,
        )

    init {
        // P0-3: ALL writes to engine.updateAutoEq / engine.clearAutoEq go through
        // DeviceAutoEqManager. The ViewModel never calls those directly. This
        // gives us a single owner for the engine's AutoEQ chain so route changes
        // and UI selections cannot race / overwrite each other.
        //
        // Restore behavior on launch:
        //   - If saved id == null:           deviceManager.clearProfileForCurrentDevice()
        //   - If saved id matches catalog:   deviceManager.selectProfileForCurrentDevice(entry)
        //   - If saved id is stale:          purge from settings (don't keep retrying)
        //
        // Subsequent runtime changes:
        //   - settings.selectedProfileId → null   ⇒ deviceManager.clearProfileForCurrentDevice()
        //   - UI selectProfile(entry)             ⇒ delegates to deviceManager directly
        //   - AudioDeviceCallback route change   ⇒ deviceManager reconcile() handles it
        viewModelScope.launch {
            val savedId = settings.selectedProfileId.first()
            if (savedId == null) {
                // No saved profile — manager will clear the engine on its first reconcile().
                _selectedProfile.value = null
                return@launch
            }

            // Wait for catalog to be loaded so we can look up the entry.
            val loaded = catalogState.first { it is CatalogState.Loaded } as CatalogState.Loaded
            val entry = loaded.entries.firstOrNull { it.id == savedId }
            if (entry == null) {
                // Saved profile no longer in catalog (upstream removed / hash changed).
                settings.setSelectedProfileId(null)
                return@launch
            }
            val applied = deviceManager.selectProfileForCurrentDevice(entry)
            if (applied) {
                _selectedProfile.value = api.resolve(entry)?.validated()
            }
        }

        // Track explicit clears at runtime (settings.selectedProfileId → null).
        viewModelScope.launch {
            settings.selectedProfileId.collect { id ->
                if (id == null && _selectedProfile.value != null) {
                    _selectedProfile.value = null
                    deviceManager.clearProfileForCurrentDevice()
                }
            }
        }

        viewModelScope.launch {
            combine(
                isCorrectionEnabled,
                preampEnabled,
                killSwitchEngaged,
            ) { c, p, killed -> Triple(c, p, killed) }
                .collect { (correction, preamp, killed) ->
                    // Kill switch wins over user toggles. When engaged the engine is
                    // forced into pure passthrough — both manual and AutoEQ disabled,
                    // preamp bypassed. P1-4: also propagate to the repository so
                    // catalog refresh + profile fetch short-circuit immediately.
                    api.repository.setKillSwitchEngaged(killed)
                    engine.setAutoEqEnabled(if (killed) false else correction)
                    engine.setManualEqEnabled(if (killed) false else true)
                    engine.setAutoEqPreampEnabled(if (killed) false else preamp)
                }
        }

        // P1-5: keep the LRU profile cache from evicting whatever the user is
        // actively listening to (selected) or has favorited. Repository.setProtectedIds
        // is a fire-and-forget callback the eviction policy honors at sweep time.
        viewModelScope.launch {
            combine(
                settings.selectedProfileId,
                settings.favoriteProfileIds,
            ) { selectedId, favs ->
                buildSet {
                    if (selectedId != null) add(selectedId)
                    addAll(favs)
                }
            }.collect { protectedIds ->
                api.repository.setProtectedIds(protectedIds)
            }
        }
    }

    fun setKillSwitchEngaged(engaged: Boolean) {
        viewModelScope.launch { settings.setKillSwitchEngaged(engaged) }
    }

    /** Phase 6 / P3-C: current native sample rate for the diagnostics card. */
    fun engineSampleRate(): Int = engine.sampleRate

    /**
     * Hashed identifier of the currently routed output device, suitable for
     * surfacing in the Diagnostics card without leaking PII (BT MAC etc.).
     */
    fun currentDeviceHash(): String? = deviceManager.currentDeviceHash

    // ----------------- Public actions -----------------

    fun onQueryChanged(q: String) { _query.value = q }

    /**
     * UI-side selection. P0-3: delegates to [DeviceAutoEqManager], the single
     * owner of writes to [AudioEngine.updateAutoEq]. The manager persists the
     * selection (global + per-device) and applies the profile to the engine.
     */
    fun selectProfile(entry: AutoEqCatalogEntry) {
        viewModelScope.launch {
            val applied = deviceManager.selectProfileForCurrentDevice(entry)
            if (applied) {
                _selectedProfile.value = api.resolve(entry)?.validated()
            }
        }
    }

    fun clearProfile() {
        viewModelScope.launch {
            deviceManager.clearProfileForCurrentDevice()
            _selectedProfile.value = null
        }
    }

    fun toggleCorrection() {
        viewModelScope.launch {
            settings.setAutoEqEnabled(!isCorrectionEnabled.value)
        }
    }

    fun togglePreamp() {
        viewModelScope.launch {
            settings.setPreampEnabled(!preampEnabled.value)
        }
    }

    fun toggleFavorite(id: String) {
        viewModelScope.launch {
            settings.toggleFavorite(id)
        }
    }

    // ----------------- P3-A user import (SAF) -----------------

    /**
     * Result of a single import attempt — surfaced via [importMessage] for one-shot
     * snackbar / toast UX. Nullable to indicate "nothing to display".
     */
    private val _importMessage = MutableStateFlow<String?>(null)
    val importMessage: StateFlow<String?> = _importMessage.asStateFlow()

    /** ACK an [importMessage] after the UI has surfaced it. */
    fun consumeImportMessage() { _importMessage.value = null }

    /**
     * Run a SAF-picked URI through the repository. The display name is what the
     * picker reported (typically the file basename). On success, the new entry
     * appears in search and we auto-select it.
     */
    fun importFromUri(uri: android.net.Uri, displayName: String) {
        viewModelScope.launch {
            val cleanName = displayName
                .substringBeforeLast('.', missingDelimiterValue = displayName)
                .trim()
                .ifBlank { "Imported profile" }
            val result = api.importFromUri(uri, cleanName)
            when (result) {
                is com.coreline.autoeq.model.ParseResult.Success -> {
                    _importMessage.value = "Imported \"$cleanName\""
                    // Auto-select the freshly imported profile.
                    val entry = AutoEqCatalogEntry(
                        id = result.profile.id,
                        name = result.profile.name,
                        measuredBy = "Imported",
                        relativePath = "",
                    )
                    selectProfile(entry)
                }
                is com.coreline.autoeq.model.ParseResult.Failure -> {
                    val reason = result.error::class.simpleName ?: "ParseError"
                    _importMessage.value = "Import failed: $reason"
                }
            }
        }
    }

    /** Phase 4 user action — wipe the network cache. Imports are NOT touched. */
    fun clearNetworkCache() {
        viewModelScope.launch {
            api.repository.clearCache()
            _importMessage.value = "Cache cleared"
        }
    }

    /** Delete a user-imported profile (no-op for fetched profiles). */
    fun deleteImported(id: String) {
        viewModelScope.launch {
            api.deleteImported(id)
            if (selectedProfile.value?.id == id) clearProfile()
        }
    }

    // (P0-3 — applyToEngine() removed. All AutoEQ writes go through
    //  DeviceAutoEqManager, which is the single owner of engine.updateAutoEq.)

    companion object {
        const val DEBOUNCE_MS = 150L
        const val DIAG_POLL_MS = 1_000L
        private const val SUBSCRIBE_TIMEOUT_MS = 5_000L
    }
}
