// DebugSupport.kt  (src/release — NO-OP implementation)
// Phase 1 A1: release variant. Same FQN/API as src/debug, but does nothing — so the release
// build never references AudioFxSessionProbe, MediaStore direct-play, or the PoC card.
package com.coreline.auraltune.ui

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.Composable

object DebugSupport {

    /** Release: no MediaStore shortcut — caller uses SAF. */
    fun firstPlayableUri(context: Context): Uri? = null

    /** Release: no PoC measurement card. */
    @Composable
    fun AudioFxProbeCard() {
        // intentionally empty
    }
}
