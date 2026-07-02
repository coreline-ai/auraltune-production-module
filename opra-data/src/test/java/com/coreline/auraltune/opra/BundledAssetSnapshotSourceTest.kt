package com.coreline.auraltune.opra

import com.coreline.auraltune.opra.model.OpraEqProfile
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException
import java.security.MessageDigest
import java.time.Instant
import java.util.zip.GZIPOutputStream

/**
 * Verifies the RELEASE OPRA source: it reads the bundled JSONL + manifest from assets, exposes the
 * manifest provenance up front, and verifies the bytes' sha256 against the manifest (lazily, on
 * first iteration). A mismatch throws so the repository keeps the last-good cache. Also covers the
 * .gz fallback (used if a platform ships/keeps the compressed asset).
 */
class BundledAssetSnapshotSourceTest {

    private val jsonl = listOf(
        """{"type":"vendor","id":"pud","data":{"name":"Pud"}}""",
        """{"type":"product","id":"pud::vogue","data":{"name":"Vogue","type":"headphones","subtype":"over_the_ear","vendor_id":"pud"}}""",
        """{"type":"eq","id":"pud:vogue::a","data":{"author":"A","type":"parametric_eq","parameters":{"gain_db":-3,"bands":[{"type":"peak_dip","frequency":1000,"gain_db":2,"q":1}]},"product_id":"pud::vogue"}}""",
    ).joinToString("\n") + "\n"

    private val rawBytes = jsonl.toByteArray(Charsets.UTF_8)
    private val gzBytes = ByteArrayOutputStream()
        .also { GZIPOutputStream(it).use { z -> z.write(rawBytes) } }
        .toByteArray()
    private val goodSha = sha256Hex(rawBytes)
    private val generatedAt = "2026-06-24T05:39:23Z"

    private fun manifest(sha256: String) =
        """{"schema_version":"v1","snapshot_version":"abc123def","opra_commit":"abc123def",""" +
            """"sha256":"$sha256","source_url":"https://example/db.jsonl",""" +
            """"generated_at":"$generatedAt",""" +
            """"license_url":"${OpraEqProfile.LICENSE_URL}"}"""

    /** Source serving the raw jsonl under the primary path (what AGP's extracted asset looks like). */
    private fun rawSource(manifestJson: String) = BundledAssetSnapshotSource(
        openAsset = { name ->
            when {
                name.endsWith("manifest.json") -> ByteArrayInputStream(manifestJson.toByteArray(Charsets.UTF_8))
                name == BundledAssetSnapshotSource.JSONL_PATH -> ByteArrayInputStream(rawBytes)
                else -> throw FileNotFoundException(name)
            }
        },
    )

    /** Source serving ONLY the .gz (raw path missing) — exercises the GZIP fallback. */
    private fun gzOnlySource(manifestJson: String) = BundledAssetSnapshotSource(
        openAsset = { name ->
            when {
                name.endsWith("manifest.json") -> ByteArrayInputStream(manifestJson.toByteArray(Charsets.UTF_8))
                name == BundledAssetSnapshotSource.JSONL_GZ_PATH -> ByteArrayInputStream(gzBytes)
                else -> throw FileNotFoundException(name)
            }
        },
    )

    @Test
    fun fetch_validChecksum_yieldsLinesAndProvenance() = runBlocking {
        val snap = rawSource(manifest(goodSha)).fetch()
        // Provenance is available without touching the payload.
        assertEquals("abc123def", snap.syncState.opraCommit)
        assertEquals(goodSha, snap.syncState.sha256)
        assertEquals(Instant.parse(generatedAt).toEpochMilli(), snap.syncState.generatedAt)
        assertEquals(1, snap.syncState.schemaVersion)
        assertEquals(OpraEqProfile.LICENSE_URL, snap.syncState.licenseUrl)
        // Iterating reads + verifies, then yields lines (trailing blank line ignored by parser).
        val lines = snap.lines.toList().filter { it.isNotBlank() }
        assertEquals(3, lines.size)
        assertTrue(lines[0].contains("\"vendor\""))
    }

    @Test
    fun fetch_gzFallback_yieldsLines() = runBlocking {
        val snap = gzOnlySource(manifest(goodSha)).fetch()
        val lines = snap.lines.toList().filter { it.isNotBlank() }
        assertEquals(3, lines.size)
    }

    @Test
    fun fetch_tamperedChecksum_throwsOnIteration() = runBlocking {
        val snap = rawSource(manifest("deadbeef")).fetch()
        try {
            snap.lines.toList()
            fail("expected OpraIntegrityException")
        } catch (e: OpraIntegrityException) {
            assertTrue(e.message!!.contains("sha256"))
        }
    }

    @Test
    fun fetch_emptyManifestSha_throws() = runBlocking {
        try {
            rawSource(manifest("")).fetch()
            fail("expected OpraIntegrityException")
        } catch (e: OpraIntegrityException) {
            assertTrue(e.message!!.contains("sha256"))
        }
    }

    companion object {
        private fun sha256Hex(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
