// ProfileEntity.kt
// Phase 5 (dev-plan 110525): DB storage of the MINIMAL data needed to apply a profile.
// We store the parsed header + filters — NEVER the raw ParametricEQ.txt body. The raw text
// is re-derivable from upstream and only the engine-relevant fields are persisted.
package com.coreline.autoeq.db

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation
import com.coreline.audio.EqFilterType
import com.coreline.autoeq.model.AutoEqFilter
import com.coreline.autoeq.model.AutoEqProfile
import com.coreline.autoeq.model.AutoEqSource

@Entity(tableName = "profiles")
data class ProfileEntity(
    @PrimaryKey val id: String,
    val catalogId: String?,
    val name: String,
    val measuredBy: String?,
    /** [AutoEqSource] name (BUNDLED / IMPORTED / FETCHED). */
    val source: String,
    val preampDb: Float,
    val optimizedSampleRate: Double,
    val sourceUrl: String?,
    val sourceSha256: String?,
    val fetchedAtMs: Long,
    /** Touched on every read — drives LRU eviction. */
    val lastAccessMs: Long,
)

@Entity(
    tableName = "profile_filters",
    primaryKeys = ["profileId", "position"],
    foreignKeys = [
        ForeignKey(
            entity = ProfileEntity::class,
            parentColumns = ["id"],
            childColumns = ["profileId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("profileId")],
)
data class ProfileFilterEntity(
    val profileId: String,
    val position: Int,
    /** [EqFilterType] name (PEAKING / LOW_SHELF / HIGH_SHELF / HIGH_PASS). */
    val type: String,
    val frequencyHz: Double,
    val gainDb: Float,
    val q: Double,
)

/** Room @Relation aggregate: one profile header + its ordered filters. */
data class ProfileWithFilters(
    @Embedded val profile: ProfileEntity,
    @Relation(parentColumn = "id", entityColumn = "profileId")
    val filters: List<ProfileFilterEntity>,
)

// ---- mapping ----

fun AutoEqProfile.toEntity(
    sourceUrl: String?,
    sourceSha256: String?,
    nowMs: Long,
): ProfileEntity = ProfileEntity(
    id = id,
    catalogId = id,
    name = name,
    measuredBy = measuredBy,
    source = source.name,
    preampDb = preampDB,
    optimizedSampleRate = optimizedSampleRate,
    sourceUrl = sourceUrl,
    sourceSha256 = sourceSha256,
    fetchedAtMs = nowMs,
    lastAccessMs = nowMs,
)

fun AutoEqProfile.toFilterEntities(): List<ProfileFilterEntity> =
    filters.mapIndexed { i, f ->
        ProfileFilterEntity(
            profileId = id,
            position = i,
            type = f.type.name,
            frequencyHz = f.frequency,
            gainDb = f.gainDB,
            q = f.q,
        )
    }

/** Reassemble the domain [AutoEqProfile] from stored rows (filters ordered by position). */
fun ProfileWithFilters.toDomain(): AutoEqProfile = AutoEqProfile(
    id = profile.id,
    name = profile.name,
    source = runCatching { AutoEqSource.valueOf(profile.source) }.getOrDefault(AutoEqSource.FETCHED),
    measuredBy = profile.measuredBy,
    preampDB = profile.preampDb,
    filters = filters.sortedBy { it.position }.map { f ->
        AutoEqFilter(
            type = runCatching { EqFilterType.valueOf(f.type) }.getOrDefault(EqFilterType.PEAKING),
            frequency = f.frequencyHz,
            gainDB = f.gainDb,
            q = f.q,
        )
    },
    optimizedSampleRate = profile.optimizedSampleRate,
)
