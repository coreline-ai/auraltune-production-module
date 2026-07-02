// SettingsStore.kt
// DataStore Preferences wrapper. Exposes a Flow per setting and suspending setters.
// Per-device AutoEq selections are serialized as a JSON string keyed under PERSISTED_SELECTIONS.
package com.coreline.auraltune.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.coreline.autoeq.model.AutoEqCatalogEntry
import com.coreline.autoeq.model.AutoEqSelection
import com.coreline.auraltune.audio.eq.GraphicEqBands
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "auraltune_settings")

/**
 * DataStore Preferences wrapper for app-wide MVP settings.
 *
 * Exposes [Flow] readers and `suspend` writers. Per-device AutoEQ selections are kept
 * as a JSON-serialized `Map<deviceKey, AutoEqSelection>` under one preferences key so we
 * can grow the schema without further migrations during MVP.
 */
class SettingsStore(context: Context) {

    private val store = context.dataStore

    // ----------------- Active correction provider (AUTOEQ vs OPRA comparison) -----------------
    // Which data source the currently-applied correction came from. Existing installs have no
    // value → default AUTOEQ (i.e. legacy AutoEqSelection is interpreted as provider=AUTOEQ).
    val activeCorrectionProvider: Flow<String> =
        store.data.map { it[KEY_ACTIVE_PROVIDER] ?: PROVIDER_AUTOEQ }
    suspend fun setActiveCorrectionProvider(provider: String) {
        store.edit { it[KEY_ACTIVE_PROVIDER] = provider }
    }

    /** Currently-selected OPRA profile id (used to restore an OPRA selection on launch). */
    val activeOpraProfileId: Flow<String?> = store.data.map { it[KEY_ACTIVE_OPRA_ID] }
    suspend fun setActiveOpraProfileId(id: String?) {
        store.edit { prefs ->
            if (id == null) prefs.remove(KEY_ACTIVE_OPRA_ID) else prefs[KEY_ACTIVE_OPRA_ID] = id
        }
    }

    /**
     * 마지막으로 OPRA 데이터를 import한 파서 버전. 앱의 현재 파서 버전보다 낮으면 init에서 1회 강제
     * 재파싱(파서/매핑 수정을 기존 설치본에 전파)한 뒤 갱신한다. 미설정(0)도 갱신 대상.
     */
    val opraParserVersion: Flow<Int> = store.data.map { it[KEY_OPRA_PARSER_VERSION] ?: 0 }
    suspend fun setOpraParserVersion(version: Int) {
        store.edit { it[KEY_OPRA_PARSER_VERSION] = version }
    }

    // ----------------- Player: queue + 현재 곡/위치 (재시작 복원) -----------------
    /** 마지막 큐 + 현재 곡 인덱스/위치. 비어 있으면 null. */
    val playbackSnapshot: Flow<PlaybackSnapshot?> =
        store.data.map { prefs -> prefs[KEY_PLAYBACK_SNAPSHOT]?.let { decodePlayback(it) } }

    suspend fun setPlaybackSnapshot(snapshot: PlaybackSnapshot?) {
        store.edit { prefs ->
            if (snapshot == null || snapshot.tracks.isEmpty()) prefs.remove(KEY_PLAYBACK_SNAPSHOT)
            else prefs[KEY_PLAYBACK_SNAPSHOT] = encodePlayback(snapshot)
        }
    }

    // ----------------- Player: 반복/셔플 모드 -----------------
    /** ExoPlayer repeatMode (0=OFF, 1=ONE, 2=ALL). 범위 밖 값은 OFF로 보정. */
    val repeatMode: Flow<Int> =
        store.data.map { prefs ->
            val m = prefs[KEY_REPEAT_MODE] ?: DEFAULT_REPEAT_MODE
            if (m in 0..2) m else DEFAULT_REPEAT_MODE
        }
    suspend fun setRepeatMode(mode: Int) {
        store.edit { it[KEY_REPEAT_MODE] = if (mode in 0..2) mode else DEFAULT_REPEAT_MODE }
    }

    /** 셔플(랜덤) 재생 여부. */
    val shuffleEnabled: Flow<Boolean> =
        store.data.map { it[KEY_SHUFFLE_ENABLED] ?: DEFAULT_SHUFFLE_ENABLED }
    suspend fun setShuffleEnabled(enabled: Boolean) {
        store.edit { it[KEY_SHUFFLE_ENABLED] = enabled }
    }

