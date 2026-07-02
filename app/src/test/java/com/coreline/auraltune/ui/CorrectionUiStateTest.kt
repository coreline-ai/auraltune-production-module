package com.coreline.auraltune.ui

import com.coreline.autoeq.model.AutoEqProfile
import com.coreline.autoeq.model.AutoEqSource
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
}
