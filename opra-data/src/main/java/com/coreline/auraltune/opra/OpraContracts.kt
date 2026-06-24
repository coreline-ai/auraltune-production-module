// OpraContracts.kt
// Phase 1 scaffold — interfaces + result types only. Implementations land in Phase 2 (parser)
// and Phase 3 (store/repository/sync). Kept deliberately small and engine-agnostic.
package com.coreline.auraltune.opra

import com.coreline.auraltune.opra.model.OpraCatalogEntry
import com.coreline.auraltune.opra.model.OpraEqProfile
import com.coreline.auraltune.opra.model.OpraProduct
import com.coreline.auraltune.opra.model.OpraSyncState
import com.coreline.auraltune.opra.model.OpraVendor
import kotlinx.coroutines.flow.Flow

/**
 * Result of parsing a database_v1.jsonl stream. Malformed lines are skipped (counted in
 * [malformedLines]), never fatal. [catalogEntries] are the joined product+eq rows for the OPRA
 * tab. Orphans (eq whose product_id is missing, product whose vendor_id is missing) are counted
 * but still surfaced as best-effort entries.
 */
data class OpraParseResult(
    val vendors: List<OpraVendor> = emptyList(),
    val products: List<OpraProduct> = emptyList(),
    val profiles: List<OpraEqProfile> = emptyList(),
    val catalogEntries: List<OpraCatalogEntry> = emptyList(),
    val malformedLines: Int = 0,
    val orphanProfiles: Int = 0,
    val orphanProducts: Int = 0,
)

/** Outcome of an OPRA snapshot refresh (mirror/cache or bundled), mirrors AutoEq DeltaResult style. */
sealed interface OpraSyncResult {
    data object NoChange : OpraSyncResult
    data class Updated(val vendors: Int, val products: Int, val profiles: Int) : OpraSyncResult
    data class Failed(val reason: String) : OpraSyncResult
}

/** Streams `database_v1.jsonl` lines into domain models. Phase 2 implements this. */
interface OpraParser {
    /** Parse the given JSONL lines (one JSON object per line) without loading the whole file. */
    fun parse(lines: Sequence<String>): OpraParseResult
}

/** Local persistence/query for OPRA data (Room-backed in Phase 3). */
interface OpraStore {
    fun observeCatalog(): Flow<List<OpraCatalogEntry>>
    suspend fun search(query: String, limit: Int = 50): List<OpraCatalogEntry>
    suspend fun resolve(profileId: String): OpraEqProfile?
    suspend fun upsert(result: OpraParseResult, nowMs: Long)
    suspend fun syncState(): OpraSyncState?
    suspend fun setSyncState(state: OpraSyncState)
}

/**
 * High-level OPRA data access for the app's OPRA tab. Independent of :autoeq-data; the
 * OpraEqProfile -> engine-model adapter lives in :app.
 */
interface OpraRepository {
    fun observeCatalog(): Flow<List<OpraCatalogEntry>>
    suspend fun search(query: String, limit: Int = 50): List<OpraCatalogEntry>
    suspend fun resolve(entry: OpraCatalogEntry): OpraEqProfile?
    /** Resolve by profile id (used to restore a persisted OPRA selection on launch). */
    suspend fun resolveById(profileId: String): OpraEqProfile?
    /** Refresh from the configured source (debug: GitHub raw; release: mirror/cache or bundled). */
    suspend fun refresh(): OpraSyncResult
}
