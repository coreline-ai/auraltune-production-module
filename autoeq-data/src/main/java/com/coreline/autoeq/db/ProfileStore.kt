// ProfileStore.kt
// Phase 5: DB-first profile storage on top of [ProfileDao]. Stores only parsed header+filters
// (no raw text). Reads touch lastAccessMs for LRU; writes replace the filter set atomically.
package com.coreline.autoeq.db

import com.coreline.autoeq.model.AutoEqProfile
import com.coreline.autoeq.model.AutoEqSource

class ProfileStore(private val dao: ProfileDao) {

    /** DB hit → domain profile (and bump LRU timestamp). Null when absent. */
    suspend fun read(id: String, nowMs: Long): AutoEqProfile? {
        val stored = dao.getProfile(id) ?: return null
        dao.touch(id, nowMs)
        return stored.toDomain()
    }

    /** Persist a freshly parsed profile (header + full filter-set replace). */
    suspend fun upsert(
        profile: AutoEqProfile,
        sourceUrl: String?,
        sourceSha256: String?,
        nowMs: Long,
    ) {
        dao.replaceProfile(
            profile = profile.toEntity(sourceUrl, sourceSha256, nowMs),
            filters = profile.toFilterEntities(),
        )
    }

    /** Remove a profile (filters cascade-delete). Used by delta sync on upstream removal. */
    suspend fun delete(id: String) = dao.deleteProfile(id)

    /**
     * Full remote resync invalidates APK-bundled/fetched rows so later profile resolves
     * fetch current upstream data on demand. User-imported profiles are kept.
     */
    suspend fun deleteNonImportedProfiles(): Int =
        dao.deleteProfilesExceptSource(AutoEqSource.IMPORTED.name)

    /**
     * LRU trim: keep the [keep] most-recently-accessed profiles plus everything in
     * [protectedIds] (currently applied + favorites). Filters cascade-delete with their header.
     */
    suspend fun evictIfNeeded(protectedIds: Set<String>, keep: Int = MAX_PROFILES) {
        if (dao.count() <= keep) return
        val victims = dao.evictionCandidates(protectedIds, keep)
        if (victims.isNotEmpty()) dao.deleteProfiles(victims)
    }

    companion object {
        /** Soft cap on stored fetched profiles (excl. protected). */
        const val MAX_PROFILES = 200
    }
}
