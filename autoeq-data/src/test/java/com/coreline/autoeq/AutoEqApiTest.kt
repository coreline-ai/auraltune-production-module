package com.coreline.autoeq

import com.coreline.autoeq.cache.ImportedProfileStore
import com.coreline.autoeq.model.AutoEqCatalogEntry
import com.coreline.autoeq.model.CatalogState
import com.coreline.autoeq.repository.AutoEqRepository
import com.coreline.autoeq.search.AutoEqSearchEngine
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [AutoEqApi.observe]'s catalog/imports merge.
 *
 * Regression: when [AutoEqRepository.loadCatalog] emitted a non-Loaded state
 * (e.g. `Error("offline")` on cold start with no network) the previous
 * implementation passed the state through unchanged, so user-imported profiles
 * were dropped on the floor and the search index never saw them. The fix
 * promotes imports to a `Loaded(imports)` state whenever the catalog is not
 * itself Loaded but imports exist.
 */
class AutoEqApiTest {

    private fun importedEntry(id: String, name: String): AutoEqCatalogEntry =
        AutoEqCatalogEntry(
            id = id,
            name = name,
            measuredBy = "Imported",
            relativePath = "Imported/$name",
        )

    private fun catalogEntry(id: String, name: String): AutoEqCatalogEntry =
        AutoEqCatalogEntry(
            id = id,
            name = name,
            measuredBy = "oratory1990",
            relativePath = "oratory1990/$name",
        )

    private fun stubRepository(
        catalogStates: List<CatalogState>,
        imports: List<AutoEqCatalogEntry>,
    ): AutoEqRepository {
        val repo = mockk<AutoEqRepository>(relaxed = true)
        val importsStore = mockk<ImportedProfileStore>(relaxed = true)
        val importsFlow = MutableStateFlow(imports)
        every { importsStore.entries } returns importsFlow
        every { repo.imports } returns importsStore
        every { repo.loadCatalog(any<CoroutineScope>()) } returns flowOf(*catalogStates.toTypedArray())
        return repo
    }

    @Test
    fun `imports are surfaced as Loaded when catalog is Error`() = runTest {
        val imports = listOf(
            importedEntry("imp-1", "My Custom HD600"),
            importedEntry("imp-2", "Studio Tuning"),
        )
        val repo = stubRepository(
            catalogStates = listOf(CatalogState.Error("offline")),
            imports = imports,
        )
        val engine = AutoEqSearchEngine()
        val api = AutoEqApi(repository = repo, searchEngine = engine)

        val observed = api.observe(TestScope()).first()
        assertTrue(
            "expected Loaded(imports) but got $observed",
            observed is CatalogState.Loaded,
        )
        observed as CatalogState.Loaded
        assertEquals(imports, observed.entries)

        // Side-effect: the search engine index should now contain the imports.
        val r = engine.search("custom")
        assertEquals(1, r.entries.size)
        assertEquals("imp-1", r.entries[0].id)
    }

    @Test
    fun `imports are surfaced as Loaded when catalog is Loading`() = runTest {
        val imports = listOf(importedEntry("imp-1", "My Tuning"))
        val repo = stubRepository(
            catalogStates = listOf(CatalogState.Loading),
            imports = imports,
        )
        val api = AutoEqApi(repository = repo)

        val observed = api.observe(TestScope()).first()
        assertTrue(observed is CatalogState.Loaded)
        observed as CatalogState.Loaded
        assertEquals(imports, observed.entries)
    }

    @Test
    fun `non-Loaded catalog with empty imports passes state through unchanged`() = runTest {
        val errorState = CatalogState.Error("offline")
        val repo = stubRepository(
            catalogStates = listOf(errorState),
            imports = emptyList(),
        )
        val api = AutoEqApi(repository = repo)

        val observed = api.observe(TestScope()).first()
        // No imports + non-Loaded catalog → original state must be preserved verbatim.
        assertSame(errorState, observed)
    }

    @Test
    fun `Loaded catalog merges imports on top`() = runTest {
        val imports = listOf(importedEntry("imp-1", "User Tuning"))
        val catalog = listOf(
            catalogEntry("cat-1", "Sennheiser HD 600"),
            catalogEntry("cat-2", "Sony WH-1000XM4"),
        )
        val repo = stubRepository(
            catalogStates = listOf(CatalogState.Loaded(catalog)),
            imports = imports,
        )
        val api = AutoEqApi(repository = repo)

        val observed = api.observe(TestScope()).first()
        assertTrue(observed is CatalogState.Loaded)
        observed as CatalogState.Loaded
        // Imports are sorted to the top (concatenated first by the API).
        assertEquals(imports + catalog, observed.entries)
    }
}
