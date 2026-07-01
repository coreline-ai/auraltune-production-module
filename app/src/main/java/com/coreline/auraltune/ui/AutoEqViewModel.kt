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
import com.coreline.auraltune.R
import com.coreline.auraltune.audio.MusicPlayerController
import com.coreline.auraltune.data.GraphicEqPreset
import com.coreline.auraltune.data.ParametricBand
import com.coreline.auraltune.data.ParametricEqPreset
import com.coreline.auraltune.data.ParametricPresetBand
import com.coreline.auraltune.data.ParametricPresetCatalog
import com.coreline.auraltune.data.ParametricPresetSource
import com.coreline.auraltune.data.RecentOpraProfile
import com.coreline.auraltune.data.SettingsStore
import com.coreline.auraltune.audio.eq.BiquadSpec
import com.coreline.auraltune.audio.eq.BiquadType
import com.coreline.auraltune.audio.eq.EqMode
import com.coreline.auraltune.audio.eq.biquadTypeFromNativeId
import com.coreline.auraltune.audio.eq.GraphicEqBands
import com.coreline.auraltune.audio.eq.toNativeId
import com.coreline.auraltune.audio.eq.updateParametricBandModel
import com.coreline.auraltune.opra.OpraSyncResult
import com.coreline.auraltune.opra.model.OpraCatalogEntry
import com.coreline.auraltune.opra.model.OpraEqProfile
import com.coreline.auraltune.opra.model.OpraSyncState
import com.coreline.auraltune.opra.toAutoEqProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    private val application = app
    private val locator = app.serviceLocator
    private val api: AutoEqApi = locator.autoEqApi
    private val settings: SettingsStore = locator.settingsStore
    // App-singleton engine (shared with the media playback service's processor + deviceManager).
    private val engine: AudioEngine = locator.audioEngine
    private val importedProfileFallback = application.getString(R.string.imported_profile_fallback)
    private val importedSourceLabel = application.getString(R.string.imported_source_label)
    private val builtInParametricPresets = ParametricPresetCatalog.builtIns(application)
    private val builtInGraphicPresets =
        com.coreline.auraltune.data.GraphicEqPresetCatalog.builtIns(application)
    private val builtInTonePresets =
        com.coreline.auraltune.data.ToneEqPresetCatalog.builtIns(application)

    /** Exposed so the screen can drive local music playback (T1). Connects to AuralTuneMediaService. */
    val musicController: MusicPlayerController =
        MusicPlayerController(app, settings, locator.spectrumAnalyzer, locator.playbackTelemetry)

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

    // 탭별 독립 선택: AutoEQ 탭 / OPRA 탭이 각자 마지막 선택을 기억(영속은 settings.selectedProfileId /
    // activeOpraProfileId). 각 탭의 StatusCard·그래프는 자기 탭 선택을 표시한다. 엔진은 한 번에 하나만
    // 재생 가능하므로 '실제 사용중'은 activeProfile(=활성 provider의 선택) 하나뿐이다.
    private val _selectedAutoEqProfile = MutableStateFlow<AutoEqProfile?>(null)
    val selectedAutoEqProfile: StateFlow<AutoEqProfile?> = _selectedAutoEqProfile.asStateFlow()
    private val _selectedOpraProfile = MutableStateFlow<AutoEqProfile?>(null)
    val selectedOpraProfile: StateFlow<AutoEqProfile?> = _selectedOpraProfile.asStateFlow()

    /** 현재 적용된 보정의 소스(provider) — 어느 탭이 '현재 사용중'인지 결정. */
    val correctionProvider: StateFlow<String> =
        settings.activeCorrectionProvider.stateIn(
            viewModelScope, SharingStarted.Eagerly, SettingsStore.PROVIDER_AUTOEQ,
        )

    /** 실제 엔진에 적용 중(=현재 사용중)인 프로파일 = 활성 provider의 선택. 플레이어/미니 배지용. */
    val activeProfile: StateFlow<AutoEqProfile?> =
        combine(correctionProvider, _selectedAutoEqProfile, _selectedOpraProfile) { p, autoSel, opraSel ->
            if (p == SettingsStore.PROVIDER_OPRA) opraSel else autoSel
        }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

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

    /** OPRA 최근(빠른 선택) 기록 — 최근순, 최대 10개. */
    val recentOpraProfiles: StateFlow<List<RecentOpraProfile>> =
        settings.recentOpraProfiles.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

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

    /** OPRA refresh/seed 직렬화 — 동시 import 방지 + init/수동 새로고침 경합 해소. */
    private val opraSyncMutex = Mutex()

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

    /** 톤 EQ 게인 [베이스, 미드, 트레블] (dB). 슬라이더가 변경, debounce 후 적용·영속. */
    private val _toneGains = MutableStateFlow(FloatArray(SettingsStore.TONE_BANDS))
    val toneGains: StateFlow<FloatArray> = _toneGains.asStateFlow()

    /** 톤 EQ 프리셋 = 내장 기본 + 사용자 저장(내장이 위, debug·release 공통). */
    val toneEqPresets: StateFlow<List<com.coreline.auraltune.data.ToneEqPreset>> =
        settings.userToneEqPresets
            .map { user -> builtInTonePresets + user }
            .stateIn(viewModelScope, SharingStarted.Eagerly, builtInTonePresets)

    /** 현재 선택된 톤 프리셋 id(없으면 null). 슬라이더를 임의 변경하면 해제(dirty). */
    val selectedToneEqPresetId: StateFlow<String?> =
        settings.selectedToneEqPresetId.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** 응답 그래프에 활성 프로파일 preamp 기준선을 표시할지. */
    val showPreampOnGraph: StateFlow<Boolean> =
        settings.showPreampOnGraph.stateIn(
            viewModelScope, SharingStarted.Eagerly, SettingsStore.DEFAULT_SHOW_PREAMP,
        )

    /** 그래픽 EQ 프리셋 = 내장 기본 프리셋 + 사용자 저장(내장이 위, debug·release 공통). */
    val graphicEqPresets: StateFlow<List<com.coreline.auraltune.data.GraphicEqPreset>> =
        settings.graphicEqPresets
            .map { user -> builtInGraphicPresets + user }
            .stateIn(viewModelScope, SharingStarted.Eagerly, builtInGraphicPresets)

    /** 현재 선택된 프리셋 id(없으면 null). 슬라이더를 임의 변경하면 해제된다(dirty). */
    val selectedGraphicEqPresetId: StateFlow<String?> =
        settings.selectedGraphicEqPresetId.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    // ── EQ 편집 모드(그래픽 / 파라메트릭) + 모드별 상태 ───────────────────────────
    /** 현재 EQ 편집 모드. 그래픽=20밴드 고정, 파라메트릭=자유 밴드(그래프 드래그). */
    val eqMode: StateFlow<EqMode> =
        settings.eqMode.map { EqMode.fromKey(it) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, EqMode.GRAPHIC)

    /** 그래픽 EQ 전역 Q 배율(넓게/보통/좁게). 모든 밴드에 동일 적용. */
    val graphicQScale: StateFlow<Float> =
        settings.graphicQScale.stateIn(
            viewModelScope, SharingStarted.Eagerly, GraphicEqBands.DEFAULT_Q_SCALE,
        )

    /** 파라메트릭 밴드 목록(타입/주파수/게인/Q). 그래프 드래그가 변경, debounce 후 적용·영속. */
    private val _parametricBands = MutableStateFlow<List<ParametricBand>>(emptyList())
    val parametricBands: StateFlow<List<ParametricBand>> = _parametricBands.asStateFlow()

    /** Built-in starting points + user-saved parametric presets. */
    val parametricPresets: StateFlow<List<ParametricEqPreset>> =
        settings.userParametricEqPresets
            .map { userPresets -> builtInParametricPresets + userPresets }
            .stateIn(viewModelScope, SharingStarted.Eagerly, builtInParametricPresets)

    /** Last native Manual EQ update status. If native rejects a chain, keep manual bypassed. */
    private val _manualEqNativeApplied = MutableStateFlow(true)

    /** 파라메트릭 편집기에서 현재 선택된 밴드 id(드래그/Q 슬라이더/타입 변경 대상). 비영속. */
    private val _selectedParametricBandId = MutableStateFlow<String?>(null)
    val selectedParametricBandId: StateFlow<String?> = _selectedParametricBandId.asStateFlow()

    /** Currently selected parametric preset. Dirty means the loaded bands were edited after apply. */
    val selectedParametricEqPresetId: StateFlow<String?> =
        settings.selectedParametricPresetId.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val selectedParametricEqPresetSource: StateFlow<ParametricPresetSource?> =
        settings.selectedParametricPresetSource.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val parametricEqPresetDirty: StateFlow<Boolean> =
        settings.parametricPresetDirty.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /**
     * 현재 모드의 Manual chain 내용 = 응답 그래프 + 엔진 적용의 단일 소스.
     * 그래픽: 20밴드 peaking(전역 Q 배율 반영). 파라메트릭: 타입드 밴드(무효 밴드 제거).
     * combine이 모드·게인·Q배율·밴드 중 하나라도 바뀌면 재계산한다.
     */
    val manualResponseSpecs: StateFlow<List<BiquadSpec>> =
        combine(eqMode, _bandGains, graphicQScale, _parametricBands, _toneGains) { mode, gains, qScale, bands, tone ->
            currentManualSpecs(mode, gains, qScale, bands, tone)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /**
     * 현재 디코드 스트림(=엔진 Manual 계수가 계산되는) 샘플레이트(Hz). 응답 그래프를 48k 고정이 아니라
     * 실제 적용 레이트로 그려 "그래프 = 소리"를 유지하기 위해 노출(고역 필터의 워핑이 레이트마다 다름).
     * 재생 중이 아니면 엔진의 현재 레이트로 폴백.
     */
    val engineSampleRateHz: StateFlow<Int> =
        musicController.state
            .map { it.audioSampleRateHz ?: engine.sampleRate }
            .stateIn(viewModelScope, SharingStarted.Eagerly, engine.sampleRate)

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
        // provider==OPRA일 때 라우트 변경 시 deviceManager가 AutoEQ 선택 대신 OPRA 프로파일을
        // 재적용하도록 resolver 주입(OPRA 선택 clobber 방지). start() 전에 설정해야 초기 reconcile에 반영.
        deviceManager.opraReapplyProvider = {
            val id = settings.activeOpraProfileId.first()
            if (id != null) opraRepository.resolveById(id)?.toAutoEqProfile() else null
        }
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
            // AutoEQ 선택을 '표시용'으로 항상 복원한다(탭별 독립 선택). 엔진 적용은 AutoEQ가 활성
            // provider일 때만 — OPRA가 활성이면 엔진은 아래 OPRA 복원이 담당하고, 여기선 AutoEQ 탭
            // StatusCard/그래프에 보여줄 선택만 채운다(엔진 clobber 금지).
            val savedId = settings.selectedProfileId.first() ?: return@launch
            val loaded = catalogState.first { it is CatalogState.Loaded } as CatalogState.Loaded
            val entry = loaded.entries.firstOrNull { it.id == savedId }
            if (entry == null) {
                // Saved profile no longer in catalog (upstream removed / hash changed).
                settings.setSelectedProfileId(null)
                return@launch
            }
            val autoEqActive = settings.activeCorrectionProvider.first() != SettingsStore.PROVIDER_OPRA
            if (autoEqActive && deviceManager.selectProfileForCurrentDevice(entry)) {
                _selectedAutoEqProfile.value = api.resolve(entry)?.validated()
            } else {
                // 표시용으로만 복원(엔진은 건드리지 않음).
                _selectedAutoEqProfile.value = api.resolve(entry)?.validated()
            }
        }

        // Track explicit clears at runtime (settings.selectedProfileId → null).
        viewModelScope.launch {
            settings.selectedProfileId.collect { id ->
                if (id == null && _selectedAutoEqProfile.value != null) {
                    _selectedAutoEqProfile.value = null
                    // AutoEQ가 활성일 때만 엔진을 비운다(OPRA 재생 중이면 건드리지 않음).
                    if (correctionProvider.value == SettingsStore.PROVIDER_AUTOEQ) {
                        deviceManager.clearProfileForCurrentDevice()
                    }
                }
            }
        }

        // 청취 모드(ORIGINAL/AUTOEQ/USER) + preamp 토글이 엔진 상태를 결정한다.
        // ORIGINAL = AutoEQ·매뉴얼·preamp 전부 off(순수 원음), AUTOEQ = 프로파일만,
        // USER = 프로파일 + (비평탄) 사용자 EQ. manualResponseSpecs(그래픽/파라메트릭 통합 소스)를
        // combine 소스로 포함 → 복원 전 빈 상태로 manual을 잘못 끄는 init 경합 제거, 동시에
        // manual on/off의 단독 소유자가 되어 pushManualSpecs와의 이중 writer 경합도 없앤다.
        // (engine.setAutoEqEnabled는 값 불변이면 early-return이라 틱마다 호출돼도 무해.)
        viewModelScope.launch {
            combine(
                listenMode,
                preampEnabled,
                manualResponseSpecs,
                _manualEqNativeApplied,
            ) { mode, preamp, specs, manualNativeOk ->
                ManualEnableState(mode, preamp, specs, manualNativeOk)
            }.collect { state ->
                val mode = state.mode
                val specs = state.specs
                val auto = mode != ListenMode.ORIGINAL
                val manual = mode == ListenMode.USER && specs.isNotEmpty() && state.manualNativeOk
                // 비교용 전환이지만 클릭 방지를 위해 0.5s 크로스페이드 사용(immediate=false).
                engine.setAutoEqEnabled(auto, immediate = false)
                engine.setManualEqEnabled(manual)
                engine.setAutoEqPreampEnabled(auto && state.preampEnabled)
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

        // 그래픽 게인 + 파라메트릭 밴드를 한 코루틴에서 복원한 "뒤에만" 영속화 collector를 시작한다.
        // (복원이 끝나기 전 빈 초기값이 debounce(400) 저장 창에 걸려 저장된 EQ를 덮어쓰는 — 파라메트릭은
        //  prefs.remove로 영구 삭제까지 가능한 — 콜드스타트 레이스를 제거.) 게인/한계는 별도 키라 복원 시
        //  한계로 clamp해 불변식 유지. 복원 길이는 MAX_BANDS로 절단(손상/다운그레이드 데이터 방어).
        viewModelScope.launch {
            val limit = settings.graphicEqGainLimitDb.first()
            _bandGains.value = clampToLimit(settings.currentGraphicEqGains.first(), limit)
            _parametricBands.value = settings.parametricBands.first()
                .map { it.normalized() }.take(ParametricBand.MAX_BANDS)
            _toneGains.value = clampTone(settings.toneGains.first(), limit)
            // 복원 완료 후에만 영속화 시작 — debounce(400ms)로 DataStore 쓰기 빈도를 낮춘다.
            launch { _bandGains.debounce(400L).collect { gains -> settings.setCurrentGraphicEqGains(gains) } }
            launch { _parametricBands.debounce(400L).collect { bands -> settings.setParametricBands(bands) } }
            launch { _toneGains.debounce(400L).collect { tone -> settings.setToneGains(tone) } }
        }
        // 사용자 EQ 변경(그래픽/파라메트릭/Q배율/모드) → debounce(40ms) → Manual chain 적용.
        viewModelScope.launch {
            manualResponseSpecs.debounce(40L).collect { specs -> pushManualSpecs(specs) }
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
            val catalogEmpty = opraRepository.observeCatalog().first().isEmpty()
            val parserStale = settings.opraParserVersion.first() < OPRA_PARSER_VERSION
            when {
                // 빈 카탈로그: 일반 시드(commit 기준 파싱).
                catalogEmpty -> {
                    if (syncOpra(notify = false) !is OpraSyncResult.Failed) {
                        settings.setOpraParserVersion(OPRA_PARSER_VERSION)
                    }
                }
                // 데이터는 있으나 옛 파서로 파싱됨: commit이 같아도 1회 강제 재파싱(이름 매핑 수정 전파).
                parserStale -> {
                    if (syncOpra(notify = false, force = true) !is OpraSyncResult.Failed) {
                        settings.setOpraParserVersion(OPRA_PARSER_VERSION)
                    } else {
                        _opraSyncState.value = opraRepository.syncState()
                    }
                }
                else -> _opraSyncState.value = opraRepository.syncState()
            }
        }
        // OPRA 선택도 '표시용'으로 항상 복원(탭별 독립). 엔진 적용은 OPRA가 활성일 때만(AutoEq 복원 뒤 override).
        viewModelScope.launch {
            val id = settings.activeOpraProfileId.first() ?: return@launch
            // OPRA 카탈로그가 적어도 한 번 로드된 뒤 resolve(첫 import 완료 대기).
            opraRepository.observeCatalog().first { it.isNotEmpty() }
            val auto = opraRepository.resolveById(id)?.toAutoEqProfile() ?: return@launch
            _selectedOpraProfile.value = auto.validated()
            if (settings.activeCorrectionProvider.first() == SettingsStore.PROVIDER_OPRA) {
                if (!deviceManager.applyResolvedProfile(auto)) {
                    settings.setActiveCorrectionProvider(SettingsStore.PROVIDER_AUTOEQ)
                    _importMessage.value = application.getString(R.string.opra_restore_failed)
                }
            }
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
        // 레거시 디버그 테스트 프리셋(이제 내장 카탈로그로 대체)을 정리 — 이전 빌드 설치본의 중복 제거.
        viewModelScope.launch {
            listOf("test-bass-boost", "test-v-shape", "test-treble-cut")
                .forEach { settings.deleteGraphicEqPreset(it) }
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
        // 파라메트릭 밴드도 새 한계로 즉시 재클램프(그래픽과 동일 규칙). HP도 숨겨진 gain을
        // clamp해 둬야 이후 shelf/peaking으로 되돌릴 때 이전 큰 gain이 되살아나지 않는다.
        val curBands = _parametricBands.value
        val clampedBands = curBands.map { b ->
            b.copy(gainDb = b.gainDb.coerceIn(-snapped, snapped))
        }
        val bandsChanged = clampedBands != curBands
        // 톤 3밴드도 동일 규칙으로 새 한계에 재클램프.
        val curTone = _toneGains.value
        val clampedTone = clampTone(curTone, snapped)
        val toneChanged = !clampedTone.contentEquals(curTone)
        viewModelScope.launch {
            // 한계와 (clamp된) 게인/밴드/톤을 함께 즉시 기록 — 저장 지연 차이로 인한 불일치 제거.
            settings.setGraphicEqGainLimitDb(snapped)
            clamped?.let { settings.setCurrentGraphicEqGains(it) }
            if (bandsChanged) settings.setParametricBands(clampedBands)
            if (toneChanged) settings.setToneGains(clampedTone)
        }
        if (clamped != null) {
            _bandGains.value = clamped
            markGraphicEqPresetDirty()
        }
        if (bandsChanged) {
            _parametricBands.value = clampedBands
            markParametricPresetDirty()
        }
        if (toneChanged) _toneGains.value = clampedTone
    }

    /** 게인 배열을 ±limit로 clamp(NaN/Inf → 0). [_bandGains]가 항상 라이브 한계 내에 있도록 보장. */
    private fun clampToLimit(gains: FloatArray, limit: Float): FloatArray =
        FloatArray(GraphicEqBands.COUNT) { i ->
            val v = gains.getOrElse(i) { 0f }
            if (v.isFinite()) v.coerceIn(-limit, limit) else 0f
        }

    /** 톤 3밴드를 ±limit로 clamp(NaN/Inf → 0, 길이 3 보장). */
    private fun clampTone(gains: FloatArray, limit: Float): FloatArray =
        FloatArray(SettingsStore.TONE_BANDS) { i ->
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

    /** 톤 밴드 게인 설정(0=베이스,1=미드,2=트레블). 라이브 한계로 clamp + '내 설정'으로 자동 전환. */
    fun setToneGain(index: Int, gainDb: Float) {
        if (index !in 0 until SettingsStore.TONE_BANDS) return
        val arr = _toneGains.value.let { if (it.size == SettingsStore.TONE_BANDS) it.copyOf() else FloatArray(SettingsStore.TONE_BANDS) }
        val limit = gainLimitDb.value
        arr[index] = if (gainDb.isFinite()) gainDb.coerceIn(-limit, limit) else 0f
        _toneGains.value = arr
        markToneEqPresetDirty()
        // 톤을 만지면 편집 결과가 즉시 들리도록 '내 설정' 청취 모드로 전환.
        if (listenMode.value != ListenMode.USER) setListenMode(ListenMode.USER)
    }

    /** 톤 3밴드 0dB로 리셋. */
    fun resetTone() {
        _toneGains.value = FloatArray(SettingsStore.TONE_BANDS)
        markToneEqPresetDirty()
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
        ensureUserListenMode() // 프리셋을 불러오면 즉시 '내 설정'으로 전환되어 들리게(파라메트릭과 동일).
        viewModelScope.launch { settings.setSelectedGraphicEqPresetId(id) }
    }

    fun deleteGraphicEqPreset(id: String) {
        // 내장 기본 프리셋은 삭제 불가(사용자 저장 프리셋만 삭제).
        if (com.coreline.auraltune.data.GraphicEqPresetCatalog.isBuiltInId(id)) return
        viewModelScope.launch { settings.deleteGraphicEqPreset(id) }
    }

    /** 현재 톤 게인을 이름 있는 사용자 프리셋으로 저장 + 선택 표시. */
    fun saveToneEqPreset(name: String) {
        val now = System.currentTimeMillis()
        val preset = com.coreline.auraltune.data.ToneEqPreset(
            id = java.util.UUID.randomUUID().toString(),
            name = name.trim().ifBlank { "프리셋" },
            gainsDb = _toneGains.value.toList(),
            createdAtMs = now,
            updatedAtMs = now,
        )
        viewModelScope.launch {
            settings.upsertToneEqPreset(preset)
            settings.setSelectedToneEqPresetId(preset.id)
        }
    }

    /** 톤 프리셋 불러오기: 3게인 적용(라이브 한계 clamp) + 선택 표시. */
    fun loadToneEqPreset(id: String) {
        val preset = toneEqPresets.value.firstOrNull { it.id == id } ?: return
        _toneGains.value = clampTone(preset.gainsDb.toFloatArray(), gainLimitDb.value)
        ensureUserListenMode() // 프리셋을 불러오면 즉시 '내 설정'으로 전환되어 들리게(파라메트릭과 동일).
        viewModelScope.launch { settings.setSelectedToneEqPresetId(id) }
    }

    fun deleteToneEqPreset(id: String) {
        // 내장 기본 프리셋은 삭제 불가.
        if (com.coreline.auraltune.data.ToneEqPresetCatalog.isBuiltInId(id)) return
        viewModelScope.launch { settings.deleteToneEqPreset(id) }
    }

    /** 톤 슬라이더를 직접 바꾸면 선택 프리셋과 달라지므로 선택 해제(dirty). */
    private fun markToneEqPresetDirty() {
        if (selectedToneEqPresetId.value != null) {
            viewModelScope.launch { settings.setSelectedToneEqPresetId(null) }
        }
    }

    /** 사용자가 밴드를 직접 바꾸면 선택된 프리셋과 달라지므로 선택을 해제(dirty). */
    private fun markGraphicEqPresetDirty() {
        if (selectedGraphicEqPresetId.value != null) {
            viewModelScope.launch { settings.setSelectedGraphicEqPresetId(null) }
        }
    }

    // ── EQ 모드 / 전역 Q / 파라메트릭 편집 액션 ──────────────────────────────────

    /** EQ 편집 모드 전환. 전환 후 그 모드의 결과가 들리도록(비평탄) USER 청취 모드로 보장. */
    fun setEqMode(mode: EqMode) {
        viewModelScope.launch { settings.setEqMode(mode.name) }
        val specs = currentManualSpecs(mode, _bandGains.value, graphicQScale.value, _parametricBands.value, _toneGains.value)
        if (specs.isNotEmpty()) ensureUserListenMode()
    }

    /** 그래픽 전역 Q 배율(넓게/보통/좁게) 변경 — 허용 옵션으로 스냅 후 영속. 적용은 흐름이 담당. */
    fun setGraphicQScale(scale: Float) {
        val snapped = GraphicEqBands.snapQScale(scale)
        viewModelScope.launch { settings.setGraphicQScale(snapped) }
        if (eqMode.value == EqMode.GRAPHIC && GraphicEqBands.toSpecs(_bandGains.value, snapped).isNotEmpty()) {
            ensureUserListenMode()
        }
    }

    /**
     * 파라메트릭 밴드 추가(peaking, 평탄). 상한 [ParametricBand.MAX_BANDS]. 새 밴드를 선택.
     * 새 밴드는 기존 밴드 사이의 **가장 넓은 주파수 간격(로그)의 중앙**에 배치한다 — 같은 자리에
     * 겹쳐 점 하나처럼 보이던 문제를 방지하고, 매번 잡기 쉬운 위치에 떨어뜨린다.
     */
    fun addParametricBand() {
        val cur = _parametricBands.value
        if (cur.size >= ParametricBand.MAX_BANDS) return
        // 새 밴드에 작은 기본 게인(+3dB, 한계 내)을 줘 추가 즉시 곡선·소리가 변하게 — "추가했는데
        // 아무 변화 없네?" 혼란 방지(평탄 0dB는 무음이라 currentManualSpecs에서 걸러져 안 들림).
        val band = ParametricBand.default(java.util.UUID.randomUUID().toString())
            .copy(
                freqHz = ParametricBand.nextFreqHz(cur),
                gainDb = 3f.coerceIn(-gainLimitDb.value, gainLimitDb.value),
            )
        _parametricBands.value = cur + band
        _selectedParametricBandId.value = band.id
        markParametricPresetDirty()
        if (eqMode.value != EqMode.PARAMETRIC) setEqMode(EqMode.PARAMETRIC)
        ensureUserListenMode()
    }

    /**
     * 파라메트릭 밴드의 일부 필드를 갱신(드래그=freq/gain, 슬라이더=Q, 드롭다운=type).
     * 게인은 라이브 한계([gainLimitDb])로, 나머지는 [ParametricBand] 범위로 clamp.
     */
    fun updateParametricBand(
        id: String,
        type: Int? = null,
        freqHz: Float? = null,
        gainDb: Float? = null,
        q: Float? = null,
    ) {
        val limit = gainLimitDb.value
        var changed = false
        _parametricBands.value = _parametricBands.value.map { b ->
            if (b.id != id) return@map b
            changed = true
            updateParametricBandModel(
                band = b,
                type = type,
                freqHz = freqHz,
                gainDb = gainDb,
                q = q,
                gainLimitDb = limit,
            )
        }
        if (changed) {
            _selectedParametricBandId.value = id
            markParametricPresetDirty()
            ensureUserListenMode()
        }
    }

    /** 파라메트릭 밴드 삭제. 선택돼 있던 밴드면 선택 해제. */
    fun removeParametricBand(id: String) {
        _parametricBands.value = _parametricBands.value.filterNot { it.id == id }
        if (_selectedParametricBandId.value == id) _selectedParametricBandId.value = null
        markParametricPresetDirtyOrClearIfEmpty()
    }

    /** 파라메트릭 편집기에서 선택 밴드 지정(드래그 핸들/Q 슬라이더 대상). null이면 해제. */
    fun selectParametricBand(id: String?) {
        _selectedParametricBandId.value = id
    }

    /** 파라메트릭 밴드 전체 비움(선택 해제 포함). */
    fun resetParametricEq() {
        _parametricBands.value = emptyList()
        _selectedParametricBandId.value = null
        clearParametricPresetSelection()
    }

    /**
     * Load a parametric starting point or user preset. This replaces the current
     * parametric band list; it never stacks on top of the existing list and it
     * never changes the active AutoEQ/OPRA provider.
     */
    fun applyParametricPreset(id: String, source: ParametricPresetSource) {
        val preset = findParametricPreset(id, source) ?: return
        val limit = gainLimitDb.value
        val bands = preset.toParametricBands { java.util.UUID.randomUUID().toString() }
            .map { b -> b.copy(gainDb = b.gainDb.coerceIn(-limit, limit)).normalized() }
            .take(ParametricBand.MAX_BANDS)
        if (bands.isEmpty()) return
        _parametricBands.value = bands
        _selectedParametricBandId.value = bands.firstOrNull()?.id
        viewModelScope.launch {
            settings.setSelectedParametricPreset(preset.id, source, dirty = false)
        }
        if (eqMode.value != EqMode.PARAMETRIC) setEqMode(EqMode.PARAMETRIC)
        ensureUserListenMode()
    }

    fun saveCurrentParametricPreset(name: String) {
        val bands = _parametricBands.value.map { it.normalized() }.take(ParametricBand.MAX_BANDS)
        if (bands.isEmpty()) return
        val now = System.currentTimeMillis()
        val preset = ParametricEqPreset(
            id = java.util.UUID.randomUUID().toString(),
            name = name.trim().ifBlank { application.getString(R.string.parametric_preset_default_name) },
            category = application.getString(R.string.parametric_preset_category_user),
            source = ParametricPresetSource.USER,
            bands = bands.map { ParametricPresetBand.fromBand(it) },
            createdAtMs = now,
            updatedAtMs = now,
        ).normalized()
        viewModelScope.launch {
            settings.upsertParametricEqPreset(preset)
            settings.setSelectedParametricPreset(preset.id, ParametricPresetSource.USER, dirty = false)
        }
    }

    fun deleteUserParametricPreset(id: String) {
        if (ParametricPresetCatalog.isBuiltInId(id)) return
        viewModelScope.launch {
            settings.deleteUserParametricEqPreset(id)
            if (
                selectedParametricEqPresetId.value == id &&
                selectedParametricEqPresetSource.value == ParametricPresetSource.USER
            ) {
                settings.setSelectedParametricPreset(null, null, dirty = false)
            }
        }
    }

    /** 사용자가 EQ를 만지면 '내 설정(USER)'으로 자동 전환 — 편집 결과가 즉시 들리게. */
    private fun ensureUserListenMode() {
        if (listenMode.value != ListenMode.USER) setListenMode(ListenMode.USER)
    }

    private fun findParametricPreset(id: String, source: ParametricPresetSource): ParametricEqPreset? =
        parametricPresets.value.firstOrNull { it.id == id && it.source == source }?.normalized()

    private fun markParametricPresetDirty() {
        if (selectedParametricEqPresetId.value != null && !parametricEqPresetDirty.value) {
            viewModelScope.launch { settings.setParametricPresetDirty(true) }
        }
    }

    private fun markParametricPresetDirtyOrClearIfEmpty() {
        if (_parametricBands.value.isEmpty()) clearParametricPresetSelection() else markParametricPresetDirty()
    }

    private fun clearParametricPresetSelection() {
        if (selectedParametricEqPresetId.value != null || parametricEqPresetDirty.value) {
            viewModelScope.launch { settings.setSelectedParametricPreset(null, null, dirty = false) }
        }
    }

    /**
     * 현재 모드의 Manual chain 스펙을 계산한다(응답 그래프·엔진 적용 공용).
     *   GRAPHIC    — 20밴드 peaking(전역 Q 배율 [qScale] 반영, ~평탄 밴드 제외).
     *   PARAMETRIC — 사용자 정의 타입드 밴드. peaking/shelf는 ~평탄(게인≈0)이면 제외하되
     *                HIGH_PASS는 게인과 무관하게 항상 동작하므로 유지한다.
     */
    private fun currentManualSpecs(
        mode: EqMode,
        gains: FloatArray,
        qScale: Float,
        bands: List<ParametricBand>,
        tone: FloatArray,
    ): List<BiquadSpec> = when (mode) {
        EqMode.TONE -> toneSpecs(tone)
        EqMode.GRAPHIC -> GraphicEqBands.toSpecs(gains, qScale)
        EqMode.PARAMETRIC -> bands.mapNotNull { b ->
            val spec = b.toBiquadSpec()
            if (spec.type == BiquadType.HIGH_PASS || kotlin.math.abs(spec.gainDb) >= 0.05) spec else null
        }
    }

    /**
     * 톤 3밴드 → Manual chain 스펙. 베이스(로우셸프 100Hz) · 미드(피킹 1kHz) · 트레블(하이셸프 10kHz).
     * 게인≈0 밴드는 제외(무음 0dB 필터 방지). 주파수/Q는 고정값.
     */
    private fun toneSpecs(tone: FloatArray): List<BiquadSpec> {
        fun band(nativeId: Int, hz: Double, q: Double, db: Float): BiquadSpec? =
            if (kotlin.math.abs(db) < 0.05f) null
            else BiquadSpec(biquadTypeFromNativeId(nativeId), hz, db.toDouble(), q)
        return listOfNotNull(
            band(1, 100.0, 0.70, tone.getOrElse(0) { 0f }),    // Low Shelf — Bass
            band(0, 1_000.0, 0.90, tone.getOrElse(1) { 0f }),  // Peaking — Mid
            band(2, 10_000.0, 0.70, tone.getOrElse(2) { 0f }), // High Shelf — Treble
        )
    }

    /**
     * 통합 Manual chain push — 그래픽/파라메트릭 공용. 타입별 계수는 엔진이 [filterTypes]로 분기.
     * manual on/off enable은 combine(listenMode + manualResponseSpecs)가 단독 소유 — 여기선 값만
     * push해 이중 writer 경합을 없앤다(값은 항상 push, enable은 combine이 결정).
     */
    private fun pushManualSpecs(rawSpecs: List<BiquadSpec>) {
        // 엔진 Manual 체인 상한(MAX_MANUAL_FILTERS=20) 방어 — 손상/변조/다운그레이드된 복원 데이터가
        // 20개를 넘겨도 updateManualEq의 require()로 크래시하지 않고 안전하게 절단.
        val specs = if (rawSpecs.size > AudioEngine.MAX_MANUAL_FILTERS)
            rawSpecs.take(AudioEngine.MAX_MANUAL_FILTERS) else rawSpecs
        val result = engine.updateManualEq(
            frequencies = FloatArray(specs.size) { specs[it].freqHz.toFloat() },
            gainsDB = FloatArray(specs.size) { specs[it].gainDb.toFloat() },
            qFactors = FloatArray(specs.size) { specs[it].q.toFloat() },
            filterTypes = IntArray(specs.size) { specs[it].type.toNativeId() },
        )
        if (result != 0) {
            _manualEqNativeApplied.value = false
            engine.setManualEqEnabled(false)
            runCatching {
                engine.updateManualEq(FloatArray(0), FloatArray(0), FloatArray(0), IntArray(0))
            }
            _importMessage.value = application.getString(R.string.manual_eq_apply_failed_format, result)
            if (BuildConfig.DEBUG) {
                android.util.Log.w("AutoEqViewModel", "updateManualEq failed: rc=$result count=${specs.size}")
            }
        } else {
            _manualEqNativeApplied.value = true
        }
    }

    private data class ManualEnableState(
        val mode: ListenMode,
        val preampEnabled: Boolean,
        val specs: List<BiquadSpec>,
        val manualNativeOk: Boolean,
    )

    /** Phase 6 / P3-C: current native sample rate for the diagnostics card. */
    fun engineSampleRate(): Int = engine.sampleRate

    /**
     * Hashed identifier of the currently routed output device, suitable for
     * surfacing in the Diagnostics card without leaking PII (BT MAC etc.).
     */
    fun currentDeviceHash(): String? = deviceManager.currentDeviceHash

    /**
     * Tear down the ViewModel-scoped audio wiring when the retained ViewModel is finally cleared
     * (Activity finishing, not rotation):
     *   1. deviceManager.close()  — stop route callbacks / native updateAutoEq.
     *   2. musicController.close() — release the MediaController (NOT the service player).
     * The engine + spectrum analyzer are now process-lifetime app singletons (ServiceLocator),
     * shared with AuralTuneMediaService, so they are intentionally NOT closed here — playback may
     * continue in the background after the Activity/ViewModel is gone.
     */
    override fun onCleared() {
        runCatching { deviceManager.close() }
        runCatching { musicController.close() }
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
            // 표시용 선택은 라우트 적격성과 무관하게 항상 기억(탭별 독립). 엔진 적용은 deviceManager가 담당.
            val resolved = api.resolve(entry)?.validated()
            val applied = deviceManager.selectProfileForCurrentDevice(entry)
            _selectedAutoEqProfile.value = resolved
            // Record into the quick-pick spinner so the user can re-select later.
            settings.addRecentProfile(entry)
            // Selecting an AutoEQ profile makes AUTOEQ the active correction provider (현재 사용중).
            if (applied) {
                settings.setActiveCorrectionProvider(SettingsStore.PROVIDER_AUTOEQ)
            } else {
                _importMessage.value = application.getString(R.string.profile_apply_failed)
            }
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
     * [opraSyncMutex]로 직렬화 — 비원자적 check-then-set 대신 동시 import를 원천 차단하고,
     * init 자동 import 중 사용자가 새로고침해도 무음 드롭 없이 뒤이어 실행된다(스피너 유지).
     */
    private suspend fun syncOpra(notify: Boolean, force: Boolean = false): OpraSyncResult =
        opraSyncMutex.withLock {
            _opraRefreshing.value = true
            try {
                val catalogEmpty = withContext(Dispatchers.IO) {
                    opraRepository.observeCatalog().first().isEmpty()
                }
                val result = withContext(Dispatchers.IO) { opraRepository.refresh(force || catalogEmpty) }
                _opraSyncState.value = opraRepository.syncState()
                if (notify) {
                    _importMessage.value = when (result) {
                        is OpraSyncResult.NoChange ->
                            application.getString(R.string.opra_sync_no_change)
                        is OpraSyncResult.Updated ->
                            application.getString(
                                R.string.opra_sync_updated_format,
                                result.products,
                                result.profiles,
                            )
                        is OpraSyncResult.Failed ->
                            application.getString(R.string.opra_sync_failed_format, result.reason)
                    }
                }
                result
            } finally {
                _opraRefreshing.value = false
            }
        }

    /** OPRA 행 탭 → 상세 시트 표시용으로 프로파일을 resolve(적용은 아직 안 함). */
    fun openOpraDetail(entry: OpraCatalogEntry) {
        viewModelScope.launch {
            val opra = opraRepository.resolve(entry)
            if (opra == null) {
                _importMessage.value = application.getString(R.string.opra_profile_not_found)
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
                _importMessage.value = application.getString(R.string.opra_profile_unsupported)
                return@launch
            }
            if (deviceManager.applyResolvedProfile(auto)) {
                _selectedOpraProfile.value = auto.validated()
                settings.setActiveCorrectionProvider(SettingsStore.PROVIDER_OPRA)
                settings.setActiveOpraProfileId(opra.id)
                settings.addRecentOpraProfile(opra.id, opra.profileName)
            } else {
                _selectedOpraProfile.value = auto.validated()
                settings.setActiveOpraProfileId(opra.id)
                settings.addRecentOpraProfile(opra.id, opra.profileName)
                settings.setActiveCorrectionProvider(SettingsStore.PROVIDER_AUTOEQ)
                _importMessage.value = application.getString(R.string.profile_apply_failed)
            }
        }
    }

    /** OPRA 빠른선택(최근) → id로 resolve해 상세 시트 없이 바로 적용(현재 사용중). */
    fun selectOpraRecent(recent: RecentOpraProfile) {
        viewModelScope.launch {
            val auto = opraRepository.resolveById(recent.id)?.toAutoEqProfile()
            if (auto == null) {
                _importMessage.value = application.getString(R.string.opra_profile_unsupported)
                return@launch
            }
            if (deviceManager.applyResolvedProfile(auto)) {
                _selectedOpraProfile.value = auto.validated()
                settings.setActiveCorrectionProvider(SettingsStore.PROVIDER_OPRA)
                settings.setActiveOpraProfileId(recent.id)
                settings.addRecentOpraProfile(recent.id, recent.name)
            } else {
                _selectedOpraProfile.value = auto.validated()
                settings.setActiveOpraProfileId(recent.id)
                settings.addRecentOpraProfile(recent.id, recent.name)
                settings.setActiveCorrectionProvider(SettingsStore.PROVIDER_AUTOEQ)
                _importMessage.value = application.getString(R.string.profile_apply_failed)
            }
        }
    }

    /** AutoEQ 탭 선택 해제. 활성 중이면 엔진+영속 정리, 미활성이면 표시/영속만(OPRA 재생 보존). */
    fun clearAutoEqSelection() {
        viewModelScope.launch {
            _selectedAutoEqProfile.value = null
            if (correctionProvider.value == SettingsStore.PROVIDER_AUTOEQ) {
                deviceManager.clearProfileForCurrentDevice()
            } else {
                settings.setSelectedProfileId(null)
            }
        }
    }

    /** OPRA 탭 선택 해제. 활성 중이면 엔진만 비운다(AutoEQ 기억/영속은 보존). */
    fun clearOpraSelection() {
        viewModelScope.launch {
            _selectedOpraProfile.value = null
            settings.setActiveOpraProfileId(null)
            if (correctionProvider.value == SettingsStore.PROVIDER_OPRA) {
                deviceManager.clearEngineOnly()
            }
        }
    }

    /** 비활성 AutoEQ 선택을 '지금 사용' — 다시 엔진에 적용하고 AUTOEQ를 활성 provider로. */
    fun useAutoEqSelection() {
        val p = _selectedAutoEqProfile.value ?: return
        viewModelScope.launch {
            if (deviceManager.applyResolvedProfile(p)) {
                settings.setActiveCorrectionProvider(SettingsStore.PROVIDER_AUTOEQ)
            } else {
                _importMessage.value = application.getString(R.string.profile_apply_failed)
            }
        }
    }

    /** 비활성 OPRA 선택을 '지금 사용' — 다시 엔진에 적용하고 OPRA를 활성 provider로. */
    fun useOpraSelection() {
        val p = _selectedOpraProfile.value ?: return
        viewModelScope.launch {
            if (deviceManager.applyResolvedProfile(p)) {
                settings.setActiveCorrectionProvider(SettingsStore.PROVIDER_OPRA)
            } else {
                settings.setActiveCorrectionProvider(SettingsStore.PROVIDER_AUTOEQ)
                _importMessage.value = application.getString(R.string.profile_apply_failed)
            }
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
                .ifBlank { importedProfileFallback }
            val result = api.importFromUri(uri, cleanName)
            when (result) {
                is com.coreline.autoeq.model.ParseResult.Success -> {
                    _importMessage.value = application.getString(R.string.import_success_format, cleanName)
                    // Auto-select the freshly imported profile.
                    val entry = AutoEqCatalogEntry(
                        id = result.profile.id,
                        name = result.profile.name,
                        measuredBy = importedSourceLabel,
                        relativePath = "",
                    )
                    selectProfile(entry)
                }
                is com.coreline.autoeq.model.ParseResult.Failure -> {
                    val reason = result.error::class.simpleName
                        ?: application.getString(R.string.parse_error_label)
                    _importMessage.value = application.getString(R.string.import_failure_format, reason)
                }
            }
        }
    }

    /** Phase 4 user action — wipe the network cache. Imports are NOT touched. */
    fun clearNetworkCache() {
        viewModelScope.launch {
            api.repository.clearCache()
            _importMessage.value = application.getString(R.string.cache_cleared)
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
                if (manual) _importMessage.value =
                    application.getString(R.string.profile_update_skip_original)
                return@launch
            }
            if (manual) _importMessage.value =
                application.getString(R.string.profile_update_checking)
            val result = withContext(Dispatchers.IO) { api.repository.syncDelta() }
            settings.setLastDeltaCheckMs(System.currentTimeMillis())
            when (result) {
                is DeltaResult.NoChange ->
                    if (manual) _importMessage.value =
                        application.getString(R.string.profile_update_no_change)
                is DeltaResult.Updated ->
                    _importMessage.value = application.getString(
                        R.string.profile_update_updated_format,
                        result.changedProfiles,
                        result.removedProfiles,
                    )
                is DeltaResult.FullResynced ->
                    _importMessage.value = application.getString(
                        R.string.profile_update_full_resync_completed_format,
                        result.changedFileCount,
                        result.catalogEntries,
                        result.invalidatedProfiles,
                    )
                is DeltaResult.FullResyncRequired ->
                    _importMessage.value = application.getString(
                        R.string.profile_update_full_resync_required_format,
                        result.changedFileCount,
                    )
                is DeltaResult.Failed ->
                    if (manual) _importMessage.value =
                        application.getString(R.string.profile_update_failed_format, result.reason)
            }
        }
    }

    /** Delete a user-imported profile (no-op for fetched profiles). */
    fun deleteImported(id: String) {
        viewModelScope.launch {
            api.deleteImported(id)
            // 가져온 프로파일은 AutoEQ 소스 — AutoEQ 탭 선택이 그거면 해제.
            if (_selectedAutoEqProfile.value?.id == id) clearAutoEqSelection()
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

        /**
         * OPRA 파서/매핑 버전. 올리면 기존 설치본이 init에서 1회 강제 재파싱한다.
         * v2: profileName을 헤드폰명(vendor+product)으로 보정(이전엔 details 노트가 이름이 됨).
         */
        private const val OPRA_PARSER_VERSION = 2

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
