// BundledAssetSnapshotSource.kt
// RELEASE OPRA source: reads a bundled OPRA snapshot (database_v1.jsonl) + manifest.json from app
// assets. Self-contained (no network, no GitHub/Roon dependency) and verifies the snapshot's
// sha256 against the manifest BEFORE the data is parsed/imported.
//
// Asset packaging note: the snapshot is committed compressed as `database_v1.jsonl.gz` (git stays
// small), and AGP/aapt auto-extracts `.gz` assets so the APK actually contains the raw
// `database_v1.jsonl` (DEFLATE-stored in the zip, ~1.2 MB). We therefore read the raw path first
// and fall back to GZIP-decoding the `.gz` path — robust whether the platform extracts the .gz or
// not, and whether a raw or compressed asset is shipped.
//
// Verification is LAZY: fetch() reads only the tiny manifest up front, so the repository can
// NoChange-skip (commit already imported) without ever decompressing or hashing the ~12 MB
// payload. The read + sha256 check runs the first time the parser iterates the lines — i.e. only
// on an actual import — and a mismatch throws so the repository retains the last-good cache.
package com.coreline.auraltune.opra

import com.coreline.auraltune.opra.dto.OpraManifestDto
import com.coreline.auraltune.opra.model.OpraEqProfile
import com.coreline.auraltune.opra.model.OpraSyncState
import kotlinx.serialization.json.Json
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.time.Instant
import java.util.zip.GZIPInputStream

/** Thrown when the bundled snapshot's decompressed bytes do not match the manifest sha256. */
class OpraIntegrityException(message: String) : IOException(message)

class BundledAssetSnapshotSource(
    /** Opens a bundled asset by relative path. ServiceLocator passes `appContext.assets::open`. */
    private val openAsset: (String) -> InputStream,
    private val manifestPath: String = MANIFEST_PATH,
    private val jsonlPath: String = JSONL_PATH,
    private val gzPath: String = JSONL_GZ_PATH,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : OpraSnapshotSource {

    override suspend fun fetch(): OpraSnapshot {
        // Cheap: read only the tiny manifest so the repository can NoChange-skip without ever
        // touching the multi-MB payload below.
        val manifest = openAsset(manifestPath).use { it.readBytes() }
            .toString(Charsets.UTF_8)
            .let { json.decodeFromString<OpraManifestDto>(it) }
        val expectedSha = manifest.sha256.trim()
        if (expectedSha.isEmpty()) {
            throw OpraIntegrityException("OPRA snapshot sha256 missing in manifest")
        }
        val generatedAt = parseGeneratedAt(manifest.generatedAt)
        val schemaVersion = parseSchemaVersion(manifest.schemaVersion)

        // Lazy: read the raw jsonl bytes + verify sha256 only when the parser iterates (on import).
        val lines = Sequence {
            val raw = readSnapshotBytes()
            val actual = sha256Hex(raw)
            if (!actual.equals(expectedSha, ignoreCase = true)) {
                throw OpraIntegrityException(
                    "OPRA snapshot sha256 mismatch: manifest=$expectedSha actual=$actual",
                )
            }
            // raw bytes are byte-identical to the original jsonl, so the digest matches
            // `sha256sum database_v1.jsonl` recorded at bundle time.
            raw.toString(Charsets.UTF_8).lineSequence().iterator()
        }

        return OpraSnapshot(
            lines = lines,
            syncState = OpraSyncState(
                snapshotVersion = manifest.snapshotVersion,
                opraCommit = manifest.opraCommit ?: manifest.snapshotVersion,
                generatedAt = generatedAt,
                sha256 = expectedSha,
                schemaVersion = schemaVersion,
                sourceUrl = manifest.sourceUrl,
                licenseUrl = manifest.licenseUrl ?: OpraEqProfile.LICENSE_URL,
            ),
        )
    }

    /** Raw jsonl bytes — prefer the extracted raw asset, fall back to GZIP-decoding the .gz. */
    private fun readSnapshotBytes(): ByteArray =
        try {
            openAsset(jsonlPath).use { it.readBytes() }
        } catch (e: FileNotFoundException) {
            GZIPInputStream(openAsset(gzPath)).use { it.readBytes() }
        }

    companion object {
        const val MANIFEST_PATH = "opra/manifest.json"
        const val JSONL_PATH = "opra/database_v1.jsonl"
        const val JSONL_GZ_PATH = "opra/database_v1.jsonl.gz"

        private const val HEX = "0123456789abcdef"

        private fun sha256Hex(bytes: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            val sb = StringBuilder(digest.size * 2)
            for (b in digest) {
                val v = b.toInt() and 0xff
                sb.append(HEX[v ushr 4]).append(HEX[v and 0x0f])
            }
            return sb.toString()
        }

        private fun parseGeneratedAt(value: String?): Long {
            val text = value?.trim().orEmpty()
            if (text.isEmpty()) return 0L
            return runCatching { Instant.parse(text).toEpochMilli() }
                .getOrElse { throw OpraIntegrityException("OPRA manifest generated_at is invalid: $text") }
        }

        private fun parseSchemaVersion(value: String?): Int {
            val text = value?.trim().orEmpty()
            if (text.isEmpty()) return 1
            return when {
                text.equals("v1", ignoreCase = true) || text == "1" -> 1
                else -> throw OpraIntegrityException("Unsupported OPRA manifest schema_version: $text")
            }
        }
    }
}
