package com.coreline.auraltune.opra.db

import androidx.room.Room
import com.coreline.auraltune.opra.OpraJsonlParser
import com.coreline.auraltune.opra.model.OpraSyncState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class OpraRoomStoreTest {

    private lateinit var db: OpraDatabase
    private lateinit var store: OpraRoomStore

    private val vendor = """{"type":"vendor","id":"pud","data":{"name":"Pud"}}"""
    private val product =
        """{"type":"product","id":"pud::vogue","data":{"name":"Vogue","type":"headphones","subtype":"over_the_ear","vendor_id":"pud"}}"""
    private val eqSupported =
        """{"type":"eq","id":"pud:vogue::ora","data":{"author":"oratory1990","details":"Harman","link":"https://x","type":"parametric_eq","parameters":{"gain_db":-7,"bands":[{"type":"peak_dip","frequency":200,"gain_db":-7,"q":6},{"type":"high_shelf","frequency":7000,"gain_db":7,"q":0.6}]},"product_id":"pud::vogue"}}"""
    private val eqUnsupported =
        """{"type":"eq","id":"pud:vogue::lp","data":{"author":"crinacle","type":"parametric_eq","parameters":{"gain_db":0,"bands":[{"type":"low_pass","frequency":12000,"slope":12}]},"product_id":"pud::vogue"}}"""

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), OpraDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        store = OpraRoomStore(db)
    }

    @After
    fun tearDown() = db.close()

    private fun seed() = runBlocking {
        val result = OpraJsonlParser().parse(sequenceOf(vendor, product, eqSupported, eqUnsupported))
        store.upsert(result, nowMs = 1_000L)
    }

    @Test
    fun upsert_thenObserveCatalog_listsBothEntries() = runBlocking {
        seed()
        val catalog = store.observeCatalog().first()
        assertEquals(2, catalog.size)
        assertTrue(catalog.any { it.productName == "Vogue" && it.vendorName == "Pud" })
    }

    @Test
    fun resolve_returnsProfileWithFilters_andSupportFlag() = runBlocking {
        seed()
        val supported = store.resolve("pud:vogue::ora")
        assertNotNull(supported)
        assertTrue(supported!!.isSupported)
        assertEquals(2, supported.filters.size)
        assertEquals(-7f, supported.preampDb, 0.001f)
        assertEquals("oratory1990", supported.author)

        val unsupported = store.resolve("pud:vogue::lp")
        assertNotNull(unsupported)
        assertFalse(unsupported!!.isSupported)
    }

    @Test
    fun search_matchesVendorProductAndAuthor() = runBlocking {
        seed()
        assertTrue(store.search("vogue").isNotEmpty())       // product name
        assertTrue(store.search("pud").isNotEmpty())          // vendor name
        assertTrue(store.search("oratory").isNotEmpty())      // author
        assertTrue(store.search("ZZZ-none").isEmpty())
        assertTrue(store.search("").isEmpty())                // blank guard
    }

    @Test
    fun syncState_roundTrips() = runBlocking {
        assertNull(store.syncState())
        store.setSyncState(OpraSyncState(opraCommit = "c1", snapshotVersion = "v1", lastSyncedAt = 5L))
        val s = store.syncState()
        assertNotNull(s)
        assertEquals("c1", s!!.opraCommit)
        assertEquals("v1", s.snapshotVersion)
    }

    @Test
    fun upsert_isFullReplace_notAccumulate() = runBlocking {
        seed()
        assertEquals(2, store.observeCatalog().first().size)
        // Re-import with only the supported eq -> catalog should shrink to 1 (replace, not add).
        val smaller = OpraJsonlParser().parse(sequenceOf(vendor, product, eqSupported))
        store.upsert(smaller, nowMs = 2_000L)
        assertEquals(1, store.observeCatalog().first().size)
        assertNull("filters of removed profile are cascade-deleted", store.resolve("pud:vogue::lp"))
    }
}
