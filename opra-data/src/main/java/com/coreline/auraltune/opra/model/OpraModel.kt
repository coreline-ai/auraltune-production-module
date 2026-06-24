// OpraModel.kt
// OPRA domain models (engine-agnostic, fully separate from :autoeq-data).
// Mirrors the schema draft in dev-plan/implement_20260624_100339.md. Attribution fields
// (author/source/license) are first-class so the CC BY-SA 4.0 UI requirements can be met.
package com.coreline.auraltune.opra.model

/** Manufacturer metadata (OPRA "vendor" line). */
data class OpraVendor(
    val id: String,
    val name: String,
    val sourceUrl: String? = null,
    val updatedAt: Long = 0L,
)

/** Product/headphone metadata (OPRA "product" line). */
data class OpraProduct(
    val id: String,
    val vendorId: String,
    val name: String,
    val productType: String? = null,
    val aliases: List<String> = emptyList(),
    val sourceUrl: String? = null,
)

/** A single parametric-EQ band before engine conversion. */
data class OpraFilter(
    val type: OpraFilterType,
    val frequencyHz: Double,
    val gainDb: Double,
    val q: Double,
    /** dB/oct slope for shelf/pass filters when OPRA provides it; null otherwise. */
    val slope: Int? = null,
)

/**
 * An EQ profile/preset for a product (OPRA "eq" line). [author]/[source]/[license] carry the
 * CC BY-SA attribution. [isSupported] is false when any band maps to an unsupported engine
 * type (profile-level exclude policy — no partial apply).
 */
data class OpraEqProfile(
    val id: String,
    val productId: String,
    val profileName: String,
    val author: String? = null,
    val source: String? = null,
    val license: String? = null,
    val preampDb: Float = 0f,
    val filters: List<OpraFilter> = emptyList(),
) {
    val isSupported: Boolean get() = filters.isNotEmpty() && filters.all { it.type.isSupported }
}

/** Flattened row for the OPRA tab list/search. */
data class OpraCatalogEntry(
    val id: String,
    val displayName: String,
    val vendorName: String,
    val productName: String,
    val author: String? = null,
    val license: String? = null,
    val isSupported: Boolean = true,
)

/**
 * Local data version + provenance for the OPRA snapshot. Fields align 1:1 with the snapshot
 * manifest (snapshot_version/opra_commit/generated_at/sha256/schema_version/license_url) so
 * checksum verification and audit trail (Phase 3) have everything they need.
 */
data class OpraSyncState(
    val snapshotVersion: String? = null,
    val opraCommit: String? = null,
    val generatedAt: Long = 0L,
    val sourceUrl: String? = null,
    val sha256: String? = null,
    val schemaVersion: Int = 1,
    val licenseUrl: String = "https://creativecommons.org/licenses/by-sa/4.0/",
    val lastSyncedAt: Long = 0L,
)
