// DebugSupport.kt  (src/debug — REAL implementation)
// Phase 1 A1 (dev-plan 110525): debug-only helpers fully separated from main source so the
// release build references NONE of them (main-source grep for AudioFxSessionProbe/firstAudioUri
// returns 0). The release variant lives in app/src/release and is a no-op with the same API.
package com.coreline.auraltune.ui

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.coreline.auraltune.audio.audiofx.AudioFxSessionProbe

/**
 * Debug-only support surface. Same FQN exists in src/release as a no-op, so `main` calls these
 * without any `BuildConfig.DEBUG` check or reference to debug-only classes.
 */
object DebugSupport {

    /**
     * T1 auto-test shortcut: first MediaStore audio item (needs READ_MEDIA_AUDIO, declared only
     * in the debug manifest overlay). Release returns null → caller falls back to SAF.
     */
    fun firstPlayableUri(context: Context): Uri? {
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val proj = arrayOf(MediaStore.Audio.Media._ID)
        return runCatching {
            context.contentResolver.query(
                collection, proj,
                "${MediaStore.Audio.Media.IS_MUSIC}!=0", null,
                "${MediaStore.Audio.Media.TITLE} ASC",
            )?.use { c ->
                if (c.moveToFirst()) ContentUris.withAppendedId(collection, c.getLong(0)) else null
            }
        }.getOrNull()
    }

    /**
     * Phase 2 PoC card — measures which external apps broadcast effect-control-sessions
     * (T2-OS coverage gate). Owns the [AudioFxSessionProbe] lifecycle. Release renders nothing.
     */
    @Composable
    fun AudioFxProbeCard() {
        val context = LocalContext.current
        val probe = remember { AudioFxSessionProbe(context) }
        DisposableEffect(Unit) {
            probe.start()
            onDispose { probe.close() }
        }
        val detections by probe.detections.collectAsState()

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("외부앱 신호 측정 (PoC)", style = MaterialTheme.typography.titleMedium)
                Text(
                    "이 앱을 켠 채 Spotify·유튜브 등 다른 음악 앱을 재생해 보세요. " +
                        "신호를 보내는 앱만 외부 EQ 적용이 가능합니다.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Text("감지 ${detections.size}건", style = MaterialTheme.typography.bodyMedium)
                if (detections.isEmpty()) {
                    Text(
                        "아직 감지 없음",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    detections.takeLast(10).reversed().forEach { d ->
                        Text(
                            "${d.action} · ${d.packageName} · session=${d.audioSession}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }
}
