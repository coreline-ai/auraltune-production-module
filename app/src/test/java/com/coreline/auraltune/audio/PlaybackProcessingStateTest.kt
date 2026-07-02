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
        val state = PlaybackProcessingState()

        assertTrue(state.useNativeEngine())

        state.setMode(PlaybackProcessingMode.ANDROID_DYNAMICS)
        assertFalse(state.useNativeEngine())

        state.setMode(PlaybackProcessingMode.AURAL_TUNE)
        assertTrue(state.useNativeEngine())
    }

    @Test
    fun targetSpecs_areSharedAsLatestValue() {
        val state = PlaybackProcessingState()
        val specs = listOf(BiquadSpec(BiquadType.PEAKING, 1000.0, 3.0, 1.0))

        state.setTargetSpecs(specs)

        assertEquals(specs, state.targetSpecs.value)
    }
}
