package com.coreline.autoeq.search

import com.coreline.autoeq.model.AutoEqCatalogEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoEqSearchEngineTest {

    private fun entry(id: String, name: String, by: String = "oratory1990"): AutoEqCatalogEntry =
        AutoEqCatalogEntry(id = id, name = name, measuredBy = by, relativePath = "$by/$name")

    @Test
    fun `empty query returns empty result`() {
        val engine = AutoEqSearchEngine().apply {
            setCatalog(listOf(entry("1", "Sennheiser HD 600")))
        }
        val r = engine.search("")
        assertEquals(0, r.entries.size)
        assertEquals(0, r.totalCount)
    }

    @Test
    fun `exact match scores higher than substring`() {
        val engine = AutoEqSearchEngine().apply {
            setCatalog(
                listOf(
                    entry("1", "HD 600"),
                    entry("2", "Sennheiser HD 600"),
                ),
            )
        }
        val r = engine.search("HD 600")
        // exact match "HD 600" outranks the longer substring "Sennheiser HD 600"
        assertEquals("HD 600", r.entries[0].name)
        assertEquals("Sennheiser HD 600", r.entries[1].name)
    }

    @Test
    fun `prefix beats interior substring`() {
        val engine = AutoEqSearchEngine().apply {
            setCatalog(
                listOf(
                    entry("1", "Sony Mid HD"),
                    entry("2", "HD Sony"),
                ),
            )
        }
        val r = engine.search("hd")
        assertEquals("HD Sony", r.entries[0].name)
    }

    @Test
    fun `airpod pro matches Apple AirPods Pro via tier 2`() {
        val engine = AutoEqSearchEngine().apply {
            setCatalog(
                listOf(
                    entry("1", "Apple AirPods Pro"),
                    entry("2", "Sennheiser HD 600"),
                    entry("3", "Sony WH-1000XM4"),
                ),
            )
        }
        val r = engine.search("airpod pro")
        assertTrue("expected at least one match for 'airpod pro'", r.entries.isNotEmpty())
        assertEquals("Apple AirPods Pro", r.entries[0].name)
    }

    @Test
    fun `tier 3 fuzzy match handles single-character typo`() {
        val engine = AutoEqSearchEngine().apply {
            setCatalog(
                listOf(
                    entry("1", "Sennheiser HD 600"),
                    entry("2", "AKG K712"),
                ),
            )
        }
        // "sennhieser" is a typo for "sennheiser" — token edit distance 2
        val r = engine.search("sennhieser")
        assertNotNull(r.entries.firstOrNull { it.name == "Sennheiser HD 600" })
    }

    @Test
    fun `result is limited and totalCount reflects the unbounded match count`() {
        val engine = AutoEqSearchEngine().apply {
            setCatalog((1..120).map { entry(it.toString(), "Sony Model $it") })
        }
        val r = engine.search("sony", limit = 10)
        assertEquals(10, r.entries.size)
        assertEquals(120, r.totalCount)
    }

    @Test
    fun `editDistance handles trivial cases`() {
        val e = AutoEqSearchEngine()
        assertEquals(0, e.editDistance("abc", "abc"))
        assertEquals(1, e.editDistance("abc", "abd"))
        assertEquals(1, e.editDistance("abc", "ab"))
        assertEquals(3, e.editDistance("", "abc"))
    }

    @Test
    fun `large catalog search latency stays under p95 200ms`() {
        // Synthetic catalog of 10k entries.
        val brands = listOf(
            "Sennheiser", "Sony", "AKG", "Audio-Technica", "Beyerdynamic",
            "HiFiMan", "Focal", "Shure", "Etymotic", "Final",
        )
        val entries = (0 until 10_000).map { i ->
            val brand = brands[i % brands.size]
            entry(i.toString(), "$brand Model $i")
        }
        val engine = AutoEqSearchEngine().apply { setCatalog(entries) }

        val queries = listOf(
            "sennheiser", "hd 600", "sony wh", "model 42", "akg",
            "audio", "beyer", "hifiman", "focal clear", "model 9999",
            "shure", "ety", "final", "audio technica", "beyerdyn",
            "sen", "son", "hd", "wh-1000", "akg k",
        )

        // Warm up.
        repeat(3) { for (q in queries) engine.search(q) }

        val timingsNanos = LongArray(queries.size * 5)
        var idx = 0
        repeat(5) {
            for (q in queries) {
                val t0 = System.nanoTime()
                engine.search(q)
                timingsNanos[idx++] = System.nanoTime() - t0
            }
        }
        timingsNanos.sort()
        val p95 = timingsNanos[(timingsNanos.size * 95) / 100]
        val p95Ms = p95 / 1_000_000.0
        assertTrue("P95 search latency should be < 200ms but was ${p95Ms} ms", p95Ms < 200.0)
    }

    /**
     * Regression: setCatalog() used to mutate three private fields sequentially, so a
     * concurrent search() could observe `entries.size = 6028` but `loweredNames.size = 0`
     * and crash with IndexOutOfBoundsException. Switching to an immutable SearchIndex
     * snapshot published via a single volatile write fixes that — verified by hammering
     * setCatalog and search from many threads simultaneously and asserting no exception
     * leaks out + every result is index-consistent (size <= limit, totalCount sane).
     */
    @Test
    fun `concurrent setCatalog and search never observe torn state`() {
        // Two distinct catalogs we'll alternate between, each big enough that an
        // un-protected reader has a real window to observe a half-built index.
        val brands = listOf("Sennheiser", "Sony", "AKG", "Audio-Technica", "Beyerdynamic")
        val catalogA = (0 until 6_000).map { i ->
            val brand = brands[i % brands.size]
            entry("a-$i", "$brand Model A$i")
        }
        val catalogB = (0 until 6_500).map { i ->
            val brand = brands[i % brands.size]
            entry("b-$i", "$brand Model B$i")
        }

        val engine = AutoEqSearchEngine().apply { setCatalog(catalogA) }
        val queries = listOf("sennheiser", "model a42", "sony", "model b9999", "akg", "beyer")
        val iterations = 500

        runBlocking {
            coroutineScope {
                // Writer: flips between two catalogs as fast as possible.
                val writer = async(Dispatchers.Default) {
                    repeat(iterations) { i ->
                        engine.setCatalog(if (i % 2 == 0) catalogA else catalogB)
                    }
                }
                // Multiple readers: hammer search() on different queries.
                val readers = (0 until 8).map { rid ->
                    async(Dispatchers.Default) {
                        repeat(iterations) { i ->
                            val q = queries[(rid + i) % queries.size]
                            val r = engine.search(q, limit = 25)
                            // A torn read would have thrown by now (IOOBE). Also assert
                            // the result envelope respects its declared invariants.
                            assertTrue(
                                "result entries (${r.entries.size}) must not exceed limit",
                                r.entries.size <= 25,
                            )
                            assertTrue(
                                "totalCount (${r.totalCount}) must be >= entries.size",
                                r.totalCount >= r.entries.size,
                            )
                        }
                    }
                }
                awaitAll(writer, *readers.toTypedArray())
            }
        }

        // Final state should be one of the two known catalogs — search must still work.
        val finalResult = engine.search("sennheiser", limit = 5)
        assertTrue(
            "after concurrent churn, search should still return matches",
            finalResult.entries.isNotEmpty(),
        )
    }

    @Test
    fun `setCatalog with empty list resets the index`() {
        val engine = AutoEqSearchEngine().apply {
            setCatalog(listOf(entry("1", "Sennheiser HD 600")))
        }
        engine.setCatalog(emptyList())
        val r = engine.search("hd 600")
        assertEquals(0, r.entries.size)
        assertEquals(0, r.totalCount)
    }
}
