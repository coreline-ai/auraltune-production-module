package com.coreline.autoeq.cache

import android.util.Log
import com.coreline.autoeq.model.AutoEqCatalogEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Disk cache for the parsed catalog (JSON of [AutoEqCatalogEntry]).
 *
 * Layout: `<rootDir>/catalog.json`
 *
 * - Writes are atomic: write to `catalog.json.tmp`, then rename.
 * - Reads return null on missing file or JSON decode failure (after deleting the corrupt file).
 * - Staleness is checked against the file's `lastModified` timestamp.
 * - All public methods that touch the filesystem are guarded by a single [Mutex] so concurrent
 *   coroutines cannot race on `catalog.json` / `catalog.json.tmp` and corrupt the cache.
 *
 * The `rootDir` is typically `context.filesDir/autoeq` and is shared with [ProfileCache].
 */
class CatalogCache(private val rootDir: File) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /**
     * Single mutex serialising every filesystem-touching public method on this cache.
     *
     * Rationale: writes go through a `.tmp` rename dance, which is not safe under concurrent
     * `saveCatalog` calls — two writers would collide on the same temp path. Loads / staleness
     * checks must also see a consistent view rather than a half-written file. A coroutine
     * `Mutex` is preferred over `ReentrantLock` because every public method here is already
     * `suspend` (see `isCatalogStale` change below).
     */
    private val mutex = Mutex()

    /**
     * Read the cached catalog from disk.
     *
     * @return Decoded entries, or `null` if the cache file is missing, empty, or corrupt.
     *   Corrupt files are deleted as a side effect so the next refresh starts clean.
     */
    suspend fun loadCatalog(): List<AutoEqCatalogEntry>? = mutex.withLock {
        withContext(Dispatchers.IO) {
            val file = catalogFile()
            if (!file.exists() || file.length() == 0L) return@withContext null
            try {
                val text = file.readText(Charsets.UTF_8)
                json.decodeFromString(
                    kotlinx.serialization.builtins.ListSerializer(AutoEqCatalogEntry.serializer()),
                    text,
                )
            } catch (t: Throwable) {
                Log.w(TAG, "Catalog cache decode failed; deleting corrupt file: ${t.message}")
                runCatching { file.delete() }
                null
            }
        }
    }

    /**
     * Atomically persist the catalog. Writes to a sibling `.tmp` file then renames.
     * Failures are logged but never thrown — the catalog cache is a best-effort optimization.
     *
     * Concurrency: serialised against other [saveCatalog] / [loadCatalog] / [clear] /
     * [isCatalogStale] calls via [mutex] so the rename target is never observed half-written.
     */
    suspend fun saveCatalog(entries: List<AutoEqCatalogEntry>): Unit = mutex.withLock {
        withContext(Dispatchers.IO) {
            try {
                rootDir.mkdirs()
                val target = catalogFile()
                val tmp = File(target.parentFile, target.name + ".tmp")
                // Defensive: a stale tmp from a crashed prior write must not be reused as-is
                // — we always overwrite it below before renaming, but clear here to keep the
                // post-condition that tmp only ever holds bytes from THIS save.
                if (tmp.exists()) tmp.delete()
                tmp.outputStream().use { os ->
                    os.write(
                        json.encodeToString(
                            kotlinx.serialization.builtins.ListSerializer(AutoEqCatalogEntry.serializer()),
                            entries,
                        ).toByteArray(Charsets.UTF_8),
                    )
                    os.flush()
                    os.fd.sync()
                }
                if (target.exists()) target.delete()
                if (!tmp.renameTo(target)) {
                    Log.w(TAG, "Atomic rename failed; falling back to copy")
                    tmp.copyTo(target, overwrite = true)
                    tmp.delete()
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Catalog cache save failed: ${t.message}")
            }
        }
    }

    /**
     * @return true if the catalog file is missing or older than [ttlMillis]. The default
     *   policy is 7 days (matching the macOS implementation).
     *
     * Suspending so the [mutex] can serialise the stat call against an in-flight save —
     * otherwise we could observe `lastModified` of a temp/partial file mid-rename.
     */
    suspend fun isCatalogStale(ttlMillis: Long = DEFAULT_TTL_MS): Boolean = mutex.withLock {
        withContext(Dispatchers.IO) {
            val file = catalogFile()
            if (!file.exists()) return@withContext true
            val age = System.currentTimeMillis() - file.lastModified()
            age > ttlMillis
        }
    }

    /** Path of the on-disk catalog file. */
    fun catalogFile(): File = File(rootDir, "catalog.json")

    /**
     * Delete the catalog cache (both the live file and any leftover `.tmp`). Idempotent.
     *
     * Caller policy decides when this fires — e.g. a manual user "Clear cache" action or
     * corruption recovery driven by telemetry-detected JSON faults. The cache itself does
     * not hold an opinion about whether the file *should* be deleted.
     */
    suspend fun clear() = mutex.withLock {
        withContext(Dispatchers.IO) {
            runCatching {
                val f = catalogFile()
                if (f.exists()) f.delete()
                val tmp = File(f.parentFile, f.name + ".tmp")
                if (tmp.exists()) tmp.delete()
            }
            Unit
        }
    }

    /**
     * Backwards-compatible alias for [clear]. The original name implied "only delete if
     * corrupt" but the implementation was always unconditional — kept only so existing
     * callers (e.g. `AutoEqRepository.clearCache()`) continue to compile while they migrate.
     */
    @Deprecated("Use clear()", ReplaceWith("clear()"))
    suspend fun deleteCorrupted(): Unit = clear()

    companion object {
        private const val TAG = "AutoEq[CatalogCache]"
        const val DEFAULT_TTL_MS: Long = 7L * 24L * 60L * 60L * 1000L
    }
}
