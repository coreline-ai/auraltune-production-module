// AudioFxSessionProbe.kt
// Phase 2 PoC — 외부앱 커버리지 실측 도구 (게이트).
//
// 어떤 앱이 effect-control-session broadcast
// (AudioEffect.ACTION_OPEN/CLOSE_AUDIO_EFFECT_CONTROL_SESSION)를 보내는지 실기기에서 측정한다.
// 이게 측정돼야만 그 앱의 audioSessionId에 OS effect를 attach할 수 있다(T2 본구현의 전제).
//
// 이 클래스는 본구현이 아니라 임시 측정용이다. 측정이 끝나면 제거/대체된다.
// PII: 패키지명은 디버그 로그/화면 표시에만 쓰고, telemetry로 내보내지 않는다.
package com.coreline.auraltune.audio.audiofx

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.audiofx.AudioEffect
import android.os.Build
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.Closeable

class AudioFxSessionProbe(private val context: Context) : Closeable {

    /** 한 건의 broadcast 감지 기록. */
    data class Detection(
        val action: String,       // "OPEN" / "CLOSE"
        val packageName: String,  // broadcast를 보낸 앱
        val audioSession: Int,    // 그 앱의 audioSessionId
        val contentType: Int,     // CONTENT_TYPE_MUSIC 등
    )

    private val _detections = MutableStateFlow<List<Detection>>(emptyList())
    val detections: StateFlow<List<Detection>> = _detections.asStateFlow()

    private var receiver: BroadcastReceiver? = null

    /** 측정 시작 — 런타임 등록(implicit broadcast 제한으로 manifest 정적 등록 불가). */
    fun start() {
        if (receiver != null) return
        val r = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                intent ?: return
                val action = when (intent.action) {
                    AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION -> "OPEN"
                    AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION -> "CLOSE"
                    else -> return
                }
                val pkg = intent.getStringExtra(AudioEffect.EXTRA_PACKAGE_NAME) ?: "(unknown)"
                val session = intent.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, AudioEffect.ERROR)
                val content = intent.getIntExtra(AudioEffect.EXTRA_CONTENT_TYPE, -1)
                val d = Detection(action, pkg, session, content)
                Log.i(TAG, "broadcast: $d")
                // 최근 50건만 유지.
                _detections.value = (_detections.value + d).takeLast(50)
            }
        }
        val filter = IntentFilter().apply {
            addAction(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION)
            addAction(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION)
        }
        // API 33+ : 외부(다른 앱/시스템) broadcast를 받으려면 RECEIVER_EXPORTED 명시 필수.
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(r, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(r, filter)
        }
        receiver = r
        Log.i(TAG, "probe started")
    }

    /** 측정 종료 — 수신 해제. */
    fun stop() {
        receiver?.let { runCatching { context.unregisterReceiver(it) } }
        receiver = null
        Log.i(TAG, "probe stopped")
    }

    override fun close() = stop()

    companion object {
        private const val TAG = "AudioFxProbe"
    }
}
