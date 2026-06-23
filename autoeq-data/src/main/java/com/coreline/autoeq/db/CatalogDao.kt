// CatalogDao.kt
// Phase 4: data access for the DB-first AutoEQ catalog.
package com.coreline.autoeq.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

@Dao
interface CatalogDao {

    @Query("SELECT COUNT(*) FROM catalog_entries WHERE isDeleted = 0")
    suspend fun count(): Int

    /** All non-deleted entries — fed into the in-memory fuzzy search index. */
    @Query("SELECT * FROM catalog_entries WHERE isDeleted = 0 ORDER BY name COLLATE NOCASE ASC")
    suspend fun getAll(): List<CatalogEntryEntity>

    @Upsert
    suspend fun upsertAll(entries: List<CatalogEntryEntity>)

    /**
     * Tombstone sweep (Phase 4b): after a FULL upstream sync upserts every current entry
     * with `lastSeenAtMs = syncMs`, mark any still-live row NOT touched this sync as deleted.
     * Returns the number of rows tombstoned. Reappearing entries are un-deleted by the upsert
     * (toEntity sets isDeleted=false), so this only hides entries genuinely removed upstream.
     */
    @Query("UPDATE catalog_entries SET isDeleted = 1 WHERE lastSeenAtMs < :syncMs AND isDeleted = 0")
    suspend fun tombstoneOlderThan(syncMs: Long): Int

    @Query("SELECT * FROM sync_state WHERE `key` = :key LIMIT 1")
    suspend fun syncState(key: String): SyncStateEntity?

    @Upsert
    suspend fun putSyncState(state: SyncStateEntity)
}