    // ----------------- Player: processing backend -----------------
    val playbackProcessingMode: Flow<PlaybackProcessingMode> =
        store.data.map { prefs -> PlaybackProcessingMode.fromKey(prefs[KEY_PLAYBACK_PROCESSING_MODE]) }

    suspend fun setPlaybackProcessingMode(mode: PlaybackProcessingMode) {
        store.edit { it[KEY_PLAYBACK_PROCESSING_MODE] = mode.key }
    }

    // ----------------- Selected profile -----------------
    val selectedProfileId: Flow<String?> = store.data.map { it[KEY_SELECTED_PROFILE_ID] }
    suspend fun setSelectedProfileId(id: String?) {
        store.edit { prefs ->
            if (id == null) prefs.remove(KEY_SELECTED_PROFILE_ID)
            else prefs[KEY_SELECTED_PROFILE_ID] = id
        }
    }

    // ----------------- AutoEQ enabled -----------------
    val autoEqEnabled: Flow<Boolean> =
        store.data.map { it[KEY_AUTOEQ_ENABLED] ?: DEFAULT_AUTOEQ_ENABLED }
    suspend fun setAutoEqEnabled(enabled: Boolean) {
        store.edit { it[KEY_AUTOEQ_ENABLED] = enabled }
    }

    // ----------------- Preamp enabled -----------------
    val preampEnabled: Flow<Boolean> =
        store.data.map { it[KEY_PREAMP_ENABLED] ?: DEFAULT_PREAMP_ENABLED }
    suspend fun setPreampEnabled(enabled: Boolean) {
        store.edit { it[KEY_PREAMP_ENABLED] = enabled }
    }

    // ----------------- Favorites -----------------
    val favoriteProfileIds: Flow<Set<String>> =
        store.data.map { it[KEY_FAVORITE_IDS] ?: emptySet() }
    suspend fun setFavoriteProfileIds(ids: Set<String>) {
        store.edit { it[KEY_FAVORITE_IDS] = ids }
    }
    suspend fun toggleFavorite(id: String) {
        store.edit { prefs ->
            val current = prefs[KEY_FAVORITE_IDS] ?: emptySet()
            prefs[KEY_FAVORITE_IDS] = if (id in current) current - id else current + id
        }
    }

    // ----------------- Per-device selections -----------------
    val perDeviceSelections: Flow<Map<String, AutoEqSelection>> =
        store.data.map { prefs ->
            prefs[KEY_PER_DEVICE_SELECTIONS]?.let { decodeSelections(it) } ?: emptyMap()
        }

    suspend fun setPerDeviceSelections(map: Map<String, AutoEqSelection>) {
        store.edit { it[KEY_PER_DEVICE_SELECTIONS] = encodeSelections(map) }
    }

    suspend fun setSelectionForDevice(deviceKey: String, selection: AutoEqSelection?) {
        store.edit { prefs ->
            val current = prefs[KEY_PER_DEVICE_SELECTIONS]?.let { decodeSelections(it) }.orEmpty()
            val updated = if (selection == null) current - deviceKey else current + (deviceKey to selection)
            prefs[KEY_PER_DEVICE_SELECTIONS] = encodeSelections(updated)
        }
    }

    // ----------------- Kill switch (local) -----------------
    //
    // Phase 7 release-engineering hook. When the kill switch is engaged the app
    // routes audio straight through the engine in passthrough mode (no DSP
    // applied) and disables catalog fetches. The intent is to give us a single
    // toggle in case a regression slips into a release: users can flip this off
    // in Settings instead of needing to uninstall.
    //
    // For MVP this is a LOCAL DataStore flag — Phase 7's "remote" kill switch
    // (Firebase Remote Config / Play Integrity) is a follow-up.
    val killSwitchEngaged: Flow<Boolean> =
        store.data.map { it[KEY_KILL_SWITCH] ?: DEFAULT_KILL_SWITCH_ENGAGED }

    suspend fun setKillSwitchEngaged(engaged: Boolean) {
        store.edit { prefs -> prefs[KEY_KILL_SWITCH] = engaged }
    }

