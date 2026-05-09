// SmokeTest.kt
// Minimal instrumentation smoke test. Verifies that MainActivity launches and the app_name
// string resource is reachable from a running app context, without touching the Compose
// hierarchy directly (no Compose test runtime in MVP deps).
package com.coreline.auraltune

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SmokeTest {

    @Test
    fun appName_isAuralTune() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("AuralTune", ctx.getString(R.string.app_name))
    }

    @Test
    fun mainActivity_launches() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            // If the activity reaches RESUMED without throwing, we consider the smoke test green.
            scenario.onActivity { activity ->
                require(!activity.isFinishing) { "Activity finished during launch" }
            }
        }
    }
}
