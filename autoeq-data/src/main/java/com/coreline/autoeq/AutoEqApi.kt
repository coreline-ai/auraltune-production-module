package com.coreline.autoeq

import com.coreline.autoeq.model.AutoEqCatalogEntry
import com.coreline.autoeq.model.AutoEqProfile
import com.coreline.autoeq.model.CatalogState
import com.coreline.autoeq.model.ParseResult
import com.coreline.autoeq.repository.AutoEqRepository
import com.coreline.autoeq.search.AutoEqSearchEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onEach

/**
 * Convenience facade that wires the repository + search engine together.
 *
 * Typical usage from a ViewModel:
 * ```
 * val api = AutoEqApi(AutoEqRepository(context))
 * api.observe(viewModelScope).collect { state -> ... }
 * val results = api.search("hd 600")
 * val profile = api.resolve(results.entries.first())
 * ```
 */
class AutoEqApi(
    val repository: AutoEqRepository,
    val searchEngine: AutoEqSearchEngine = AutoEqSearchEngine(),
) {

    /**
     * Observe catalog state and keep the bundled [searchEngine] index in sync.
     *
     * The flow merges fetched (network) entries with user-imported entries
     * before publishing into the search engine, so a fuzzy search hits both
     * pools transparently. Imported entries are sorted to the top of the
     * search index so user-trusted profiles surface before noisy fixture
     * matches.
     *
     * The flow re-emits [CatalogState] from the underlying
     * [AutoEqRepository.loadCatalog]. When imports change (after [importFromUri]
     * or [deleteImported]), we re-emit the previous Loaded state so subscribers
     * see the new merged list.
     */
    fun observe(scope: CoroutineScope): Flow<CatalogState> {
        // Side-channel: combine catalog state with the imports stream so any
        // mutation to imports triggers a fresh emission.
        return combine(
            repository.loadCatalog(scope),
            repository.imports.entries,
        ) { state, imported ->
            when {
                // Catalog is loaded — merge imports on top so user-trusted entries surface first.
                state is CatalogState.Loaded -> CatalogState.Loaded(imported + state.entries)
                // Catalog is Loading/Error/etc but the user has imports — promote them to Loaded
                // so the search index is populated and imports remain findable offline / cold start.
                imported.isNotEmpty() -> CatalogState.Loaded(imported)
                // No catalog and no imports — pass the underlying state through unchanged.
                else -> state
            }
        }.onEach { state ->
            if (state is CatalogState.Loaded) {
                searchEngine.setCatalog(state.entries)
            }
        }
    }

    /** Search the current catalog. Returns empty results until [observe] has emitted Loaded. */
    fun search(query: String, limit: Int = 50): AutoEqSearchEngine.Result =
        searchEngine.search(query, limit)

    /** Resolve a full profile through the repository (cache-first / imports-aware). */
    suspend fun resolve(entry: AutoEqCatalogEntry): AutoEqProfile? = repository.fetchProfile(entry)

    /** Initialize the imported store. Call once at app startup. */
    suspend fun primeImports() = repository.primeImports()

    /** SAF entry point — import a user-picked .txt URI under [displayName]. */
    suspend fun importFromUri(uri: android.net.Uri, displayName: String): ParseResult =
        repository.importFromUri(uri, displayName)

    /** Delete a user-imported profile by id. No-op for non-imported ids. */
    suspend fun deleteImported(id: String) = repository.deleteImported(id)
}
