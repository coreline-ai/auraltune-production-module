package com.coreline.autoeq.parser

import android.util.Log
import com.coreline.autoeq.model.AutoEqCatalogEntry
import com.coreline.autoeq.model.CatalogIdGenerator
import java.net.URLDecoder

/**
 * Parser for the AutoEq upstream `INDEX.md` file.
 *
 * Each list item is one of:
 * ```
 * - [Name](./relative/path) by Source
 * - [Name](./relative/path) by Source on Rig
 * ```
 *
 * Notes:
 * - Headphone names may contain parentheses (`64 Audio A12t (m15 Apex module)`), so the
 *   parser uses `](` as the name/url boundary and `lastIndexOf(") by ")` as the URL end.
 * - Paths are percent-encoded in INDEX.md (`Sennheiser%20HD%20600`); we URL-decode them.
 * - Duplicates (same name from multiple measurement sources) are deduped by source priority,
 *   keeping the highest-priority measurement.
 *
 * Source priority list mirrors `AutoEQFetcher.swift` exactly, including casing.
 */
object IndexMdParser {

    private const val TAG = "AutoEq[IndexMdParser]"

    /**
     * Source priority for deduplication (lower index = preferred). Case-sensitive — must
     * match the strings emitted by AutoEq's `INDEX.md` exactly.
     */
    private val SOURCE_PRIORITY: List<String> = listOf(
        "oratory1990",
        "crinacle",
        "Rtings",
        "Innerfidelity",
        "Super Review",
        "Headphone.com Legacy",
    )

    /**
     * Parse the full INDEX.md text into a deduplicated, sorted list of catalog entries.
     *
     * @param text Raw `INDEX.md` body.
     * @return Catalog entries sorted by name (case-insensitive, locale-insensitive).
     */
    fun parse(text: String): List<AutoEqCatalogEntry> {
        val lines = text.split('\n')

        // name(lowercased) → (entry, priorityIndex)
        val bestByName = HashMap<String, Pair<AutoEqCatalogEntry, Int>>(lines.size.coerceAtLeast(16))

        for (line in lines) {
            val entry = parseLine(line) ?: continue
            val key = entry.name.lowercase()
            val priority = sourcePriorityIndex(entry.measuredBy)
            val existing = bestByName[key]
            if (existing == null || priority < existing.second) {
                bestByName[key] = entry to priority
            }
        }

        return bestByName.values
            .map { it.first }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
    }

    /**
     * Parse a single INDEX.md line. Returns null for non-list lines, headings, blanks,
     * or malformed entries (not a fatal error — INDEX.md mixes list items with prose).
     */
    internal fun parseLine(rawLine: String): AutoEqCatalogEntry? {
        val trimmed = rawLine.trim()
        if (!trimmed.startsWith("- [")) return null

        // Find "](" — the markdown link boundary between name and URL. Using "](" instead of
        // bare "(" avoids confusion with parenthesized names like "A12t (m15 Apex module)".
        val linkBoundary = trimmed.indexOf("](")
        if (linkBoundary < 0) return null

        // Name is between "- [" (offset 3) and "](".
        val name = trimmed.substring(3, linkBoundary)
        if (name.isEmpty()) return null

        // URL ends at the LAST ") by " — URLs themselves may contain "(...)" segments
        // (e.g. ".../64%20Audio%20A12t%20(m15%20Apex%20module)").
        val urlEnd = trimmed.lastIndexOf(") by ")
        if (urlEnd < 0 || urlEnd <= linkBoundary + 2) return null

        var rawPath = trimmed.substring(linkBoundary + 2, urlEnd)
        if (rawPath.startsWith("./")) {
            rawPath = rawPath.substring(2)
        }

        // Decode "%20" etc. AutoEq paths are stable percent-encoded forms; failure is rare,
        // but we fall back to the raw string to avoid losing the entry entirely.
        val relativePath = try {
            URLDecoder.decode(rawPath, "UTF-8")
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Failed to URL-decode path '$rawPath': ${e.message}")
            rawPath
        }

        // Source (and optional "on Rig") is everything after ") by ".
        val sourceAndRig = trimmed.substring(urlEnd + ") by ".length)

        val onIdx = sourceAndRig.indexOf(" on ")
        val measuredBy = if (onIdx >= 0) {
            sourceAndRig.substring(0, onIdx).trim()
        } else {
            sourceAndRig.trim()
        }
        if (measuredBy.isEmpty()) return null

        val id = CatalogIdGenerator.generate(
            source = measuredBy,
            relativePath = relativePath,
            name = name,
        )
        return AutoEqCatalogEntry(
            id = id,
            name = name,
            measuredBy = measuredBy,
            relativePath = relativePath,
        )
    }

    private fun sourcePriorityIndex(source: String): Int {
        val idx = SOURCE_PRIORITY.indexOf(source)
        return if (idx >= 0) idx else SOURCE_PRIORITY.size
    }
}
