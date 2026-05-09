// ServiceLocator.kt
// Manual DI factory. Owns process-lifetime singletons and produces per-session AudioEngine
// instances on demand. We intentionally avoid Hilt/Koin to keep the MVP dependency surface
// small (Phase 0 scope).
package com.coreline.auraltune.di

import android.content.Context
import com.coreline.audio.AudioEngine
import com.coreline.autoeq.AutoEqApi
import com.coreline.autoeq.repository.AutoEqRepository
import com.coreline.auraltune.data.SettingsStore

/**
 * Owns long-lived dependencies. The class itself is created in
 * [com.coreline.auraltune.AuralTuneApplication.onCreate] and lives for the whole process.
 *
 * Per-session resources (e.g. [AudioEngine] which holds a native handle) are produced via
 * factory methods so callers can scope them with `Closeable` / lifecycle.
 */
class ServiceLocator(context: Context) {

    private val appContext: Context = context.applicationContext

    /** Repository owns catalog cache, profile cache, and the network fetcher. */
    val autoEqRepository: AutoEqRepository by lazy { AutoEqRepository(appContext) }

    /** Stable AutoEqApi facade (observe / search / resolve). */
    val autoEqApi: AutoEqApi by lazy { AutoEqApi(autoEqRepository) }

    /** DataStore-backed settings store. */
    val settingsStore: SettingsStore by lazy { SettingsStore(appContext) }

    /**
     * Build a new [AudioEngine] for a playback session. Callers MUST close the returned
     * instance after the audio thread has joined — see [com.coreline.auraltune.audio.AudioPlayerService].
     */
    fun createAudioEngine(sampleRate: Int = DEFAULT_SAMPLE_RATE): AudioEngine =
        AudioEngine(sampleRate)

    companion object {
        const val DEFAULT_SAMPLE_RATE = 48_000
    }
}
