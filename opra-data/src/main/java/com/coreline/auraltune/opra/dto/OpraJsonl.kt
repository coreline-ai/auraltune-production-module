// OpraJsonl.kt
// DTOs for the single OPRA `database_v1.jsonl` (one JSON object per line, discriminated by
// `type`). Field names match the verified OPRA schema exactly. The parser decodes the per-line
// envelope, then re-decodes `data` into the typed DTO for that `type`. Decode with
// ignoreUnknownKeys = true (schemas are additionalProperties:false but be defensive).
//
//   vendor  : {"type":"vendor","id":"pud","data":{"name":"Pud"}}
//   product : {"type":"product","id":"pud::vogue","data":{"name":"Vogue","type":"headphones",
//                "subtype":"over_the_ear","vendor_id":"pud","line_art_svg":"...","line_art_96x64_png":"..."}}
//   eq      : {"type":"eq","id":"pud:vogue::oratory1990_harman_target","data":{"author":"oratory1990",
//                "details":"Harman Target","link":"https://...","type":"parametric_eq",
//                "parameters":{"gain_db":-7,"bands":[{"type":"peak_dip","frequency":200,"gain_db":-7,"q":6},...]},
//                "product_id":"pud::vogue"}}
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
    @SerialName("official_name") val officialName: String? = null,
    val blurb: String? = null,
    val logo: String? = null,
)

@Serializable
data class OpraProductDataDto(
    val name: String,
    val type: String? = null,
    val subtype: String? = null,
    @SerialName("vendor_id") val vendorId: String? = null,
    val blurb: String? = null,
)

@Serializable
data class OpraEqDataDto(
    val author: String? = null,
    val type: String? = null,
    val details: String? = null,
    val link: String? = null,
    val parameters: OpraParametersDto = OpraParametersDto(),
    @SerialName("product_id") val productId: String? = null,
)

@Serializable
data class OpraParametersDto(
    @SerialName("gain_db") val gainDb: Double = 0.0,
    val bands: List<OpraBandDto> = emptyList(),
)

@Serializable
data class OpraBandDto(
    val type: String? = null,
    val frequency: Double = 0.0,
    @SerialName("gain_db") val gainDb: Double = 0.0,
    val q: Double = 0.0,
    val slope: Int? = null,
)
