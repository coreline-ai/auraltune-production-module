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
 * Built-in tone presets must be valid defaults: exactly 3 gains, finite and within the absolute
 * ceiling, unique built-in-prefixed ids. User-style ids are NOT treated as built-in.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class ToneEqPresetCatalogTest {

    @Test
    fun builtInTonePresetsAreSafeDefaults() {
        val presets = ToneEqPresetCatalog.builtIns(RuntimeEnvironment.getApplication())
        assertEquals(ToneEqPresetCatalog.count, presets.size)
        assertTrue("at least 3 built-in tone presets", presets.size >= 3)

        val max = GraphicEqBands.MAX_GAIN_LIMIT_DB
        val ids = HashSet<String>()
        presets.forEach { p ->
            assertTrue("built-in id prefix: ${p.id}", ToneEqPresetCatalog.isBuiltInId(p.id))
            assertTrue("unique id: ${p.id}", ids.add(p.id))
            assertTrue("name non-blank", p.name.isNotBlank())
            assertEquals(SettingsStore.TONE_BANDS, p.gainsDb.size)
            p.gainsDb.forEach { g ->
                assertTrue("finite gain", g.isFinite())
                assertTrue("gain within ceiling: $g", g in -max..max)
            }
        }
        assertTrue(!ToneEqPresetCatalog.isBuiltInId("uuid-user-tone-preset"))
    }
}
