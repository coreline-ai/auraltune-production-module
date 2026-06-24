// OpraEntities.kt
// Phase 3 — Room entities for the OPRA-only local DB (separate file/DB from :autoeq-data).
// The catalog table is denormalized (vendor/product names joined at import) for fast list/search;
// eq_profiles + eq_filters hold what's needed to apply a preset; sync_state tracks provenance.
package com.coreline.auraltune.opra.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.coreline.auraltune.opra.model.OpraCatalogEntry
import com.coreline.auraltune.opra.model.OpraEqProfile
import com.coreline.auraltune.opra.model.OpraFilter
import com.coreline.auraltune.opra.model.OpraFilterType
import com.coreline.auraltune.opra.model.OpraSyncState

@Entity(tableName = "opra_catalog_entries", indices = [Index("searchText"), Index("isSupported")])
data class OpraCatalogEntryEntity(
    @PrimaryKey val id: String,
    val displayName: String,
    val vendorName: String,
    val productName: String,
    val author: String?,
    val license: String,
    val isSupported: Boolean,
    /** Lowercased "vendor product author" for cheap LIKE search. */
    val searchText: String,
)

@Entity(tableName = "opra_eq_profiles")
data class OpraEqProfileEntity(
    @PrimaryKey val id: String,
    val productId: String?,
    val profileName: String,
    val author: String?,
    val details: String?,
    val link: String?,
    val license: String,
    val preampDb: Float,
    val isSupported: Boolean,
    val unsupportedReason: String?,
)

@Entity(
    tableName = "opra_eq_filters",
    primaryKeys = ["profileId", "position"],
    foreignKeys = [
        ForeignKey(
            entity = OpraEqProfileEntity::class,
            parentColumns = ["id"],
            childColumns = ["profileId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("profileId")],
)
data class OpraEqFilterEntity(
    val profileId: String,
    val position: Int,
    val type: String,
    val frequencyHz: Double,
    val gainDb: Double,
    val q: Double,
    val slope: Int?,
)

@Entity(tableName = "opra_sync_state")
data class OpraSyncStateEntity(
    @PrimaryKey val key: String,
    val snapshotVersion: String?,
    val opraCommit: String?,
    val generatedAt: Long,
    val sourceUrl: String?,
    val sha256: String?,
    val schemaVersion: Int,
    val licenseUrl: String,
    val lastSyncedAt: Long,
)

// ---- mappers ----

fun OpraCatalogEntry.toEntity(): OpraCatalogEntryEntity = OpraCatalogEntryEntity(
    id = id,
    displayName = displayName,
    vendorName = vendorName,
    productName = productName,
    author = author,
    license = license,
    isSupported = isSupported,
    searchText = listOfNotNull(vendorName, productName, author).joinToString(" ").lowercase(),
)

fun OpraCatalogEntryEntity.toDomain(): OpraCatalogEntry = OpraCatalogEntry(
    id = id,
    displayName = displayName,
    vendorName = vendorName,
    productName = productName,
    author = author,
    license = license,
    isSupported = isSupported,
)

fun OpraEqProfile.toEntity(): OpraEqProfileEntity = OpraEqProfileEntity(
    id = id,
    productId = productId,
    profileName = profileName,
    author = author,
    details = details,
    link = link,
    license = license,
    preampDb = preampDb,
    isSupported = isSupported,
    unsupportedReason = unsupportedReason,
)

fun OpraEqProfile.toFilterEntities(): List<OpraEqFilterEntity> = filters.mapIndexed { i, f ->
    OpraEqFilterEntity(
        profileId = id,
        position = i,
        type = f.type.name,
        frequencyHz = f.frequencyHz,
        gainDb = f.gainDb,
        q = f.q,
        slope = f.slope,
    )
}

fun OpraEqProfileEntity.toDomain(filters: List<OpraFilter>): OpraEqProfile = OpraEqProfile(
    id = id,
    productId = productId,
    profileName = profileName,
    author = author,
    details = details,
    link = link,
    license = license,
    preampDb = preampDb,
    filters = filters,
)

fun OpraEqFilterEntity.toDomain(): OpraFilter = OpraFilter(
    type = runCatching { OpraFilterType.valueOf(type) }.getOrDefault(OpraFilterType.UNKNOWN),
    frequencyHz = frequencyHz,
    gainDb = gainDb,
    q = q,
    slope = slope,
)

fun OpraSyncState.toEntity(key: String): OpraSyncStateEntity = OpraSyncStateEntity(
    key = key,
    snapshotVersion = snapshotVersion,
    opraCommit = opraCommit,
    generatedAt = generatedAt,
    sourceUrl = sourceUrl,
    sha256 = sha256,
    schemaVersion = schemaVersion,
    licenseUrl = licenseUrl,
    lastSyncedAt = lastSyncedAt,
)

fun OpraSyncStateEntity.toDomain(): OpraSyncState = OpraSyncState(
    snapshotVersion = snapshotVersion,
    opraCommit = opraCommit,
    generatedAt = generatedAt,
    sourceUrl = sourceUrl,
    sha256 = sha256,
    schemaVersion = schemaVersion,
    licenseUrl = licenseUrl,
    lastSyncedAt = lastSyncedAt,
)
