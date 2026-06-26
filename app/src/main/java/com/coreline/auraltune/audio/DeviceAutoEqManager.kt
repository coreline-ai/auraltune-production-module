// DeviceAutoEqManager.kt
// Phase 5 — owns per-device AutoEQ selection state and drives the engine when
// the active output route changes. This collapses three previously-separate
// concerns into a single coordinator:
//
//   1. Per-device profile selection persistence (delegated to SettingsStore).
//   2. Route observation (AudioDeviceCallback) and sample-rate updates.
//   3. Profile resolution + native engine push when the route changes.
//
// Threading: all public methods are control-thread safe. Internal callbacks
// dispatch onto the main looper. The native engine is NEVER touched from a
// callback thread directly.
package com.coreline.auraltune.audio

import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.coreline.auraltune.BuildConfig
import com.coreline.auraltune.R
import com.coreline.audio.AudioEngine
import com.coreline.autoeq.AutoEqApi
import com.coreline.autoeq.cache.ImportedProfileStore
import com.coreline.autoeq.model.AutoEqCatalogEntry
import com.coreline.autoeq.model.CatalogState
import com.coreline.autoeq.model.AutoEqProfile
import com.coreline.autoeq.model.AutoEqSelection
import com.coreline.auraltune.data.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.Closeable

/**
 * Coordinates per-device AutoEQ state. This is the SINGLE owner of writes to
 * [AudioEngine.updateAutoEq] / [AudioEngine.clearAutoEq] — the ViewModel
 * delegates UI selections through [selectProfileForCurrentDevice] so we never
 * have two writers racing on the engine (P0-3).
 *
 * Lifecycle:
 *   - [start] hooks into AudioManager and immediately reconciles the engine
 *     with the current active route.
 *   - [stop] / [close] unhooks and joins outstanding work.
 *
 * Phase 5 invariants:
 *   - Speaker / HDMI / telephony routes are auto-skipped (DeviceKey returns
 *     supportsAutoEq=false). Engine receives clearAutoEq() in those cases.
 *   - Saved selection for an unknown device key persists silently — when the
 *     device returns, the saved profile is reapplied.
 *   - PII redaction: only DeviceKey.stableHash() may leave the device.
 */
