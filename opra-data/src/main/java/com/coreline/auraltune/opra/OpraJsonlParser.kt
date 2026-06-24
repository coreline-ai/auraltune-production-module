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
        var orphanProfiles = 0
        val catalog = profiles.map { p ->
            val product = p.productId?.let { products[it] }
            if (product == null) orphanProfiles++
            val vendor = product?.vendorId?.let { vendors[it] }
            val vendorName = vendor?.name ?: UNKNOWN_VENDOR
            val productName = product?.name ?: UNKNOWN_PRODUCT
            OpraCatalogEntry(
                id = p.id,
                displayName = listOf(vendorName, productName).filter { it.isNotBlank() }.joinToString(" "),
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
            profiles = profiles,
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
