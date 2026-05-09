// AuralTuneApplication.kt
// Application subclass; owns the manual-DI ServiceLocator that holds long-lived singletons
// (catalog repository, AutoEqApi, settings store) for the process lifetime.
package com.coreline.auraltune

import android.app.Application
import com.coreline.auraltune.di.ServiceLocator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Process-wide application class. The [ServiceLocator] is created eagerly in [onCreate]
 * so that the first activity launch finds catalog/api/settings already wired.
 *
 * P1-4: also kicks off [com.coreline.autoeq.AutoEqApi.primeImports] on a background
 * coroutine so user-imported profiles hydrate from disk before the catalog flow first
 * emits Loaded. Without this, the imported set would be empty until the user re-imports
 * after each cold start.
 */
class AuralTuneApplication : Application() {

    lateinit var serviceLocator: ServiceLocator
        private set

    /** Application-scoped coroutine scope for fire-and-forget startup work. */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        serviceLocator = ServiceLocator(this)

        appScope.launch {
            // Hydrate user-imported AutoEQ profiles from disk so they appear in
            // search results immediately. Failure is non-fatal — imports just won't
            // surface until the next launch.
            runCatching { serviceLocator.autoEqApi.primeImports() }
        }
    }
}
