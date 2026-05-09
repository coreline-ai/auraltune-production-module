// ImportedProfileStore.kt
// Phase 3 finalization — local storage for user-imported ParametricEQ.txt
// profiles. Separate from ProfileCache (which stores network-fetched fixtures
// behind an LRU policy). Imports are user-owned and never auto-evicted.
package com.coreline.autoeq.cache

import com.coreline.autoeq.model.AutoEqCatalogEntry
import com.coreline.autoeq.model.AutoEqProfile
import com.coreline.autoeq.model.AutoEqSource
import com.coreline.autoeq.model.CatalogIdGenerator
import com.coreline.autoeq.model.ParseResult
import com.coreline.autoeq.parser.ParametricEqParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest

/**
 * Persists user-imported AutoEQ profiles. Layout:
 *
 *   <rootDir>/imported/
 *       <id>.txt        ← original ParametricEQ.txt content (UTF-8)
 *       <id>.meta.json  ← [ImportedMeta] (display name + import time)
 *
 * IDs are content-addressed (`imp-<24-hex-of-sha256(text|name)>`) so the same
 * file imported twice resolves to the same ID — duplicates are deduped at the
 * filesystem layer.
 *
 * Crash safety: each file is written via a sibling `<name>.tmp` and then
 * renamed into place. The pair (txt + meta) is written under a single mutex,
 * txt first, so on reload, presence of `<id>.meta.json` implies presence of
 * `<id>.txt`. [reloadFromDisk] sweeps stray `.tmp` files and orphan halves
 * left behind by a kill mid-import.
 */
class ImportedProfileStore(rootDir: File) {

    private val dir = File(rootDir, "imported").apply { mkdirs() }
    private val mutex = Mutex()

    /** Reactive stream of currently-imported entries. Updated on import/delete. */
    private val _entries = MutableStateFlow<List<AutoEqCatalogEntry>>(emptyList())
    val entries: StateFlow<List<AutoEqCatalogEntry>> = _entries.asStateFlow()

    private val json: Json = Json { ignoreUnknownKeys = true }

    /**
     * Initialize the in-memory list from disk. Call once at app startup.
     *
     * Also performs crash-recovery housekeeping:
     *   - deletes any `*.tmp` left over from a kill mid-write
     *   - deletes orphan `<id>.txt` (no matching `<id>.meta.json`)
     *   - deletes orphan `<id>.meta.json` (no matching `<id>.txt`)
     */
    suspend fun reloadFromDisk() = withContext(Dispatchers.IO) {
        mutex.withLock {
            sweepCrashRemnants()
            _entries.value = listFromDisk()
        }
    }

    /**
     * Import a ParametricEQ.txt blob with a user-supplied display name (usually
     * derived from the filename). On parse failure, returns the parse error
     * without touching disk.
     */
    suspend fun importFromText(name: String, text: String): ParseResult =
        withContext(Dispatchers.IO) {
            val cleanName = name.ifBlank { "Imported profile" }
            val id = generateId(cleanName, text)
            val parseResult = ParametricEqParser.parse(
                text = text,
                name = cleanName,
                id = id,
                measuredBy = "Imported",
                source = AutoEqSource.IMPORTED,
            )
            if (parseResult is ParseResult.Success) {
                mutex.withLock {
                    // Write txt first (larger payload). On reload, presence of meta
                    // implies presence of txt — so meta must land last.
                    writeAtomic(File(dir, "$id.txt"), text)
                    writeAtomic(
                        File(dir, "$id.meta.json"),
                        json.encodeToString(
                            ImportedMeta.serializer(),
                            ImportedMeta(
                                id = id,
                                name = cleanName,
                                importedAtMs = System.currentTimeMillis(),
                            ),
                        ),
                    )
                    _entries.value = listFromDisk()
                }
            }
            parseResult
        }

