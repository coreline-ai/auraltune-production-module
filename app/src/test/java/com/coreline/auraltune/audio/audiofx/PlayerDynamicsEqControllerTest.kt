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
            scheduleRampStep = immediateScheduler,
        )

        controller.onAudioSessionIdChanged(42)
        controller.setTargetSpecs(
            listOf(BiquadSpec(BiquadType.PEAKING, 1000.0, 6.0, 1.0)),
            headroomDb = -6.5f,
        )
        controller.setMode(PlaybackProcessingMode.ANDROID_DYNAMICS)

        val status = statuses.last()
        assertTrue(status.active)
        assertEquals("FakeDynamics", status.backend)
        assertEquals(3, status.bandCount)
        assertEquals(42, status.audioSessionId)
        assertEquals(-6.5f, status.headroomDb, 0.0001f)
        assertTrue(backend.applyCount > 1)
        assertEquals(-6.5f, backend.lastInputGainDb, 0.0001f)
        assertFalse(backend.released)
    }

    @Test
    fun leavingAndroidMode_releasesAttachedBackend() {
        val backend = FakeBackend()
        val controller = PlayerDynamicsEqController(
            statusSink = {},
            attachBackend = { backend to AttachResult.Attached(backend.name, backend.bandCenters().size) },
            scheduleRampStep = immediateScheduler,
        )

        controller.onAudioSessionIdChanged(7)
        controller.setTargetSpecs(listOf(BiquadSpec(BiquadType.PEAKING, 1000.0, 3.0, 1.0)))
        controller.setMode(PlaybackProcessingMode.ANDROID_DYNAMICS)
        controller.setMode(PlaybackProcessingMode.AURAL_TUNE)

        assertTrue(backend.released)
        assertEquals(0f, backend.history.last().inputGainDb, 0.0001f)
        assertTrue(backend.history.last().bands.all { it.gainDb == 0.0 })
    }

    @Test
    fun positiveHeadroom_isNotAppliedAsBoost() {
        val backend = FakeBackend()
        val statuses = ArrayList<PlayerDynamicsEqStatus>()
        val controller = PlayerDynamicsEqController(
            statusSink = statuses::add,
            attachBackend = { backend to AttachResult.Attached(backend.name, backend.bandCenters().size) },
            scheduleRampStep = immediateScheduler,
        )

        controller.onAudioSessionIdChanged(11)
        controller.setTargetSpecs(
            listOf(BiquadSpec(BiquadType.PEAKING, 1000.0, 3.0, 1.0)),
            headroomDb = 4f,
        )
        controller.setMode(PlaybackProcessingMode.ANDROID_DYNAMICS)

        assertTrue(statuses.last().active)
        assertEquals(0f, statuses.last().headroomDb, 0.0001f)
        assertEquals(0f, backend.lastInputGainDb, 0.0001f)
    }

    @Test
    fun nonFiniteAndTooLowHeadroom_areSanitized() {
        val backend = FakeBackend()
        val statuses = ArrayList<PlayerDynamicsEqStatus>()
        val controller = PlayerDynamicsEqController(
            statusSink = statuses::add,
            attachBackend = { backend to AttachResult.Attached(backend.name, backend.bandCenters().size) },
            scheduleRampStep = immediateScheduler,
        )
        val specs = listOf(BiquadSpec(BiquadType.PEAKING, 1000.0, 3.0, 1.0))

        controller.onAudioSessionIdChanged(12)
        controller.setMode(PlaybackProcessingMode.ANDROID_DYNAMICS)
        controller.setTargetSpecs(specs, headroomDb = Float.NaN)

        assertTrue(statuses.last().active)
        assertEquals(0f, statuses.last().headroomDb, 0.0001f)
        assertEquals(0f, backend.lastInputGainDb, 0.0001f)

        controller.setTargetSpecs(specs, headroomDb = -42f)

        assertEquals(-30f, statuses.last().headroomDb, 0.0001f)
        assertEquals(-30f, backend.lastInputGainDb, 0.0001f)
    }

    @Test
    fun targetlessAndroidMode_doesNotAttachBackend() {
        var attachCount = 0
        val statuses = ArrayList<PlayerDynamicsEqStatus>()
        val backend = FakeBackend()
        val controller = PlayerDynamicsEqController(
            statusSink = statuses::add,
            attachBackend = {
                attachCount++
                backend to AttachResult.Attached("FakeDynamics", 3)
            },
            scheduleRampStep = immediateScheduler,
        )

        controller.onAudioSessionIdChanged(9)
        controller.setMode(PlaybackProcessingMode.ANDROID_DYNAMICS)

        assertEquals(1, attachCount)
        assertEquals(1, backend.applyCount)
        assertEquals(0f, backend.lastInputGainDb, 0.0001f)
        assertFalse(backend.released)
        assertFalse(statuses.last().active)
    }

    @Test
    fun headroomChanges_areAppliedInRampSteps() {
        val backend = FakeBackend()
        val controller = PlayerDynamicsEqController(
            statusSink = {},
            attachBackend = { backend to AttachResult.Attached(backend.name, backend.bandCenters().size) },
            scheduleRampStep = immediateScheduler,
        )

        controller.onAudioSessionIdChanged(21)
        controller.setMode(PlaybackProcessingMode.ANDROID_DYNAMICS)
        controller.setTargetSpecs(
            listOf(BiquadSpec(BiquadType.PEAKING, 1000.0, 3.0, 1.0)),
            headroomDb = -6f,
        )

        val nonNeutralSteps = backend.history.drop(1)
        assertTrue(nonNeutralSteps.size > 1)
        assertEquals(-6f, backend.lastInputGainDb, 0.0001f)
        assertTrue(nonNeutralSteps.zipWithNext().all { (a, b) -> b.inputGainDb <= a.inputGainDb })
    }

    private class FakeBackend : OsEffectBackend {
        override val name: String = "FakeDynamics"
        var applyCount = 0
        var lastInputGainDb = 0f
        var released = false
        val history = ArrayList<ApplyCall>()

        override fun bandCenters(): DoubleArray = doubleArrayOf(250.0, 1000.0, 4000.0)

        override fun apply(bands: List<AutoEqApprox.BandGain>, inputGainDb: Float): Boolean {
            applyCount++
            lastInputGainDb = inputGainDb
            history += ApplyCall(bands = bands, inputGainDb = inputGainDb)
            return bands.size == 3
        }

        override fun release() {
            released = true
        }
    }

    private data class ApplyCall(
        val bands: List<AutoEqApprox.BandGain>,
        val inputGainDb: Float,
    )

    private companion object {
        val immediateScheduler: (Long, () -> Unit) -> Unit = { _, step -> step() }
    }
}
