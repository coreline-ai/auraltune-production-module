package com.coreline.autoeq.model

import com.coreline.audio.EqFilterType
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.security.MessageDigest

/**
 * kotlinx-serialization adapter for [com.coreline.audio.EqFilterType].
 *
 * Lives here because `:audio-engine` does not depend on kotlinx-serialization. We serialize
 * by enum name (PEAKING / LOW_SHELF / HIGH_SHELF) so cached files survive ordinal changes.
 */
internal object EqFilterTypeSerializer : KSerializer<EqFilterType> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.coreline.audio.EqFilterType", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: EqFilterType) {
        encoder.encodeString(value.name)
    }

    override fun deserialize(decoder: Decoder): EqFilterType {
        return EqFilterType.valueOf(decoder.decodeString())
    }
}

/**
 * Provenance of an AutoEQ profile.
 *
 * - [BUNDLED]: shipped inside the APK as a built-in fallback.
 * - [IMPORTED]: user-imported `ParametricEQ.txt` from device storage.
 * - [FETCHED]: downloaded on-demand from the AutoEq GitHub repository.
 */
@Serializable
enum class AutoEqSource { BUNDLED, IMPORTED, FETCHED }

/**
 * A single biquad filter section in an AutoEQ correction profile.
 *
 * @property type Filter topology (peaking / low-shelf / high-shelf), serialized via the
 *   audio-engine [EqFilterType] enum so the JNI ordinal contract stays consistent.
 * @property frequency Center / cutoff frequency in Hz. Must be finite and > 0.
 * @property gainDB Gain in dB. Validation clamps to ±30 dB.
 * @property q Quality factor. Must be finite and > 0.
 */
@Serializable
data class AutoEqFilter(
    @Serializable(with = EqFilterTypeSerializer::class) val type: EqFilterType,
    val frequency: Double,
    val gainDB: Float,
    val q: Double,
)

/**
 * A headphone / earphone correction profile derived from an EqualizerAPO `ParametricEQ.txt`.
 *
 * Profiles are parsed once and cached as a structured DTO so that the audio-engine does not
 * have to re-parse text on the audio thread.
 *
 * @property id Stable identifier, typically `sha256(source|relativePath|name).take(24)`.
 * @property name Human-readable headphone name.
 * @property source Provenance — see [AutoEqSource].
 * @property measuredBy Measurement source (e.g. `oratory1990`). Null for imported profiles.
 * @property preampDB Negative preamp to prevent clipping. Validation clamps to ±30 dB.
 * @property filters Up to [MAX_FILTERS] biquad sections.
 * @property optimizedSampleRate Sample rate the filter parameters were optimized for. AutoEq
 *   master files target 48000 Hz; the audio-engine pre-warps when the device rate differs.
 */
@Serializable
data class AutoEqProfile(
    val id: String,
    val name: String,
    val source: AutoEqSource,
    val measuredBy: String?,
    val preampDB: Float,
    val filters: List<AutoEqFilter>,
    val optimizedSampleRate: Double = 48000.0,
) {
    /**
     * Defensive validation matching the parser rules.
     *
     * - Clamps `preampDB` into `[-30, +30]`.
     * - Drops filters with non-finite values, non-positive frequency / Q, or |gain| > 30 dB.
     * - Truncates to [MAX_FILTERS].
     *
     * Use this whenever a profile crosses a trust boundary (disk, network, JNI).
     */
    fun validated(): AutoEqProfile = copy(
        preampDB = preampDB.coerceIn(-30f, 30f),
        filters = filters
            .filter {
                it.frequency.isFinite() && it.frequency > 0 &&
                    it.q.isFinite() && it.q > 0 &&
                    it.gainDB.isFinite() && kotlin.math.abs(it.gainDB) <= 30f
            }
            .take(MAX_FILTERS),
    )

    companion object {
        /** Native AutoEQ chain has at most 10 sections (matches `AudioEngine.MAX_AUTOEQ_FILTERS`). */
        const val MAX_FILTERS = 10
    }
}