    // ----------------- Listen mode (A/B/C compare: ORIGINAL / AUTOEQ / USER) -----------------
    // 3-way listening switch that replaces the kill switch + AutoEQ-correction toggle:
    //   ORIGINAL = no EQ (pure passthrough), AUTOEQ = profile only, USER = profile + graphic EQ.
    // Defaults to USER so a fresh install behaves like "AutoEQ + (flat) user EQ" == AutoEQ.
    val listenMode: Flow<String> =
        store.data.map { it[KEY_LISTEN_MODE] ?: LISTEN_USER }

    suspend fun setListenMode(mode: String) {
        store.edit { prefs -> prefs[KEY_LISTEN_MODE] = mode }
    }

    // ----------------- Graphic EQ: current gains (auto save/restore) -----------------
    /** Live 20-band gains; restored on launch. Always normalized to [GraphicEqBands.COUNT]. */
    val currentGraphicEqGains: Flow<FloatArray> =
        store.data.map { prefs ->
            prefs[KEY_CURRENT_GEQ_GAINS]?.let { decodeGains(it) } ?: FloatArray(GraphicEqBands.COUNT)
        }

    suspend fun setCurrentGraphicEqGains(gains: FloatArray) {
        store.edit { it[KEY_CURRENT_GEQ_GAINS] = encodeGains(gains) }
    }

    // ----------------- Graphic EQ: gain limit (±dB) + preamp graph overlay -----------------
    /** Selectable slider limit (±dB). Coerced to a valid [GraphicEqBands.GAIN_LIMIT_OPTIONS] value. */
    val graphicEqGainLimitDb: Flow<Float> =
        store.data.map { prefs ->
            val raw = prefs[KEY_GEQ_GAIN_LIMIT] ?: GraphicEqBands.MAX_GAIN_DB
            // Snap to the nearest allowed option so a stale/invalid value can't widen the range.
            GraphicEqBands.GAIN_LIMIT_OPTIONS.minByOrNull { kotlin.math.abs(it - raw) }
                ?: GraphicEqBands.MAX_GAIN_DB
        }

    suspend fun setGraphicEqGainLimitDb(limitDb: Float) {
        store.edit { it[KEY_GEQ_GAIN_LIMIT] = limitDb }
    }

    /** Whether the response graph overlays the active profile's preamp as a reference line. */
    val showPreampOnGraph: Flow<Boolean> =
        store.data.map { it[KEY_GEQ_SHOW_PREAMP] ?: DEFAULT_SHOW_PREAMP }

    suspend fun setShowPreampOnGraph(show: Boolean) {
        store.edit { it[KEY_GEQ_SHOW_PREAMP] = show }
    }

    // ----------------- Delta sync (incremental profile update) cooldown -----------------
    /** Epoch ms of the last background delta-sync check (0 = never). */
    val lastDeltaCheckMs: Flow<Long> = store.data.map { it[KEY_LAST_DELTA_CHECK] ?: 0L }

    suspend fun setLastDeltaCheckMs(ms: Long) {
        store.edit { it[KEY_LAST_DELTA_CHECK] = ms }
    }

    // ----------------- Graphic EQ: named presets -----------------
    val graphicEqPresets: Flow<List<GraphicEqPreset>> =
        store.data.map { prefs ->
            prefs[KEY_GEQ_PRESETS]?.let { decodePresets(it) } ?: emptyList()
        }

    val selectedGraphicEqPresetId: Flow<String?> =
        store.data.map { it[KEY_SELECTED_GEQ_PRESET] }

    suspend fun setSelectedGraphicEqPresetId(id: String?) {
        store.edit { prefs ->
            if (id == null) prefs.remove(KEY_SELECTED_GEQ_PRESET) else prefs[KEY_SELECTED_GEQ_PRESET] = id
        }
    }

    /** Insert or replace a preset by id (gains are normalized before storing). */
    suspend fun upsertGraphicEqPreset(preset: GraphicEqPreset) {
        store.edit { prefs ->
            val current = prefs[KEY_GEQ_PRESETS]?.let { decodePresets(it) }.orEmpty()
            val normalized = preset.copy(gainsDb = normalizeGains(preset.gainsDb.toFloatArray()).toList())
            val updated = current.filter { it.id != preset.id } + normalized
            prefs[KEY_GEQ_PRESETS] = encodePresets(updated)
        }
    }

