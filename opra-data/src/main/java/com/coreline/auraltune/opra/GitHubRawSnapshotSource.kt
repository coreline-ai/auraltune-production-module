// GitHubRawSnapshotSource.kt
// DEBUG/dev OPRA source: fetches the live database_v1.jsonl straight from GitHub raw. Per the
// data policy this is for development/testing only — release builds must use the AuralTune
// mirror/cache or a bundled snapshot (Phase 3 deferred), NOT this.
package com.coreline.auraltune.opra

import com.coreline.auraltune.opra.model.OpraSyncState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

class GitHubRawSnapshotSource(
    private val client: OkHttpClient = OkHttpClient(),
) : OpraSnapshotSource {

    override suspend fun fetch(): OpraSnapshot = withContext(Dispatchers.IO) {
        // Latest commit SHA (cheap) — enables NoChange short-circuit; null if it fails.
        val commit = runCatching { headCommit() }.getOrNull()

        val req = Request.Builder().url(JSONL_URL).build()
        val body = client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("OPRA jsonl HTTP ${resp.code}")
            resp.body?.string() ?: throw IOException("empty OPRA jsonl body")
        }
        OpraSnapshot(
            lines = body.lineSequence(),
            syncState = OpraSyncState(
                snapshotVersion = commit,
                opraCommit = commit,
                sourceUrl = JSONL_URL,
            ),
        )
    }

    private fun headCommit(): String? {
        val req = Request.Builder().url(COMMITS_URL).header("Accept", "application/vnd.github.sha").build()
        return client.newCall(req).execute().use { resp ->
            if (resp.isSuccessful) resp.body?.string()?.trim()?.takeIf { it.isNotEmpty() } else null
        }
    }

    companion object {
        private const val RAW = "https://raw.githubusercontent.com/opra-project/OPRA/main"
        const val JSONL_URL = "$RAW/dist/database_v1.jsonl"
        const val COMMITS_URL = "https://api.github.com/repos/opra-project/OPRA/commits/main"
    }
}

/** Placeholder source for builds with no configured OPRA source yet (e.g. release pre-mirror). */
object EmptyOpraSnapshotSource : OpraSnapshotSource {
    override suspend fun fetch(): OpraSnapshot =
        throw IOException("no OPRA snapshot source configured for this build")
}
