// OpraRoomStore.kt
// Phase 3 — Room-backed OpraStore. The full-snapshot import is a single transaction so a failure
// rolls back and the previous cache is retained (refresh fallback).
package com.coreline.auraltune.opra.db

import androidx.room.withTransaction
import com.coreline.auraltune.opra.OpraParseResult
import com.coreline.auraltune.opra.OpraStore
import com.coreline.auraltune.opra.model.OpraCatalogEntry
import com.coreline.auraltune.opra.model.OpraEqProfile
import com.coreline.auraltune.opra.model.OpraSyncState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class OpraRoomStore(private val db: OpraDatabase) : OpraStore {

    private val dao = db.opraDao()

    suspend fun count(): Int = dao.catalogCount()

    override fun observeCatalog(): Flow<List<OpraCatalogEntry>> =
        dao.observeCatalog().map { rows -> rows.map { it.toDomain() } }

    override suspend fun search(query: String, limit: Int): List<OpraCatalogEntry> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        return dao.search(q, limit).map { it.toDomain() }
    }

    override suspend fun resolve(profileId: String): OpraEqProfile? {
        val p = dao.profile(profileId) ?: return null
        val filters = dao.filters(profileId).map { it.toDomain() }
        return p.toDomain(filters)
    }

    /** Full replace in one transaction: clear, then insert profiles -> filters -> catalog. */
    override suspend fun upsert(result: OpraParseResult, nowMs: Long) {
        val profiles = result.profiles.map { it.toEntity() }
        val filters = result.profiles.flatMap { it.toFilterEntities() }
        val catalog = result.catalogEntries.map { it.toEntity() }
        db.withTransaction {
            dao.clearProfiles() // cascade-clears filters
            dao.clearCatalog()
            dao.upsertProfiles(profiles)
            dao.upsertFilters(filters)
            dao.upsertCatalog(catalog)
        }
    }

    override suspend fun syncState(): OpraSyncState? = dao.syncState(KEY)?.toDomain()

    override suspend fun setSyncState(state: OpraSyncState) {
        dao.putSyncState(state.toEntity(KEY))
    }

    companion object {
        private const val KEY = "catalog"
    }
}