    suspend fun deleteGraphicEqPreset(id: String) {
        store.edit { prefs ->
            val current = prefs[KEY_GEQ_PRESETS]?.let { decodePresets(it) }.orEmpty()
            prefs[KEY_GEQ_PRESETS] = encodePresets(current.filter { it.id != id })
            if (prefs[KEY_SELECTED_GEQ_PRESET] == id) prefs.remove(KEY_SELECTED_GEQ_PRESET)
        }
    }

    // ----------------- Tone EQ (Bass/Mid/Treble) -----------------
    /** 3-band tone gains [bass, mid, treble] in dB. Always normalized to length 3. */
    val toneGains: Flow<FloatArray> =
        store.data.map { prefs -> prefs[KEY_TONE_GAINS]?.let { decodeTone(it) } ?: FloatArray(TONE_BANDS) }
    suspend fun setToneGains(gains: FloatArray) {
        store.edit { it[KEY_TONE_GAINS] = encodeTone(gains) }
    }

    // ----------------- Tone EQ: named presets -----------------
    /** User-saved tone presets. Built-in starting points live in [ToneEqPresetCatalog]. */
    val userToneEqPresets: Flow<List<ToneEqPreset>> =
        store.data.map { prefs -> prefs[KEY_TONE_PRESETS]?.let { decodeTonePresets(it) } ?: emptyList() }

    val selectedToneEqPresetId: Flow<String?> =
        store.data.map { it[KEY_SELECTED_TONE_PRESET] }

    suspend fun setSelectedToneEqPresetId(id: String?) {
        store.edit { prefs ->
            if (id == null) prefs.remove(KEY_SELECTED_TONE_PRESET) else prefs[KEY_SELECTED_TONE_PRESET] = id
        }
    }

    /** Insert/replace a user tone preset (gains normalized to length 3; built-in ids rejected). */
    suspend fun upsertToneEqPreset(preset: ToneEqPreset) {
        if (ToneEqPresetCatalog.isBuiltInId(preset.id)) return
        store.edit { prefs ->
            val current = prefs[KEY_TONE_PRESETS]?.let { decodeTonePresets(it) }.orEmpty()
            val normalized = preset.copy(gainsDb = normalizeTone(preset.gainsDb.toFloatArray()).toList())
            val updated = current.filter { it.id != preset.id } + normalized
            prefs[KEY_TONE_PRESETS] = encodeTonePresets(updated)
        }
    }

    suspend fun deleteToneEqPreset(id: String) {
        store.edit { prefs ->
            val current = prefs[KEY_TONE_PRESETS]?.let { decodeTonePresets(it) }.orEmpty()
            prefs[KEY_TONE_PRESETS] = encodeTonePresets(current.filter { it.id != id })
            if (prefs[KEY_SELECTED_TONE_PRESET] == id) prefs.remove(KEY_SELECTED_TONE_PRESET)
        }
    }

    // ----------------- EQ mode (GRAPHIC vs PARAMETRIC) -----------------
    /** Which Manual-chain editor the user is in. Persisted as the EqMode enum name. */
    val eqMode: Flow<String> = store.data.map { it[KEY_EQ_MODE] ?: DEFAULT_EQ_MODE }
    suspend fun setEqMode(mode: String) {
        store.edit { it[KEY_EQ_MODE] = mode }
    }

    // ----------------- Graphic EQ: global Q scale (넓게/보통/좁게) -----------------
    /** Uniform Q multiplier for all graphic bands. Snapped to [GraphicEqBands.Q_SCALE_OPTIONS]. */
    val graphicQScale: Flow<Float> =
        store.data.map { prefs ->
            GraphicEqBands.snapQScale(prefs[KEY_GEQ_Q_SCALE] ?: GraphicEqBands.DEFAULT_Q_SCALE)
        }

    suspend fun setGraphicQScale(scale: Float) {
        store.edit { it[KEY_GEQ_Q_SCALE] = scale }
    }

    // ----------------- Parametric EQ: free-form band list -----------------
    /** Persisted parametric bands (each normalized on read). Empty when unset. */
    val parametricBands: Flow<List<ParametricBand>> =
        store.data.map { prefs -> prefs[KEY_PARAMETRIC_BANDS]?.let { decodeParametric(it) } ?: emptyList() }

