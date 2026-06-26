// OpraRepositoryImpl.kt
// Phase 3 — high-level OPRA data access. Queries delegate to the store; refresh() pulls a snapshot
// from the source, and on ANY failure returns Failed WITHOUT touching the store, so the existing
// cache is retained (the store's import is also transactional). NoChange short-circuits when the
// snapshot's commit matches what's already stored.
package com.coreline.auraltune.opra

import android.util.Log
import com.coreline.auraltune.opra.model.OpraCatalogEntry
import com.coreline.auraltune.opra.model.OpraEqProfile
import com.coreline.auraltune.opra.model.OpraSyncState

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

    override suspend fun resolveById(profileId: String): OpraEqProfile? =
        store.resolve(profileId)

    override suspend fun syncState(): OpraSyncState? =
        store.syncState()

    override suspend fun refresh(force: Boolean): OpraSyncResult {
        val snapshot = runCatching { source.fetch() }.getOrElse {
            Log.w(TAG, "snapshot fetch failed; keeping cache", it)
            return OpraSyncResult.Failed("fetch: ${it.message ?: it::class.simpleName}")
        }

        val current = store.syncState()
        val newCommit = snapshot.syncState.opraCommit
        // force=true는 commit이 같아도 재파싱한다(파서/매핑 수정을 기존 캐시에 전파).
        // catalog가 비어 있으면 syncState만 남은 손상 상태일 수 있으므로 NoChange로
        // 빠지지 않고 재파싱해 캐시를 복구한다.
        val hasCatalogRows = store.catalogCount() > 0
        if (!force && hasCatalogRows && newCommit != null && newCommit == current?.opraCommit) {
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
