// MainActivity.kt
// Single launcher activity for the AuralTune MVP. Hosts the Compose tree.
package com.coreline.auraltune

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.coreline.auraltune.ui.AuralTuneApp

/**
 * Single-activity launcher. All UI state lives in Compose + ViewModel; this class only
 * sets up the content tree.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { AuralTuneApp() }
    }
}