class DeviceAutoEqManager(
    private val context: Context,
    private val engine: AudioEngine,
    private val api: AutoEqApi,
    private val settings: SettingsStore,
    private val coroutineScope: CoroutineScope,
    private val mainHandler: Handler = Handler(Looper.getMainLooper()),
) : Closeable {

    private val audioManager: AudioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private var callback: AudioDeviceCallback? = null

    /** Last device key we successfully reconciled to, for debounce. */
    private var lastKey: DeviceKey? = null

    // (lastSampleRate removed in P0-2 — engine sample rate is the rate the
    //  playback path (MusicPlayerController / its AudioProcessor) feeds; route
    //  detection no longer drives engine.updateSampleRate.)

    /** Job for the in-flight profile resolution after route change. */
    private var resolveJob: Job? = null

    /**
     * Provider-aware reapply hook. When the active correction provider is OPRA, route changes must
     * reapply the OPRA profile — NOT the AutoEq [selectedProfileId] (which would clobber the user's
     * OPRA choice). The ViewModel injects this resolver (active OPRA id → engine-model profile);
     * null means "no OPRA profile" → the eligible route is cleared. Set before [start].
     */
    @Volatile
    var opraReapplyProvider: (suspend () -> AutoEqProfile?)? = null

    /**
     * The currently-applied DeviceKey hash, exposed for diagnostics UI / logs.
     * Always returns a one-way hash — never the raw key.
     */
    @Volatile
    var currentDeviceHash: String? = null
        private set

    fun start() {
        if (callback != null) return
        val cb = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
                reconcile()
            }
            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
                reconcile()
            }
        }
        audioManager.registerAudioDeviceCallback(cb, mainHandler)
        callback = cb
        reconcile() // initial state.
    }

    /**
     * Stop accepting route notifications and **wait** for any in-flight
     * profile-resolution job to finish before returning. Without the join, an
     * outstanding `resolveJob` could call `engine.updateAutoEq(...)` after the
     * caller has gone on to `engine.close()`. We cancel cooperatively, then
     * runBlocking-join with a hard timeout — the resolver hits a suspension
     * point regularly (network / disk / catalog wait), so cancellation
     * propagates within milliseconds in practice.
     *
     * Safe to call from any control thread. Idempotent.
     */
    fun stop() {
        callback?.let { audioManager.unregisterAudioDeviceCallback(it) }
        callback = null

        val job = resolveJob
        resolveJob = null
        if (job != null && job.isActive) {
            job.cancel()
            // Block briefly so the caller can safely close the engine after this returns.
            // The resolver does network/disk/awaits — cooperative cancellation is fast.
            kotlinx.coroutines.runBlocking {
                kotlin.runCatching {
                    kotlinx.coroutines.withTimeout(STOP_JOIN_TIMEOUT_MS) { job.join() }
                }
            }
        }
    }

    override fun close() = stop()

    /**
     * User-initiated profile selection (UI) — persists for the current device.
     * If no current device, persists the global selection only. Returns true
     * if a profile was successfully applied to the engine.
     */
    suspend fun selectProfileForCurrentDevice(entry: AutoEqCatalogEntry): Boolean {
        val key = lastKey
        if (BuildConfig.DEBUG) {
            Log.i(TAG, "selectProfile: '${entry.name}' key=${key?.displayName} eligible=${key?.supportsAutoEq}")
        }
        // Always persist the global "selectedProfileId" so post-restart restore works
        // even when no device is currently routed.
        settings.setSelectedProfileId(entry.id)
        if (key?.supportsAutoEq != true) {
            // Host-adapter contract: selecting a profile in the UI must not
            // make speaker / HDMI / line-out routes receive headphone correction.
            // Persist the global choice for the next eligible headphone route,
            // but keep the current non-headphone route in passthrough.
            engine.clearAutoEq()
            return false
        }
        settings.setSelectionForDevice(
            key.raw,
            AutoEqSelection(profileId = entry.id, isEnabled = true),
        )
        return applyProfile(entry, expectedDeviceRaw = key.raw)
    }

    suspend fun clearProfileForCurrentDevice() {
        val key = lastKey
        settings.setSelectedProfileId(null)
        if (key != null) {
            settings.setSelectionForDevice(key.raw, null)
        }
        engine.clearAutoEq()
    }

    /** Resolve and push a profile to the engine. Returns success. */
    private suspend fun applyProfile(
        entry: AutoEqCatalogEntry,
        expectedDeviceRaw: String? = null,
    ): Boolean {
        val profile = api.resolve(entry) ?: return false
        currentCoroutineContext().ensureActive()
        if (expectedDeviceRaw != null) {
            val current = lastKey
            if (current?.raw != expectedDeviceRaw || !current.supportsAutoEq) {
                return false
            }
        }
        val validated = profile.validated()
        if (validated.filters.isEmpty()) return false
        pushToEngine(validated)
        return true
    }

    /**
     * Provider-agnostic engine apply. Used by the OPRA comparison path (the OPRA profile is
     * converted to [AutoEqProfile] by the adapter in :app, then applied through the SAME engine
     * path AutoEq uses). [validated] is applied defensively. This intentionally bypasses the
     * per-device AutoEq selection bookkeeping — the caller (ViewModel) owns active-provider state.
     */
    fun applyResolvedProfile(profile: AutoEqProfile) {
        pushToEngine(profile.validated())
    }

    /**
     * 엔진 AutoEQ 체인만 비운다(영속 상태 불변). 활성 OPRA 보정을 해제하되 기억해 둔 per-device
     * AutoEQ 선택을 지우지 않기 위함 — provider별 선택을 ViewModel이 독립적으로 소유하기 때문.
     */
    fun clearEngineOnly() {
        engine.clearAutoEq()
    }

    private fun pushToEngine(p: AutoEqProfile) {
        val n = p.filters.size
        val types = IntArray(n)
        val freqs = FloatArray(n)
        val gains = FloatArray(n)
        val qs = FloatArray(n)
        for (i in 0 until n) {
            val f = p.filters[i]
            types[i] = f.type.nativeId
            freqs[i] = f.frequency.toFloat()
            gains[i] = f.gainDB
            qs[i] = f.q.toFloat()
        }
        engine.updateAutoEq(
            preampDB = p.preampDB,
            enableLimiter = true,
            profileOptimizedRate = p.optimizedSampleRate,
            filterTypes = types,
            frequencies = freqs,
            gainsDB = gains,
            qFactors = qs,
        )
        if (BuildConfig.DEBUG) {
            Log.i(TAG, "pushToEngine: applied $n filters, preamp=${p.preampDB}")
        }
    }

    /**
     * Reconcile engine state with the current physical route. Called on every
     * AudioDeviceCallback notification AND on [start].
     *
     * Steps:
     *   1. Pick the highest-priority output device.
     *   2. Build its DeviceKey (or skip if not eligible).
     *   3. Keep engine sample rate locked to the actual AudioTrack PCM rate.
     *   4. If device key changed, look up saved selection for this device and
     *      either resolve+apply or clearAutoEq.
     */
    private fun reconcile() {
        val devices = try {
            audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        } catch (t: Throwable) {
            Log.w(TAG, "getDevices threw", t); return
        }

        val chosen = pickPriorityOutput(devices) ?: run {
            // No output we recognize. Don't disturb the engine.
            return
        }
        val btAvail = hasBluetoothConnect()
        val key = DeviceKey.fromAudioDevice(chosen, btAddressAvailable = btAvail)
            ?: return

        // (a) Sample-rate update — DELIBERATELY DISABLED for MVP (P0-2).
        //
        // Rationale: AudioDeviceInfo.sampleRates lists the rates a device
        // CAN support, not the rate the current playback stream is ACTUALLY
        // using. The engine sample rate is driven by the playback path
        // (MusicPlayerController's AudioProcessor reports its stream rate), not
        // by route detection; if we silently call engine.updateSampleRate(
        // routeRate) here, the engine's coefficient pre-warp targets a rate
        // that doesn't match the PCM rate we're actually feeding it, and
        // AutoEQ center frequencies drift.
        //
        // So route detection deliberately does NOT touch the sample rate — the
        // playback path owns it (the AudioProcessor reconfigures the engine to
        // the decoded stream rate, e.g. 44100/48000).

        // (b) Device-key change → reapply / clear profile.
        if (lastKey?.raw != key.raw) {
            lastKey = key
            currentDeviceHash = key.stableHash()
            if (BuildConfig.DEBUG) {
                // displayName can be a BT/USB product name (user-identifiable) — debug-only.
                Log.i(TAG, "route → ${key.displayName} (eligible=${key.supportsAutoEq}, " +
                    "hash=${key.stableHash()})")
            }

            if (!key.supportsAutoEq) {
                resolveJob?.cancel()
                engine.clearAutoEq()
                return
            }

            // Resolve saved selection for this device. Cancel any in-flight resolve.
            resolveJob?.cancel()
            resolveJob = coroutineScope.launch {
                // Provider-aware: when OPRA is the active provider, reapply the OPRA profile on
                // this (eligible) route instead of the AutoEq selection — otherwise a route change
                // would overwrite/clear the user's OPRA choice.
                if (settings.activeCorrectionProvider.first() == SettingsStore.PROVIDER_OPRA) {
                    val opra = opraReapplyProvider?.invoke()
                    currentCoroutineContext().ensureActive()
                    if (opra != null && opra.validated().filters.isNotEmpty() && lastKey?.raw == key.raw) {
                        pushToEngine(opra.validated())
                    } else {
                        engine.clearAutoEq()
                    }
                    return@launch
                }

                val perDevice = settings.perDeviceSelections.first()
                val savedId = perDevice[key.raw]?.takeIf { it.isEnabled }?.profileId
                    ?: settings.selectedProfileId.first()

                if (savedId == null) {
                    engine.clearAutoEq()
                    return@launch
                }

                val entry = resolveSavedEntry(savedId)
                if (entry == null) {
                    engine.clearAutoEq()
                    return@launch
                }
                val applied = applyProfile(entry, expectedDeviceRaw = key.raw)
                if (!applied && lastKey?.raw == key.raw) {
                    engine.clearAutoEq()
                }
            }
        }
    }

    /**
     * Resolve a persisted profile id to a catalog entry.
     *
     * Important: use [AutoEqApi.observe], not repository.loadCatalog() directly,
     * so the lookup sees the same merged view as the UI: user imports + fetched
     * catalog. This fixes the cold-start/offline case where a saved `imp-*`
     * profile exists locally but the GitHub catalog is unavailable.
     *
     * For imported ids we also provide a direct synthetic-entry fast path. The
     * repository resolves imported profiles by id from [ImportedProfileStore], so
     * this works even if Application.primeImports() has not yet hydrated the
     * imports StateFlow.
     */
    private suspend fun resolveSavedEntry(savedId: String): AutoEqCatalogEntry? {
        if (savedId.startsWith(ImportedProfileStore.IMPORTED_PREFIX)) {
            return AutoEqCatalogEntry(
                id = savedId,
                name = context.getString(R.string.imported_profile_fallback),
                measuredBy = context.getString(R.string.imported_source_label),
                relativePath = "",
            )
        }

        val state = api.observe(coroutineScope).first {
            it is CatalogState.Loaded || it is CatalogState.Error
        }
        return (state as? CatalogState.Loaded)
            ?.entries
            ?.firstOrNull { it.id == savedId }
    }

    /**
     * Priority order — Phase 5 device-eligibility heuristic.
     */
    private fun pickPriorityOutput(devices: Array<AudioDeviceInfo>): AudioDeviceInfo? {
        val priority = intArrayOf(
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            if (Build.VERSION.SDK_INT >= 31) AudioDeviceInfo.TYPE_BLE_HEADSET else -1,
            // Non-headphone output routes are deliberately included after all
            // headphone-class routes. That lets DeviceKey mark them
            // supportsAutoEq=false and forces engine.clearAutoEq(), instead of
            // returning null and accidentally leaving the previous headphone
            // correction active on HDMI / line-out / speaker.
            AudioDeviceInfo.TYPE_HDMI,
            AudioDeviceInfo.TYPE_HDMI_ARC,
            AudioDeviceInfo.TYPE_HDMI_EARC,
            AudioDeviceInfo.TYPE_LINE_DIGITAL,
            AudioDeviceInfo.TYPE_LINE_ANALOG,
            AudioDeviceInfo.TYPE_TELEPHONY,
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, // final fallback.
        ).filter { it >= 0 }
        for (typeKey in priority) {
            devices.firstOrNull { it.type == typeKey }?.let { return it }
        }
        return null
    }

    private fun hasBluetoothConnect(): Boolean {
        if (Build.VERSION.SDK_INT < 31) return true // permission only required on API 31+
        return context.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED
    }

    /**
     * P1-2: getNativeOutputSampleRate() expects a stream type, not a channel
     * mask. Passing AudioFormat.CHANNEL_OUT_STEREO yields garbage on every
     * device we tested.
     */
    private fun pickFromAudioTrackHint(): Int? = try {
        AudioTrack.getNativeOutputSampleRate(AudioManager.STREAM_MUSIC)
            .takeIf { it in MIN_RATE..MAX_RATE }
    } catch (t: Throwable) { null }

    companion object {
        private const val TAG = "DeviceAutoEqManager"
        private const val MIN_RATE = 8_000
        private const val MAX_RATE = 192_000
        private const val STOP_JOIN_TIMEOUT_MS = 1_500L
    }
}
