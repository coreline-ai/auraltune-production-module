// SettingsStore.kt
// DataStore Preferences wrapper. Exposes a Flow per setting and suspending setters.
// Per-device AutoEq selections are serialized as a JSON string keyed under PERSISTED_SELECTIONS.
package com.coreline.auraltune.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.coreline.autoeq.model.AutoEqSelection
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
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

    // ----------------- Internals -----------------
    private fun encodeSelections(map: Map<String, AutoEqSelection>): String =
        json.encodeToString(selectionsSerializer, map)

    private fun decodeSelections(raw: String): Map<String, AutoEqSelection> =
        runCatching { json.decodeFromString(selectionsSerializer, raw) }.getOrElse { emptyMap() }

    companion object {
        const val DEFAULT_AUTOEQ_ENABLED = true
        const val DEFAULT_PREAMP_ENABLED = true
        const val DEFAULT_KILL_SWITCH_ENGAGED = false

        private val KEY_SELECTED_PROFILE_ID = stringPreferencesKey("selected_profile_id")
        private val KEY_AUTOEQ_ENABLED = booleanPreferencesKey("autoeq_enabled")
        private val KEY_PREAMP_ENABLED = booleanPreferencesKey("preamp_enabled")
        private val KEY_FAVORITE_IDS = stringSetPreferencesKey("favorite_profile_ids")
        private val KEY_PER_DEVICE_SELECTIONS = stringPreferencesKey("per_device_selections_json")
        private val KEY_KILL_SWITCH = booleanPreferencesKey("kill_switch_engaged")

        private val json: Json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        private val selectionsSerializer =
            MapSerializer(String.serializer(), AutoEqSelection.serializer())
    }
}

/**
 * Tagging type for any local @Serializable wrappers we may need to persist (placeholder
 * to keep the proguard-rules.pro `com.coreline.auraltune.data.**` keep clause meaningful).
 */
@Serializable
internal data class PersistedFlag(val value: Boolean = false)
