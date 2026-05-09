package com.coreline.autoeq.cache

import com.coreline.autoeq.model.AutoEqSource
import com.coreline.autoeq.model.ParseResult
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Unit tests for [ImportedProfileStore] (R1-3).
 *
 * Covers atomic write under crash, orphan cleanup on reload, the round-trip,
 * delete, concurrent same-content imports (content-addressed dedupe), and
 * cold-start reload across store instances.
 */
class ImportedProfileStoreTest {

    private lateinit var rootDir: File
    private lateinit var importedDir: File
    private lateinit var store: ImportedProfileStore

    @Before
    fun setUp() {
        rootDir = Files.createTempDirectory("imported-store-test").toFile()
        importedDir = File(rootDir, "imported")
        store = ImportedProfileStore(rootDir)
    }

    @After
    fun tearDown() {
        rootDir.deleteRecursively()
    }

    // ---- helpers ------------------------------------------------------------

    private val sampleText = """
        Preamp: -3.0 dB
        Filter 1: ON PK Fc 100 Hz Gain 1.0 dB Q 1.0
        Filter 2: ON PK Fc 1000 Hz Gain 2.0 dB Q 1.0
    """.trimIndent()

    // ---- 1. round-trip ------------------------------------------------------

    @Test
    fun `import then load returns parsed profile and entries flow updates`() = runTest {
        val name = "Sennheiser HD 600"
        val result = store.importFromText(name, sampleText)

        assertTrue("Expected Success but got $result", result is ParseResult.Success)
        val id = (result as ParseResult.Success).profile.id
        assertTrue("Imported id must use prefix", id.startsWith(ImportedProfileStore.IMPORTED_PREFIX))

        val flowEntries = store.entries.value
        assertEquals(1, flowEntries.size)
        assertEquals(name, flowEntries[0].name)
        assertEquals(id, flowEntries[0].id)

        val loaded = store.load(id)
        assertNotNull("load() must return a profile after import", loaded)
        assertEquals(name, loaded!!.name)
        assertEquals(AutoEqSource.IMPORTED, loaded.source)
        assertEquals(2, loaded.filters.size)
    }

    // ---- 2. atomic write under crash ----------------------------------------

    @Test
    fun `reloadFromDisk sweeps stray tmp files`() = runTest {
        importedDir.mkdirs()
        val txtTmp = File(importedDir, "imp-deadbeef0001.txt.tmp")
        val metaTmp = File(importedDir, "imp-deadbeef0002.meta.json.tmp")
        txtTmp.writeText("partial-garbage-from-kill")
        metaTmp.writeText("{partially-written-json")

        assertTrue(txtTmp.exists())
        assertTrue(metaTmp.exists())

        store.reloadFromDisk()

        assertFalse(".tmp txt must be swept", txtTmp.exists())
        assertFalse(".tmp meta must be swept", metaTmp.exists())
        assertTrue(store.entries.value.isEmpty())
    }

    // ---- 3. orphan cleanup — txt only ---------------------------------------

    @Test
    fun `reloadFromDisk deletes orphan txt with no meta`() = runTest {
        importedDir.mkdirs()
        val orphanTxt = File(importedDir, "imp-orphan000001.txt")
        orphanTxt.writeText(sampleText)
        assertTrue(orphanTxt.exists())

        store.reloadFromDisk()

        assertFalse("orphan txt must be removed", orphanTxt.exists())
        assertTrue(store.entries.value.isEmpty())
    }

    // ---- 4. orphan cleanup — meta only --------------------------------------

    @Test
    fun `reloadFromDisk deletes orphan meta with no txt`() = runTest {
        importedDir.mkdirs()
        val orphanMeta = File(importedDir, "imp-orphan000002.meta.json")
        orphanMeta.writeText(
            """{"id":"imp-orphan000002","name":"Ghost","importedAtMs":1}""",
        )
        assertTrue(orphanMeta.exists())

        store.reloadFromDisk()

        assertFalse("orphan meta must be removed", orphanMeta.exists())
        assertTrue(store.entries.value.isEmpty())
    }

    // ---- 5. delete ----------------------------------------------------------

    @Test
    fun `delete removes both files and the entry`() = runTest {
        val result = store.importFromText("Profile A", sampleText)
        val id = (result as ParseResult.Success).profile.id

        val txt = File(importedDir, "$id.txt")
        val meta = File(importedDir, "$id.meta.json")
        assertTrue(txt.exists())
        assertTrue(meta.exists())
        assertEquals(1, store.entries.value.size)

        store.delete(id)

        assertFalse(".txt must be deleted", txt.exists())
        assertFalse(".meta.json must be deleted", meta.exists())
        assertTrue("entries flow must clear after delete", store.entries.value.isEmpty())
    }

    // ---- 6. concurrent identical imports ------------------------------------

    @Test
    fun `concurrent imports with same content collapse to one entry`() = runTest {
        val name = "Same Name"
        val jobs = (0 until 4).map {
            async { store.importFromText(name, sampleText) }
        }
        val results = jobs.awaitAll()
        for (r in results) {
            assertTrue("each call should succeed: $r", r is ParseResult.Success)
        }
        // All four must produce the same content-addressed id.
        val ids = results.map { (it as ParseResult.Success).profile.id }.toSet()
        assertEquals("All concurrent imports must yield same id (content-addressed)", 1, ids.size)

        // Exactly one txt + one meta on disk.
        val files = importedDir.listFiles()?.toList().orEmpty()
        val txts = files.filter { it.name.endsWith(".txt") }
        val metas = files.filter { it.name.endsWith(".meta.json") }
        assertEquals("Only one txt file expected, got $files", 1, txts.size)
        assertEquals("Only one meta file expected, got $files", 1, metas.size)

        assertEquals(1, store.entries.value.size)
    }

    // ---- 7. cold start reload -----------------------------------------------

    @Test
    fun `cold start reload materializes all entries`() = runTest {
        store.importFromText("Alpha", sampleText)
        store.importFromText("Bravo", sampleText + "\nFilter 3: ON PK Fc 2000 Hz Gain 1.0 dB Q 1.0")
        store.importFromText("Charlie", sampleText + "\nFilter 3: ON PK Fc 3000 Hz Gain 1.0 dB Q 1.0")

        // Fresh instance against the same root dir.
        val freshStore = ImportedProfileStore(rootDir)
        assertTrue(
            "Fresh store should start with empty in-memory list",
            freshStore.entries.value.isEmpty(),
        )

        freshStore.reloadFromDisk()

        val names = freshStore.entries.value.map { it.name }.toSet()
        assertEquals(3, freshStore.entries.value.size)
        assertEquals(setOf("Alpha", "Bravo", "Charlie"), names)
    }
}
