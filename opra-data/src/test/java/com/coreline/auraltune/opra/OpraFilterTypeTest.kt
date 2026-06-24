package com.coreline.auraltune.opra

import com.coreline.audio.EqFilterType
import com.coreline.auraltune.opra.model.OpraEqProfile
import com.coreline.auraltune.opra.model.OpraFilter
import com.coreline.auraltune.opra.model.OpraFilterType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpraFilterTypeTest {

    @Test
    fun supportedTypes_mapToEngine() {
        assertEquals(EqFilterType.PEAKING, OpraFilterType.PEAKING.toEngine())
        assertEquals(EqFilterType.LOW_SHELF, OpraFilterType.LOW_SHELF.toEngine())
        assertEquals(EqFilterType.HIGH_SHELF, OpraFilterType.HIGH_SHELF.toEngine())
        assertEquals(EqFilterType.HIGH_PASS, OpraFilterType.HIGH_PASS.toEngine())
    }

    @Test
    fun unsupportedTypes_mapToNull() {
        assertNull(OpraFilterType.LOW_PASS.toEngine())
        assertNull(OpraFilterType.NOTCH.toEngine())
        assertNull(OpraFilterType.BAND_PASS.toEngine())
        assertNull(OpraFilterType.UNKNOWN.toEngine())
    }

    @Test
    fun fromToken_normalizesCommonOpraTokens() {
        assertEquals(OpraFilterType.PEAKING, OpraFilterType.fromToken("PK"))
        assertEquals(OpraFilterType.LOW_SHELF, OpraFilterType.fromToken("LSC"))
        assertEquals(OpraFilterType.HIGH_SHELF, OpraFilterType.fromToken("hsc"))
        assertEquals(OpraFilterType.HIGH_PASS, OpraFilterType.fromToken(" HPQ "))
        assertEquals(OpraFilterType.UNKNOWN, OpraFilterType.fromToken("???"))
        assertEquals(OpraFilterType.UNKNOWN, OpraFilterType.fromToken(null))
    }

    @Test
    fun profile_isSupported_onlyWhenAllBandsSupported() {
        val supported = OpraEqProfile(
            id = "p1", productId = "prod1", profileName = "x",
            filters = listOf(
                OpraFilter(OpraFilterType.PEAKING, 1000.0, 3.0, 1.0),
                OpraFilter(OpraFilterType.LOW_SHELF, 105.0, 6.0, 0.7),
            ),
        )
        assertTrue(supported.isSupported)

        val mixed = supported.copy(
            filters = supported.filters + OpraFilter(OpraFilterType.NOTCH, 8000.0, -4.0, 2.0),
        )
        assertFalse("profile with any unsupported band must be excluded", mixed.isSupported)

        val empty = supported.copy(filters = emptyList())
        assertFalse("empty profile is not applicable", empty.isSupported)
    }
}
