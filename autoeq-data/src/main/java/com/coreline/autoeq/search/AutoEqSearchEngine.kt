package com.coreline.autoeq.search

import com.coreline.autoeq.model.AutoEqCatalogEntry

/**
 * Three-tier fuzzy search over [AutoEqCatalogEntry]. Port of `AutoEQProfileManager.swift`.
 *
 * Scoring tiers (highest first):
 * - Tier 1: case-insensitive substring on the original name. Base 100. Bonuses: +50 prefix,
 *   +100 exact match, +max(0, 50 - name.length).
 * - Tier 2: case-insensitive substring on the alphanumeric-only normalized name. Base 50,
 *   +25 prefix, +max(0, 25 - normalizedName.length).
 * - Tier 3: token-based. Per query token: substring → 40, otherwise the best edit distance
 *   ≤ (1 if token length ≤ 4 else 2) gives `max(1, 30 - distance * 10)`. If any token
 *   scores 0 the whole query gets 0. Final score is `min(49, total / queryTokens)`.
 *
 * Pre-computes lower-cased and normalized names on [setCatalog] so per-query work is O(N)
 * instead of O(N · name.length²).
 *
 * Thread safety: [setCatalog] publishes a single immutable [SearchIndex] snapshot via
 * a `@Volatile` reference, and [search] snapshots that reference once at entry. Concurrent
 * `setCatalog` ↔ `search` calls therefore can never observe torn state across the three
 * parallel lists (entries / lowered / normalized) — every read sees a fully-built index.
 */
class AutoEqSearchEngine {

    /**
     * Immutable bundle of the three parallel index lists. Replacing the whole bundle
     * via a single volatile write is the linearization point for catalog updates;
     * readers snapshot the reference once and use the local for the entire scoring loop.
     */
    private data class SearchIndex(
        val entries: List<AutoEqCatalogEntry>,
        val loweredNames: List<String>,
        val normalizedNames: List<String>,
    ) {
        companion object {
            val EMPTY = SearchIndex(emptyList(), emptyList(), emptyList())
        }
    }

    @Volatile
    private var index: SearchIndex = SearchIndex.EMPTY

    /**
     * Replace the indexed catalog. Computes lowercased and normalized representations
     * up-front. Safe to call repeatedly when the catalog refreshes.
     *
     * The new index is published atomically: a concurrent [search] call either sees
     * the previous fully-consistent snapshot or the new one — never a half-built mix.
     */
    fun setCatalog(entries: List<AutoEqCatalogEntry>) {
        // Sort by name so the deterministic tiebreaker in [search] also produces a stable order.
        val sorted = entries.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
        index = SearchIndex(
            entries = sorted,
            loweredNames = sorted.map { it.name.lowercase() },
            normalizedNames = sorted.map { normalize(it.name) },
        )
    }

    /** Search result envelope: a possibly-truncated entry list + the unbounded match count. */
    data class Result(val entries: List<AutoEqCatalogEntry>, val totalCount: Int)

    /**
     * Score every catalog entry against [query], sort by descending score (with name
     * as tiebreaker), and return the top [limit].
     *
     * @param query Raw user query. Empty queries return an empty result.
     * @param limit Max entries to return. The full match count is reported via [Result.totalCount].
     */
    fun search(query: String, limit: Int = 50): Result {
        // Snapshot the index once; subsequent setCatalog() calls cannot observe torn state.
        val idx = index
        val entries = idx.entries
        val loweredNames = idx.loweredNames
        val normalizedNames = idx.normalizedNames

        if (query.isEmpty() || entries.isEmpty()) return Result(emptyList(), 0)

        val loweredQuery = query.lowercase()
        val normalizedQuery = normalize(query)

        val scored = ArrayList<IntArray>(entries.size.coerceAtMost(256))
        // IntArray of [index, score] — avoids Pair/Triple allocations in the hot loop.

        for (i in entries.indices) {
            val score = matchScore(
                loweredQuery = loweredQuery,
                normalizedQuery = normalizedQuery,
                loweredName = loweredNames[i],
                normalizedName = normalizedNames[i],
            )
            if (score > 0) scored.add(intArrayOf(i, score))
        }

        scored.sortWith(Comparator { a, b ->
            val cmp = b[1] - a[1]
            if (cmp != 0) cmp else entries[a[0]].name.compareTo(entries[b[0]].name)
        })

        val total = scored.size
        val top = scored.asSequence()
            .take(limit)
            .map { entries[it[0]] }
            .toList()
        return Result(entries = top, totalCount = total)
    }

    // ---- scoring (verbatim port from AutoEQProfileManager.swift) ----

    private fun matchScore(
        loweredQuery: String,
        normalizedQuery: String,
        loweredName: String,
        normalizedName: String,
    ): Int {
        // Tier 1: original substring (case-insensitive).
        if (loweredName.contains(loweredQuery)) {
            var score = 100
            if (loweredName.startsWith(loweredQuery)) score += 50
            if (loweredName == loweredQuery) score += 100
            score += maxOf(0, 50 - loweredName.length)
            return score
        }

        // Tier 2: normalized substring.
        if (normalizedQuery.isNotEmpty() && normalizedName.contains(normalizedQuery)) {
            var score = 50
            if (normalizedName.startsWith(normalizedQuery)) score += 25
            score += maxOf(0, 25 - normalizedName.length)
            return score
        }

        // Tier 3: token-based fuzzy match.
        val queryTokens = loweredQuery.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (queryTokens.isEmpty()) return 0

        var totalTokenScore = 0
        for (token in queryTokens) {
            val tokenScore = bestTokenMatch(token, loweredName)
            if (tokenScore == 0) return 0
            totalTokenScore += tokenScore
        }
        return minOf(49, totalTokenScore / queryTokens.size)
    }

    private fun bestTokenMatch(token: String, name: String): Int {
        if (name.contains(token)) return 40

        val nameTokens = name.split(Regex("[\\s\\-]+")).filter { it.isNotEmpty() }
        val maxAllowedDistance = if (token.length <= 4) 1 else 2

        var bestScore = 0
        for (nameToken in nameTokens) {
            val distance = editDistance(token, nameToken)
            if (distance <= maxAllowedDistance) {
                val score = maxOf(1, 30 - distance * 10)
                if (score > bestScore) bestScore = score
            }
        }
        return bestScore
    }

    /** Plain Levenshtein distance with two rolling rows. Early-exit on |Δlen| > 2. */
    internal fun editDistance(a: String, b: String): Int {
        val m = a.length
        val n = b.length
        if (m == 0) return n
        if (n == 0) return m
        if (kotlin.math.abs(m - n) > 2) return maxOf(m, n)

        var prev = IntArray(n + 1) { it }
        var curr = IntArray(n + 1)

        for (i in 1..m) {
            curr[0] = i
            val ai = a[i - 1]
            for (j in 1..n) {
                curr[j] = if (ai == b[j - 1]) {
                    prev[j - 1]
                } else {
                    1 + minOf(prev[j - 1], prev[j], curr[j - 1])
                }
            }
            val tmp = prev; prev = curr; curr = tmp
        }
        return prev[n]
    }

    companion object {
        /**
         * Lowercase and strip non-alphanumeric characters.
         *
         * Public for testing and so callers can re-use the same canonical form when adding
         * synthetic entries (e.g. imported profiles in a search index merge).
         */
        fun normalize(s: String): String {
            val sb = StringBuilder(s.length)
            for (c in s) {
                if (c.isLetterOrDigit()) sb.append(c.lowercaseChar())
            }
            return sb.toString()
        }
    }
}
