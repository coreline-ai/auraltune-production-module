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
 * Invariants:
 *   - Correction is applied on EVERY output route (speaker / HDMI / line / BT /
 *     USB / wired). Route type is NOT a gate — the engine always outputs per the
 *     active profile; matching headphones to the profile is the user's choice.
 *   - Saved selection for a device key persists silently — when the device
 *     returns, the saved profile is reapplied; routes with no remembered choice
 *     fall back to the global selection.
 *   - PII redaction: only DeviceKey.stableHash() may leave the device.
 */
internal class DeviceAutoEqManager(
    private val context: Context,
    private val engine: AutoEqEngineSink,
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
            Log.i(TAG, "selectProfile: '${entry.name}' key=${key?.displayName}")
        }
        val applied = applyProfile(entry, expectedDeviceRaw = key?.raw)
        if (!applied) return false

        // Persist only after native apply succeeds. Otherwise route restore would
        // remember a profile that never actually reached the engine.
        settings.setSelectedProfileId(entry.id)
        if (key != null) {
            settings.setSelectionForDevice(
                key.raw,
                AutoEqSelection(profileId = entry.id, isEnabled = true),
            )
        }
        return true
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
        if (expectedDeviceRaw != null && lastKey?.raw != expectedDeviceRaw) {
            // Route changed mid-resolve — abandon; the new route runs its own resolve.
            return false
        }
        val validated = profile.validated()
        if (validated.filters.isEmpty()) return false
        return pushToEngine(validated)
    }

    /**
     * Provider-agnostic engine apply. Used by OPRA and "use current selection" paths.
     * Applies on any output route; returns false only when the profile has no usable
     * filters (a genuinely bad profile), in which case the engine is left cleared.
     */
    fun applyResolvedProfile(profile: AutoEqProfile): Boolean {
        val validated = profile.validated()
        if (validated.filters.isEmpty()) {
            engine.clearAutoEq()
            return false
        }
        return pushToEngine(validated)
    }

    /**
     * 엔진 AutoEQ 체인만 비운다(영속 상태 불변). 활성 OPRA 보정을 해제하되 기억해 둔 per-device
     * AutoEQ 선택을 지우지 않기 위함 — provider별 선택을 ViewModel이 독립적으로 소유하기 때문.
     */
    fun clearEngineOnly() {
        engine.clearAutoEq()
    }

    private fun pushToEngine(p: AutoEqProfile): Boolean {
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
        val rc = engine.updateAutoEq(
            preampDB = p.preampDB,
            enableLimiter = true,
            profileOptimizedRate = p.optimizedSampleRate,
            filterTypes = types,
            frequencies = freqs,
            gainsDB = gains,
            qFactors = qs,
        )
        if (rc != 0) {
            engine.clearAutoEq()
            if (BuildConfig.DEBUG) {
                Log.w(TAG, "pushToEngine: native update rejected rc=$rc count=$n")
            }
            return false
        }
        if (BuildConfig.DEBUG) {
            Log.i(TAG, "pushToEngine: applied $n filters, preamp=${p.preampDB}")
        }
        return true
    }

    /**
     * Reconcile engine state with the current physical route. Called on every
     * AudioDeviceCallback notification AND on [start].
     *
     * Steps:
     *   1. Pick the highest-priority output device.
     *   2. Build its DeviceKey (every route maps to a key).
     *   3. Keep engine sample rate locked to the actual AudioTrack PCM rate.
     *   4. If device key changed, look up the saved selection for this device
     *      (falling back to the global selection) and reapply it. Correction is
     *      cleared only when no profile is selected at all — never by route type.
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

        // (b) Device-key change → reapply the profile for the new route
        // (correction is NEVER cleared because of the route type).
        if (lastKey?.raw != key.raw) {
            lastKey = key
            currentDeviceHash = key.stableHash()
            if (BuildConfig.DEBUG) {
                // displayName can be a BT/USB product name (user-identifiable) — debug-only.
                Log.i(TAG, "route → ${key.displayName} (hash=${key.stableHash()})")
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
                    val applied = opra != null &&
                        opra.validated().filters.isNotEmpty() &&
                        lastKey?.raw == key.raw &&
                        pushToEngine(opra.validated())
                    if (!applied) {
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
            // Non-headphone routes come after headphone-class routes so that when
            // both are present the headphone route wins as the "current" device.
            // Correction still applies on every route — none of these are skipped.
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