    suspend fun setParametricBands(bands: List<ParametricBand>) {
        store.edit { prefs ->
            if (bands.isEmpty()) prefs.remove(KEY_PARAMETRIC_BANDS)
            else prefs[KEY_PARAMETRIC_BANDS] = encodeParametric(bands)
        }
    }

    // ----------------- Parametric EQ: starting-point + user presets -----------------
    /** User-saved parametric presets. Built-in starting points live in [ParametricPresetCatalog]. */
    val userParametricEqPresets: Flow<List<ParametricEqPreset>> =
        store.data.map { prefs ->
            prefs[KEY_PARAMETRIC_PRESETS]?.let { decodeParametricPresets(it) } ?: emptyList()
        }

    val selectedParametricPresetId: Flow<String?> =
        store.data.map { it[KEY_SELECTED_PARAMETRIC_PRESET] }

    val selectedParametricPresetSource: Flow<ParametricPresetSource?> =
        store.data.map { prefs -> ParametricPresetSource.fromKey(prefs[KEY_SELECTED_PARAMETRIC_PRESET_SOURCE]) }

    val parametricPresetDirty: Flow<Boolean> =
        store.data.map { it[KEY_PARAMETRIC_PRESET_DIRTY] ?: false }

    suspend fun setSelectedParametricPreset(
        id: String?,
        source: ParametricPresetSource?,
        dirty: Boolean,
    ) {
        store.edit { prefs ->
            if (id == null || source == null) {
                prefs.remove(KEY_SELECTED_PARAMETRIC_PRESET)
                prefs.remove(KEY_SELECTED_PARAMETRIC_PRESET_SOURCE)
                prefs.remove(KEY_PARAMETRIC_PRESET_DIRTY)
            } else {
                prefs[KEY_SELECTED_PARAMETRIC_PRESET] = id
                prefs[KEY_SELECTED_PARAMETRIC_PRESET_SOURCE] = source.name
                prefs[KEY_PARAMETRIC_PRESET_DIRTY] = dirty
            }
        }
    }

    suspend fun setParametricPresetDirty(dirty: Boolean) {
        store.edit { prefs ->
            if (prefs[KEY_SELECTED_PARAMETRIC_PRESET] != null) {
                prefs[KEY_PARAMETRIC_PRESET_DIRTY] = dirty
            }
        }
    }

    suspend fun upsertParametricEqPreset(preset: ParametricEqPreset) {
        store.edit { prefs ->
            val current = prefs[KEY_PARAMETRIC_PRESETS]?.let { decodeParametricPresets(it) }.orEmpty()
            val normalized = preset.normalized().copy(source = ParametricPresetSource.USER)
            val updated = current.filter { it.id != normalized.id } + normalized
            prefs[KEY_PARAMETRIC_PRESETS] = encodeParametricPresets(updated)
        }
    }

    suspend fun deleteUserParametricEqPreset(id: String) {
        store.edit { prefs ->
            val current = prefs[KEY_PARAMETRIC_PRESETS]?.let { decodeParametricPresets(it) }.orEmpty()
            prefs[KEY_PARAMETRIC_PRESETS] = encodeParametricPresets(current.filter { it.id != id })
            if (
                prefs[KEY_SELECTED_PARAMETRIC_PRESET] == id &&
                prefs[KEY_SELECTED_PARAMETRIC_PRESET_SOURCE] == ParametricPresetSource.USER.name
            ) {
                prefs.remove(KEY_SELECTED_PARAMETRIC_PRESET)
                prefs.remove(KEY_SELECTED_PARAMETRIC_PRESET_SOURCE)
                prefs.remove(KEY_PARAMETRIC_PRESET_DIRTY)
            }
        }
    }

    // ----------------- Recent / quick-pick profiles (spinner) -----------------
    /** Most-recently-selected catalog entries (most recent first), capped at [MAX_RECENT]. */
    val recentProfiles: Flow<List<AutoEqCatalogEntry>> =
        store.data.map { prefs -> prefs[KEY_RECENT_PROFILES]?.let { decodeRecents(it) } ?: emptyList() }

    /** Prepend [entry] (dedup by id), cap at [MAX_RECENT]. */
    suspend fun addRecentProfile(entry: AutoEqCatalogEntry) {
        store.edit { prefs ->
            val cur = prefs[KEY_RECENT_PROFILES]?.let { decodeRecents(it) }.orEmpty()
            val updated = (listOf(entry) + cur.filter { it.id != entry.id }).take(MAX_RECENT)
            prefs[KEY_RECENT_PROFILES] = encodeRecents(updated)
        }
    }

