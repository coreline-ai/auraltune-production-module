// OpraSnapshotSource.kt
// Phase 3 — abstraction over WHERE the OPRA snapshot comes from (decouples the repository from
// the debug=GitHub-raw / release=AuralTune-mirror / bundled-asset policy, finalized in Phase 5).
package com.coreline.auraltune.opra

import com.coreline.auraltune.opra.model.OpraSyncState

/** A fetched OPRA snapshot: the JSONL [lines] plus its provenance/manifest [syncState]. */
data class OpraSnapshot(
    val lines: Sequence<String>,
    val syncState: OpraSyncState,
)

/**
 * Yields the current OPRA snapshot. Implementations: bundled-asset reader, AuralTune mirror/cache
 * (release), GitHub raw (debug only). May throw on IO/parse-of-manifest failure — the repository
 * catches it and retains the existing cache.
 */
interface OpraSnapshotSource {
    suspend fun fetch(): OpraSnapshot
}
