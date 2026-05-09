package com.coreline.autoeq.parser

import android.util.Log
import com.coreline.audio.EqFilterType
import com.coreline.autoeq.model.AutoEqFilter
import com.coreline.autoeq.model.AutoEqProfile
import com.coreline.autoeq.model.AutoEqSource
import com.coreline.autoeq.model.ParseError
import com.coreline.autoeq.model.ParseResult

/**
 * Parser for EqualizerAPO `ParametricEQ.txt` files.
 *
 * Behavior contract (Phase 3):
 * - Strips a leading UTF-8 BOM and normalizes CRLF line endings.
 * - Rejects inputs over [MAX_INPUT_BYTES] (64 KB) with [ParseError.FileTooLarge].
 * - Skips lines that do not start with `Filter` or `Preamp:` regardless of comment marker
 *   (`#`, `//`, `;`).
 * - Validates that frequency / Q / gain / preamp are finite. Clamps preamp to ±30 dB and
 *   drops filters with |gain| > 30 dB. NaN / Inf tokens cause the line to be rejected.
 * - Recognizes filter type tokens `PK`/`PEQ`, `LS`/`LSC`, `HS`/`HSC`. Anything else
 *   (including REW's legacy `Bell`) is rejected.
 * - Truncates to [AutoEqProfile.MAX_FILTERS] (10).
 * - Returns [ParseError.NoValidFilters] if no filters survive validation.
 */
object ParametricEqParser {

    private const val TAG = "AutoEq[ParametricEqParser]"

    /** Hard cap on input size for the parser (64 KB). */
    const val MAX_INPUT_BYTES: Long = 64L * 1024L

    // P1-6: use the explicit  escape rather than a literal byte-order
    // mark in source. A literal U+FEFF triggers Android Lint's `ByteOrderMark`
    // error and is invisible in many editors / diff tools.
    private const val UTF8_BOM: Char = '\uFEFF'

    /**
     * Parse `ParametricEQ.txt` text into a profile.
     *
     * @param text Full file contents (UTF-8 already decoded).
     * @param name Headphone display name to attach.
     * @param id Stable profile id (caller-supplied; usually the catalog entry id).
     * @param measuredBy Measurement source (`oratory1990` etc.) or null for imports.
     * @param source Where this profile came from. Default [AutoEqSource.FETCHED].
     */
    fun parse(
        text: String,
        name: String,
        id: String,
        measuredBy: String?,
        source: AutoEqSource = AutoEqSource.FETCHED,
    ): ParseResult {
        // Size check uses UTF-8 byte length — the same units the file system reports.
        val byteLen = text.toByteArray(Charsets.UTF_8).size.toLong()
        if (byteLen > MAX_INPUT_BYTES) {
            return ParseResult.Failure(ParseError.FileTooLarge(byteLen))
        }

        // Strip BOM, normalize CRLF.
        var normalized = text
        if (normalized.isNotEmpty() && normalized[0] == UTF8_BOM) {
            normalized = normalized.substring(1)
        }
        normalized = normalized.replace("\r\n", "\n").replace('\r', '\n')

        if (normalized.isBlank()) {
            return ParseResult.Failure(ParseError.Empty)
        }

        var preampDB = 0f
        val filters = ArrayList<AutoEqFilter>(AutoEqProfile.MAX_FILTERS)

        for (rawLine in normalized.split('\n')) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue

            // Skip lines that don't begin with the two structural keywords. This drops
            // everything else — comments, headings, and stray prose — regardless of which
            // comment marker the author used.
            val lower = line.lowercase()
            when {
                lower.startsWith("preamp:") -> {
                    val parsed = parsePreamp(line)
                    if (parsed != null) {
                        preampDB = parsed.coerceIn(-30f, 30f)
                    }
                }
                lower.startsWith("filter") -> {
                    when (val res = parseFilterLine(line)) {
                        is FilterParseOutcome.Ok -> filters.add(res.filter)
                        FilterParseOutcome.Skip -> {}
                        is FilterParseOutcome.Reject -> {
                            return ParseResult.Failure(ParseError.InvalidFormat(res.reason))
                        }
                    }
                    if (filters.size >= AutoEqProfile.MAX_FILTERS) {
                        // Truncate eagerly so we don't keep allocating beyond the cap.
                        // Remaining filter lines are silently ignored.
                        break
                    }
                }
                else -> { /* ignore */ }
            }
        }

        if (filters.isEmpty()) {
            return ParseResult.Failure(ParseError.NoValidFilters)
        }

        val profile = AutoEqProfile(
            id = id,
            name = name,
            source = source,
            measuredBy = measuredBy,
            preampDB = preampDB,
            filters = filters,
            optimizedSampleRate = 48000.0,
        ).validated()

