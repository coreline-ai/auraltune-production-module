// OpraRepositoryImpl.kt
// Phase 3 — high-level OPRA data access. Queries delegate to the store; refresh() pulls a snapshot
// from the source, and on ANY failure returns Failed WITHOUT touching the store, so the existing
// cache is retained (the store's import is also transactional). NoChange short-circuits when the
// snapshot's commit matches what's already stored.
package com.coreline.auraltune.opra

import android.util.Log
import com.coreline.auraltune.opra.model.OpraCatalogEntry
import com.coreline.auraltune.opra.model.OpraEqProfile

class OpraRepositoryImpl(
    private val store: OpraStore,
    private val source: OpraSnapshotSource,
    private val parser: OpraParser = OpraJsonlParser(),
    private val clock: () -> Long = System::currentTimeMillis,
) : OpraRepository {

    override fun observeCatalog() = store.observeCatalog()

    override suspend fun search(query: String, limit: Int): List<OpraCatalogEntry> =
        store.search(query, limit)

    override suspend fun resolve(entry: OpraCatalogEntry): OpraEqProfile? =
        store.resolve(entry.id)

    override suspend fun refresh(): OpraSyncResult {
        val snapshot = runCatching { source.fetch() }.getOrElse {
            Log.w(TAG, "snapshot fetch failed; keeping cache", it)
            return OpraSyncResult.Failed("fetch: ${it.message ?: it::class.simpleName}")
        }

        val current = store.syncState()
        val newCommit = snapshot.syncState.opraCommit
        if (newCommit != null && newCommit == current?.opraCommit) {
            return OpraSyncResult.NoChange
        }

        val startNs = System.nanoTime()
        val result = runCatching { parser.parse(snapshot.lines) }.getOrElse {
            Log.w(TAG, "snapshot parse failed; keeping cache", it)
            return OpraSyncResult.Failed("parse: ${it.message ?: it::class.simpleName}")
        }

        runCatching {
            store.upsert(result, clock())
            store.setSyncState(snapshot.syncState.copy(lastSyncedAt = clock()))
        }.getOrElse {
            Log.w(TAG, "snapshot store failed; cache retained (transaction rolled back)", it)
            return OpraSyncResult.Failed("store: ${it.message ?: it::class.simpleName}")
        }

        val ms = (System.nanoTime() - startNs) / 1_000_000
        Log.i(
            TAG,
            "OPRA import: vendors=${result.vendors.size} products=${result.products.size} " +
                "profiles=${result.profiles.size} malformed=${result.malformedLines} " +
                "orphanEq=${result.orphanProfiles} in ${ms}ms",
        )
        return OpraSyncResult.Updated(
            vendors = result.vendors.size,
            products = result.products.size,
            profiles = result.profiles.size,
        )
    }

    companion object {
        private const val TAG = "OpraRepository"
    }
}
