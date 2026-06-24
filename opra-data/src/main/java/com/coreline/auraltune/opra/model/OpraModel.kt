// OpraModel.kt
// OPRA domain models (engine-agnostic, fully separate from :autoeq-data).
// Field names follow the verified OPRA database_v1.jsonl schema:
//   vendor.data  = {name, official_name?, blurb?, logo?}
//   product.data = {name, type, subtype, vendor_id(dump), blurb?, line_art_*?}
//   eq.data      = {author, type, details?, link?, parameters:{gain_db, bands[]}, product_id(dump)}
//   band         = {type, frequency, gain_db, q?, slope?}
// Attribution in OPRA is author/details/link only (NO per-entry source/license key); the
// whole dataset is uniformly CC BY-SA 4.0, so [license] defaults to that.
package com.coreline.auraltune.opra.model

import com.coreline.audio.AudioEngine
import kotlin.math.abs

/** Manufacturer metadata (OPRA "vendor" line). */
data class OpraVendor(
    val id: String,
    val name: String,
    val officialName: String? = null,
    val blurb: String? = null,
    val logo: String? = null,
)

/** Product/headphone metadata (OPRA "product" line). */
data class OpraProduct(
    val id: String,
    val vendorId: String?,
    val name: String,
    /** OPRA `type` (today always "headphones"). */
    val productType: String? = null,
    /** OPRA `subtype`: over_the_ear / on_ear / in_ear / earbuds. */
    val subtype: String? = null,
    val blurb: String? = null,
)

/** A single parametric-EQ band (OPRA `parameters.bands[]`) before engine conversion. */
data class OpraFilter(
    val type: OpraFilterType,
    val frequencyHz: Double,
    val gainDb: Double,
    val q: Double,
    /** dB/oct for low_pass/high_pass; null for peak/shelf bands. */
    val slope: Int? = null,
) {
    /**
     * True when the band is realizable by the shared DSP engine — mirrors the native
     * updateAutoEq validation (finite, freq in (0, Nyquist@48k), q>0, |gain|<=30 dB).
     */
    val isValid: Boolean
        get() = frequencyHz.isFinite() && frequencyHz > 0.0 && frequencyHz < NYQUIST_48K &&
            q.isFinite() && q > 0.0 &&
            gainDb.isFinite() && abs(gainDb) <= MAX_ABS_GAIN_DB

    companion object {
        const val MAX_ABS_GAIN_DB = 30.0      // matches engine kMaxAbsGainDB
        const val NYQUIST_48K = 24000.0       // profiles target 48 kHz; engine pre-warps per device
    }
}

/**
 * An EQ profile/preset for a product (OPRA "eq" line). [preampDb] is OPRA's
 * `parameters.gain_db` (separate from per-band gains). Attribution = [author]/[details]/[link].
 *
 * [isSupported] enforces the "no partial apply" policy: the profile is applicable only if it has
 * 1..[MAX_FILTERS] bands AND every band is both an engine-supported type and value-valid. Profiles
 * exceeding the engine's section count are EXCLUDED (not truncated) to avoid a wrong correction.
 */
data class OpraEqProfile(
    val id: String,
    val productId: String?,
    val profileName: String,
    val author: String? = null,
    val details: String? = null,
    val link: String? = null,
    val license: String = LICENSE_NAME,
    val preampDb: Float = 0f,
    val filters: List<OpraFilter> = emptyList(),
) {
    val isSupported: Boolean
        get() = filters.isNotEmpty() &&
            filters.size <= MAX_FILTERS &&
            filters.all { it.type.isSupported && it.isValid }

    /** Why the profile is not applicable (for UI / diagnostics), or null when supported. */
    val unsupportedReason: String?
        get() = when {
            isSupported -> null
            filters.isEmpty() -> "no bands"
            filters.size > MAX_FILTERS -> "too many bands (${filters.size} > $MAX_FILTERS)"
            filters.any { !it.type.isSupported } -> "unsupported filter type"
            else -> "invalid band values"
        }

    companion object {
        /** Engine section limit (matches AudioEngine.MAX_AUTOEQ_FILTERS = 10). */
        val MAX_FILTERS = AudioEngine.MAX_AUTOEQ_FILTERS
        const val LICENSE_NAME = "CC BY-SA 4.0"
        const val LICENSE_URL = "https://creativecommons.org/licenses/by-sa/4.0/"
    }
}

/** Flattened row for the OPRA tab list/search (one per eq profile, joined with vendor/product). */
data class OpraCatalogEntry(
    val id: String,
    val displayName: String,
    val vendorName: String,
    val productName: String,
    val author: String? = null,
    val license: String = OpraEqProfile.LICENSE_NAME,
    val isSupported: Boolean = true,
)

/**
 * Local data version + provenance for the OPRA snapshot. Fields align 1:1 with the snapshot
 * manifest (snapshot_version/opra_commit/generated_at/sha256/schema_version/license_url).
 */
data class OpraSyncState(
    val snapshotVersion: String? = null,
    val opraCommit: String? = null,
    val generatedAt: Long = 0L,
    val sourceUrl: String? = null,
    val sha256: String? = null,
    val schemaVersion: Int = 1,
    val licenseUrl: String = OpraEqProfile.LICENSE_URL,
    val lastSyncedAt: Long = 0L,
)
