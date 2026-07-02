package com.coreline.auraltune.audio

import com.coreline.auraltune.audio.eq.BiquadSpec
import com.coreline.auraltune.audio.eq.BiquadType
import com.coreline.auraltune.data.PlaybackProcessingMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackProcessingStateTest {
    @Test
    fun mode_controlsNativeEngineGate() {
        val scheduled = ArrayList<() -> Unit>()
        val state = PlaybackProcessingState(
            scheduleNativeGateChange = { _, action -> scheduled += action },
        )

        assertTrue(state.useNativeEngine())

        state.setMode(PlaybackProcessingMode.ANDROID_DYNAMICS)
        assertTrue(state.useNativeEngine())

        scheduled.removeAt(0).invoke()
        assertFalse(state.useNativeEngine())

        state.setMode(PlaybackProcessingMode.AURAL_TUNE)
        assertTrue(state.useNativeEngine())
    }

    @Test
    fun returningToAuralTune_cancelsPendingNativeGateDisable() {
        val scheduled = ArrayList<() -> Unit>()
        val state = PlaybackProcessingState(
            scheduleNativeGateChange = { _, action -> scheduled += action },
        )

        state.setMode(PlaybackProcessingMode.ANDROID_DYNAMICS)
        state.setMode(PlaybackProcessingMode.AURAL_TUNE)
        scheduled.removeAt(0).invoke()

        assertTrue(state.useNativeEngine())
    }

    @Test
    fun targetSpecs_areSharedAsLatestValue() {
        val state = PlaybackProcessingState()
        val specs = listOf(BiquadSpec(BiquadType.PEAKING, 1000.0, 3.0, 1.0))

        state.setTargetSpecs(specs, headroomDb = -5.5f)

        assertEquals(specs, state.targetSpecs.value)
        assertEquals(-5.5f, state.targetHeadroomDb.value, 0.0001f)
    }
}
