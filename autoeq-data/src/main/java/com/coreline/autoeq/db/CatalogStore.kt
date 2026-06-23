// CatalogStore.kt
// Phase 4: DB-first catalog orchestration on top of [CatalogDao].
//
// Responsibilities:
//   - First-run offline SEED from the bundled assets/autoeq/INDEX.md (parsed via IndexMdParser).
//   - One-shot MIGRATION of a legacy catalog.json list into the DB.
//   - Serve the catalog from the DB (mapped to domain models).
//   - Apply a remote refresh (upsert + ETag/content-hash bookkeeping).
//
// The store holds NO network logic; AutoEqRepository owns HTTP and hands parsed results here.
package com.coreline.autoeq.db

import android.content.res.AssetManager
import com.coreline.autoeq.model.AutoEqCatalogEntry
import com.coreline.autoeq.parser.IndexMdParser
import java.security.MessageDigest

class CatalogStore(
    private val dao: CatalogDao,
    private val assets: AssetManager,
    private val seedAssetPath: String = "autoeq/INDEX.md",
    private val seedVersion: Int = SEED_VERSION,
) {

    suspend fun count(): Int = dao.count()

    suspend fun loadFromDb(): List<AutoEqCatalogEntry> = dao.getAll().map { it.toDomain() }

    suspend fun syncState(): SyncStateEntity? = dao.syncState(SyncStateEntity.KEY_CATALOG)

    /**
     * Seed the DB from the bundled INDEX.md on first run, or when the bundled [seedVersion]
     * is newer than what was imported. Returns true if a seed was applied.
     */
    suspend fun seedIfNeeded(nowMs: Long): Boolean {
        val state = dao.syncState(SyncStateEntity.KEY_CATALOG)
        val alreadySeeded = (state?.seedVersion ?: 0) >= seedVersion
        if (dao.count() > 0 && alreadySeeded) return false

        val text = runCatching {
            assets.open(seedAssetPath).bufferedReader(Charsets.UTF_8).use { it.readText() }
        }.getOrNull() ?: return false
        val entries = IndexMdParser.parse(text)
        if (entries.isEmpty()) return false

        dao.upsertAll(entries.map { it.toEntity(nowMs) })
        dao.putSyncState(
            (state ?: SyncStateEntity(SyncStateEntity.KEY_CATALOG)).copy(
                seedVersion = seedVersion,
                contentSha256 = sha256(text),
                lastSyncAtMs = nowMs,
                status = "seed",
            ),
        )
        return true
    }

    /** One-shot import of a legacy list (e.g. catalog.json) when the DB is still empty. */
    suspend fun importLegacyIfEmpty(entries: List<AutoEqCatalogEntry>, nowMs: Long): Boolean {
        if (dao.count() > 0 || entries.isEmpty()) return false
        dao.upsertAll(entries.map { it.toEntity(nowMs) })
        val state = dao.syncState(SyncStateEntity.KEY_CATALOG) ?: SyncStateEntity(SyncStateEntity.KEY_CATALOG)
        dao.putSyncState(state.copy(lastSyncAtMs = nowMs, status = "migrated"))
        return true
    }

    /**
     * Apply a freshly fetched FULL catalog: upsert all entries (stamping lastSeenAtMs=[nowMs]),
     * tombstone entries removed upstream, and record ETag/content hash. [entries] MUST be the
     * complete upstream index (INDEX.md), otherwise the tombstone sweep would hide live rows.
     *
     * @return number of entries tombstoned (removed upstream since the last sync).
     */
    suspend fun applyRemote(
        entries: List<AutoEqCatalogEntry>,
        nowMs: Long,
        etag: String?,
        contentSha256: String?,
    ): Int {
        if (entries.isEmpty()) return 0
        dao.upsertAll(entries.map { it.toEntity(nowMs) })
        val tombstoned = dao.tombstoneOlderThan(nowMs)
        val state = dao.syncState(SyncStateEntity.KEY_CATALOG) ?: SyncStateEntity(SyncStateEntity.KEY_CATALOG)
        dao.putSyncState(
            state.copy(
                etag = etag ?: state.etag,
                contentSha256 = contentSha256 ?: state.contentSha256,
                lastSyncAtMs = nowMs,
                status = "network",
            ),
        )
        return tombstoned
    }

    /** AutoEq git commit the local data corresponds to (for delta sync). Stored in a separate
     *  sync_state row's [SyncStateEntity.etag] field — no schema change needed. */
    suspend fun autoEqCommit(): String? = dao.syncState(KEY_COMMIT)?.etag

    suspend fun setAutoEqCommit(commit: String, nowMs: Long) {
        val s = dao.syncState(KEY_COMMIT) ?: SyncStateEntity(KEY_COMMIT)
        dao.putSyncState(s.copy(etag = commit, lastSyncAtMs = nowMs, status = "delta"))
    }

    suspend fun markNotModified(nowMs: Long) {
        val state = dao.syncState(SyncStateEntity.KEY_CATALOG) ?: SyncStateEntity(SyncStateEntity.KEY_CATALOG)
        dao.putSyncState(state.copy(lastSyncAtMs = nowMs, status = "not_modified"))
    }

    companion object {
        /** Bump when the bundled INDEX.md asset is refreshed to force a re-seed. */
        const val SEED_VERSION = 1
        /** sync_state row key holding the AutoEq git commit (delta-sync base). */
        const val KEY_COMMIT = "autoeq_commit"

        fun sha256(text: String): String {
            val bytes = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            return buildString(bytes.size * 2) { bytes.forEach { append("%02x".format(it)) } }
        }
    }
}
