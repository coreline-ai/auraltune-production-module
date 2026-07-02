package com.coreline.auraltune.ui

import com.coreline.autoeq.model.AutoEqProfile
import com.coreline.autoeq.model.AutoEqSource
import com.coreline.auraltune.data.PlaybackProcessingMode
import com.coreline.auraltune.data.SettingsStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CorrectionUiStateTest {

    @Test
    fun correctionSourceLabel_isHiddenInOriginalMode_evenWhenProfileIsSelected() {
        val profile = AutoEqProfile(
            id = "p1",
            name = "Profile",
            source = AutoEqSource.FETCHED,
            measuredBy = "tester",
            preampDB = -3f,
            filters = emptyList(),
        )

        assertFalse(isCorrectionAudible(ListenMode.ORIGINAL))
        assertNull(
            correctionSourceLabel(
                activeProfile = profile,
                correctionProvider = SettingsStore.PROVIDER_OPRA,
                listenMode = ListenMode.ORIGINAL,
            ),
        )
    }

    @Test
    fun correctionSourceLabel_tracksActiveProviderWhenAudible() {
        val profile = AutoEqProfile(
            id = "p1",
            name = "Profile",
            source = AutoEqSource.FETCHED,
            measuredBy = "tester",
            preampDB = -3f,
            filters = emptyList(),
        )

        assertTrue(isCorrectionAudible(ListenMode.AUTOEQ))
        assertEquals(
            "AutoEQ",
            correctionSourceLabel(
                activeProfile = profile,
                correctionProvider = SettingsStore.PROVIDER_AUTOEQ,
                listenMode = ListenMode.AUTOEQ,
            ),
        )
        assertEquals(
            "OPRA",
            correctionSourceLabel(
                activeProfile = profile,
                correctionProvider = SettingsStore.PROVIDER_OPRA,
                listenMode = ListenMode.USER,
            ),
        )
    }

    @Test
    fun androidDynamicsHeadroom_isOnlyAppliedInUserMode() {
        assertEquals(
            0f,
            androidDynamicsHeadroomDb(
                profilePreampDb = -6.5f,
                listenMode = ListenMode.ORIGINAL,
                processingMode = PlaybackProcessingMode.ANDROID_DYNAMICS,
            ),
            0.0001f,
        )
        assertEquals(
            0f,
            androidDynamicsHeadroomDb(
                profilePreampDb = -6.5f,
                listenMode = ListenMode.AUTOEQ,
                processingMode = PlaybackProcessingMode.ANDROID_DYNAMICS,
            ),
            0.0001f,
        )
        assertEquals(
            -6.5f,
            androidDynamicsHeadroomDb(
                profilePreampDb = -6.5f,
                listenMode = ListenMode.USER,
                processingMode = PlaybackProcessingMode.ANDROID_DYNAMICS,
            ),
            0.0001f,
        )
        assertEquals(
            0f,
            androidDynamicsHeadroomDb(
                profilePreampDb = -6.5f,
                listenMode = ListenMode.USER,
                processingMode = PlaybackProcessingMode.AURAL_TUNE,
            ),
            0.0001f,
        )
    }
}