/**
 * Lightweight catalog entry parsed from `INDEX.md`.
 *
 * Catalog entries do not carry filter data — full profiles are resolved on demand
 * by [com.coreline.autoeq.repository.AutoEqRepository.fetchProfile].
 *
 * @property id `sha256(source|relativePath|name).take(24)` (see [CatalogIdGenerator]).
 * @property name Display name (e.g. `Sennheiser HD 600`).
 * @property measuredBy Measurement source (e.g. `oratory1990`).
 * @property relativePath URL-decoded path under the AutoEq `results/` directory
 *   (e.g. `oratory1990/over-ear/Sennheiser HD 600`). Note: not URL-encoded.
 */
@Serializable
data class AutoEqCatalogEntry(
    val id: String,
    val name: String,
    val measuredBy: String,
    val relativePath: String,
)

/**
 * Per-output-route AutoEQ selection (see Phase 5).
 */
@Serializable
data class AutoEqSelection(
    val profileId: String,
    val isEnabled: Boolean,
)

/**
 * Observable state of the catalog, exposed by the repository.
 */
sealed class CatalogState {
    /** Initial state before any load attempt. */
    data object Idle : CatalogState()

    /** A network refresh is in progress and there is no cache to serve. */
    data object Loading : CatalogState()

    /**
     * Catalog is available. May represent either a fresh download or a cached snapshot;
     * the repository keeps emitting [Loaded] even when a background refresh fails.
     */
    data class Loaded(val entries: List<AutoEqCatalogEntry>) : CatalogState()

    /** Catalog could not be loaded and there is no usable cache. */
    data class Error(val message: String) : CatalogState()
}

/**
 * Parser failure modes. Surfaced to the UI so the user gets actionable error messages
 * (e.g. `"This file is too large"` rather than a generic `"parse failed"`).
 */
sealed class ParseError {
    /** Input was empty or contained only blank / comment lines. */
    data object Empty : ParseError()

    /** A specific structural problem; [reason] is human-readable but not localized. */
    data class InvalidFormat(val reason: String) : ParseError()

    /** All filter lines were rejected (disabled, malformed, NaN, etc.). */
    data object NoValidFilters : ParseError()

    /** Imported file exceeded the maximum permitted size. */
    data class FileTooLarge(val size: Long) : ParseError()

    /** UTF-8 decoding failed. */
    data class DecodeFailed(val cause: Throwable) : ParseError()
}

/**
 * A typed parse outcome. Mirrors `Result<AutoEqProfile, ParseError>` from Rust / Swift —
 * we use a sealed class instead of `kotlin.Result` because the failure type is bounded.
 */
sealed class ParseResult {
    data class Success(val profile: AutoEqProfile) : ParseResult()
    data class Failure(val error: ParseError) : ParseResult()
}

/**
 * Generates stable `AutoEqCatalogEntry.id` values from `(source, relativePath, name)`.
 *
 * Phase 0 decision: profile IDs are `sha256(source|relativePath|name).take(24)`, NOT a
 * slugified name. This avoids slug collisions between e.g. `Sony WH-1000XM4` measured by
 * different sources.
 */
object CatalogIdGenerator {
    /**
     * @return The first 24 hex characters of `SHA-256(source|relativePath|name)`.
     */
    fun generate(source: String, relativePath: String, name: String): String {
        val payload = "$source|$relativePath|$name".toByteArray(Charsets.UTF_8)
        val digest = MessageDigest.getInstance("SHA-256").digest(payload)
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) {
            val v = b.toInt() and 0xFF
            sb.append(HEX[v ushr 4])
            sb.append(HEX[v and 0x0F])
        }
        return sb.substring(0, 24)
    }

    private val HEX = "0123456789abcdef".toCharArray()
}
