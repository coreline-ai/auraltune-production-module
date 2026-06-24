// OpraJsonl.kt
// DTOs for the single OPRA `database_v1.jsonl` (one JSON object per line, discriminated by
// `type`: "vendor" | "product" | "eq"). Verified line shapes:
//   {"type":"vendor","id":"zempireaudio","data":{"name":"ZEMPIREAUDIO"}}
//   {"type":"product","id":"...","data":{"name":"ZE51B","vendor_id":"zempireaudio",...}}
//   {"type":"eq","id":"...","data":{"author":"AutoEQ",...}}
// The `data` payload is kept as a raw JsonObject here; Phase 2 decodes it per type (the exact
// `eq` band schema is finalized then). DTO -> domain model mapping also lives in Phase 2.
package com.coreline.auraltune.opra.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** One line of database_v1.jsonl. `type` selects how `data` is interpreted. */
@Serializable
data class OpraLineDto(
    val type: String,
    val id: String? = null,
    val data: JsonObject = JsonObject(emptyMap()),
)

@Serializable
data class OpraVendorDataDto(
    val name: String,
    @SerialName("logo") val logo: String? = null,
)

@Serializable
data class OpraProductDataDto(
    val name: String,
    @SerialName("vendor_id") val vendorId: String? = null,
    @SerialName("type") val productType: String? = null,
    @SerialName("aliases") val aliases: List<String> = emptyList(),
)

@Serializable
data class OpraEqDataDto(
    val author: String? = null,
    val details: String? = null,
    val source: String? = null,
    @SerialName("preamp") val preampDb: Double? = null,
    // NOTE(Phase 2): the parametric band array shape is finalized when the parser lands.
)
