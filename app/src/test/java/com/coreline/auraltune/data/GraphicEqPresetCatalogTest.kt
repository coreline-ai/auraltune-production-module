package com.coreline.auraltune.data

import com.coreline.auraltune.audio.eq.GraphicEqBands
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Built-in graphic EQ presets must be valid defaults in BOTH debug and release: correct band
 * count, gains finite and within the absolute ceiling, unique built-in-prefixed ids.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class GraphicEqPresetCatalogTest {

    @Test
    fun builtInGraphicPresetsAreSafeDefaults() {
        val presets = GraphicEqPresetCatalog.builtIns(RuntimeEnvironment.getApplication())
        assertEquals(GraphicEqPresetCatalog.count, presets.size)
        assertTrue("at least 3 built-in graphic presets", presets.size >= 3)

        val max = GraphicEqBands.MAX_GAIN_LIMIT_DB
        val ids = HashSet<String>()
        presets.forEach { p ->
            assertTrue("built-in id prefix: ${p.id}", GraphicEqPresetCatalog.isBuiltInId(p.id))
            assertTrue("unique id: ${p.id}", ids.add(p.id))
            assertTrue("name non-blank", p.name.isNotBlank())
            assertEquals(GraphicEqBands.COUNT, p.gainsDb.size)
            p.gainsDb.forEach { g ->
                assertTrue("finite gain", g.isFinite())
                assertTrue("gain within ceiling: $g", g in -max..max)
            }
        }

        // A user-style id is NOT treated as built-in (delete-guard correctness).
        assertTrue(!GraphicEqPresetCatalog.isBuiltInId("3f7c-uuid-user-preset"))
    }
}
