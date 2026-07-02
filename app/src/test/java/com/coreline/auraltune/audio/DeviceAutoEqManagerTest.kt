package com.coreline.auraltune.audio

import com.coreline.audio.EqFilterType
import com.coreline.autoeq.AutoEqApi
import com.coreline.autoeq.model.AutoEqCatalogEntry
import com.coreline.autoeq.model.AutoEqFilter
import com.coreline.autoeq.model.AutoEqProfile
import com.coreline.autoeq.model.AutoEqSource
import com.coreline.auraltune.data.SettingsStore
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class DeviceAutoEqManagerTest {

    @Test
    fun nativeAutoEqRejection_returnsFalse_clearsEngine_andDoesNotPersistSelection() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val settings = SettingsStore(context)
        settings.setSelectedProfileId(null)

        val entry = AutoEqCatalogEntry(
            id = "hd600",
            name = "Sennheiser HD 600",
            measuredBy = "oratory1990",
            relativePath = "oratory1990/over-ear/Sennheiser HD 600",
        )
        val profile = testProfile(entry.id)
        val api = mockk<AutoEqApi>(relaxed = true)
        coEvery { api.resolve(entry) } returns profile

        val sink = FakeEngineSink(updateResult = -2)
        val manager = DeviceAutoEqManager(
            context = context,
            engine = sink,
            api = api,
            settings = settings,
            coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        )

        assertFalse(manager.applyResolvedProfile(profile))
        assertEquals(1, sink.updateCalls)
        assertEquals(1, sink.clearCalls)

        assertFalse(manager.selectProfileForCurrentDevice(entry))
        assertEquals(2, sink.updateCalls)
        assertEquals(2, sink.clearCalls)
        assertNull(settings.selectedProfileId.first())
    }

    private fun testProfile(id: String) = AutoEqProfile(
        id = id,
        name = "Sennheiser HD 600",
        source = AutoEqSource.FETCHED,
        measuredBy = "oratory1990",
        preampDB = -6f,
        filters = listOf(
            AutoEqFilter(
                type = EqFilterType.PEAKING,
                frequency = 1000.0,
                gainDB = 2f,
                q = 1.0,
            ),
        ),
    )

    private class FakeEngineSink(
        private val updateResult: Int,
    ) : AutoEqEngineSink {
        var updateCalls = 0
            private set
        var clearCalls = 0
            private set

        override fun updateAutoEq(
            preampDB: Float,
            enableLimiter: Boolean,
            profileOptimizedRate: Double,
            filterTypes: IntArray,
            frequencies: FloatArray,
            gainsDB: FloatArray,
            qFactors: FloatArray,
        ): Int {
            updateCalls += 1
            return updateResult
        }

        override fun clearAutoEq() {
            clearCalls += 1
        }
    }
}
