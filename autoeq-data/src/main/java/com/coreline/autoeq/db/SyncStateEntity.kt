// SyncStateEntity.kt
// Phase 4: tracks whether the local catalog needs an upstream refresh.
// One row keyed by [key] (e.g. "catalog"). The ETag / content hash let us skip work when
// the remote INDEX.md is unchanged. seedVersion is retained for the legacy
// INDEX.md seed fallback; current releases normally open the prebuilt Room seed.
package com.coreline.autoeq.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "sync_state")
data class SyncStateEntity(
    @PrimaryKey val key: String,
    /** HTTP ETag of the last successfully imported INDEX.md (for If-None-Match). */
    val etag: String? = null,
    /** SHA-256 of the last imported INDEX.md body (defensive change detection). */
    val contentSha256: String? = null,
    /** Version of the legacy bundled INDEX.md seed that has been imported into the DB. */
    val seedVersion: Int = 0,
    /** Epoch millis of the last successful sync (network or seed). */
    val lastSyncAtMs: Long = 0L,
    /** Free-form last status, e.g. "seed", "network", "not_modified", "failed:...". */
    val status: String? = null,
) {
    companion object {
        const val KEY_CATALOG = "catalog"
    }
}
