package com.coreline.auraltune.opra

import androidx.room.Room
import com.coreline.auraltune.opra.db.OpraDatabase
import com.coreline.auraltune.opra.db.OpraRoomStore
import com.coreline.auraltune.opra.model.OpraSyncState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class OpraRepositoryImplTest {

    private lateinit var db: OpraDatabase
    private lateinit var store: OpraRoomStore

    private val vendor = """{"type":"vendor","id":"pud","data":{"name":"Pud"}}"""
    private val product =
        """{"type":"product","id":"pud::vogue","data":{"name":"Vogue","type":"headphones","subtype":"over_the_ear","vendor_id":"pud"}}"""
    private val product2 =
        """{"type":"product","id":"pud::aria","data":{"name":"Aria","type":"headphones","subtype":"in_ear","vendor_id":"pud"}}"""
    private fun eq(id: String, productId: String) =
        """{"type":"eq","id":"$id","data":{"author":"A","type":"parametric_eq","parameters":{"gain_db":-3,"bands":[{"type":"peak_dip","frequency":1000,"gain_db":2,"q":1}]},"product_id":"$productId"}}"""

    private class FakeSource(
        var snapshot: OpraSnapshot?,
        var fail: Boolean = false,
    ) : OpraSnapshotSource {
        override suspend fun fetch(): OpraSnapshot {
            if (fail) throw IOException("network down")
            return snapshot ?: throw IOException("no snapshot")
        }
    }

    private fun snapshot(commit: String, lines: List<String>) =
        OpraSnapshot(lines.asSequence(), OpraSyncState(opraCommit = commit))

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), OpraDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        store = OpraRoomStore(db)
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun refresh_updated_populatesStoreAndSyncState() = runBlocking {
        val repo = OpraRepositoryImpl(store, FakeSource(snapshot("c1", listOf(vendor, product, eq("pud:vogue::a", "pud::vogue")))))
        val r = repo.refresh()
        assertTrue(r is OpraSyncResult.Updated)
        assertEquals(1, store.observeCatalog().first().size)
        assertEquals("c1", store.syncState()!!.opraCommit)
    }

    @Test
    fun refresh_sameCommit_isNoChange() = runBlocking {
        val src = FakeSource(snapshot("c1", listOf(vendor, product, eq("pud:vogue::a", "pud::vogue"))))
        val repo = OpraRepositoryImpl(store, src)
        assertTrue(repo.refresh() is OpraSyncResult.Updated)
        // Same commit again -> short-circuit, no re-import.
        assertTrue(repo.refresh() is OpraSyncResult.NoChange)
    }

    @Test
    fun refresh_fetchFails_isFailed_andCacheRetained() = runBlocking {
        // Seed with a good snapshot first.
        OpraRepositoryImpl(store, FakeSource(snapshot("c1", listOf(vendor, product, eq("pud:vogue::a", "pud::vogue"))))).refresh()
        val before = store.observeCatalog().first().size
        assertEquals(1, before)

        // Now a failing source must NOT wipe the cache or sync state.
        val r = OpraRepositoryImpl(store, FakeSource(snapshot = null, fail = true)).refresh()
        assertTrue(r is OpraSyncResult.Failed)
        assertEquals(before, store.observeCatalog().first().size)
        assertEquals("c1", store.syncState()!!.opraCommit)
    }

    @Test
    fun refresh_newCommit_replacesData() = runBlocking {
        OpraRepositoryImpl(store, FakeSource(snapshot("c1", listOf(vendor, product, eq("pud:vogue::a", "pud::vogue"))))).refresh()
        assertEquals(1, store.observeCatalog().first().size)

        // New commit with two products/eqs -> full replace.
        val newLines = listOf(vendor, product, product2, eq("pud:vogue::a", "pud::vogue"), eq("pud:aria::b", "pud::aria"))
        val r = OpraRepositoryImpl(store, FakeSource(snapshot("c2", newLines))).refresh()
        assertTrue(r is OpraSyncResult.Updated)
        assertEquals(2, store.observeCatalog().first().size)
        assertEquals("c2", store.syncState()!!.opraCommit)
    }
}