    /** Replace the whole list. */
    suspend fun setRecentProfiles(list: List<AutoEqCatalogEntry>) {
        store.edit { it[KEY_RECENT_PROFILES] = encodeRecents(list.take(MAX_RECENT)) }
    }

    /**
     * One-time pre-seed. Writes [list] ONLY if no recents key exists yet — the empty-check and the
     * write happen inside the same [store.edit] block (DataStore serializes writers), so a concurrent
     * [addRecentProfile] from a user tap can never be clobbered: whichever transaction commits first
     * sets the key, and the other sees it non-null (seed → no-op; add → prepend onto seeded list).
     * Returns true iff it actually seeded.
     */
    suspend fun seedRecentProfilesIfEmpty(list: List<AutoEqCatalogEntry>): Boolean {
        var seeded = false
        store.edit { prefs ->
            if (prefs[KEY_RECENT_PROFILES] == null && list.isNotEmpty()) {
                prefs[KEY_RECENT_PROFILES] = encodeRecents(list.take(MAX_RECENT))
                seeded = true
            }
        }
        return seeded
    }

    // ----------------- Recent / quick-pick OPRA profiles (별도 기록, 최대 [MAX_RECENT]) -----------------
    /** 최근 선택한 OPRA 프로파일(id+이름), 최근순. */
    val recentOpraProfiles: Flow<List<RecentOpraProfile>> =
        store.data.map { prefs -> prefs[KEY_RECENT_OPRA]?.let { decodeRecentOpra(it) } ?: emptyList() }

    /** Prepend (dedup by id), cap at [MAX_RECENT]. */
    suspend fun addRecentOpraProfile(id: String, name: String) {
        store.edit { prefs ->
            val cur = prefs[KEY_RECENT_OPRA]?.let { decodeRecentOpra(it) }.orEmpty()
            val updated = (listOf(RecentOpraProfile(id, name)) + cur.filter { it.id != id }).take(MAX_RECENT)
            prefs[KEY_RECENT_OPRA] = encodeRecentOpra(updated)
        }
    }

    // ----------------- Internals -----------------
    private fun encodeRecents(list: List<AutoEqCatalogEntry>): String =
        json.encodeToString(recentsSerializer, list)

    private fun decodeRecents(raw: String): List<AutoEqCatalogEntry> =
        runCatching { json.decodeFromString(recentsSerializer, raw) }.getOrElse { emptyList() }

    private fun encodeRecentOpra(list: List<RecentOpraProfile>): String =
        json.encodeToString(recentOpraSerializer, list)

    private fun decodeRecentOpra(raw: String): List<RecentOpraProfile> =
        runCatching { json.decodeFromString(recentOpraSerializer, raw) }.getOrElse { emptyList() }

    private fun encodeParametric(list: List<ParametricBand>): String =
        json.encodeToString(parametricSerializer, list.map { it.normalized() }.take(ParametricBand.MAX_BANDS))

    private fun decodeParametric(raw: String): List<ParametricBand> =
        runCatching {
            json.decodeFromString(parametricSerializer, raw).map { it.normalized() }.take(ParametricBand.MAX_BANDS)
        }.getOrElse { emptyList() }

    private fun encodeParametricPresets(list: List<ParametricEqPreset>): String =
        json.encodeToString(
            parametricPresetSerializer,
            list.map { it.normalized().copy(source = ParametricPresetSource.USER) },
        )

    private fun decodeParametricPresets(raw: String): List<ParametricEqPreset> =
        runCatching {
            json.decodeFromString(parametricPresetSerializer, raw)
                .map { it.normalized().copy(source = ParametricPresetSource.USER) }
                .filter { it.bands.isNotEmpty() && !ParametricPresetCatalog.isBuiltInId(it.id) }
        }.getOrElse { emptyList() }

    private fun encodePlayback(s: PlaybackSnapshot): String =
        json.encodeToString(PlaybackSnapshot.serializer(), s)

    private fun decodePlayback(raw: String): PlaybackSnapshot? =
        runCatching { json.decodeFromString(PlaybackSnapshot.serializer(), raw) }.getOrNull()

