// OpraManifest.kt
// DTO for the bundled snapshot manifest (assets/opra/manifest.json). Kept in the `dto` package so
// the proven `-keep,includedescriptorclasses class com.coreline.auraltune.opra.dto.** { *; }`
// consumer rule retains its generated kotlinx.serialization $serializer under R8 (a nested
// private DTO would need a fragile per-class keep rule).
package com.coreline.auraltune.opra.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class OpraManifestDto(
    @SerialName("schema_version") val schemaVersion: String? = null,
    @SerialName("snapshot_version") val snapshotVersion: String? = null,
    @SerialName("opra_commit") val opraCommit: String? = null,
    @SerialName("sha256") val sha256: String = "",
    @SerialName("generated_at") val generatedAt: String? = null,
    @SerialName("source_url") val sourceUrl: String? = null,
    @SerialName("license_url") val licenseUrl: String? = null,
)
