// AutoEqViewModelFactory.kt
// Manual ViewModelProvider.Factory so we can build AutoEqViewModel from the Application
// singleton without pulling in Hilt for the MVP.
//
// Phase 2 (dev-plan 110525): the ViewModel — not the host Composable — now OWNS the audio
// stack (engine / MusicPlayerController / DeviceAutoEqManager). The factory therefore only
// needs the Application; the ViewModel constructs and closes the stack itself, so it survives
// configuration changes (rotation) and there is no dead-engine reference after recreation.
package com.coreline.auraltune.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.coreline.auraltune.AuralTuneApplication

/**
 * Factory that constructs [AutoEqViewModel] from the [AuralTuneApplication] singleton.
 *
 * `create()` runs once per ViewModelStore (i.e. once for the Activity's retained scope), so
 * the audio stack the ViewModel builds is created exactly once and retained across rotation.
 */
class AutoEqViewModelFactory(
    private val app: AuralTuneApplication,
) : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(AutoEqViewModel::class.java)) {
            "Unexpected ViewModel class $modelClass"
        }
        return AutoEqViewModel(app) as T
    }
}
