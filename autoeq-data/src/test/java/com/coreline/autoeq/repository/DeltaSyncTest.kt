package com.coreline.autoeq.repository

import android.content.Context
import androidx.room.Room
import com.coreline.autoeq.cache.CatalogCache
import com.coreline.autoeq.cache.ImportedProfileStore
import com.coreline.autoeq.cache.ProfileCache
import com.coreline.autoeq.db.AutoEqDatabase
import com.coreline.autoeq.db.CatalogStore
import com.coreline.autoeq.db.ProfileStore
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap

/**
 * QA verification of incremental delta sync ([AutoEqRepository.syncDelta]).
 *
 * Strategy: in-memory Room + a URL-routing fake OkHttp interceptor. The interceptor
 * dispatches on the request URL so we can return distinct bodies for:
 *   - GET /commits/master   → {"sha": "<HEAD>"}
 *   - GET /compare/A...B     → {"files":[{filename, status}]}
 *   - INDEX_URL              → a tiny INDEX.md containing the Foo entry
 *   - profile raw URL        → a valid ParametricEQ.txt
 * It also counts hits per URL category so we can assert that NoChange / kill-switch
 * perform zero compare/profile fetches.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class DeltaSyncTest {

    private lateinit var rootDir: File
    private lateinit var ctx: Context
    private lateinit var db: AutoEqDatabase
    private lateinit var catalogStore: CatalogStore
    private lateinit var profileStore: ProfileStore

    // The Foo catalog entry — must round-trip through path→entry mapping in syncDelta.
    //   relativePath:          oratory1990/over-ear/Foo
    //   compare filename:      results/oratory1990/over-ear/Foo/Foo ParametricEQ.txt
    //   profile raw URL:       .../results/oratory1990/over-ear/Foo/Foo%20ParametricEQ.txt
    private val fooPath = "oratory1990/over-ear/Foo"
    private val fooFilename = "results/$fooPath/Foo ParametricEQ.txt"
    private val indexMd = """
        # Index
        - [Foo](./oratory1990/over-ear/Foo) by oratory1990
    """.trimIndent()
    private val fooProfileTxt = """
        Preamp: -6.5 dB
        Filter 1: ON PK Fc 21 Hz Gain 4.0 dB Q 1.41
        Filter 2: ON PK Fc 105 Hz Gain -2.0 dB Q 0.7
    """.trimIndent()

    @Before
    fun setUp() {
        rootDir = Files.createTempDirectory("delta-sync-test").toFile()
        ctx = mockk(relaxed = true)
        every { ctx.filesDir } returns File(rootDir, "ctx-files").apply { mkdirs() }
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AutoEqDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        catalogStore = CatalogStore(db.catalogDao(), RuntimeEnvironment.getApplication().assets)
        profileStore = ProfileStore(db.profileDao())
    }

    @After
    fun tearDown() {
        db.close()
        rootDir.deleteRecursively()
    }

    // ---- URL-routing fake client ----

    /** Per-category hit counters. */
    private val hits = ConcurrentHashMap<String, Int>()
    private fun bump(key: String) { hits.merge(key, 1) { a, b -> a + b } }
    private fun count(key: String) = hits[key] ?: 0

    /**
     * @param head sha returned for /commits/master
     * @param compareFiles JSON array body for the compare "files" field (raw json string)
     */
    private fun routingClient(
        head: String,
        compareFiles: String,
        indexBody: String = indexMd,
    ): OkHttpClient {
        val interceptor = Interceptor { chain ->
            val req = chain.request()
            val url = req.url.toString()
            val body: String = when {
                url.endsWith("/commits/master") -> {
                    bump("commits"); """{"sha":"$head"}"""
                }
                url.contains("/compare/") -> {
                    bump("compare"); """{"files":[$compareFiles]}"""
                }
                url == AutoEqRepository.INDEX_URL -> {
                    bump("index"); indexBody
                }
                url.endsWith("ParametricEQ.txt") -> {
                    bump("profile"); fooProfileTxt
                }
                else -> { bump("other"); "" }
            }
            Response.Builder()
                .request(req)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(body.toResponseBody())
                .build()
        }
        return OkHttpClient.Builder().addInterceptor(interceptor).build()
    }

    private fun newRepo(client: OkHttpClient): AutoEqRepository = AutoEqRepository(
        context = ctx,
        httpClient = client,
        catalogCache = CatalogCache(File(rootDir, "cat")),
        profileCache = ProfileCache(File(rootDir, "pc")),
        catalogStore = catalogStore,
        profileStore = profileStore,
        importedStore = ImportedProfileStore(File(rootDir, "imp")),
    )

    /** Look up the Foo entry's id (after seeding catalog from the test INDEX). */
    private suspend fun fooEntryId(): String {
        val e = catalogStore.loadFromDb().firstOrNull { it.relativePath == fooPath }
        assertNotNull("Foo entry must exist in catalog", e)
        return e!!.id
    }

    // ---------- NoChange: HEAD == local ----------

    @Test
    fun `NoChange when HEAD equals local commit and no further fetches`() = runBlocking {
        val client = routingClient(head = "commitA", compareFiles = "")
        val repo = newRepo(client)
        catalogStore.setAutoEqCommit("commitA", System.currentTimeMillis())

        val result = repo.syncDelta()

        assertEquals(DeltaResult.NoChange, result)
        assertEquals("exactly one HEAD probe", 1, count("commits"))
        assertEquals("no compare fetch on NoChange", 0, count("compare"))
        assertEquals("no profile fetch on NoChange", 0, count("profile"))
        assertEquals("no index fetch on NoChange", 0, count("index"))
    }

    // ---------- Updated: HEAD != local, modified profile ----------

    @Test
    fun `Updated upserts a modified profile and advances commit`() = runBlocking {
        val client = routingClient(
            head = "commitB",
            compareFiles = """{"filename":"$fooFilename","status":"modified"}""",
        )
        val repo = newRepo(client)
        catalogStore.setAutoEqCommit("commitA", System.currentTimeMillis())

        val result = repo.syncDelta()

        assertTrue("expected Updated, was $result", result is DeltaResult.Updated)
        val upd = result as DeltaResult.Updated
        assertTrue("changedProfiles >= 1, was ${upd.changedProfiles}", upd.changedProfiles >= 1)
        assertEquals("commit advances to HEAD", "commitB", upd.toCommit)

        // Profile is now actually in the DB.
        val profile = profileStore.read(fooEntryId(), System.currentTimeMillis())
        assertNotNull("modified profile should be persisted in DB", profile)
        assertTrue("profile has filters", profile!!.filters.isNotEmpty())

        // Commit was persisted.
        assertEquals("commitB", catalogStore.autoEqCommit())

        // Network shape: 1 HEAD + 1 compare + 1 index + 1 profile.
        assertEquals(1, count("commits"))
        assertEquals(1, count("compare"))
        assertEquals(1, count("profile"))
    }

    // ---------- removed: profile deleted ----------

    @Test
    fun `removed status deletes the profile`() = runBlocking {
        val client = routingClient(
            head = "commitB",
            compareFiles = """{"filename":"$fooFilename","status":"removed"}""",
        )
        val repo = newRepo(client)
        catalogStore.setAutoEqCommit("commitA", System.currentTimeMillis())

        // Pre-seed catalog (so Foo entry exists) and pre-store the Foo profile, so the
        // removal has something to delete. Seed catalog first via the INDEX path: we seed
        // the catalog by reconciling — but to pre-store the profile we need the entry id,
        // which requires the INDEX to have been parsed. Drive one INDEX parse manually.
        catalogStore.applyRemote(
            com.coreline.autoeq.parser.IndexMdParser.parse(indexMd),
            System.currentTimeMillis(), null, null,
        )
        val id = fooEntryId()
        // Insert a profile row to be deleted.
        val parsed = com.coreline.autoeq.parser.ParametricEqParser.parse(
            text = fooProfileTxt, name = "Foo", id = id, measuredBy = "oratory1990",
        )
        assertTrue(parsed is com.coreline.autoeq.model.ParseResult.Success)
        profileStore.upsert(
            (parsed as com.coreline.autoeq.model.ParseResult.Success).profile,
            "u", "s", System.currentTimeMillis(),
        )
        assertNotNull("precondition: Foo profile stored", profileStore.read(id, System.currentTimeMillis()))

        val result = repo.syncDelta()

        assertTrue("expected Updated, was $result", result is DeltaResult.Updated)
        assertEquals("one removal", 1, (result as DeltaResult.Updated).removedProfiles)
        assertNull("Foo profile must be deleted", profileStore.read(id, System.currentTimeMillis()))
        assertEquals("no profile fetch for a removal", 0, count("profile"))
    }

    // ---------- kill switch ----------

    @Test
    fun `kill switch returns Failed with zero network hits`() = runBlocking {
        val client = routingClient(head = "commitB", compareFiles = "")
        val repo = newRepo(client)
        catalogStore.setAutoEqCommit("commitA", System.currentTimeMillis())
        repo.setKillSwitchEngaged(true)

        val result = repo.syncDelta()

        assertTrue("expected Failed, was $result", result is DeltaResult.Failed)
        assertEquals("no HEAD probe under kill switch", 0, count("commits"))
        assertEquals(0, count("compare"))
        assertEquals(0, count("profile"))
        assertEquals(0, count("index"))
    }

    // ---------- no base commit ----------

    @Test
    fun `no local base commit adopts HEAD and returns NoChange`() = runBlocking {
        val client = routingClient(head = "commitB", compareFiles = "")
        val repo = newRepo(client)
        // No setAutoEqCommit → autoEqCommit() == null

        val result = repo.syncDelta()

        assertEquals(DeltaResult.NoChange, result)
        assertEquals("HEAD adopted as base", "commitB", catalogStore.autoEqCommit())
        assertEquals("no compare when adopting base", 0, count("compare"))
        assertEquals("no profile fetch when adopting base", 0, count("profile"))
    }

    // ---------- ADVERSARIAL #1: truncation guard (>= 300 files) ----------

    /**
     * GitHub's compare API caps files[] at 300; beyond that the overflow is silently
     * dropped. syncDelta() must NOT half-apply such a delta: it returns Failed and leaves
     * the stored commit at the base (NOT advanced to HEAD), and performs NO profile fetches.
     * The guard short-circuits BEFORE the INDEX reconcile, so index hits are 0 as well.
     */
    @Test
    fun `truncation guard - 300 compare files returns Failed and does not advance commit`() = runBlocking {
        // Build EXACTLY 300 distinct ParametricEQ.txt file entries (== the cap → guard trips).
        val files = (1..300).joinToString(",") { i ->
            """{"filename":"results/oratory1990/over-ear/Bulk$i/Bulk$i ParametricEQ.txt","status":"modified"}"""
        }
        val client = routingClient(head = "commitB", compareFiles = files)
        val repo = newRepo(client)
        catalogStore.setAutoEqCommit("commitA", System.currentTimeMillis())

        val result = repo.syncDelta()

        assertTrue("expected Failed for an oversized delta, was $result", result is DeltaResult.Failed)
        assertTrue(
            "Failed reason should mention size/resync, was '${(result as DeltaResult.Failed).reason}'",
            result.reason.contains("too large") || result.reason.contains("resync"),
        )
        // C: commit must NOT advance — still the base, setAutoEqCommit was never reached.
        assertEquals("commit MUST stay at base (no half-apply)", "commitA", catalogStore.autoEqCommit())
        // No work past the guard.
        assertEquals("HEAD probed once", 1, count("commits"))
        assertEquals("compare fetched once", 1, count("compare"))
        assertEquals("no profile fetches when guard trips", 0, count("profile"))
        assertEquals("no INDEX reconcile when guard trips", 0, count("index"))
    }

    // ---------- ADVERSARIAL #2: encoded non-ASCII path match (decode fallback) ----------

    /**
     * Encoding-tolerant lookup. The catalog relativePath is URL-DECODED by IndexMdParser
     * (literal "ē"); the GitHub compare API returns the filename percent-ENCODED
     * ("A%C4%93R%20Audio%20Aure"). The raw lookup byRel[rawRel] misses; the decoded
     * fallback byRel[URLDecoder.decode(rawRel)] must match the decoded catalog path so
     * the profile is fetched, parsed and persisted.
     *
     * The INDEX fake carries the same Kazi entry so syncDelta's reconcile seeds it into
     * the catalog with a fresh timestamp (no tombstoning), proving the end-to-end path.
     */
    @Test
    fun `encoded non-ASCII compare path matches decoded catalog relativePath`() = runBlocking {
        // Decoded catalog path (what IndexMdParser produces, what byRel is keyed on):
        //   Kazi/in-ear/AēR Audio Aure
        // INDEX.md carries the percent-ENCODED link (as upstream does); the parser decodes it.
        val encodedLink = "./Kazi/in-ear/A%C4%93R%20Audio%20Aure"
        val kaziIndexMd = """
            # Index
            - [AēR Audio Aure](${encodedLink}) by oratory1990
        """.trimIndent()

        // compare filename is percent-ENCODED for the non-ASCII char (ē = %C4%93) and space (%20).
        val encodedCompareFilename =
            "results/Kazi/in-ear/A%C4%93R%20Audio%20Aure/A%C4%93R Audio Aure ParametricEQ.txt"

        val client = routingClient(
            head = "commitB",
            compareFiles = """{"filename":"$encodedCompareFilename","status":"modified"}""",
            indexBody = kaziIndexMd,
        )
        val repo = newRepo(client)
        catalogStore.setAutoEqCommit("commitA", System.currentTimeMillis())

        val result = repo.syncDelta()

        assertTrue("expected Updated (decode fallback should match), was $result", result is DeltaResult.Updated)
        val upd = result as DeltaResult.Updated
        assertTrue("changedProfiles >= 1, was ${upd.changedProfiles}", upd.changedProfiles >= 1)

        // The decoded catalog entry must exist and its profile must be persisted.
        val kaziEntry = catalogStore.loadFromDb()
            .firstOrNull { it.relativePath == "Kazi/in-ear/AēR Audio Aure" }
        assertNotNull("decoded Kazi entry must exist in catalog", kaziEntry)
        val profile = profileStore.read(kaziEntry!!.id, System.currentTimeMillis())
        assertNotNull("profile for the non-ASCII entry must be persisted (decode fallback matched)", profile)
        assertTrue("profile has filters", profile!!.filters.isNotEmpty())

        assertEquals("a profile WAS fetched", 1, count("profile"))
        assertEquals("commit advances to HEAD on a successful delta", "commitB", catalogStore.autoEqCommit())
    }
}