    private fun encodeSelections(map: Map<String, AutoEqSelection>): String =
        json.encodeToString(selectionsSerializer, map)

    private fun decodeSelections(raw: String): Map<String, AutoEqSelection> =
        runCatching { json.decodeFromString(selectionsSerializer, raw) }.getOrElse { emptyMap() }

    private fun encodeGains(gains: FloatArray): String =
        json.encodeToString(gainsSerializer, normalizeGains(gains).toList())

    private fun decodeGains(raw: String): FloatArray =
        runCatching { normalizeGains(json.decodeFromString(gainsSerializer, raw).toFloatArray()) }
            .getOrElse { FloatArray(GraphicEqBands.COUNT) }

    private fun encodePresets(list: List<GraphicEqPreset>): String =
        json.encodeToString(presetsSerializer, list)

    private fun decodePresets(raw: String): List<GraphicEqPreset> =
        runCatching { json.decodeFromString(presetsSerializer, raw) }.getOrElse { emptyList() }

    /**
     * Defensive: fix length to COUNT, drop NaN/Inf → 0, clamp to the absolute ceiling
     * (±[GraphicEqBands.MAX_GAIN_LIMIT_DB], not the live user limit) so gains saved while a
     * wider limit was active survive a round-trip. The live limit is enforced at edit time.
     */
    private fun normalizeGains(gains: FloatArray): FloatArray {
        val n = GraphicEqBands.COUNT
        val max = GraphicEqBands.MAX_GAIN_LIMIT_DB
        return FloatArray(n) { i ->
            val v = gains.getOrElse(i) { 0f }
            if (v.isFinite()) v.coerceIn(-max, max) else 0f
        }
    }

    private fun encodeTone(gains: FloatArray): String =
        json.encodeToString(gainsSerializer, normalizeTone(gains).toList())

    private fun decodeTone(raw: String): FloatArray =
        runCatching { normalizeTone(json.decodeFromString(gainsSerializer, raw).toFloatArray()) }
            .getOrElse { FloatArray(TONE_BANDS) }

    /** Fix length to [TONE_BANDS], drop NaN/Inf → 0, clamp to the absolute graphic ceiling. */
    private fun normalizeTone(gains: FloatArray): FloatArray {
        val max = GraphicEqBands.MAX_GAIN_LIMIT_DB
        return FloatArray(TONE_BANDS) { i ->
            val v = gains.getOrElse(i) { 0f }
            if (v.isFinite()) v.coerceIn(-max, max) else 0f
        }
    }

    private fun encodeTonePresets(list: List<ToneEqPreset>): String =
        json.encodeToString(
            toneEqPresetSerializer,
            list.map { it.copy(gainsDb = normalizeTone(it.gainsDb.toFloatArray()).toList()) },
        )

    private fun decodeTonePresets(raw: String): List<ToneEqPreset> =
        runCatching {
            json.decodeFromString(toneEqPresetSerializer, raw)
                .map { it.copy(gainsDb = normalizeTone(it.gainsDb.toFloatArray()).toList()) }
                .filter { !ToneEqPresetCatalog.isBuiltInId(it.id) }
        }.getOrElse { emptyList() }

