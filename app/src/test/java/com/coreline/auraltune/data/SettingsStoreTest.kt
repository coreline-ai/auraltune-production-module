package com.coreline.auraltune.data

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Provider-aware selection migration (Phase 6): legacy installs (no provider key) must read as
 * AUTOEQ, and OPRA selections must persist + clear independently. All assertions run in one method
 * with one [SettingsStore] instance to avoid Robolectric's "multiple DataStores for the same file"
 * limitation across test methods.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class SettingsStoreTest {

    @Test
    fun correctionProvider_defaultsToAutoEq_persists_andOpraIdRoundTrips() = runBlocking {
        val store = SettingsStore(RuntimeEnvironment.getApplication())

        // Legacy install (no key) -> AUTOEQ. This is the migration guarantee for existing users.
        assertEquals(SettingsStore.PROVIDER_AUTOEQ, store.activeCorrectionProvider.first())
        assertNull(store.activeOpraProfileId.first())

        // Switch to OPRA + record a profile id -> both persist.
        store.setActiveCorrectionProvider(SettingsStore.PROVIDER_OPRA)
        store.setActiveOpraProfileId("pud:vogue::ora")
        assertEquals(SettingsStore.PROVIDER_OPRA, store.activeCorrectionProvider.first())
        assertEquals("pud:vogue::ora", store.activeOpraProfileId.first())

        // Switch back to AUTOEQ + clear the OPRA id.
        store.setActiveCorrectionProvider(SettingsStore.PROVIDER_AUTOEQ)
        store.setActiveOpraProfileId(null)
        assertEquals(SettingsStore.PROVIDER_AUTOEQ, store.activeCorrectionProvider.first())
        assertNull(store.activeOpraProfileId.first())

        // Player queue snapshot: SAF URIs + current index/position survive a JSON round-trip.
        val snapshot = PlaybackSnapshot(
            tracks = listOf(
                PlaybackTrack("content://media/external/audio/media/1", "Track One"),
                PlaybackTrack("content://media/external/audio/media/2", "Track Two"),
            ),
            index = 1,
            positionMs = 12_345L,
        )
        store.setPlaybackSnapshot(snapshot)
        assertEquals(snapshot, store.playbackSnapshot.first())
        store.setPlaybackSnapshot(null)
        assertNull(store.playbackSnapshot.first())

        // Parametric presets are user Manual-EQ data; changing them must not alter OPRA provider.
        store.setActiveCorrectionProvider(SettingsStore.PROVIDER_OPRA)
        val preset = ParametricEqPreset(
            id = "user-preset",
            name = "My FPS",
            category = "사용자",
            source = ParametricPresetSource.USER,
            bands = listOf(
                ParametricPresetBand(type = 0, freqHz = 250f, gainDb = -2f, q = 1f),
                ParametricPresetBand(type = 0, freqHz = 2_500f, gainDb = 3f, q = 1.2f),
            ),
            createdAtMs = 1L,
            updatedAtMs = 1L,
        )
        store.upsertParametricEqPreset(preset)
        store.setSelectedParametricPreset(preset.id, ParametricPresetSource.USER, dirty = false)
        assertEquals(SettingsStore.PROVIDER_OPRA, store.activeCorrectionProvider.first())
        assertEquals(listOf(preset.normalized()), store.userParametricEqPresets.first())
        assertEquals(preset.id, store.selectedParametricPresetId.first())
        assertEquals(ParametricPresetSource.USER, store.selectedParametricPresetSource.first())
        assertEquals(false, store.parametricPresetDirty.first())

        store.setParametricPresetDirty(true)
        assertEquals(true, store.parametricPresetDirty.first())
        assertEquals(SettingsStore.PROVIDER_OPRA, store.activeCorrectionProvider.first())

        store.deleteUserParametricEqPreset(preset.id)
        assertEquals(emptyList<ParametricEqPreset>(), store.userParametricEqPresets.first())
        assertNull(store.selectedParametricPresetId.first())
        assertNull(store.selectedParametricPresetSource.first())
        assertEquals(false, store.parametricPresetDirty.first())
        assertEquals(SettingsStore.PROVIDER_OPRA, store.activeCorrectionProvider.first())
    }
}
