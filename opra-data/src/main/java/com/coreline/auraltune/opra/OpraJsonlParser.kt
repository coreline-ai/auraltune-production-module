// OpraJsonlParser.kt
// Phase 2 — streaming parser for the single OPRA `database_v1.jsonl`.
// Processes one line at a time from a Sequence<String> (the whole 12.6MB file text is never
// held at once); accumulates parsed DOMAIN objects so vendor/product/eq can be joined regardless
// of line order. Malformed lines are counted and skipped (never fatal). Supported/validity and
// the >MAX_FILTERS "exclude, don't truncate" policy are enforced by OpraEqProfile.isSupported.
package com.coreline.auraltune.opra

import com.coreline.auraltune.opra.dto.OpraEqDataDto
import com.coreline.auraltune.opra.dto.OpraLineDto
import com.coreline.auraltune.opra.dto.OpraProductDataDto
import com.coreline.auraltune.opra.dto.OpraVendorDataDto
import com.coreline.auraltune.opra.model.OpraCatalogEntry
import com.coreline.auraltune.opra.model.OpraEqProfile
import com.coreline.auraltune.opra.model.OpraFilter
import com.coreline.auraltune.opra.model.OpraFilterType
import com.coreline.auraltune.opra.model.OpraProduct
import com.coreline.auraltune.opra.model.OpraVendor
import kotlinx.serialization.json.Json

class OpraJsonlParser(private val json: Json = DEFAULT_JSON) : OpraParser {

    override fun parse(lines: Sequence<String>): OpraParseResult {
        val vendors = LinkedHashMap<String, OpraVendor>()
        val products = LinkedHashMap<String, OpraProduct>()
        val profiles = ArrayList<OpraEqProfile>()
        var malformed = 0

        for (raw in lines) {
            val line = raw.trim()
            if (line.isEmpty()) continue

            val env = runCatching { json.decodeFromString(OpraLineDto.serializer(), line) }.getOrNull()
            if (env == null || env.id.isNullOrBlank()) {
                malformed++
                continue
            }
            val id = env.id

            when (env.type) {
                "vendor" -> {
                    val d = runCatching {
                        json.decodeFromJsonElement(OpraVendorDataDto.serializer(), env.data)
                    }.getOrNull()
                    if (d == null) { malformed++; continue }
                    vendors[id] = OpraVendor(
                        id = id, name = d.name, officialName = d.officialName,
                        blurb = d.blurb, logo = d.logo,
                    )
                }

                "product" -> {
                    val d = runCatching {
                        json.decodeFromJsonElement(OpraProductDataDto.serializer(), env.data)
                    }.getOrNull()
                    if (d == null) { malformed++; continue }
                    products[id] = OpraProduct(
                        id = id, vendorId = d.vendorId, name = d.name,
                        productType = d.type, subtype = d.subtype, blurb = d.blurb,
                    )
                }

                "eq" -> {
                    val d = runCatching {
                        json.decodeFromJsonElement(OpraEqDataDto.serializer(), env.data)
                    }.getOrNull()
                    if (d == null) { malformed++; continue }
                    profiles += d.toProfile(id)
                }

                else -> {
                    // Unknown/forward-compat line type (e.g. future "graphic_eq"): skip, not an error.
                }
            }
        }

        // Join eq -> product -> vendor for the OPRA tab; count orphans (best-effort surfaced).
        // 같은 join에서 프로파일 표시명(profileName)을 헤드폰명(vendor + product)으로 보정한다.
        // (toProfile은 product를 모르므로 profileName이 details 노트/author로 채워져 상태카드·상세 제목이
        //  "Measured by …" 같은 노트로 나오던 문제를 여기서 바로잡는다. product가 없으면 기존 값 유지.)
        var orphanProfiles = 0
        val enrichedProfiles = ArrayList<OpraEqProfile>(profiles.size)
        val catalog = profiles.map { p ->
            val product = p.productId?.let { products[it] }
            if (product == null) orphanProfiles++
            val vendor = product?.vendorId?.let { vendors[it] }
            val vendorName = vendor?.name ?: UNKNOWN_VENDOR
            val productName = product?.name ?: UNKNOWN_PRODUCT
            val displayName = listOf(vendorName, productName).filter { it.isNotBlank() }.joinToString(" ")
            enrichedProfiles += if (product != null && displayName.isNotBlank()) {
                p.copy(profileName = displayName)
            } else {
                p
            }
            OpraCatalogEntry(
                id = p.id,
                displayName = displayName,
                vendorName = vendorName,
                productName = productName,
                author = p.author,
                license = p.license,
                isSupported = p.isSupported,
            )
        }
        val orphanProducts = products.values.count { it.vendorId == null || it.vendorId !in vendors }

        return OpraParseResult(
            vendors = vendors.values.toList(),
            products = products.values.toList(),
            profiles = enrichedProfiles,
            catalogEntries = catalog,
            malformedLines = malformed,
            orphanProfiles = orphanProfiles,
            orphanProducts = orphanProducts,
        )
    }

    private fun OpraEqDataDto.toProfile(id: String): OpraEqProfile {
        val bands = parameters.bands.map { b ->
            OpraFilter(
                type = OpraFilterType.fromToken(b.type),
                frequencyHz = b.frequency,
                gainDb = b.gainDb,
                q = b.q,
                slope = b.slope,
            )
        }
        val name = details?.takeIf { it.isNotBlank() } ?: author ?: id
        return OpraEqProfile(
            id = id,
            productId = productId,
            profileName = name,
            author = author,
            details = details,
            link = link,
            preampDb = parameters.gainDb.toFloat(),
            filters = bands,
        )
    }

    companion object {
        const val UNKNOWN_VENDOR = "Unknown"
        const val UNKNOWN_PRODUCT = "Unknown product"
        val DEFAULT_JSON = Json {
            ignoreUnknownKeys = true
            isLenient = false
        }
    }
}
