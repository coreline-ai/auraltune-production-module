// OpraDao.kt
// Phase 3 — data access for the OPRA local DB.
package com.coreline.auraltune.opra.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface OpraDao {

    @Query("SELECT COUNT(*) FROM opra_catalog_entries")
    suspend fun catalogCount(): Int

    @Query("SELECT * FROM opra_catalog_entries ORDER BY displayName COLLATE NOCASE ASC")
    fun observeCatalog(): Flow<List<OpraCatalogEntryEntity>>

    @Query(
        "SELECT * FROM opra_catalog_entries WHERE searchText LIKE '%' || :q || '%' ESCAPE '\\' " +
            "ORDER BY displayName COLLATE NOCASE ASC LIMIT :limit",
    )
    suspend fun search(q: String, limit: Int): List<OpraCatalogEntryEntity>

    @Query("SELECT * FROM opra_eq_profiles WHERE id = :id LIMIT 1")
    suspend fun profile(id: String): OpraEqProfileEntity?

    @Query("SELECT * FROM opra_eq_filters WHERE profileId = :profileId ORDER BY position ASC")
    suspend fun filters(profileId: String): List<OpraEqFilterEntity>

    @Upsert
    suspend fun upsertCatalog(rows: List<OpraCatalogEntryEntity>)

    @Upsert
    suspend fun upsertProfiles(rows: List<OpraEqProfileEntity>)

    @Upsert
    suspend fun upsertFilters(rows: List<OpraEqFilterEntity>)

    // Full-replace clears (filters cascade-deleted via the profiles FK).
    @Query("DELETE FROM opra_eq_profiles")
    suspend fun clearProfiles()

    @Query("DELETE FROM opra_catalog_entries")
    suspend fun clearCatalog()

    @Query("SELECT * FROM opra_sync_state WHERE `key` = :key LIMIT 1")
    suspend fun syncState(key: String): OpraSyncStateEntity?

    @Upsert
    suspend fun putSyncState(state: OpraSyncStateEntity)
}
