// AutoEqViewModelFactory.kt
// Manual ViewModelProvider.Factory so we can inject :audio-engine + :autoeq-data + SettingsStore
// + DeviceAutoEqManager without pulling in Hilt for the MVP. The factory holds references for
// the lifetime of the host Composable; the produced ViewModel takes ownership.
package com.coreline.auraltune.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.coreline.audio.AudioEngine
import com.coreline.autoeq.AutoEqApi
import com.coreline.auraltune.audio.DeviceAutoEqManager
import com.coreline.auraltune.data.SettingsStore

/**
 * Factory that constructs [AutoEqViewModel] with its collaborators.
 *
 * P0-3: [deviceManager] is the single owner of writes to [AudioEngine.updateAutoEq];
 * the ViewModel never calls those engine methods directly. The factory passes the
 * manager through so the ViewModel can delegate UI selections to it.
 */
class AutoEqViewModelFactory(
    private val api: AutoEqApi,
    private val settings: SettingsStore,
    private val engine: AudioEngine,
    private val deviceManager: DeviceAutoEqManager,
) : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(AutoEqViewModel::class.java)) {
            "Unexpected ViewModel class $modelClass"
        }
        return AutoEqViewModel(api, settings, engine, deviceManager) as T
    }
}
