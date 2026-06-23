// CatalogEntryEntity.kt
// Phase 4 (dev-plan 110525): Room entity for one AutoEQ catalog entry.
// Mirrors com.coreline.autoeq.model.AutoEqCatalogEntry plus search/sync metadata. Profile
// FILTER data is NOT stored here (that is Phase 5, a separate table) — this is the search index.
package com.coreline.autoeq.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.coreline.autoeq.model.AutoEqCatalogEntry

@Entity(
    tableName = "catalog_entries",
    indices = [Index("normalizedName"), Index("isDeleted")],
)
data class CatalogEntryEntity(
    @PrimaryKey val id: String,
    val name: String,
    /** Lowercased name for cheap DB-side prefix/contains filtering. */
    val normalizedName: String,
    val measuredBy: String,
    val relativePath: String,
    /** Epoch millis when this row was last seen in an upstream sync (for staleness/tombstone). */
    val lastSeenAtMs: Long,
    /** Soft-delete tombstone: rows removed upstream stay until a sweep, but are filtered out. */
    val isDeleted: Boolean = false,
)

/** Map a domain [AutoEqCatalogEntry] to a row, stamping [lastSeenAtMs]. */
fun AutoEqCatalogEntry.toEntity(seenAtMs: Long): CatalogEntryEntity = CatalogEntryEntity(
    id = id,
    name = name,
    normalizedName = name.lowercase(),
    measuredBy = measuredBy,
    relativePath = relativePath,
    lastSeenAtMs = seenAtMs,
    isDeleted = false,
)

/** Map a row back to the domain model the search engine / UI consume. */
fun CatalogEntryEntity.toDomain(): AutoEqCatalogEntry = AutoEqCatalogEntry(
    id = id,
    name = name,
    measuredBy = measuredBy,
    relativePath = relativePath,
)