    companion object {
        /** Tone EQ band count: [bass, mid, treble]. */
        const val TONE_BANDS = 3
        const val LISTEN_ORIGINAL = "ORIGINAL"
        const val LISTEN_AUTOEQ = "AUTOEQ"
        const val LISTEN_USER = "USER"

        const val DEFAULT_AUTOEQ_ENABLED = true
        const val DEFAULT_PREAMP_ENABLED = true
        const val DEFAULT_KILL_SWITCH_ENGAGED = false
        const val DEFAULT_SHOW_PREAMP = true
        const val DEFAULT_REPEAT_MODE = 0 // Player.REPEAT_MODE_OFF
        const val DEFAULT_SHUFFLE_ENABLED = false

        private val KEY_SELECTED_PROFILE_ID = stringPreferencesKey("selected_profile_id")
        private val KEY_AUTOEQ_ENABLED = booleanPreferencesKey("autoeq_enabled")
        private val KEY_PREAMP_ENABLED = booleanPreferencesKey("preamp_enabled")
        private val KEY_FAVORITE_IDS = stringSetPreferencesKey("favorite_profile_ids")
        private val KEY_PER_DEVICE_SELECTIONS = stringPreferencesKey("per_device_selections_json")
        private val KEY_KILL_SWITCH = booleanPreferencesKey("kill_switch_engaged")
        private val KEY_LISTEN_MODE = stringPreferencesKey("listen_mode")
        private val KEY_CURRENT_GEQ_GAINS = stringPreferencesKey("graphic_eq_current_gains_json")
        private val KEY_GEQ_PRESETS = stringPreferencesKey("graphic_eq_presets_json")
        private val KEY_SELECTED_GEQ_PRESET = stringPreferencesKey("graphic_eq_selected_preset_id")
        private val KEY_GEQ_GAIN_LIMIT = floatPreferencesKey("graphic_eq_gain_limit_db")
        private val KEY_GEQ_SHOW_PREAMP = booleanPreferencesKey("graphic_eq_show_preamp")
        private val KEY_LAST_DELTA_CHECK = longPreferencesKey("last_delta_check_ms")
        private val KEY_ACTIVE_PROVIDER = stringPreferencesKey("active_correction_provider")
        private val KEY_ACTIVE_OPRA_ID = stringPreferencesKey("active_opra_profile_id")

        const val PROVIDER_AUTOEQ = "AUTOEQ"
        const val PROVIDER_OPRA = "OPRA"
        private val KEY_RECENT_PROFILES = stringPreferencesKey("recent_profiles_json")
        private val KEY_RECENT_OPRA = stringPreferencesKey("recent_opra_profiles_json")
        private val KEY_OPRA_PARSER_VERSION = intPreferencesKey("opra_parser_version")
        private val KEY_PLAYBACK_SNAPSHOT = stringPreferencesKey("playback_snapshot_json")
        private val KEY_REPEAT_MODE = intPreferencesKey("playback_repeat_mode")
        private val KEY_SHUFFLE_ENABLED = booleanPreferencesKey("playback_shuffle_enabled")
        private val KEY_PLAYBACK_PROCESSING_MODE = stringPreferencesKey("playback_processing_mode")
        private val KEY_EQ_MODE = stringPreferencesKey("eq_mode")
        private val KEY_GEQ_Q_SCALE = floatPreferencesKey("graphic_eq_q_scale")
        private val KEY_PARAMETRIC_BANDS = stringPreferencesKey("parametric_bands_json")
        private val KEY_TONE_GAINS = stringPreferencesKey("tone_gains_json")
        private val KEY_TONE_PRESETS = stringPreferencesKey("tone_presets_json")
        private val KEY_SELECTED_TONE_PRESET = stringPreferencesKey("tone_selected_preset_id")
        private val KEY_PARAMETRIC_PRESETS = stringPreferencesKey("parametric_presets_json")
        private val KEY_SELECTED_PARAMETRIC_PRESET = stringPreferencesKey("parametric_selected_preset_id")
        private val KEY_SELECTED_PARAMETRIC_PRESET_SOURCE = stringPreferencesKey("parametric_selected_preset_source")
        private val KEY_PARAMETRIC_PRESET_DIRTY = booleanPreferencesKey("parametric_preset_dirty")

        /** Default EQ editing mode — must match EqMode.GRAPHIC.name. */
        const val DEFAULT_EQ_MODE = "GRAPHIC"

        /** Spinner / quick-pick capacity. */
        const val MAX_RECENT = 10

        private val json: Json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        private val selectionsSerializer =
            MapSerializer(String.serializer(), AutoEqSelection.serializer())
        private val gainsSerializer = ListSerializer(Float.serializer())
        private val presetsSerializer = ListSerializer(GraphicEqPreset.serializer())
        private val recentsSerializer = ListSerializer(AutoEqCatalogEntry.serializer())
        private val recentOpraSerializer = ListSerializer(RecentOpraProfile.serializer())
        private val parametricSerializer = ListSerializer(ParametricBand.serializer())
        private val parametricPresetSerializer = ListSerializer(ParametricEqPreset.serializer())
        private val toneEqPresetSerializer = ListSerializer(ToneEqPreset.serializer())
    }
}

/**
 * Tagging type for any local @Serializable wrappers we may need to persist (placeholder
 * to keep the proguard-rules.pro `com.coreline.auraltune.data.**` keep clause meaningful).
 */
@Serializable
internal data class PersistedFlag(val value: Boolean = false)
