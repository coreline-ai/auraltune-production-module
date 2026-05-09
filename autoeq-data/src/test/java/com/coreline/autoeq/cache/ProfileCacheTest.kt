package com.coreline.autoeq.cache

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
 * Unit tests for [ProfileCache] (R1-2).
 *
 * Covers the round-trip, eviction, protected-id semantics, and the new mutex/atomic-write
 * guarantees: concurrent same-id writers must not produce a torn file, and a leftover
 * `<id>.txt.tmp` from a simulated crash must not contaminate the next successful write.
 */
class ProfileCacheTest {

    private lateinit var rootDir: File
    private lateinit var cache: ProfileCache

    @Before
    fun setUp() {
        rootDir = Files.createTempDirectory("profile-cache-test").toFile()
        cache = ProfileCache(rootDir)
    }

    @After
    fun tearDown() {
        rootDir.deleteRecursively()
    }

    // ---- basic round-trip ---------------------------------------------------

    @Test
    fun `write then read returns same text`() = runTest {
        val id = "headphone-001"
        val body = "GraphicEQ: 20 -1.5; 21 -1.4\nPreamp: -3.5 dB"

        cache.write(id, body)
        val read = cache.read(id)

        assertEquals(body, read)
    }

    @Test
    fun `read of unknown id returns null`() = runTest {
        assertNull(cache.read("never-written"))
    }

    @Test
    fun `delete removes the file and subsequent read returns null`() = runTest {
        val id = "to-delete"
        cache.write(id, "payload")
        assertNotNull(cache.read(id))

        cache.delete(id)

        assertNull(cache.read(id))
        assertFalse(File(File(rootDir, "fetched"), "$id.txt").exists())
    }

    @Test
    fun `clear removes all files and access log`() = runTest {
        cache.write("a", "aa")
        cache.write("b", "bb")
        cache.write("c", "cc")

        cache.clear()

        val fetched = File(rootDir, "fetched")
        // After clear, the directory may exist but must contain no profile files.
        val remaining = fetched.listFiles()?.toList().orEmpty()
        assertTrue(
            "Expected no remaining cache files after clear(), but got $remaining",
            remaining.isEmpty(),
        )
        assertFalse(File(rootDir, "access_log.json").exists())
        assertNull(cache.read("a"))
        assertNull(cache.read("b"))
        assertNull(cache.read("c"))
    }

    // ---- LRU eviction --------------------------------------------------------

    @Test
    fun `evictIfNeeded drops count to soft cap when over by entry count`() = runTest {
        // Tiny payloads so we hit the entry cap (200) long before the 5 MB byte cap.
        repeat(250) { i ->
            cache.write("id-${"%04d".format(i)}", "x")
        }
        // Sanity: we wrote 250 distinct files.
        val fetched = File(rootDir, "fetched")
        val before = fetched.listFiles()?.size ?: 0
        assertEquals(250, before)

        cache.evictIfNeeded(emptySet())

        val after = fetched.listFiles()?.size ?: 0
        assertTrue(
            "Expected count <= ${ProfileCache.MAX_ENTRIES} after eviction, got $after",
            after <= ProfileCache.MAX_ENTRIES,
        )
    }

    @Test
    fun `evictIfNeeded preserves protected ids even when oldest`() = runTest {
        // Write the protected id FIRST so its lastAccess is the oldest -> it would be the
        // first to evict if it weren't protected.
        val protectedId = "keepme"
        cache.write(protectedId, "protected-payload")

        repeat(250) { i ->
            cache.write("id-${"%04d".format(i)}", "x")
        }

        cache.evictIfNeeded(setOf(protectedId))

        val survivor = cache.read(protectedId)
        assertEquals("protected-payload", survivor)
    }

    // ---- concurrency / atomicity --------------------------------------------

    @Test
    fun `concurrent same-id writes never produce a torn file`() = runTest {
        val id = "race-target"
        val values = (0 until 8).map { "value-$it".repeat(64) } // ~512 bytes each

        val jobs = values.map { v ->
            async { cache.write(id, v) }
        }
        jobs.awaitAll()

        val read = cache.read(id)
        assertNotNull("Expected a value to be present after concurrent writes", read)
        assertTrue(
            "Read body must be one of the eight written values, got: $read",
            read in values,
        )
    }

    @Test
    fun `write recovers cleanly from a leftover tmp file`() = runTest {
        val id = "crash-recover"
        val fetched = File(rootDir, "fetched").apply { mkdirs() }
        val tmp = File(fetched, "$id.txt.tmp")
        // Simulate a crash mid-write: a stale tmp from a previous run.
        tmp.writeText("GARBAGE-FROM-CRASHED-WRITE", Charsets.UTF_8)
        assertTrue(tmp.exists())

        val real = "the-real-payload"
        cache.write(id, real)

        // Final file is the real value.
        assertEquals(real, cache.read(id))
        // Tmp must not still hold the garbage; either deleted, or overwritten with the
        // real value (acceptable on platforms where rename failed and we copy+delete).
        if (tmp.exists()) {
            assertEquals(
                "Stale tmp file must not retain garbage after a successful write",
                real,
                tmp.readText(Charsets.UTF_8),
            )
        }
    }
}
