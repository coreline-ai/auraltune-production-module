package com.coreline.autoeq.cache

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * LRU disk cache for fetched `ParametricEQ.txt` payloads.
 *
 * Layout:
 * ```
 * <rootDir>/fetched/<id>.txt        // profile body
 * <rootDir>/access_log.json         // {id → epochMs of last access}
 * ```
 *
 * Eviction policy (Phase 4):
 * - Soft cap: at most [MAX_ENTRIES] entries OR [MAX_BYTES_TOTAL] total bytes (whichever
 *   triggers first).
 * - Protected ids — typically the currently-applied profile plus user favorites — are
 *   never evicted, even if the cache exceeds the cap.
 * - When evicting, sort non-protected entries by ascending `lastAccess` and delete until
 *   both bounds are satisfied.
 *
 * Concurrency (R1-2):
 * - All public methods acquire a single shared [Mutex] for their *entire* body, so that
 *   the file I/O itself (read body, atomic tmp+rename write, delete, directory wipe,
 *   eviction sweep) is serialized along with the access-log update. Without this, two
 *   concurrent `write(id, ...)` calls on the same id could race on the rename, and a
 *   `read(id)` overlapping `delete(id)` could observe a half-deleted file.
 * - The mutex is fair-ish (Kotlin's [Mutex] grants in suspension order); per-id locking
 *   would scale better but the cache is small and write traffic is bursty, so a single
 *   mutex is simpler and correct.
 * - File writes additionally go through a per-id tmp file (`<id>.txt.tmp`) and `renameTo`
 *   the final name, so even if the mutex is ever relaxed in the future, two writers on
 *   different ids never share a tmp path. This is defense in depth on top of the lock.
 */
class ProfileCache(private val rootDir: File) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val mutex = Mutex()

    /**
     * Load the cached profile body for [id], or null if no cached copy exists.
     * Touches the access timestamp so a recently-read entry is preferred during eviction.
     */
    suspend fun read(id: String): String? = withContext(Dispatchers.IO) {
        mutex.withLock {
            val file = profileFile(id)
            if (!file.exists()) return@withLock null
            val text = try {
                file.readText(Charsets.UTF_8)
            } catch (t: Throwable) {
                Log.w(TAG, "Profile cache read failed for $id: ${t.message}")
                return@withLock null
            }
            touchLocked(id)
            text
        }
    }

    /**
     * Persist [text] under [id]. Atomic write via per-id `.tmp` rename. Updates the
     * access log. The full method is mutex-guarded so concurrent writers on the same id
     * never race on the rename step.
     */
    suspend fun write(id: String, text: String): Unit = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                fetchedDir().mkdirs()
                val target = profileFile(id)
                // Per-id tmp file: even if the mutex is ever loosened, writes to *different*
                // ids cannot collide on the tmp path. Same-id writers are still serialized
                // by the surrounding mutex.
                val tmp = File(target.parentFile, target.name + ".tmp")
                tmp.outputStream().use { os ->
                    os.write(text.toByteArray(Charsets.UTF_8))
                    os.flush()
                    os.fd.sync()
                }
                if (target.exists()) target.delete()
                if (!tmp.renameTo(target)) {
                    tmp.copyTo(target, overwrite = true)
                    tmp.delete()
                }
                // Belt-and-braces: in case rename succeeded but a stale tmp lingers from a
                // prior crash, ensure we don't leave a tmp in the cache dir.
                if (tmp.exists()) tmp.delete()
                touchLocked(id)
            } catch (t: Throwable) {
                Log.w(TAG, "Profile cache write failed for $id: ${t.message}")
            }
        }
    }

    /** Delete a single cache entry. No-op if absent. */
    suspend fun delete(id: String): Unit = withContext(Dispatchers.IO) {
        mutex.withLock {
            runCatching { profileFile(id).delete() }
            val log = readAccessLogLocked().toMutableMap()
            if (log.remove(id) != null) writeAccessLogLocked(log)
        }
    }

    /** Delete every cached profile. The access log is also cleared. */
    suspend fun clear(): Unit = withContext(Dispatchers.IO) {
        mutex.withLock {
            runCatching {
                fetchedDir().listFiles()?.forEach { it.delete() }
                accessLogFile().delete()
            }
        }
    }

    /**
     * Run the LRU eviction sweep against the current cap.
     *
     * @param protectedIds ids to keep regardless of LRU order — typically the currently
     *   applied profile plus user favorites.
     */
    suspend fun evictIfNeeded(protectedIds: Set<String>): Unit = withContext(Dispatchers.IO) {
        mutex.withLock {
            val dir = fetchedDir()
            if (!dir.exists()) return@withLock

            val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".txt") } ?: return@withLock
            val totalCount = files.size
            val totalBytes = files.sumOf { it.length() }

            if (totalCount <= MAX_ENTRIES && totalBytes <= MAX_BYTES_TOTAL) return@withLock

            val log = readAccessLogLocked()
            val now = System.currentTimeMillis()

            // Evictable = non-protected. Sort by ascending access time, missing log entries
            // are treated as ancient (= 0L) so untouched files are evicted first.
            data class Candidate(val id: String, val file: File, val size: Long, val lastAccess: Long)

            val candidates = files
                .map { f ->
                    val id = f.nameWithoutExtension
                    Candidate(
                        id = id,
                        file = f,
                        size = f.length(),
                        lastAccess = log[id] ?: 0L,
                    )
                }
                .filter { it.id !in protectedIds }
                .sortedBy { it.lastAccess }

            var liveCount = totalCount
            var liveBytes = totalBytes
            val updatedLog = log.toMutableMap()

            for (cand in candidates) {
                if (liveCount <= MAX_ENTRIES && liveBytes <= MAX_BYTES_TOTAL) break
                if (cand.file.delete()) {
                    updatedLog.remove(cand.id)
                    liveCount -= 1
                    liveBytes -= cand.size
                    Log.d(
                        TAG,
                        "Evicted ${cand.id} (lastAccess=${cand.lastAccess}, " +
                            "ageMs=${now - cand.lastAccess})",
                    )
                }
            }

            writeAccessLogLocked(updatedLog)
        }
    }

    // ---- internals ----

    private fun fetchedDir(): File = File(rootDir, "fetched")

    private fun profileFile(id: String): File = File(fetchedDir(), "$id.txt")

    private fun accessLogFile(): File = File(rootDir, "access_log.json")

    /** mutex must be held. */
    private fun touchLocked(id: String) {
        try {
            val log = readAccessLogLocked().toMutableMap()
            log[id] = System.currentTimeMillis()
            writeAccessLogLocked(log)
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to touch access log for $id: ${t.message}")
        }
    }

    /** mutex must be held. */
    private fun readAccessLogLocked(): Map<String, Long> {
        val f = accessLogFile()
        if (!f.exists() || f.length() == 0L) return emptyMap()
        return try {
            json.decodeFromString(
                MapSerializer(String.serializer(), Long.serializer()),
                f.readText(Charsets.UTF_8),
            )
        } catch (t: Throwable) {
            Log.w(TAG, "Access log corrupt; resetting: ${t.message}")
            f.delete()
            emptyMap()
        }
    }

    /** mutex must be held. */
    private fun writeAccessLogLocked(map: Map<String, Long>) {
        try {
            rootDir.mkdirs()
            val target = accessLogFile()
            val tmp = File(target.parentFile, target.name + ".tmp")
            tmp.outputStream().use { os ->
                os.write(
                    json.encodeToString(
                        MapSerializer(String.serializer(), Long.serializer()),
                        map,
                    ).toByteArray(Charsets.UTF_8),
                )
                os.flush()
                os.fd.sync()
            }
            if (target.exists()) target.delete()
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Access log write failed: ${t.message}")
        }
    }

    companion object {
        private const val TAG = "AutoEq[ProfileCache]"

        /** LRU soft cap on number of cached profiles. */
        const val MAX_ENTRIES: Int = 200

        /** LRU soft cap on total cache size in bytes (5 MB). */
        const val MAX_BYTES_TOTAL: Long = 5L * 1024L * 1024L
    }
}
