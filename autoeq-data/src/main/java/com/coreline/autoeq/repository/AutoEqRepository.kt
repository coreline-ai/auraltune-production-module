package com.coreline.autoeq.repository

import android.content.Context
import android.util.Log
import com.coreline.autoeq.cache.CatalogCache
import com.coreline.autoeq.cache.ImportedProfileStore
import com.coreline.autoeq.cache.ProfileCache
import com.coreline.autoeq.model.AutoEqCatalogEntry
import com.coreline.autoeq.model.AutoEqProfile
import com.coreline.autoeq.model.CatalogState
import com.coreline.autoeq.model.ParseResult
import com.coreline.autoeq.parser.IndexMdParser
import com.coreline.autoeq.parser.ParametricEqParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Catalog + profile fetcher for the AutoEq GitHub repository.
 *
 * Phase 4 contract:
 * - Cache-first reads for both catalog and profiles.
 * - Background refresh with TTL (7 days) for the catalog.
 * - Concurrent profile fetches for the same id are coalesced via a `ConcurrentHashMap`
 *   keyed by id and entries are removed in `finally`.
 *
 * Threading: all suspend methods are dispatcher-agnostic; HTTP and disk work happen on
 * `Dispatchers.IO`. Never call from the audio thread.
 */
class AutoEqRepository(
    private val context: Context,
    private val httpClient: OkHttpClient = defaultHttpClient(),
    private val catalogCache: CatalogCache = CatalogCache(File(context.filesDir, "autoeq")),
    private val profileCache: ProfileCache = ProfileCache(File(context.filesDir, "autoeq")),
    private val importedStore: ImportedProfileStore =
        ImportedProfileStore(File(context.filesDir, "autoeq")),
    private val telemetry: AutoEqTelemetry = AutoEqTelemetry.NoOp,
    /**
     * R1-4: structured scope tying every in-flight profile fetch to the repository's
     * lifetime. Defaulted to a fresh `SupervisorJob() + Dispatchers.IO` scope so a single
     * fetch failure does not cascade and cancel its siblings. Long-running services
     * (e.g. a foreground audio service that owns a `ServiceLocator`-shared instance)
     * SHOULD call [close] on shutdown to abort still-pending fetches; short-lived
     * tests can pass their own scope so cleanup is implicit when the test scope ends.
     */
    private val repositoryScope: CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : Closeable {

    private val protectedIds = AtomicReference<Set<String>>(emptySet())

    /**
     * P1-4: kill switch — when engaged, all NETWORK operations short-circuit
     * (catalog refresh, profile fetch). Cached/imported reads still work so
     * the user can still browse what they already had.
     */
    private val killSwitchEngaged = java.util.concurrent.atomic.AtomicBoolean(false)

    /** In-flight profile fetches keyed by profile id. */
    private val inflight = ConcurrentHashMap<String, Deferred<AutoEqProfile?>>()

    /** Expose the imported store so [com.coreline.autoeq.AutoEqApi] can subscribe to it. */
    val imports: ImportedProfileStore get() = importedStore

    /**
     * Engage / disengage the local kill switch. Engaging it instantly halts new
     * network calls (catalog refresh + profile fetch). In-flight requests
     * complete normally — caller is expected to also bypass the engine via
     * [com.coreline.audio.AudioEngine.setAutoEqEnabled]/setManualEqEnabled.
     */
    fun setKillSwitchEngaged(engaged: Boolean) {
        killSwitchEngaged.set(engaged)
    }


    /**
     * Observe the catalog as a [Flow] of [CatalogState] transitions:
     *
     * 1. If a cached catalog exists, emit `Loaded(cached)` immediately.
     *    Otherwise emit `Loading` and refresh from network.
     * 2. If the cache is stale (> 7 days), refresh in the background. On success emit a new
     *    `Loaded`. On failure with cache present, keep the existing `Loaded` (we never
     *    downgrade to `Error` while serviceable cache is in memory).
     */
    fun loadCatalog(scope: CoroutineScope): Flow<CatalogState> = callbackFlow {
        var hasServedCache = false

        val cached = catalogCache.loadCatalog()
        if (cached != null) {
            trySend(CatalogState.Loaded(cached))
            hasServedCache = true
        } else {
            trySend(CatalogState.Loading)
        }

        val needsRefresh = cached == null || catalogCache.isCatalogStale()
        val job = if (needsRefresh) {
            scope.launch(Dispatchers.IO) {
                val result = refreshCatalog()
                result.onSuccess { entries ->
                    trySend(CatalogState.Loaded(entries))
                }.onFailure { err ->
                    if (!hasServedCache) {
                        trySend(CatalogState.Error(err.message ?: "Catalog fetch failed"))
                    } else {
                        Log.w(TAG, "Background refresh failed; keeping cached catalog: ${err.message}")
                    }
                }
            }
        } else {
            null
        }

        awaitClose { job?.cancel() }
    }

    /**
     * One-shot refresh: GET INDEX.md, parse, persist, return entries.
     *
     * On HTTP / parse / IO failure the existing cache is left untouched.
     */
    suspend fun refreshCatalog(): Result<List<AutoEqCatalogEntry>> = withContext(Dispatchers.IO) {
        if (killSwitchEngaged.get()) {
            return@withContext Result.failure(IOException("kill switch engaged"))
        }
        try {
            val request = Request.Builder()
                .url(INDEX_URL)
                .get()
                .build()
            httpClient.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    val msg = "HTTP ${resp.code}"
                    telemetry.event(
                        "fetch_failed",
                        mapOf("kind" to "catalog", "http_code" to resp.code),
                    )
                    return@withContext Result.failure(IOException(msg))
                }
                val body = resp.body?.string()
                    ?: return@withContext Result.failure(IOException("Empty body"))
                val entries = IndexMdParser.parse(body)
                if (entries.isEmpty()) {
                    telemetry.event("parse_failed", mapOf("kind" to "catalog"))
                    return@withContext Result.failure(IOException("Catalog parse yielded no entries"))
                }
                catalogCache.saveCatalog(entries)
                Result.success(entries)
            }
        } catch (t: Throwable) {
            telemetry.event(
                "fetch_failed",
                mapOf("kind" to "catalog", "error" to (t::class.simpleName ?: "Throwable")),
            )
            Result.failure(t)
        }
    }

    /**
     * Resolve the full profile for [entry], coalescing duplicate concurrent requests.
     *
     * Resolution order:
     * 1. ProfileCache hit → parse, return.
     * 2. HTTP GET → parse → ProfileCache.write → return.
     *
     * Returns null on terminal failure. The caller is expected to surface this as a
     * non-fatal UX error and offer retry.
     */
    suspend fun fetchProfile(entry: AutoEqCatalogEntry): AutoEqProfile? {
        // P3-A: imports take a different code path — load directly from disk,
        // never hit network or use the LRU profile cache.
        if (importedStore.isImported(entry.id)) {
            return importedStore.load(entry.id)
        }

        // R1-4 / C3 / #5+6: Coalesce — re-use any in-flight Deferred for the same id.
        // The async runs in [repositoryScope] (a SupervisorJob + Dispatchers.IO),
        // so a single fetch failure cannot tear down sibling fetches AND every
        // in-flight call is reliably cancelled when the repository is [close]d.
        //
        // C3: cleanup is bound to the DEFERRED's lifecycle (`invokeOnCompletion`),
        // NOT each caller's `finally`. If we removed the entry from `inflight`
        // when an individual caller cancelled, the still-running `repositoryScope`
        // fetch would be orphaned — and a fresh `fetchProfile(id)` arriving a
        // moment later would `computeIfAbsent` a SECOND network call for the
        // same id, breaking coalescing. Tying removal to the deferred itself
        // (compare-and-remove via `inflight.remove(id, d)`) keeps the map entry
        // alive exactly as long as the work is alive.
        //
        // #5+6: use [CoroutineStart.LAZY] so the async block CANNOT start running
        // until we have explicitly registered [invokeOnCompletion]. With the eager
        // default, a future change to the repositoryScope dispatcher (e.g.
        // `Dispatchers.Unconfined`) could complete the deferred before our handler
        // attaches — kotlinx then fires the handler synchronously inside the
        // [computeIfAbsent] lambda, which would race against ConcurrentHashMap's
        // own bin lock. The LAZY+start() shape closes that window structurally:
        // pre-verified by `LazyDeferredBehaviorTest` (G1-G5).
        val deferred = inflight.computeIfAbsent(entry.id) {
            val d = repositoryScope.async(start = CoroutineStart.LAZY) {
                fetchProfileInternal(entry)
            }
            d.invokeOnCompletion {
                // Compare-and-remove: only drop if we're still the registered
                // deferred for this id. (Defensive — a fresh registration cannot
                // happen while we're still in the map, but the CHM contract is
                // clearer this way and costs nothing.)
                inflight.remove(entry.id, d)
            }
            // Now that the completion handler is attached, kick off the actual
            // work. If [repositoryScope] is already cancelled, [start] is a no-op
            // and the deferred transitions straight to CANCELLED — the handler
            // then fires and removes the entry. Verified by G3/G4.
            d.start()
            d
        }
        return try {
            deferred.await()
        } catch (ce: CancellationException) {
            // Distinguish two flavours of CancellationException at this site:
            //  1. The CALLER's coroutine was cancelled (their scope is no longer
            //     active). We MUST rethrow so structured concurrency unwinds
            //     correctly for them.
            //  2. The DEFERRED was cancelled out from under us (e.g. repository
            //     was [close]d while we were awaiting). Our caller is still
            //     alive and just wants a null back — surfacing CE here would be
            //     a surprising leak of internal cancellation as caller-facing.
            //
            // #7: a suspend function entry guarantees a Job in the context, but
            // we still use `?:` over `!!` so a hypothetical detached call site
            // can't NPE us — the safe default is "caller is active" (rethrow only
            // when we positively observe a cancelled caller).
            val callerActive =
                kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]?.isActive ?: true
            if (!callerActive) {
                throw ce
            }
            Log.w(TAG, "fetchProfile(${entry.id}) cancelled internally: ${ce.message}")
            null
        } catch (t: Throwable) {
            Log.w(TAG, "fetchProfile(${entry.id}) failed: ${t.message}")
            null
        }
        // No `finally { inflight.remove(...) }` here on purpose — see C3 above.
    }

    /**
     * Initialize the imported-profile store from disk. Idempotent — call once
     * at app startup before observing the catalog.
     */
    suspend fun primeImports() = importedStore.reloadFromDisk()

    /**
     * Read the contents of a SAF [android.net.Uri] and import it as a user
     * profile. Returns the [ParseResult] so the UI can surface specific errors.
     *
     * @param displayName Human-readable name (typically derived from the file's
     *        display name in SAF, e.g. "Apple AirPods Pro 2.txt" → "Apple AirPods Pro 2").
     */
    suspend fun importFromUri(uri: android.net.Uri, displayName: String): ParseResult =
        withContext<ParseResult>(Dispatchers.IO) {
            // P2-1: stream-level size cap. Reading the whole file via
            // readBytes() and then size-checking allows an attacker (or a
            // mis-picked 100MB binary) to allocate huge buffers. We pull
            // up to MAX_IMPORT_BYTES + 1 then bail.
            val text: String = try {
                val stream = context.contentResolver.openInputStream(uri)
                    ?: return@withContext ParseResult.Failure(
                        com.coreline.autoeq.model.ParseError.DecodeFailed(IOException("Could not open URI"))
                    )
                val bytes = stream.use { input ->
                    val buf = java.io.ByteArrayOutputStream(8 * 1024)
                    val chunk = ByteArray(8 * 1024)
                    var total = 0
                    while (true) {
                        val read = input.read(chunk)
                        if (read <= 0) break
                        total += read
                        if (total > MAX_IMPORT_BYTES) {
                            telemetry.event(
                                "parse_failed",
                                mapOf("kind" to "import", "reason" to "FileTooLarge"),
                            )
                            return@withContext ParseResult.Failure(
                                com.coreline.autoeq.model.ParseError.FileTooLarge(total.toLong())
                            )
                        }
                        buf.write(chunk, 0, read)
                    }
                    buf.toByteArray()
                }
                bytes.toString(Charsets.UTF_8)
            } catch (t: Throwable) {
                telemetry.event(
                    "fetch_failed",
                    mapOf("kind" to "import_uri", "error" to (t::class.simpleName ?: "Throwable")),
                )
                return@withContext ParseResult.Failure(
                    com.coreline.autoeq.model.ParseError.DecodeFailed(t)
                )
            }
            val result = importedStore.importFromText(displayName, text)
            if (result is ParseResult.Failure) {
                telemetry.event(
                    "parse_failed",
                    mapOf("kind" to "import", "reason" to result.error::class.simpleName.orEmpty()),
                )
            }
            result
        }

    /** Delete a user-imported profile by id. No-op if not imported. */
    suspend fun deleteImported(id: String) {
        if (importedStore.isImported(id)) importedStore.delete(id)
    }

    private suspend fun fetchProfileInternal(entry: AutoEqCatalogEntry): AutoEqProfile? {
        // 1. Cache.
        val cached = profileCache.read(entry.id)
        if (cached != null) {
            val parsed = ParametricEqParser.parse(
                text = cached,
                name = entry.name,
                id = entry.id,
                measuredBy = entry.measuredBy,
            )
            when (parsed) {
                is ParseResult.Success -> return parsed.profile
                is ParseResult.Failure -> {
                    // Corrupt cache — drop and re-fetch.
                    Log.w(TAG, "Cached profile ${entry.id} failed to parse; re-fetching")
                    profileCache.delete(entry.id)
                    telemetry.event(
                        "parse_failed",
                        mapOf("kind" to "profile_cache", "id" to entry.id),
                    )
                }
            }
        }

        // 2. Network — skip when the kill switch is engaged. Caller observes
        //    the resulting null and surfaces "no profile available" UX.
        if (killSwitchEngaged.get()) {
            telemetry.event("fetch_failed", mapOf("kind" to "profile", "reason" to "kill_switch"))
            return null
        }
        val url = buildProfileUrl(entry)
        val text = try {
            withContext(Dispatchers.IO) {
                val request = Request.Builder().url(url).get().build()
                httpClient.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        telemetry.event(
                            "fetch_failed",
                            mapOf(
                                "kind" to "profile",
                                "http_code" to resp.code,
                                "id" to entry.id,
                            ),
                        )
                        return@use null
                    }
                    resp.body?.string()
                }
            }
        } catch (t: Throwable) {
            telemetry.event(
                "fetch_failed",
                mapOf(
                    "kind" to "profile",
                    "id" to entry.id,
                    "error" to (t::class.simpleName ?: "Throwable"),
                ),
            )
            null
        } ?: return null

        val parsed = ParametricEqParser.parse(
            text = text,
            name = entry.name,
            id = entry.id,
            measuredBy = entry.measuredBy,
        )
        return when (parsed) {
            is ParseResult.Success -> {
                profileCache.write(entry.id, text)
                profileCache.evictIfNeeded(protectedIds.get())
                parsed.profile
            }
            is ParseResult.Failure -> {
                telemetry.event(
                    "parse_failed",
                    mapOf("kind" to "profile_network", "id" to entry.id),
                )
                null
            }
        }
    }

    /** Build the upstream URL for this catalog entry's `ParametricEQ.txt`. */
    internal fun buildProfileUrl(entry: AutoEqCatalogEntry): String {
        val path = entry.relativePath
        val lastSegment = path.substringAfterLast('/', missingDelimiterValue = entry.name)
        val fileName = "$lastSegment ParametricEQ.txt"
        // We split on '/' so that we don't percent-encode the path separators themselves.
        val encodedPath = path
            .split('/')
            .joinToString("/") { percentEncodePathSegment(it) }
        val encodedFile = percentEncodePathSegment(fileName)
        return "$PROFILE_BASE_URL$encodedPath/$encodedFile"
    }

    private fun percentEncodePathSegment(segment: String): String {
        // R2-2: produce path-segment encoding that matches the upstream Pasanen URL
        // shape served from `raw.githubusercontent.com/.../results/...`:
        //
        //   - space      → %20  (NOT '+', which is form-encoding, not path-encoding)
        //   - '+'        → %2B  (literal plus, e.g. "Audeze LCD-3+")
        //   - '(' / ')'  → kept as '(' / ')' — RFC 3986 sub-delims are valid in
        //                  path segments and INDEX.md links carry them unencoded
        //                  (e.g. ".../64%20Audio%20A12t%20(m15%20Apex%20module)").
        //   - non-ASCII  → UTF-8 percent-encoded (URLEncoder default)
        //
        // We start from URLEncoder (application/x-www-form-urlencoded), then patch
        // the few divergences from path-segment semantics.
        return URLEncoder.encode(segment, "UTF-8")
            .replace("+", "%20")
            .replace("%28", "(")
            .replace("%29", ")")
    }

    /**
     * Wipe both caches. Used by the "Clear AutoEQ cache" Settings action.
     *
     * R2-3: switched from the legacy `deleteCorrupted()` alias to the canonical
     * [CatalogCache.clear] now that it is the agreed-upon name across the codebase.
     */
    suspend fun clearCache() {
        catalogCache.clear()
        profileCache.clear()
    }

    /**
     * R1-4: cancel every in-flight profile fetch and stop accepting new ones.
     *
     * Callers in long-running services (foreground audio service, app process)
     * SHOULD invoke this on shutdown so that pending HTTP work does not survive
     * past the lifecycle that owned the repository. After [close], `fetchProfile`
     * calls will surface as `null` (the deferred terminates with
     * `CancellationException`, which the caller swallows internally).
     *
     * Idempotent — calling [close] more than once is a no-op for the scope.
     */
    override fun close() {
        repositoryScope.cancel(CancellationException("AutoEqRepository.close()"))
    }

    /**
     * Update the set of profile ids exempt from LRU eviction (current applied profile +
     * favorites). Plain assignment is safe because callers update on the main thread.
     */
    fun setProtectedIds(ids: Set<String>) {
        protectedIds.set(ids.toSet())
    }

    companion object {
        private const val TAG = "AutoEq[Repository]"

        /** Upstream INDEX.md URL. */
        const val INDEX_URL: String =
            "https://raw.githubusercontent.com/jaakkopasanen/AutoEq/master/results/INDEX.md"

        /** Base URL for `<source>/<class>/<headphone>/<headphone> ParametricEQ.txt`. */
        const val PROFILE_BASE_URL: String =
            "https://raw.githubusercontent.com/jaakkopasanen/AutoEq/master/results/"

        /**
         * P2-1: hard upper bound on imported ParametricEQ.txt size. Real AutoEq
         * profiles are 1-3 KB; 64 KiB is generous head room for hand-edited
         * variants while still bounding memory exposure on a malicious pick.
         */
        const val MAX_IMPORT_BYTES: Int = 64 * 1024

        /**
         * Default OkHttpClient with 10s connect / 30s read timeouts, redirects on, and
         * a small response cache disabled (we manage caching ourselves).
         */
        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .build()
    }
}
