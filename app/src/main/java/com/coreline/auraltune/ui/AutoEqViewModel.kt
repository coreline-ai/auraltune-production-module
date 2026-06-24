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
import com.coreline.autoeq.repository.DeltaResult
import com.coreline.autoeq.search.AutoEqSearchEngine
import com.coreline.auraltune.AuralTuneApplication
import com.coreline.auraltune.BuildConfig
import com.coreline.auraltune.audio.MusicPlayerController
import com.coreline.auraltune.data.SettingsStore
import com.coreline.auraltune.audio.eq.GraphicEqBands
import com.coreline.auraltune.opra.OpraSyncResult
import com.coreline.auraltune.opra.model.OpraCatalogEntry
import com.coreline.auraltune.opra.model.OpraEqProfile
import com.coreline.auraltune.opra.model.OpraSyncState
import com.coreline.auraltune.opra.toAutoEqProfile
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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Single ViewModel for the MVP screen. Public state is exposed as [StateFlow] so Compose
 * can collect it via `collectAsStateWithLifecycle`. All side effects (engine updates,
 * persistence, telemetry) run inside [viewModelScope].
 */
@OptIn(FlowPreview::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AutoEqViewModel(
    app: AuralTuneApplication,
) : ViewModel() {

    // ── Phase 2: the ViewModel OWNS the audio stack ────────────────────────────────
    // Created here (not in the host Composable) so it survives configuration changes
    // (rotation). Closed in onCleared(). The Composable only reads state / calls actions.
    // Application context is intentional — a retained ViewModel must not hold an Activity.
    private val locator = app.serviceLocator
    private val api: AutoEqApi = locator.autoEqApi
    private val settings: SettingsStore = locator.settingsStore
    private val engine: AudioEngine = locator.createAudioEngine()

    /** Exposed so the screen can drive local music playback (T1). Owned/closed here. */
    val musicController: MusicPlayerController = MusicPlayerController(app, engine)

    private val deviceManager = com.coreline.auraltune.audio.DeviceAutoEqManager(
        context = app,
        engine = engine,
        api = api,
        settings = settings,
        coroutineScope = viewModelScope,
    )

    // OPRA comparison source (fully separate data module). Shares THIS engine via the adapter.
    private val opraRepository = locator.opraRepository

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

    /**
     * A/B/C 청취 비교 모드 — 킬스위치 + AutoEQ correction 토글을 대체한다.
     *   ORIGINAL = EQ 미적용(순수 패스스루), AUTOEQ = 프로파일만, USER = 프로파일 + 그래픽 EQ.
     * 기본 USER(그래픽 EQ가 평탄이라 첫 실행 체감은 AUTOEQ와 동일).
     */
    val listenMode: StateFlow<ListenMode> =
        settings.listenMode.map { ListenMode.fromKey(it) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, ListenMode.USER)

    fun setListenMode(mode: ListenMode) {
        viewModelScope.launch { settings.setListenMode(mode.key) }
    }

    val preampEnabled: StateFlow<Boolean> =
        settings.preampEnabled.stateIn(
            viewModelScope, SharingStarted.Eagerly, SettingsStore.DEFAULT_PREAMP_ENABLED,
        )

    val favoriteIds: StateFlow<Set<String>> =
        settings.favoriteProfileIds.stateIn(
            viewModelScope, SharingStarted.Eagerly, emptySet(),
        )

    /** 최근(빠른 선택) 프로파일 — 스피너용. 최근 선택순, 최대 10개. 첫 실행 시 10개 pre-seed. */
    val recentProfiles: StateFlow<List<AutoEqCatalogEntry>> =
        settings.recentProfiles.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    // ── OPRA 비교 탭 상태 (별도 소스, 같은 엔진) ──────────────────────────────────
    private val _opraQuery = MutableStateFlow("")
    val opraQuery: StateFlow<String> = _opraQuery.asStateFlow()

    /** OPRA 검색 결과 — 검색어가 [OPRA_MIN_QUERY_LENGTH]자 이상일 때만 추천 목록을 노출. */
    val opraResults: StateFlow<List<OpraCatalogEntry>> =
        _opraQuery.debounce(DEBOUNCE_MS)
            .flatMapLatest { q ->
                val query = q.trim()
                if (query.length < OPRA_MIN_QUERY_LENGTH) {
                    // 2자 미만: 카탈로그 전체를 쏟아내지 않고 빈 목록(안내 문구만 표시).
                    flow { emit(emptyList<OpraCatalogEntry>()) }
                } else {
                    flow {
                        emit(withContext(Dispatchers.Default) { opraRepository.search(query, OPRA_LIST_LIMIT) })
                    }
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIBE_TIMEOUT_MS), emptyList())

    /** OPRA 데이터 갱신 진행 표시. */
    private val _opraRefreshing = MutableStateFlow(false)
    val opraRefreshing: StateFlow<Boolean> = _opraRefreshing.asStateFlow()

    /** OPRA 스냅샷 provenance(commit/version/source/license) — About/진단 표시용. */
    private val _opraSyncState = MutableStateFlow<OpraSyncState?>(null)
    val opraSyncState: StateFlow<OpraSyncState?> = _opraSyncState.asStateFlow()

    /** OPRA 행 탭 시 상세(author/source/license + 적용)로 띄울 resolved 프로파일. null이면 닫힘. */
    private val _opraDetail = MutableStateFlow<OpraEqProfile?>(null)
    val opraDetail: StateFlow<OpraEqProfile?> = _opraDetail.asStateFlow()

    /** 그래픽 EQ(Manual chain) 20밴드 게인(dB). 슬라이더가 변경, debounce 후 엔진 적용. */
    private val _bandGains = MutableStateFlow(FloatArray(GraphicEqBands.COUNT))
    val bandGains: StateFlow<FloatArray> = _bandGains.asStateFlow()

    /** 사용자 선택 게인 한계(±dB). 칩으로 변경. 기본 [GraphicEqBands.MAX_GAIN_DB]. */
    val gainLimitDb: StateFlow<Float> =
        settings.graphicEqGainLimitDb.stateIn(
            viewModelScope, SharingStarted.Eagerly, GraphicEqBands.MAX_GAIN_DB,
        )

    /** 응답 그래프에 활성 프로파일 preamp 기준선을 표시할지. */
    val showPreampOnGraph: StateFlow<Boolean> =
        settings.showPreampOnGraph.stateIn(
            viewModelScope, SharingStarted.Eagerly, SettingsStore.DEFAULT_SHOW_PREAMP,
        )

    /** 저장된 그래픽 EQ 프리셋 목록(이름 있는). */
    val graphicEqPresets: StateFlow<List<com.coreline.auraltune.data.GraphicEqPreset>> =
        settings.graphicEqPresets.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** 현재 선택된 프리셋 id(없으면 null). 슬라이더를 임의 변경하면 해제된다(dirty). */
    val selectedGraphicEqPresetId: StateFlow<String?> =
        settings.selectedGraphicEqPresetId.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Polled native diagnostics. RT-safe: only [AudioEngine.readDiagnostics] is called. */
    val diagnostics: StateFlow<AudioEngine.Diagnostics> =
        flow {
            while (true) {
                // DEBUG(검증용): correction/preamp 토글이 엔진에 반영되는지 데이터로 추적.
                if (BuildConfig.DEBUG) {
                    val s = engine.readAppliedSnapshot()
                    android.util.Log.i(
                        "EqState",
                        "autoEqEnabled=${s.autoEqEnabled} filters=${s.autoEqFilterCount} " +
                            "manualEnabled=${s.manualEnabled} " +
                            "preampEnabled=${s.preampEnabled} preampGain=${s.preampLinearGain}",
                    )
                }
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

    init {
        // Phase 2: start the device-route manager here (was DisposableEffectClose in the
        // Composable). The manager + engine are now owned by this retained ViewModel, so
        // route detection / sample-rate / saved-profile restore survive rotation.
        deviceManager.start()

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

        // 청취 모드(ORIGINAL/AUTOEQ/USER) + preamp 토글이 엔진 상태를 결정한다.
        // ORIGINAL = AutoEQ·매뉴얼·preamp 전부 off(순수 원음), AUTOEQ = 프로파일만,
        // USER = 프로파일 + (비평탄) 그래픽 EQ. 매뉴얼 enable 규칙은 applyGraphicEq와 동일.
        viewModelScope.launch {
            combine(listenMode, preampEnabled) { mode, preamp -> mode to preamp }
                .collect { (mode, preamp) ->
                    val auto = mode != ListenMode.ORIGINAL
                    val manual = mode == ListenMode.USER &&
                        GraphicEqBands.toSpecs(_bandGains.value).isNotEmpty()
                    // 비교용 전환이지만 클릭 방지를 위해 0.5s 크로스페이드 사용(immediate=false).
                    engine.setAutoEqEnabled(auto, immediate = false)
                    engine.setManualEqEnabled(manual)
                    engine.setAutoEqPreampEnabled(auto && preamp)
                    // 원음 청취 중엔 백그라운드 카탈로그/프로파일 fetch를 멈춘다(기존 킬스위치 의미 계승).
                    api.repository.setKillSwitchEngaged(mode == ListenMode.ORIGINAL)
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

        // 그래픽 EQ: 저장된 현재 게인 1회 복원(앱 재시작 후에도 유지). 회전은 retained VM이 처리.
        // 게인과 한계는 별도 키라 저장 시점이 다를 수 있으므로, 복원 시 한계로 clamp해 불변식 유지.
        viewModelScope.launch {
            val limit = settings.graphicEqGainLimitDb.first()
            _bandGains.value = clampToLimit(settings.currentGraphicEqGains.first(), limit)
        }
        // 밴드 게인 변경 → debounce(40ms) → Manual chain 적용(과도한 publish 방지).
        viewModelScope.launch {
            _bandGains.debounce(40L).collect { gains -> applyGraphicEq(gains) }
        }
        // 변경 영속화는 더 긴 debounce(400ms)로 DataStore 쓰기 빈도를 낮춘다.
        viewModelScope.launch {
            _bandGains.debounce(400L).collect { gains -> settings.setCurrentGraphicEqGains(gains) }
        }
        // 앱 시작 시 백그라운드 delta 체크(쿨다운 DELTA_CHECK_INTERVAL_MS). 변경 있을 때만 알림.
        viewModelScope.launch {
            val last = settings.lastDeltaCheckMs.first()
            if (System.currentTimeMillis() - last > DELTA_CHECK_INTERVAL_MS) {
                checkProfileUpdates(manual = false)
            }
        }
        // OPRA: 로컬 OPRA가 비어 있으면 1회 자동 import(debug=GitHub raw, release=번들 스냅샷).
        // 백그라운드 시드이므로 조용히(스낵바 없이) 처리. 이후 실행은 카탈로그가 차 있어 skip
        // (번들은 commit 기준 NoChange로도 한 번 더 가드). 번들 검증 실패 시엔 카탈로그가 빈 채로
        // 남아 다음 콜드스타트에 재시도(크래시/ANR 없음, 업데이트로 자가복구).
        viewModelScope.launch {
            if (opraRepository.observeCatalog().first().isEmpty()) {
                syncOpra(notify = false)
            } else {
                _opraSyncState.value = opraRepository.syncState()
            }
        }
        // OPRA: 마지막 활성 provider가 OPRA였다면 선택을 복원해 같은 엔진에 적용(AutoEq 복원 뒤에 override).
        viewModelScope.launch {
            if (settings.activeCorrectionProvider.first() != SettingsStore.PROVIDER_OPRA) return@launch
            val id = settings.activeOpraProfileId.first() ?: return@launch
            // OPRA 카탈로그가 적어도 한 번 로드된 뒤 resolve(첫 import 완료 대기).
            opraRepository.observeCatalog().first { it.isNotEmpty() }
            val auto = opraRepository.resolveById(id)?.toAutoEqProfile() ?: return@launch
            deviceManager.applyResolvedProfile(auto)
            _selectedProfile.value = auto.validated()
        }
        // 최초 1회: 최근 프로파일이 비어 있으면 카탈로그 Loaded 시점에 큐레이션 10개를 pre-seed.
        // 경합 안전: empty-check+write를 settings 내부 store.edit 1트랜잭션으로 처리(사용자 탭과 무손실).
        viewModelScope.launch {
            if (settings.recentProfiles.first().isNotEmpty()) return@launch // 이미 있으면 catalog 대기 불필요
            val loaded = catalogState.first { it is CatalogState.Loaded } as CatalogState.Loaded
            // 이름(대소문자/공백 무시)으로 그룹화 후 측정 소스 우선순위로 결정적 선택(같은 이름·다른 측정 혼선 방지).
            val byName = loaded.entries.groupBy { it.name.lowercase().trim() }
            val seed = SEED_PROFILE_NAMES.mapNotNull { name -> pickPreferredEntry(byName[name.lowercase().trim()]) }
            settings.seedRecentProfilesIfEmpty(seed)
        }
    }

    /** 슬라이더 → 밴드 게인 설정(현재 선택 한계로 clamp). 적용은 debounce 흐름이 담당. */
    fun setBandGain(index: Int, gainDb: Float) {
        val arr = _bandGains.value.copyOf()
        if (index in arr.indices) {
            val limit = gainLimitDb.value
            arr[index] = gainDb.coerceIn(-limit, limit)
            _bandGains.value = arr
            markGraphicEqPresetDirty()
            // 그래픽 EQ를 만지면 '내 설정'으로 자동 전환 — 편집 결과가 즉시 들리게.
            if (listenMode.value != ListenMode.USER) setListenMode(ListenMode.USER)
        }
    }

    /**
     * 게인 한계(±dB) 변경. 허용 옵션으로 스냅. 한계를 낮추면 현재 게인을 새 한계로 즉시 clamp하고
     * **clamp된 게인을 즉시 영속화**(400ms debounce 창에서 프로세스 종료 시 한계<게인 불일치 방지).
     */
    fun setGainLimit(limitDb: Float) {
        val snapped = GraphicEqBands.GAIN_LIMIT_OPTIONS.minByOrNull { kotlin.math.abs(it - limitDb) }
            ?: GraphicEqBands.MAX_GAIN_DB
        val arr = _bandGains.value
        val needsClamp = arr.any { it > snapped || it < -snapped }
        val clamped = if (needsClamp) clampToLimit(arr, snapped) else null
        viewModelScope.launch {
            // 한계와 (clamp된) 게인을 함께 즉시 기록 — 둘의 저장 지연 차이로 인한 불일치 제거.
            settings.setGraphicEqGainLimitDb(snapped)
            clamped?.let { settings.setCurrentGraphicEqGains(it) }
        }
        if (clamped != null) {
            _bandGains.value = clamped
            markGraphicEqPresetDirty()
        }
    }

    /** 게인 배열을 ±limit로 clamp(NaN/Inf → 0). [_bandGains]가 항상 라이브 한계 내에 있도록 보장. */
    private fun clampToLimit(gains: FloatArray, limit: Float): FloatArray =
        FloatArray(GraphicEqBands.COUNT) { i ->
            val v = gains.getOrElse(i) { 0f }
            if (v.isFinite()) v.coerceIn(-limit, limit) else 0f
        }

    /** 응답 그래프 preamp 기준선 표시 토글. */
    fun setShowPreampOnGraph(show: Boolean) {
        viewModelScope.launch { settings.setShowPreampOnGraph(show) }
    }

    /** 전 밴드 0으로 리셋(Manual chain 비움). */
    fun resetGraphicEq() {
        _bandGains.value = FloatArray(GraphicEqBands.COUNT)
        markGraphicEqPresetDirty()
    }

    /** 현재 게인을 이름 있는 프리셋으로 저장하고 선택 상태로 표시. */
    fun saveGraphicEqPreset(name: String) {
        val now = System.currentTimeMillis()
        val preset = com.coreline.auraltune.data.GraphicEqPreset(
            id = java.util.UUID.randomUUID().toString(),
            name = name.trim().ifBlank { "프리셋" },
            gainsDb = _bandGains.value.toList(),
            createdAtMs = now,
            updatedAtMs = now,
        )
        viewModelScope.launch {
            settings.upsertGraphicEqPreset(preset)
            settings.setSelectedGraphicEqPresetId(preset.id)
        }
    }

    /** 프리셋 불러오기: 게인 적용(라이브 한계로 clamp) + 선택 표시. */
    fun loadGraphicEqPreset(id: String) {
        val preset = graphicEqPresets.value.firstOrNull { it.id == id } ?: return
        _bandGains.value = clampToLimit(preset.gainsDb.toFloatArray(), gainLimitDb.value)
        viewModelScope.launch { settings.setSelectedGraphicEqPresetId(id) }
    }

    fun deleteGraphicEqPreset(id: String) {
        viewModelScope.launch { settings.deleteGraphicEqPreset(id) }
    }

    /** 사용자가 밴드를 직접 바꾸면 선택된 프리셋과 달라지므로 선택을 해제(dirty). */
    private fun markGraphicEqPresetDirty() {
        if (selectedGraphicEqPresetId.value != null) {
            viewModelScope.launch { settings.setSelectedGraphicEqPresetId(null) }
        }
    }

    private fun applyGraphicEq(gains: FloatArray) {
        val specs = GraphicEqBands.toSpecs(gains)
        engine.updateManualEq(
            frequencies = FloatArray(specs.size) { specs[it].freqHz.toFloat() },
            gainsDB = FloatArray(specs.size) { specs[it].gainDb.toFloat() },
            qFactors = FloatArray(specs.size) { specs[it].q.toFloat() },
        )
        // 매뉴얼 체인은 '내 설정'(USER) 모드에서 비평탄 밴드가 있을 때만 on (combine과 동일 규칙).
        engine.setManualEqEnabled(specs.isNotEmpty() && listenMode.value == ListenMode.USER)
    }

    /** Phase 6 / P3-C: current native sample rate for the diagnostics card. */
    fun engineSampleRate(): Int = engine.sampleRate

    /**
     * Hashed identifier of the currently routed output device, suitable for
     * surfacing in the Diagnostics card without leaking PII (BT MAC etc.).
     */
    fun currentDeviceHash(): String? = deviceManager.currentDeviceHash

    /**
     * Phase 2: tear down the owned audio stack when the retained ViewModel is finally
     * cleared (Activity finishing, not rotation). Order mirrors the old DisposableEffectClose:
     *   1. deviceManager.close()  — stop route callbacks / native updateAutoEq.
     *   2. musicController.close() — release ExoPlayer (its audio thread).
     *   3. engine.close()         — free the native handle. Lifecycle-safe per P0-3.
     * Each pre-close step is wrapped so a throw never prevents engine.close().
     */
    override fun onCleared() {
        runCatching { deviceManager.close() }
        runCatching { musicController.close() }
        engine.close()
        super.onCleared()
    }

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
            // Record into the quick-pick spinner regardless of route eligibility so the user
            // can re-select later (selection persists via settings.selectedProfileId too).
            settings.addRecentProfile(entry)
            // Selecting an AutoEQ profile makes AUTOEQ the active correction provider.
            settings.setActiveCorrectionProvider(SettingsStore.PROVIDER_AUTOEQ)
        }
    }

    // ── OPRA 비교 탭 액션 ────────────────────────────────────────────────────────
    fun onOpraQueryChanged(q: String) { _opraQuery.value = q }

    /** OPRA 스냅샷 갱신(release: 번들, debug: GitHub raw). 사용자 버튼 → 스낵바로 결과 표시. */
    fun refreshOpra() {
        viewModelScope.launch { syncOpra(notify = true) }
    }

    /**
     * refresh + provenance 적재를 한 코루틴에서 순차 처리(_opraSyncState를 refresh 완료 후 갱신해
     * About 카드가 stale/null commit을 보이지 않게 함). [_opraRefreshing]을 잡아 OPRA 탭이
     * 로딩 상태("불러오는 중…")를 보이게 한다 — 번들 import(압축해제+파싱+Room 삽입)는 수초 걸림.
     * [notify]=false면 스낵바 없이 조용히 seed(앱 첫 실행 자동 import — 백그라운드 시드).
     * 진행 중이면 재진입을 막아 중복 import를 방지한다.
     */
    private suspend fun syncOpra(notify: Boolean) {
        if (_opraRefreshing.value) return
        _opraRefreshing.value = true
        try {
            val result = withContext(Dispatchers.IO) { opraRepository.refresh() }
            _opraSyncState.value = opraRepository.syncState()
            if (notify) {
                _importMessage.value = when (result) {
                    is OpraSyncResult.NoChange -> "OPRA: 최신 상태"
                    is OpraSyncResult.Updated -> "OPRA 갱신됨 (제품 ${result.products}, 프로파일 ${result.profiles})"
                    is OpraSyncResult.Failed -> "OPRA 갱신 실패: ${result.reason}"
                }
            }
        } finally {
            _opraRefreshing.value = false
        }
    }

    /** OPRA 행 탭 → 상세 시트 표시용으로 프로파일을 resolve(적용은 아직 안 함). */
    fun openOpraDetail(entry: OpraCatalogEntry) {
        viewModelScope.launch {
            val opra = opraRepository.resolve(entry)
            if (opra == null) {
                _importMessage.value = "OPRA 프로파일을 찾을 수 없습니다"
                return@launch
            }
            _opraDetail.value = opra
        }
    }

    fun dismissOpraDetail() { _opraDetail.value = null }

    /**
     * 상세 시트의 '적용' → 이미 resolve된 [opraDetail] 프로파일을 어댑터로 엔진 입력모델로 변환해
     * 같은 엔진에 적용 + OPRA 활성 기록(재시작 복원용). resolve를 두 번 하지 않는다.
     */
    fun applyOpraDetail() {
        val opra = _opraDetail.value ?: return
        // 시트를 즉시 닫는다: 더블탭 중복 적용 방지 + 변환 실패(미지원) 시에도 시트가 떠 있지 않게.
        _opraDetail.value = null
        viewModelScope.launch {
            val auto = opra.toAutoEqProfile()
            if (auto == null) {
                _importMessage.value = "이 OPRA 프로파일은 적용 불가 (미지원 필터 또는 밴드 10개 초과)"
                return@launch
            }
            deviceManager.applyResolvedProfile(auto)
            _selectedProfile.value = auto.validated()
            settings.setActiveCorrectionProvider(SettingsStore.PROVIDER_OPRA)
            settings.setActiveOpraProfileId(opra.id)
        }
    }

    fun clearProfile() {
        viewModelScope.launch {
            deviceManager.clearProfileForCurrentDevice()
            _selectedProfile.value = null
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

    /**
     * Incremental profile update: fetch only what changed upstream (delta sync via the GitHub
     * compare API) and upsert into the local DB. [manual]=true surfaces every outcome; the
     * background/auto path stays silent unless something actually changed (avoids startup noise).
     * 원음(ORIGINAL) 청취 중엔 건너뛴다(보정을 쓰지 않는 상태).
     */
    fun checkProfileUpdates(manual: Boolean = true) {
        viewModelScope.launch {
            if (listenMode.value == ListenMode.ORIGINAL) {
                if (manual) _importMessage.value = "원음 모드 — 업데이트를 건너뜁니다"
                return@launch
            }
            if (manual) _importMessage.value = "프로파일 업데이트 확인 중…"
            val result = withContext(Dispatchers.IO) { api.repository.syncDelta() }
            settings.setLastDeltaCheckMs(System.currentTimeMillis())
            when (result) {
                is DeltaResult.NoChange ->
                    if (manual) _importMessage.value = "프로파일이 최신 상태입니다"
                is DeltaResult.Updated ->
                    _importMessage.value =
                        "프로파일 업데이트됨 (변경 ${result.changedProfiles}, 제거 ${result.removedProfiles})"
                is DeltaResult.Failed ->
                    if (manual) _importMessage.value = "업데이트 확인 실패: ${result.reason}"
            }
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

        /** Background delta-sync cooldown: auto-check profile updates at most once per 24h. */
        private const val DELTA_CHECK_INTERVAL_MS = 24L * 60L * 60L * 1_000L

        /** Max OPRA rows shown in the list/search (DB-side LIMIT). */
        private const val OPRA_LIST_LIMIT = 100

        /** OPRA 추천 목록을 띄우는 최소 검색어 길이 — 그 미만은 빈 목록(안내만). */
        private const val OPRA_MIN_QUERY_LENGTH = 2

        /**
         * 빠른 선택(스피너) 최초 pre-seed용 큐레이션 프로파일 이름.
         * 카탈로그 Loaded 시 정확히(대소문자 무시) 일치하는 항목만 채택 — 못 찾으면 조용히 건너뜀.
         * 널리 알려진 레퍼런스/인기 모델로 구성(소스별 표기 일치 우선).
         */
        /** 같은 이름이 여러 측정 소스로 존재할 때 결정적으로 한 항목을 고르는 우선순위. */
        private val SEED_SOURCE_PRIORITY = listOf("oratory1990", "crinacle", "Rtings")

        /** 후보 중 우선순위 소스를 먼저, 없으면 첫 항목을 선택. null/빈 후보는 null. */
        private fun pickPreferredEntry(candidates: List<AutoEqCatalogEntry>?): AutoEqCatalogEntry? {
            if (candidates.isNullOrEmpty()) return null
            for (src in SEED_SOURCE_PRIORITY) {
                candidates.firstOrNull { it.measuredBy.equals(src, ignoreCase = true) }?.let { return it }
            }
            return candidates.first()
        }

        private val SEED_PROFILE_NAMES = listOf(
            "Sennheiser HD 600",
            "Sennheiser HD 650",
            "Sony WH-1000XM4",
            "Sony WH-1000XM5",
            "AKG K712 PRO",
            "Beyerdynamic DT 990 PRO",
            "HIFIMAN Sundara",
            "Moondrop Blessing 2",
            "Apple AirPods Pro 2",
            "Audio-Technica ATH-M50x",
        )
    }
}

/** A/B/C 청취 비교 모드. 영속 키는 [SettingsStore.listenMode] 문자열. */
enum class ListenMode(val key: String) {
    /** EQ 미적용 — 순수 원음(패스스루). */
    ORIGINAL(SettingsStore.LISTEN_ORIGINAL),
    /** AutoEQ/OPRA 프로파일만 적용. */
    AUTOEQ(SettingsStore.LISTEN_AUTOEQ),
    /** 프로파일 + 사용자 그래픽 EQ(초기엔 평탄이라 AUTOEQ와 동일). */
    USER(SettingsStore.LISTEN_USER);

    companion object {
        fun fromKey(key: String): ListenMode = entries.firstOrNull { it.key == key } ?: USER
    }
}
