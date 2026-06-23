// ProfileDao.kt
// Phase 5: data access for DB-stored profiles + filters.
package com.coreline.autoeq.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert

@Dao
interface ProfileDao {

    @Transaction
    @Query("SELECT * FROM profiles WHERE id = :id LIMIT 1")
    suspend fun getProfile(id: String): ProfileWithFilters?

    @Query("UPDATE profiles SET lastAccessMs = :nowMs WHERE id = :id")
    suspend fun touch(id: String, nowMs: Long)

    @Query("SELECT COUNT(*) FROM profiles")
    suspend fun count(): Int

    @Upsert
    suspend fun upsertProfile(profile: ProfileEntity)

    @Query("DELETE FROM profile_filters WHERE profileId = :id")
    suspend fun deleteFilters(id: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFilters(filters: List<ProfileFilterEntity>)

    /** Atomic replace: header upsert + full filter-set swap (source hash changed, etc.). */
    @Transaction
    suspend fun replaceProfile(profile: ProfileEntity, filters: List<ProfileFilterEntity>) {
        upsertProfile(profile)
        deleteFilters(profile.id)
        insertFilters(filters)
    }

    @Query("DELETE FROM profiles WHERE id = :id")
    suspend fun deleteProfile(id: String)

    /**
     * LRU eviction candidates: oldest-accessed profile ids beyond [keep], excluding
     * [protectedIds] (currently applied + favorites). FK cascade removes their filters.
     */
    @Query(
        """
        SELECT id FROM profiles
        WHERE id NOT IN (:protectedIds)
        ORDER BY lastAccessMs DESC
        LIMIT 1000 OFFSET :keep
        """,
    )
    suspend fun evictionCandidates(protectedIds: Collection<String>, keep: Int): List<String>

    @Query("DELETE FROM profiles WHERE id IN (:ids)")
    suspend fun deleteProfiles(ids: List<String>)
}
