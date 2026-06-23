package com.coreline.autoeq.db

import androidx.room.Room
import com.coreline.autoeq.model.AutoEqCatalogEntry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Phase 4 self-test — DB-first catalog store logic (no network, no bundled-asset dependency).
 * The seed-from-asset path is verified on-device (offline seed load).
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class CatalogStoreTest {

    private lateinit var db: AutoEqDatabase
    private lateinit var store: CatalogStore

    @Before
    fun setup() {
        val ctx = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(ctx, AutoEqDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        store = CatalogStore(db.catalogDao(), ctx.assets)
    }

    @After
    fun teardown() = db.close()

    private fun entry(id: String, name: String) =
        AutoEqCatalogEntry(id = id, name = name, measuredBy = "src", relativePath = "p/$id")

    @Test
    fun `legacy import populates db when empty and is one-shot`() = runBlocking {
        assertEquals(0, store.count())
        val imported = store.importLegacyIfEmpty(listOf(entry("a", "HD 600"), entry("b", "K712")), 1000L)
        assertTrue(imported)
        assertEquals(2, store.count())
        // Second call is a no-op because the DB is no longer empty.
        assertFalse(store.importLegacyIfEmpty(listOf(entry("c", "DT 990")), 2000L))
        assertEquals(2, store.count())
    }

    @Test
    fun `loadFromDb round-trips domain fields and lowercases normalizedName`() = runBlocking {
        store.applyRemote(listOf(entry("x", "Sennheiser HD 600")), 5L, etag = "et", contentSha256 = "sha")
        val domain = store.loadFromDb()
        assertEquals(1, domain.size)
        assertEquals("Sennheiser HD 600", domain[0].name)
        assertEquals("p/x", domain[0].relativePath)
        // normalizedName is the lowercased name (DB-side filtering aid).
        assertEquals("sennheiser hd 600", db.catalogDao().getAll()[0].normalizedName)
    }

    @Test
    fun `applyRemote stores etag and content hash for conditional refresh`() = runBlocking {
        assertNull(store.syncState())
        store.applyRemote(listOf(entry("x", "X")), 9L, etag = "\"abc\"", contentSha256 = "deadbeef")
        val s = store.syncState()!!
        assertEquals("\"abc\"", s.etag)
        assertEquals("deadbeef", s.contentSha256)
        assertEquals("network", s.status)
        assertEquals(9L, s.lastSyncAtMs)
    }

    @Test
    fun `applyRemote tombstones entries removed upstream and un-deletes reappearing ones`() = runBlocking {
        // Initial sync: a, b, c.
        store.applyRemote(listOf(entry("a", "A"), entry("b", "B"), entry("c", "C")), 100L, "e1", "h1")
        assertEquals(3, store.count())

        // Next sync drops c → c is tombstoned (hidden from loadFromDb/count).
        val tombstoned = store.applyRemote(listOf(entry("a", "A"), entry("b", "B")), 200L, "e2", "h2")
        assertEquals(1, tombstoned)
        assertEquals(2, store.count())
        assertEquals(setOf("a", "b"), store.loadFromDb().map { it.id }.toSet())

        // c reappears upstream → un-deleted by the upsert.
        store.applyRemote(listOf(entry("a", "A"), entry("b", "B"), entry("c", "C")), 300L, "e3", "h3")
        assertEquals(3, store.count())
        assertEquals(setOf("a", "b", "c"), store.loadFromDb().map { it.id }.toSet())
    }

    // --- Adversarial regression guards (QA, Phase 4b tombstone sweep) ---

    @Test
    fun `applyRemote with same nowMs twice does not tombstone prior entries`() = runBlocking {
        // Worst case: clock not advanced between two full syncs (coarse/identical millis,
        // or two refreshes within the same ms). tombstoneOlderThan uses strict `<`, and the
        // re-upsert restamps lastSeenAtMs = nowMs, so nothing should be wrongly hidden.
        store.applyRemote(listOf(entry("a", "A"), entry("b", "B")), 100L, "e1", "h1")
        assertEquals(2, store.count())

        val tombstoned = store.applyRemote(listOf(entry("a", "A"), entry("b", "B")), 100L, "e2", "h2")
        assertEquals(0, tombstoned) // no row has lastSeenAtMs < 100 after re-upsert
        assertEquals(2, store.count())
        assertEquals(setOf("a", "b"), store.loadFromDb().map { it.id }.toSet())

        // And a genuine same-ms drop of c must still NOT tombstone a/b, but c was never present;
        // verify that dropping b at the SAME ms does tombstone b only if its stamp is older.
        // Here b is re-sent so it survives; only an absent older row would go.
        val t2 = store.applyRemote(listOf(entry("a", "A")), 100L, "e3", "h3")
        // b still has lastSeenAtMs == 100 (not < 100) → survives. This is the documented
        // strict-< behavior: a same-ms sync cannot reap entries dropped in that same ms.
        assertEquals(0, t2)
        assertEquals(setOf("a", "b"), store.loadFromDb().map { it.id }.toSet())
    }

    @Test
    fun `applyRemote with empty entries never tombstones the live catalog`() = runBlocking {
        store.applyRemote(listOf(entry("a", "A"), entry("b", "B")), 100L, "e1", "h1")
        assertEquals(2, store.count())
        // Transient empty fetch (defensive): must be a no-op, NOT a full wipe via sweep.
        val tombstoned = store.applyRemote(emptyList(), 999_999L, "e2", "h2")
        assertEquals(0, tombstoned)
        assertEquals(2, store.count())
        assertEquals(setOf("a", "b"), store.loadFromDb().map { it.id }.toSet())
    }

    @Test
    fun `applyRemote with smaller nowMs than prior sync does not tombstone newer rows`() = runBlocking {
        // Clock regression (NTP step / non-monotonic wall clock). A later sync arriving with a
        // SMALLER nowMs must not reap rows stamped with the larger prior timestamp.
        store.applyRemote(listOf(entry("a", "A"), entry("b", "B")), 500L, "e1", "h1")
        assertEquals(2, store.count())
        val tombstoned = store.applyRemote(listOf(entry("a", "A")), 200L, "e2", "h2")
        // b has lastSeenAtMs=500, sweep is `< 200` → b NOT reaped (correct: no false delete).
        assertEquals(0, tombstoned)
        assertEquals(setOf("a", "b"), store.loadFromDb().map { it.id }.toSet())
    }

    @Test
    fun `markNotModified updates sync timestamp without touching entries`() = runBlocking {
        store.applyRemote(listOf(entry("x", "X")), 9L, etag = "e", contentSha256 = "h")
        store.markNotModified(20L)
        val s = store.syncState()!!
        assertEquals("not_modified", s.status)
        assertEquals(20L, s.lastSyncAtMs)
        assertEquals("e", s.etag) // etag preserved
        assertEquals(1, store.count())
    }
}
