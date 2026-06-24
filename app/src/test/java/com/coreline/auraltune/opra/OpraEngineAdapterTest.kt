package com.coreline.auraltune.opra

import com.coreline.audio.EqFilterType
import com.coreline.auraltune.opra.model.OpraEqProfile
import com.coreline.auraltune.opra.model.OpraFilter
import com.coreline.auraltune.opra.model.OpraFilterType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class OpraEngineAdapterTest {

    private fun band(type: OpraFilterType, f: Double, g: Double, q: Double) =
        OpraFilter(type, f, g, q)

    @Test
    fun supportedProfile_convertsToEngineModel() {
        val opra = OpraEqProfile(
            id = "pud:vogue::ora",
            productId = "pud::vogue",
            profileName = "oratory1990",
            author = "oratory1990",
            details = "Harman",
            link = "https://x",
            preampDb = -7f,
            filters = listOf(
                band(OpraFilterType.LOW_SHELF, 105.0, -3.5, 0.7),
                band(OpraFilterType.PEAKING, 1000.0, 3.0, 1.0),
                band(OpraFilterType.HIGH_SHELF, 7000.0, 7.0, 0.6),
            ),
        )
        val auto = opra.toAutoEqProfile()
        assertNotNull(auto)
        assertEquals("pud:vogue::ora", auto!!.id)
        assertEquals("oratory1990", auto.measuredBy)
        assertEquals(-7f, auto.preampDB, 0.001f)
        assertEquals(3, auto.filters.size)
        assertEquals(EqFilterType.LOW_SHELF, auto.filters[0].type)
        assertEquals(EqFilterType.PEAKING, auto.filters[1].type)
        assertEquals(EqFilterType.HIGH_SHELF, auto.filters[2].type)
        assertEquals(1000.0, auto.filters[1].frequency, 0.001)
        assertEquals(3.0f, auto.filters[1].gainDB, 0.001f)
    }

    @Test
    fun unsupportedFilterType_returnsNull() {
        val opra = OpraEqProfile(
            id = "x", productId = "p", profileName = "n",
            filters = listOf(
                band(OpraFilterType.PEAKING, 1000.0, 2.0, 1.0),
                band(OpraFilterType.LOW_PASS, 12000.0, 0.0, 0.7),
            ),
        )
        assertNull(opra.toAutoEqProfile())
    }

    @Test
    fun tooManyBands_returnsNull() {
        val opra = OpraEqProfile(
            id = "x", productId = "p", profileName = "n",
            filters = (1..11).map { band(OpraFilterType.PEAKING, 100.0 * it, 1.0, 1.0) },
        )
        assertNull(opra.toAutoEqProfile())
    }

    @Test
    fun emptyProfile_returnsNull() {
        assertNull(OpraEqProfile(id = "x", productId = "p", profileName = "n").toAutoEqProfile())
    }
}