        // Defensive: validated() may have dropped filters for non-finite values that slipped
        // past the per-line check (extreme parser inputs).
        if (profile.filters.isEmpty()) {
            return ParseResult.Failure(ParseError.NoValidFilters)
        }

        return ParseResult.Success(profile)
    }

    // ---- Preamp ----

    /** Parse `Preamp: -6.2 dB` → -6.2f. Returns null when unparseable / non-finite. */
    private fun parsePreamp(line: String): Float? {
        val colonIdx = line.indexOf(':')
        if (colonIdx < 0) return null
        val tail = line.substring(colonIdx + 1).trim()
        val firstToken = tail.split(Regex("\\s+")).firstOrNull() ?: return null
        val value = firstToken.toFloatOrNull() ?: return null
        if (!value.isFinite()) return null
        return value
    }

    // ---- Filter line ----

    private sealed class FilterParseOutcome {
        data class Ok(val filter: AutoEqFilter) : FilterParseOutcome()

        /** Disabled filter or unparseable — silently skip but keep parsing the file. */
        data object Skip : FilterParseOutcome()

        /**
         * Format we explicitly do not support (e.g. REW `Bell`); fail the whole parse with
         * a specific error so the user sees a clear message.
         */
        data class Reject(val reason: String) : FilterParseOutcome()
    }

    private fun parseFilterLine(line: String): FilterParseOutcome {
        val tokens = line.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (tokens.size < 2) return FilterParseOutcome.Skip

        // Disabled filter — must contain an "ON" token. EqualizerAPO uses "ON"/"OFF".
        if (tokens.none { it.equals("ON", ignoreCase = true) }) {
            return FilterParseOutcome.Skip
        }

        // Find the filter type token. Reject REW legacy variants explicitly.
        val typeToken = tokens.firstOrNull { isKnownTypeToken(it) || isLegacyTypeToken(it) }
            ?: return FilterParseOutcome.Skip

        if (isLegacyTypeToken(typeToken)) {
            return FilterParseOutcome.Reject(
                "Unsupported filter type '$typeToken' (REW legacy format). " +
                    "Re-export from EqualizerAPO with PK/LS/HS tokens.",
            )
        }

        val type = parseFilterType(typeToken) ?: return FilterParseOutcome.Skip

        val frequency = extractValue(tokens, "Fc") ?: return FilterParseOutcome.Skip
        val gainDB = extractValue(tokens, "Gain") ?: return FilterParseOutcome.Skip
        val q = extractValue(tokens, "Q") ?: return FilterParseOutcome.Skip

        if (!frequency.isFinite() || frequency <= 0f) return FilterParseOutcome.Skip
        if (!q.isFinite() || q <= 0f) return FilterParseOutcome.Skip
        if (!gainDB.isFinite() || kotlin.math.abs(gainDB) > 30f) return FilterParseOutcome.Skip

        return FilterParseOutcome.Ok(
            AutoEqFilter(
                type = type,
                frequency = frequency.toDouble(),
                gainDB = gainDB,
                q = q.toDouble(),
            ),
        )
    }

    private fun isKnownTypeToken(token: String): Boolean = when (token.uppercase()) {
        "PK", "PEQ", "LS", "LSC", "HS", "HSC" -> true
        else -> false
    }

    private fun isLegacyTypeToken(token: String): Boolean = when (token.uppercase()) {
        "BELL", "LP", "HP", "BP", "NO", "AP", "MODAL" -> true
        else -> false
    }

    private fun parseFilterType(token: String): EqFilterType? = when (token.uppercase()) {
        "PK", "PEQ" -> EqFilterType.PEAKING
        "LS", "LSC" -> EqFilterType.LOW_SHELF
        "HS", "HSC" -> EqFilterType.HIGH_SHELF
        else -> null
    }

    /**
     * Find the numeric value following [keyword] in [tokens] (case-insensitive).
     * Returns null when the keyword is absent, has no successor, or the successor is not
     * a finite float (NaN/Inf are rejected here too).
     */
    private fun extractValue(tokens: List<String>, keyword: String): Float? {
        val idx = tokens.indexOfFirst { it.equals(keyword, ignoreCase = true) }
        if (idx < 0 || idx + 1 >= tokens.size) return null
        val raw = tokens[idx + 1]
        // toFloatOrNull accepts "Infinity"/"NaN" — reject those explicitly.
        if (raw.equals("NaN", ignoreCase = true) ||
            raw.equals("Infinity", ignoreCase = true) ||
            raw.equals("-Infinity", ignoreCase = true) ||
            raw.equals("+Infinity", ignoreCase = true)
        ) {
            return null
        }
        val v = raw.toFloatOrNull() ?: return null
        return if (v.isFinite()) v else null
    }
}
