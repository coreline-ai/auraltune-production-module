package com.coreline.autoeq.cache

import com.coreline.autoeq.model.AutoEqCatalogEntry
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Unit tests for [CatalogCache] covering R1-1 (mutex serialisation + atomic save) and
 * R2-3 (rename of `deleteCorrupted` → `clear`).
 *
 * We use `Files.createTempDirectory` rather than JUnit 5's `@TempDir` because this module
 * is on JUnit 4 (Robolectric / mockk wiring in `build.gradle.kts`).
 */
class CatalogCacheTest {

    private lateinit var rootDir: File
    private lateinit var cache: CatalogCache

    @Before
    fun setUp() {
        rootDir = Files.createTempDirectory("catalog-cache-test").toFile()
        cache = CatalogCache(rootDir)
    }

    @After
    fun tearDown() {
        rootDir.deleteRecursively()
    }

    private fun entry(i: Int) = AutoEqCatalogEntry(
        id = "id-$i",
        name = "Headphone $i",
        measuredBy = "oratory1990",
        relativePath = "results/oratory1990/over-ear/$i.txt",
    )

    private fun entries(count: Int): List<AutoEqCatalogEntry> = (0 until count).map(::entry)

    // ---------- Round trip ----------

    @Test
    fun `save then load returns same entries`() = runTest {
        val saved = entries(5)
        cache.saveCatalog(saved)
        val loaded = cache.loadCatalog()
        assertNotNull(loaded)
        assertEquals(saved, loaded)
    }

    // ---------- Missing file ----------

    @Test
    fun `load returns null when no catalog file exists`() = runTest {
        assertFalse(cache.catalogFile().exists())
        assertNull(cache.loadCatalog())
    }

    // ---------- Corrupt file ----------

    @Test
    fun `load returns null when catalog file is corrupt`() = runTest {
        rootDir.mkdirs()
        cache.catalogFile().writeBytes(byteArrayOf(0x7B, 0x21, 0x40, 0x23)) // "{!@#" — invalid JSON
        val loaded = cache.loadCatalog()
        assertNull(loaded)
        // Side effect: corrupt file is removed so next refresh starts clean.
        assertFalse(
            "loadCatalog should delete corrupt files",
            cache.catalogFile().exists(),
        )
    }

    // ---------- clear() ----------

    @Test
    fun `clear removes catalog file and load returns null`() = runTest {
        cache.saveCatalog(entries(3))
        assertTrue(cache.catalogFile().exists())

        cache.clear()

        assertFalse(cache.catalogFile().exists())
        assertNull(cache.loadCatalog())
    }

    @Test
    fun `clear is idempotent when no file exists`() = runTest {
        assertFalse(cache.catalogFile().exists())
        cache.clear() // must not throw
        assertFalse(cache.catalogFile().exists())
    }

    @Test
    fun `clear also removes a leftover tmp file`() = runTest {
        rootDir.mkdirs()
        val tmp = File(rootDir, "catalog.json.tmp")
        tmp.writeText("partial garbage")
        assertTrue(tmp.exists())

        cache.clear()

        assertFalse("clear should sweep stale tmp files", tmp.exists())
    }

    // ---------- Concurrency ----------

    @Test
    fun `concurrent saves leave a complete catalog`() = runTest {
        // Eight writers, each with a distinct list size so we can recognise which winner
        // landed on disk and confirm the file is not a partial mash-up of two writes.
        val sizes = listOf(1, 2, 3, 5, 8, 13, 21, 34)
        val writes = sizes.map { size -> entries(size) }

        val jobs = writes.map { batch ->
            async { cache.saveCatalog(batch) }
        }
        jobs.awaitAll()

        val loaded = cache.loadCatalog()
        assertNotNull("file must be readable after concurrent saves", loaded)
        // The final on-disk content must equal one of the eight input batches in full —
        // never a truncated or interleaved variant.
        assertTrue(
            "loaded catalog must equal exactly one of the writes (got size=${loaded!!.size})",
            writes.any { it == loaded },
        )

        // And no stale tmp file left behind.
        val tmp = File(rootDir, "catalog.json.tmp")
        assertFalse("tmp must be cleaned up after every save", tmp.exists())
    }

    // ---------- Atomic save ----------

    @Test
    fun `saveCatalog leaves catalog intact when a stale tmp exists`() = runTest {
        // Simulate a crash that left `catalog.json.tmp` on disk without the rename.
        rootDir.mkdirs()
        val tmp = File(rootDir, "catalog.json.tmp")
        tmp.writeText("crash residue — not valid json")

        val saved = entries(4)
        cache.saveCatalog(saved)

        val loaded = cache.loadCatalog()
        assertEquals(saved, loaded)
        assertFalse("post-save tmp must be cleaned up", tmp.exists())
    }

    @Test
    fun `saveCatalog uses atomic rename (final file matches written bytes)`() = runTest {
        val saved = entries(7)
        cache.saveCatalog(saved)

        val target = cache.catalogFile()
        assertTrue(target.exists())
        // After a successful save we must see the real file but no orphan tmp.
        val tmp = File(rootDir, "catalog.json.tmp")
        assertFalse(tmp.exists())

        // Sanity: file content round-trips.
        val loaded = cache.loadCatalog()
        assertEquals(saved, loaded)
    }
}
