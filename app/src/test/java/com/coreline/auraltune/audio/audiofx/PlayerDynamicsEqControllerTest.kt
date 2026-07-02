package com.coreline.auraltune.audio.audiofx

import com.coreline.auraltune.audio.eq.BiquadSpec
import com.coreline.auraltune.audio.eq.BiquadType
import com.coreline.auraltune.data.PlaybackProcessingMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerDynamicsEqControllerTest {
    @Test
    fun androidMode_attachesAndAppliesProfileToSession() {
        val backend = FakeBackend()
        val statuses = ArrayList<PlayerDynamicsEqStatus>()
        val controller = PlayerDynamicsEqController(
            statusSink = statuses::add,
            attachBackend = { backend to AttachResult.Attached(backend.name, backend.bandCenters().size) },
        )

        controller.onAudioSessionIdChanged(42)
        controller.setTargetSpecs(listOf(BiquadSpec(BiquadType.PEAKING, 1000.0, 6.0, 1.0)))
        controller.setMode(PlaybackProcessingMode.ANDROID_DYNAMICS)

        val status = statuses.last()
        assertTrue(status.active)
        assertEquals("FakeDynamics", status.backend)
        assertEquals(3, status.bandCount)
        assertEquals(42, status.audioSessionId)
        assertEquals(1, backend.applyCount)
        assertFalse(backend.released)
    }

    @Test
    fun leavingAndroidMode_releasesAttachedBackend() {
        val backend = FakeBackend()
        val controller = PlayerDynamicsEqController(
            statusSink = {},
            attachBackend = { backend to AttachResult.Attached(backend.name, backend.bandCenters().size) },
        )

        controller.onAudioSessionIdChanged(7)
        controller.setTargetSpecs(listOf(BiquadSpec(BiquadType.PEAKING, 1000.0, 3.0, 1.0)))
        controller.setMode(PlaybackProcessingMode.ANDROID_DYNAMICS)
        controller.setMode(PlaybackProcessingMode.AURAL_TUNE)

        assertTrue(backend.released)
    }

    @Test
    fun targetlessAndroidMode_doesNotAttachBackend() {
        var attachCount = 0
        val statuses = ArrayList<PlayerDynamicsEqStatus>()
        val controller = PlayerDynamicsEqController(
            statusSink = statuses::add,
            attachBackend = {
                attachCount++
                FakeBackend() to AttachResult.Attached("FakeDynamics", 3)
            },
        )

        controller.onAudioSessionIdChanged(9)
        controller.setMode(PlaybackProcessingMode.ANDROID_DYNAMICS)

        assertEquals(0, attachCount)
        assertFalse(statuses.last().active)
    }

    private class FakeBackend : OsEffectBackend {
        override val name: String = "FakeDynamics"
        var applyCount = 0
        var released = false

        override fun bandCenters(): DoubleArray = doubleArrayOf(250.0, 1000.0, 4000.0)

        override fun apply(bands: List<AutoEqApprox.BandGain>): Boolean {
            applyCount++
            return bands.size == 3
        }

        override fun release() {
            released = true
        }
    }
}
