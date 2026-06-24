package com.coreline.auraltune.di

import com.coreline.auraltune.opra.BundledAssetSnapshotSource
import com.coreline.auraltune.opra.GitHubRawSnapshotSource
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

/**
 * Release-distribution guard (Phase 6): a RELEASE build must NEVER reach GitHub for OPRA data — it
 * must use the bundled, sha256-verified snapshot. Testing the extracted policy function directly
 * lets us assert BOTH branches, since BuildConfig.DEBUG is always true under testDebugUnitTest.
 */
class OpraSourcePolicyTest {

    private val openAsset: (String) -> java.io.InputStream = { ByteArrayInputStream(ByteArray(0)) }

    @Test
    fun release_usesBundledSnapshot_notGitHub() {
        val source = ServiceLocator.opraSnapshotSource(isDebug = false, openAsset = openAsset)
        assertTrue("release must use the bundled snapshot", source is BundledAssetSnapshotSource)
        assertFalse("release must NOT use the GitHub source", source is GitHubRawSnapshotSource)
    }

    @Test
    fun debug_usesGitHubSource() {
        val source = ServiceLocator.opraSnapshotSource(isDebug = true, openAsset = openAsset)
        assertTrue("debug uses the live GitHub-raw source", source is GitHubRawSnapshotSource)
    }

    @Test
    fun gitHubSourceUrl_isDebugOnlyUpstream() {
        // Documents that the GitHub URL is the dev/debug upstream — must not appear in release paths.
        assertTrue(GitHubRawSnapshotSource.JSONL_URL.contains("raw.githubusercontent.com"))
        assertTrue(GitHubRawSnapshotSource.JSONL_URL.contains("opra-project/OPRA"))
    }
}
