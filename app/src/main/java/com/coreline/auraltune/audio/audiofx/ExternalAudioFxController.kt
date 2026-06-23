// ExternalAudioFxController.kt
// Phase 6 (T2-OS): the actual external-app approximation controller (the step after the probe).
//
// When a supported external player broadcasts ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION, this
// attaches an OS equalizer ([OsEffectBackend]) to that session, fits the active AutoEQ profile to
// the backend's bands ([AutoEqApprox]), and applies it. On CLOSE (or a timeout safety net) it
// releases. T2 is an APPROXIMATION and only works for apps that broadcast the session — both are
// surfaced to the UI. Device-verified (touches android.media.audiofx); not unit-testable.
//
// NOTE: this is the implementation scaffold (dev-plan 110525 Phase 6, "피팅/백엔드 코드 먼저").
// It is NOT auto-started yet — coverage (which apps broadcast) must be measured first, and T1's
// own engine path must never be applied to the same session as T2 (택일).
package com.coreline.auraltune.audio.audiofx

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.audiofx.AudioEffect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.coreline.auraltune.BuildConfig
import com.coreline.auraltune.audio.eq.BiquadSpec
import java.io.Closeable

class ExternalAudioFxController(
    private val context: Context,
    /** Active AutoEQ correction as engine-equivalent filters. Empty = nothing to apply. */
    private val targetProvider: () -> List<BiquadSpec>,
    /** Master gate (T2 enabled AND not kill-switched). Re-checked on every OPEN. */
    private val isEnabled: () -> Boolean,
    private val sampleRate: Double = 48_000.0,
    private val sessionTimeoutMs: Long = 30 * 60 * 1000L,
) : Closeable {

    /** One attached external session: its backend + the measured approximation error. */
    data class Session(
        val audioSessionId: Int,
        val packageName: String,
        val backend: String,
        val rmsErrorDb: Double,
        val maxErrorDb: Double,
    )

    private val mainHandler = Handler(Looper.getMainLooper())
    private val backends = HashMap<Int, OsEffectBackend>()
    private val timeouts = HashMap<Int, Runnable>()
    private var receiver: BroadcastReceiver? = null

    /** Currently-attached sessions, for UI ("지원 앱 한정 / 근사 / 기기 의존" display). */
    val sessions = ArrayList<Session>()

    fun start() {
        if (receiver != null) return
        val r = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                when (intent?.action) {
                    AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION -> onOpen(intent)
                    AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION -> onClose(intent)
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION)
            addAction(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(r, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(r, filter)
        }
        receiver = r
    }

    // A throwing targetProvider/isEnabled or OS call must never escape onReceive (would ANR/crash
    // the host). The whole OPEN handler is guarded.
    private fun onOpen(intent: Intent) = runCatching { onOpenInner(intent) }.getOrElse {
        if (BuildConfig.DEBUG) Log.w(TAG, "onOpen failed: ${it.message}")
    }

    private fun onOpenInner(intent: Intent) {
        if (!isEnabled()) return
        val session = intent.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, AudioEffect.ERROR)
        if (session == AudioEffect.ERROR || backends.containsKey(session)) return
        val pkg = intent.getStringExtra(AudioEffect.EXTRA_PACKAGE_NAME) ?: "(unknown)"

        val target = targetProvider()
        if (target.isEmpty()) return // no active correction → nothing to approximate

        val (backend, result) = OsEffectBackend.attach(session)
        if (backend == null || result !is AttachResult.Attached) {
            if (BuildConfig.DEBUG) Log.w(TAG, "attach failed for session=$session: $result")
            return
        }

        val fit = AutoEqApprox.fit(target, backend.bandCenters(), sampleRate)
        if (!backend.apply(fit.bands)) {
            backend.release()
            return
        }

        backends[session] = backend
        sessions.add(Session(session, pkg, backend.name, fit.rmsErrorDb, fit.maxErrorDb))
        scheduleTimeout(session)
        if (BuildConfig.DEBUG) {
            Log.i(TAG, "attached ${backend.name} → session=$session pkg=$pkg " +
                "approx rms=%.2f max=%.2f dB".format(fit.rmsErrorDb, fit.maxErrorDb))
        }
    }

    private fun onClose(intent: Intent) {
        val session = intent.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, AudioEffect.ERROR)
        if (session != AudioEffect.ERROR) release(session)
    }

    private fun scheduleTimeout(session: Int) {
        timeouts.remove(session)?.let { mainHandler.removeCallbacks(it) }
        val r = Runnable { release(session) } // safety net for a missed CLOSE
        timeouts[session] = r
        mainHandler.postDelayed(r, sessionTimeoutMs)
    }

    private fun release(session: Int) {
        backends.remove(session)?.release()
        timeouts.remove(session)?.let { mainHandler.removeCallbacks(it) }
        sessions.removeAll { it.audioSessionId == session }
    }

    override fun close() {
        receiver?.let { runCatching { context.unregisterReceiver(it) } }
        receiver = null
        // The session maps are only ever mutated on the main thread (receiver + timeout run there).
        // Confine teardown to main too, so close() from any thread can't race those mutations.
        val teardown = Runnable { backends.keys.toList().forEach { release(it) } }
        if (Looper.myLooper() == Looper.getMainLooper()) teardown.run()
        else mainHandler.post(teardown)
    }

    private companion object {
        const val TAG = "ExternalAudioFx"
    }
}
