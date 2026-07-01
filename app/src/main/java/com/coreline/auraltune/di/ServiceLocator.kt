// ServiceLocator.kt
// Manual DI factory. Owns process-lifetime singletons and produces per-session AudioEngine
// instances on demand. We intentionally avoid Hilt/Koin to keep the MVP dependency surface
// small (Phase 0 scope).
package com.coreline.auraltune.di

import android.content.Context
import com.coreline.audio.AudioEngine
import com.coreline.auraltune.audio.AlbumArtCache
import com.coreline.auraltune.audio.PlaybackTelemetry
import com.coreline.auraltune.audio.SpectrumAnalyzer
import com.coreline.autoeq.AutoEqApi
import com.coreline.autoeq.repository.AutoEqRepository
import com.coreline.auraltune.BuildConfig
import com.coreline.auraltune.data.SettingsStore
import com.coreline.auraltune.opra.BundledAssetSnapshotSource
import com.coreline.auraltune.opra.GitHubRawSnapshotSource
import com.coreline.auraltune.opra.OpraRepository
import com.coreline.auraltune.opra.OpraRepositoryImpl
import com.coreline.auraltune.opra.OpraSnapshotSource
import com.coreline.auraltune.opra.db.OpraDatabase
import com.coreline.auraltune.opra.db.OpraRoomStore
import java.io.InputStream

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
     * OPRA (comparison) repository — fully separate Room DB + source from AutoEq.
     * Source policy: release reads a bundled, sha256-verified snapshot from assets (self-contained,
     * no network/GitHub dependency — the commercial-distribution requirement); debug fetches the
     * live GitHub raw snapshot so devs always test against upstream.
     */
    val opraRepository: OpraRepository by lazy {
        val store = OpraRoomStore(OpraDatabase.get(appContext))
        OpraRepositoryImpl(store, opraSnapshotSource(BuildConfig.DEBUG, appContext.assets::open))
    }

    /**
     * Process-lifetime audio EQ engine. Shared by the media playback service (its inline
     * [com.coreline.auraltune.audio.AuralTuneAudioProcessor]), the ViewModel's manual-EQ updates,
     * and [com.coreline.auraltune.audio.DeviceAutoEqManager]. Since the player now lives in a
     * MediaSessionService, the engine MUST outlive any single ViewModel — hence an app singleton,
     * not a per-session instance. The native handle is reclaimed at process death.
     */
    val audioEngine: AudioEngine by lazy { AudioEngine(DEFAULT_SAMPLE_RATE) }

    /** Process-lifetime spectrum analyzer. The service's processor feeds it; the UI reads its flow. */
    val spectrumAnalyzer: SpectrumAnalyzer by lazy { SpectrumAnalyzer() }

    /** Process-lifetime audio-format telemetry bridge (service processor → player UI). */
    val playbackTelemetry: PlaybackTelemetry by lazy { PlaybackTelemetry() }

    /** Process-lifetime LRU cache of small per-track cover thumbnails for the queue list. */
    val albumArtCache: AlbumArtCache by lazy { AlbumArtCache(appContext) }

    /**
     * Build a new [AudioEngine] for a playback session. Retained for callers/tests that need an
     * isolated engine; the app's playback path uses the [audioEngine] singleton above.
     */
    fun createAudioEngine(sampleRate: Int = DEFAULT_SAMPLE_RATE): AudioEngine =
        AudioEngine(sampleRate)

    companion object {
        const val DEFAULT_SAMPLE_RATE = 48_000

        /**
         * OPRA snapshot source policy (extracted for a deterministic unit guard):
         * release MUST use the bundled, sha256-verified snapshot (self-contained, no network);
         * debug uses the live GitHub-raw source so devs test against upstream. A release build
         * must never reach GitHub for OPRA data — [OpraSourcePolicyTest] enforces this.
         */
        fun opraSnapshotSource(
            isDebug: Boolean,
            openAsset: (String) -> InputStream,
        ): OpraSnapshotSource =
            if (isDebug) GitHubRawSnapshotSource() else BundledAssetSnapshotSource(openAsset)
    }
}
