package com.coreline.autoeq.repository

import android.content.Context
import com.coreline.autoeq.cache.CatalogCache
import com.coreline.autoeq.cache.ImportedProfileStore
import com.coreline.autoeq.cache.ProfileCache
import com.coreline.autoeq.model.AutoEqCatalogEntry
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Unit tests for [AutoEqRepository] covering:
 *
 *  - R2-2: URL builder correctness for special-character profile names (spaces,
 *          '+', parentheses, non-ASCII, embedded '/').
 *  - R1-4: fetchProfile coroutine lifecycle — coalescing, cancellation rethrow
 *          for caller-cancellation, structured shutdown via [AutoEqRepository.close].
 *  - R2-3: clearCache routes through the canonical [CatalogCache.clear] API.
 *
 * Robolectric runs the test inside its sandbox so the framework `android.util.Log`
 * calls inside the repository don't blow up on a host JVM. The [Context] is
 * mocked because every constructor parameter that touches `filesDir` is overridden
 * with explicit cache instances, so the only real Context use-sites
 * (`importFromUri`'s `contentResolver`) are not exercised here. All HTTP work
 * is intercepted via an [Interceptor] inside a real [OkHttpClient] — no
 * MockWebServer needed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class AutoEqRepositoryTest {

    private lateinit var rootDir: File
    private lateinit var ctx: Context

    @Before
    fun setUp() {
        rootDir = Files.createTempDirectory("autoeq-repo-test").toFile()
        ctx = mockk(relaxed = true)
        every { ctx.filesDir } returns File(rootDir, "ctx-files").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        rootDir.deleteRecursively()
    }

    // ---------- R2-2: buildProfileUrl ----------

    @Test
    fun `buildProfileUrl simple name and path`() {
        val repo = newRepository()
        val entry = entryOf(
            name = "Sennheiser HD 600",
            relativePath = "oratory1990/over-ear/Sennheiser HD 600",
        )
        val url = repo.buildProfileUrl(entry)
        assertEquals(
            "https://raw.githubusercontent.com/jaakkopasanen/AutoEq/master/results/" +
                "oratory1990/over-ear/Sennheiser%20HD%20600/" +
                "Sennheiser%20HD%20600%20ParametricEQ.txt",
            url,
        )
    }

    @Test
    fun `buildProfileUrl encodes literal plus as percent 2B`() {
        val repo = newRepository()
        val entry = entryOf(
            name = "Audeze LCD-3+",
            relativePath = "oratory1990/over-ear/Audeze LCD-3+",
        )
        val url = repo.buildProfileUrl(entry)
        // '+' is a literal character in the name. URL-encoding it as a space
        // (form-encoding default) would silently change the request to a
        // different file — must be percent-encoded as %2B.
        assertTrue("URL should encode '+' as %2B: $url", url.contains("Audeze%20LCD-3%2B"))
        // Sanity: no stray '+' should leak through into the path.
        assertFalse("URL should not contain literal '+': $url", url.contains("+"))
    }

    @Test
    fun `buildProfileUrl preserves parentheses unencoded to match upstream`() {
        val repo = newRepository()
        // Real upstream entry — parens are NOT percent-encoded in INDEX.md links
        // (see IndexMdParser comment about "64%20Audio%20A12t%20(m15%20Apex%20module)").
        val entry = entryOf(
            name = "64 Audio A12t (m15 Apex module)",
            relativePath = "crinacle/in-ear/64 Audio A12t (m15 Apex module)",
        )
        val url = repo.buildProfileUrl(entry)
        assertTrue(
            "URL should keep '(' and ')' unencoded to match upstream: $url",
            url.contains("64%20Audio%20A12t%20(m15%20Apex%20module)"),
        )
        assertFalse("URL must not contain %28 / %29 encodings: $url", url.contains("%28"))
        assertFalse("URL must not contain %28 / %29 encodings: $url", url.contains("%29"))
    }

    @Test
    fun `buildProfileUrl percent-encodes non-ASCII as UTF-8`() {
        val repo = newRepository()
        // Em-dash ("—", U+2014) is UTF-8 bytes E2 80 94 → %E2%80%94.
        val entry = entryOf(
            name = "Sennheiser HD 600 — Pro",
            relativePath = "oratory1990/over-ear/Sennheiser HD 600 — Pro",
        )
        val url = repo.buildProfileUrl(entry)
        assertTrue(
            "URL should UTF-8 percent-encode em-dash: $url",
            url.contains("%E2%80%94"),
        )
    }

    @Test
    fun `buildProfileUrl preserves path separators between segments`() {
        val repo = newRepository()
        val entry = entryOf(
            name = "Sennheiser HD 600",
            relativePath = "oratory1990/over-ear/Sennheiser HD 600",
        )
        val url = repo.buildProfileUrl(entry)
        // Three '/' between PROFILE_BASE_URL's trailing '/' and the file segment.
        // PROFILE_BASE_URL ends with "results/" so we expect the path '/' separators
        // preserved as plain '/' (NOT %2F).
        assertFalse("Path '/' must NOT be percent-encoded: $url", url.contains("%2F"))
        // Verify the encoded path joins cleanly:
        assertTrue(
            "Path segments should be joined by literal '/': $url",
            url.contains("oratory1990/over-ear/Sennheiser%20HD%20600/"),
        )
    }

    @Test
    fun `buildProfileUrl encodes space as percent 20 not plus`() {
        val repo = newRepository()
        val entry = entryOf(
            name = "Apple AirPods Pro 2",
            relativePath = "Apple AirPods Pro 2",
        )
        val url = repo.buildProfileUrl(entry)
        assertFalse("Spaces must NOT be encoded as '+': $url", url.contains("+"))
        assertTrue("Spaces should be %20-encoded: $url", url.contains("Apple%20AirPods%20Pro%202"))
    }

    // ---------- R1-4: fetchProfile lifecycle ----------

    @Test
    fun `fetchProfile coalesces concurrent calls into one HTTP request`() = runBlocking {
        val callCount = AtomicInteger(0)
        // Tiny gate so all four awaiters land before the response is produced — that
        // way they all observe the same in-flight Deferred via computeIfAbsent.
        val gate = CountDownLatch(1)
        val client = clientReturning(SAMPLE_PROFILE_TEXT) { _ ->
            callCount.incrementAndGet()
            // Block until the test releases — guarantees all four callers
            // observe the same in-flight Deferred.
            gate.await(5, TimeUnit.SECONDS)
        }
        val repo = newRepository(client)
        val entry = entryOf("Test", "x/y/Test")

        coroutineScope {
            val deferreds = (0 until 4).map { async { repo.fetchProfile(entry) } }
            // Give the launches a moment to all hit `computeIfAbsent`.
            delay(50)
            gate.countDown()
            val results = deferreds.awaitAll()
            assertEquals(4, results.size)
            results.forEach { assertNotNull("Each fetch should resolve to a profile", it) }
        }

        assertEquals(
            "Coalescing must reduce 4 concurrent fetchProfile calls to 1 HTTP request",
            1, callCount.get(),
        )
    }

    @Test
    fun `fetchProfile rethrows CancellationException when caller cancelled`() {
        // Slow client so withTimeout fires before the response comes back.
        val client = clientReturning(SAMPLE_PROFILE_TEXT) {
            // Sleep way longer than the timeout so the cancellation hits us first.
            Thread.sleep(2_000)
        }
        val repo = newRepository(client)
        val entry = entryOf("Test", "x/y/Test")

        // A TimeoutCancellationException IS a CancellationException — withTimeout
        // wraps the inner CE and rethrows. Our fetchProfile must propagate it
        // upward instead of swallowing as `null`.
        val thrown = assertThrows(CancellationException::class.java) {
            runBlocking { withTimeout(50) { repo.fetchProfile(entry) } }
        }
        assertNotNull(thrown)
    }

    @Test
    fun `fetchProfile clears inflight map after completion`() = runBlocking {
        val client = clientReturning(SAMPLE_PROFILE_TEXT)
        val repo = newRepository(client)
        val entry = entryOf("Test", "x/y/Test")

        val profile = repo.fetchProfile(entry)
        assertNotNull(profile)

        // Reflectively peek at the inflight map — by R1-4 contract it must be
        // empty after the deferred completes so the same id can re-fetch later.
        val inflight = inflightMap(repo)
        assertTrue(
            "inflight map should be empty after successful fetch (was: ${inflight.keys})",
            inflight.isEmpty(),
        )

        // Second fetch should ALSO hit the network path freely (not blocked
        // on a stale entry from the first call).
        val profile2 = repo.fetchProfile(entry)
        assertNotNull(profile2)
    }

    @Test
    fun `caller cancellation does not yank still-running deferred from inflight map (C3)`() = runBlocking {
        // C3 regression: if caller A cancels its `await`, an in-flight deferred D1
        // for the same id MUST stay registered in `inflight` until D1 itself
        // completes — otherwise caller B arriving a moment later would
        // computeIfAbsent a SECOND network call (D2), breaking coalescing.
        //
        // Test shape:
        //   1. Caller A calls fetchProfile(id). Interceptor blocks on a gate.
        //   2. Cancel caller A.
        //   3. While D1 is still blocked, caller B calls fetchProfile(id).
        //      With the C3 fix, B must coalesce onto D1 (no new HTTP call).
        //   4. Release the gate → D1 completes → both A's coroutine is gone
        //      and B receives the profile.
        //   5. callCount must be exactly 1.
        //
        // #9: synchronisation is purely latch-based (no `delay(50)` polling),
        //     so the test does not flake under load.
        // #10: also asserts that callerA's outer `await()` propagates a
        //     CancellationException (the `currentCoroutineContext()[Job].isActive`
        //     branch in fetchProfile must rethrow when the caller was cancelled).
        val callCount = AtomicInteger(0)
        val gate = CountDownLatch(1)
        val client = clientReturning(SAMPLE_PROFILE_TEXT) {
            callCount.incrementAndGet()
            gate.await(10, TimeUnit.SECONDS)
        }
        val repo = newRepository(client)
        val entry = entryOf("Test", "x/y/Test")

        // #9: signal-based termination tracking. We use `CompletableDeferred`
        // (suspending `await`) instead of `CountDownLatch.await` because the
        // outer scope is a `runBlocking` whose EventLoop is the same dispatcher
        // that callerA's body runs on — `CountDownLatch.await` BLOCKS the
        // EventLoop, preventing callerA from ever reaching its catch/finally
        // handlers (deadlock). `CompletableDeferred.await` yields the dispatcher
        // so callerA gets to run.
        // #10: the captured throwable lets us assert CE propagation without
        // nesting runBlocking inside coroutineScope (which has subtle
        // cancel-everything semantics).
        val callerATerminated = CompletableDeferred<Unit>()
        val callerAThrew = AtomicReference<Throwable?>(null)

        coroutineScope {
            val callerA = async {
                try {
                    repo.fetchProfile(entry)
                } catch (t: Throwable) {
                    callerAThrew.set(t)
                    throw t
                } finally {
                    callerATerminated.complete(Unit)
                }
            }
            // Wait for the interceptor to enter (proves D1 is launched and
            // already executing the http call). Polling is fine here — we are
            // waiting on a positive event (callCount > 0) with a hard timeout,
            // and `delay` yields the dispatcher so D1 (on its own IO thread)
            // can make progress.
            val started = System.nanoTime()
            while (callCount.get() == 0) {
                check(System.nanoTime() - started < 5_000_000_000L) {
                    "interceptor never entered — D1 not launched"
                }
                delay(10)
            }

            // Cancel caller A. With the OLD `finally { inflight.remove(...) }`
            // logic, this would synchronously evict the entry from `inflight`
            // even though D1 is still running. The C3 fix moves cleanup to
            // `invokeOnCompletion`, so the entry must remain.
            callerA.cancel()
            // #9: deterministic wait for A's body to unwind. Suspends, so the
            // dispatcher remains free for callerA to actually run its catch
            // and finally handlers.
            withTimeout(2_000) { callerATerminated.await() }

            // C3 invariant: even though A's coroutine has fully terminated,
            // D1 is still in flight inside repositoryScope — the inflight map
            // MUST still have the entry.
            assertFalse(
                "C3 contract: in-flight map must NOT be emptied by caller cancellation",
                inflightMap(repo).isEmpty(),
            )

            // #10: A's body MUST have observed CancellationException — that
            // proves the `currentCoroutineContext()[Job].isActive == false`
            // branch inside fetchProfile rethrew rather than swallowing as null.
            assertTrue(
                "callerA must propagate CancellationException, was ${callerAThrew.get()}",
                callerAThrew.get() is CancellationException,
            )

            // Now caller B arrives. With C3, it coalesces onto D1.
            val bStarted = CompletableDeferred<Unit>()
            val callerB = async {
                bStarted.complete(Unit)
                repo.fetchProfile(entry)
            }
            withTimeout(2_000) { bStarted.await() }
            // Yield once so B's inner suspend reaches the `await()` on D1.
            // A single yield is dispatcher-deterministic (not wall-clock-
            // dependent) and is known-stable in kotlinx-test.
            yield()

            assertEquals(
                "Caller B must coalesce onto D1, not spawn a second HTTP call",
                1, callCount.get(),
            )

            // Unblock and verify B receives a profile.
            gate.countDown()
            val resultB = withTimeout(2_000) { callerB.await() }
            assertNotNull("Caller B should receive the coalesced profile", resultB)
            // Final tally: still 1 HTTP call.
            assertEquals(1, callCount.get())
        }

        // Once D1 has completed, `invokeOnCompletion` must have cleared the map.
        assertTrue(
            "inflight map should be empty after deferred completes (was: ${inflightMap(repo).keys})",
            inflightMap(repo).isEmpty(),
        )
    }

    @Test
    @Suppress("DEPRECATION")
    fun `clearCache calls catalogCache clear`() = runBlocking {
        val realCatalogCache = CatalogCache(File(rootDir, "cat-spy"))
        val catalogSpy = spyk(realCatalogCache)
        val repo = AutoEqRepository(
            context = ctx,
            httpClient = clientReturning(""),
            catalogCache = catalogSpy,
            profileCache = ProfileCache(File(rootDir, "pc")),
            importedStore = ImportedProfileStore(File(rootDir, "imp")),
        )

        repo.clearCache()

        // mockk records each suspend invocation twice for spies (entry + resumed
        // continuation), so we use atLeast rather than exactly = 1.
        coVerify(atLeast = 1) { catalogSpy.clear() }
        // R2-3 contract: deleteCorrupted is the deprecated alias and we should NOT
        // be calling it any more from the repository. (The deprecated method itself
        // delegates to clear; we still want to assert the migration is permanent.)
        coVerify(exactly = 0) { catalogSpy.deleteCorrupted() }
    }

    @Test
    fun `close cancels inflight fetches and surfaces null to awaiters`() = runBlocking {
        val gate = CountDownLatch(1)
        val client = clientReturning(SAMPLE_PROFILE_TEXT) {
            // Block forever from the test's perspective — only the cancellation
            // path should be able to release us.
            gate.await(10, TimeUnit.SECONDS)
        }
        val repo = newRepository(client)
        val entry = entryOf("Test", "x/y/Test")

        coroutineScope {
            val deferred = async { repo.fetchProfile(entry) }
            // Let the launch race past computeIfAbsent so there's something to cancel.
            delay(50)
            assertFalse("inflight should be populated mid-fetch", inflightMap(repo).isEmpty())

            repo.close()
            // Release the interceptor so the underlying thread can wind down.
            gate.countDown()

            // The await must NOT throw — an internally-cancelled deferred where
            // the caller is still active should surface as null per R1-4.
            val result = withTimeout(2_000) { deferred.await() }
            assertNull("close()-cancelled fetch should resolve to null", result)
        }

        assertTrue(
            "inflight map should be empty after close() (was: ${inflightMap(repo).keys})",
            inflightMap(repo).isEmpty(),
        )
    }

    // ---------- helpers ----------

    private fun newRepository(
        client: OkHttpClient = clientReturning(SAMPLE_PROFILE_TEXT),
    ): AutoEqRepository = AutoEqRepository(
        context = ctx,
        httpClient = client,
        catalogCache = CatalogCache(File(rootDir, "cat")),
        profileCache = ProfileCache(File(rootDir, "pc")),
        importedStore = ImportedProfileStore(File(rootDir, "imp")),
    )

    private fun entryOf(name: String, relativePath: String): AutoEqCatalogEntry =
        AutoEqCatalogEntry(
            id = "id-${name.hashCode()}",
            name = name,
            measuredBy = "oratory1990",
            relativePath = relativePath,
        )

    /**
     * Build an [OkHttpClient] whose every request is short-circuited by the
     * supplied [body] and side-effect [onIntercept]. No real socket is opened.
     */
    private fun clientReturning(
        body: String,
        onIntercept: (okhttp3.Request) -> Unit = {},
    ): OkHttpClient {
        val interceptor = Interceptor { chain ->
            val req = chain.request()
            onIntercept(req)
            Response.Builder()
                .request(req)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(body.toResponseBody())
                .build()
        }
        return OkHttpClient.Builder()
            .addInterceptor(interceptor)
            .build()
    }

    @Suppress("UNCHECKED_CAST")
    private fun inflightMap(repo: AutoEqRepository): Map<String, *> {
        val field = AutoEqRepository::class.java.getDeclaredField("inflight")
        field.isAccessible = true
        return field.get(repo) as Map<String, *>
    }

    companion object {
        /** Minimal valid ParametricEQ.txt that the parser will accept. */
        private val SAMPLE_PROFILE_TEXT = """
            Preamp: -6.5 dB
            Filter 1: ON PK Fc 21 Hz Gain 4.0 dB Q 1.41
            Filter 2: ON PK Fc 105 Hz Gain -2.0 dB Q 0.7
        """.trimIndent()
    }
}