    /** Read back a profile by id. Returns null if the file or parse fails. */
    suspend fun load(id: String): AutoEqProfile? = withContext(Dispatchers.IO) {
        mutex.withLock {
            val txt = File(dir, "$id.txt").takeIf { it.exists() }?.readText(Charsets.UTF_8)
                ?: return@withLock null
            val meta = readMeta(id) ?: return@withLock null
            val result = ParametricEqParser.parse(
                text = txt,
                name = meta.name,
                id = meta.id,
                measuredBy = "Imported",
                source = AutoEqSource.IMPORTED,
            )
            (result as? ParseResult.Success)?.profile
        }
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            File(dir, "$id.txt").delete()
            File(dir, "$id.meta.json").delete()
            _entries.value = listFromDisk()
        }
    }

    /** True if the given entry id was produced by this store. */
    fun isImported(id: String): Boolean = id.startsWith(IMPORTED_PREFIX)

    /**
     * Atomic write: dump to `<target>.tmp`, then rename. POSIX rename within the
     * same filesystem is atomic, so a reader either sees the old file or the new
     * one — never a partial write. If the rename fails (extremely unlikely under
     * `filesDir`, which is single-volume), fall back to a direct write so the
     * import still succeeds.
     */
    private fun writeAtomic(target: File, content: String) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeText(content, Charsets.UTF_8)
        if (!tmp.renameTo(target)) {
            // Cross-volume or other rename failure — fall back to non-atomic write.
            target.writeText(content, Charsets.UTF_8)
            tmp.delete()
        }
    }

    /**
     * Delete crash-residue files so [listFromDisk] sees a clean directory:
     *   - `*.tmp` from a kill between `tmp.writeText` and `tmp.renameTo`
     *   - `<id>.txt` with no `<id>.meta.json` (kill between txt and meta writes)
     *   - `<id>.meta.json` with no `<id>.txt` (shouldn't happen given write order,
     *     but cheap to defend against — e.g. external file deletion).
     *
     * Caller must hold [mutex].
     */
    private fun sweepCrashRemnants() {
        val files = dir.listFiles() ?: return

        // 1. Stray .tmp files from a crashed writeAtomic.
        for (f in files) {
            if (f.name.endsWith(".tmp")) {
                f.delete()
            }
        }

        // 2/3. Orphan halves. Re-list since .tmp files were just removed.
        val remaining = dir.listFiles() ?: return
        val txtIds = remaining
            .asSequence()
            .filter { it.name.endsWith(".txt") }
            .map { it.name.removeSuffix(".txt") }
            .toHashSet()
        val metaIds = remaining
            .asSequence()
            .filter { it.name.endsWith(".meta.json") }
            .map { it.name.removeSuffix(".meta.json") }
            .toHashSet()

        for (id in txtIds) {
            if (id !in metaIds) {
                File(dir, "$id.txt").delete()
            }
        }
        for (id in metaIds) {
            if (id !in txtIds) {
                File(dir, "$id.meta.json").delete()
            }
        }
    }

    private fun listFromDisk(): List<AutoEqCatalogEntry> {
        val metaFiles = dir.listFiles { f -> f.name.endsWith(".meta.json") } ?: return emptyList()
        return metaFiles.mapNotNull { f ->
            val raw = runCatching { f.readText() }.getOrNull() ?: return@mapNotNull null
            val meta = runCatching { json.decodeFromString(ImportedMeta.serializer(), raw) }
                .getOrNull() ?: return@mapNotNull null
            AutoEqCatalogEntry(
                id = meta.id,
                name = meta.name,
                measuredBy = "Imported",
                relativePath = "", // not on github
            )
        }.sortedBy { it.name.lowercase() }
    }

    private fun readMeta(id: String): ImportedMeta? = runCatching {
        json.decodeFromString(
            ImportedMeta.serializer(),
            File(dir, "$id.meta.json").readText(),
        )
    }.getOrNull()

    private fun generateId(name: String, text: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val bytes = md.digest("$name|$text".toByteArray(Charsets.UTF_8))
        val hex = StringBuilder(48)
        for (i in 0 until 12) hex.append("%02x".format(bytes[i]))
        return IMPORTED_PREFIX + hex
    }

    @Serializable
    private data class ImportedMeta(
        val id: String,
        val name: String,
        val importedAtMs: Long,
    )

    companion object {
        const val IMPORTED_PREFIX = "imp-"
    }
}
