package tv.anstelle.core.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import tv.anstelle.core.model.BreakMode
import tv.anstelle.core.model.ChannelKind
import tv.anstelle.core.model.FillerKind
import tv.anstelle.core.model.FillerSource
import tv.anstelle.core.model.PlaybackOrder
import tv.anstelle.core.model.RuleField
import tv.anstelle.core.model.RuleOp
import tv.anstelle.core.model.SeasonMode

@Entity(
    tableName = "channel_group",
    foreignKeys = [
        ForeignKey(
            entity = ProfileEntity::class,
            parentColumns = ["id"],
            childColumns = ["profileId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["profileId", "sortOrder"])],
)
data class ChannelGroupEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val profileId: Long,
    val name: String,
    val sortOrder: Int,
)

@Entity(
    tableName = "channel",
    foreignKeys = [
        ForeignKey(
            entity = ProfileEntity::class,
            parentColumns = ["id"],
            childColumns = ["profileId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = ChannelGroupEntity::class,
            parentColumns = ["id"],
            childColumns = ["groupId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [
        Index(value = ["profileId", "number"]),
        Index(value = ["groupId"]),
    ],
)
data class ChannelEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val profileId: Long,
    val number: Int,
    val name: String,
    val logoUri: String?,
    val kind: ChannelKind,
    /** Schedule seed; the same seed and inputs always give the same schedule. */
    val seed: Long,
    val playbackOrder: PlaybackOrder,
    /** Episodes per show block for [PlaybackOrder.BLOCKS] (1–4). */
    val blockSize: Int,
    val isHidden: Boolean,
    val isFavorite: Boolean,
    val groupId: Long?,
    /** Slot alignment in minutes: 0 (off), 15, 30 or 60. */
    val slotMinutes: Int,
    val commercialsEnabled: Boolean,
    val breakMode: BreakMode,
    /** Maximum length of one commercial break; null uses the global setting. */
    val maxBreakSeconds: Int?,
    /** Template this channel was generated from, or null for custom channels. */
    val templateId: String?,
    val createdAtUtcMs: Long,
    val updatedAtUtcMs: Long,
)

/**
 * One matching rule. Rules with the same [logicGroup] are combined with AND, and the
 * groups are combined with OR. INCLUDE and EXCLUDE rules apply on top of the groups.
 * Rules with a [seasonId] belong to a seasonal override instead of the base channel.
 */
@Entity(
    tableName = "channel_rule",
    foreignKeys = [
        ForeignKey(
            entity = ChannelEntity::class,
            parentColumns = ["id"],
            childColumns = ["channelId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = ChannelSeasonEntity::class,
            parentColumns = ["id"],
            childColumns = ["seasonId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["channelId"]), Index(value = ["seasonId"])],
)
data class ChannelRuleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val channelId: Long,
    val seasonId: Long?,
    val logicGroup: Int,
    val field: RuleField,
    val op: RuleOp,
    val value: String,
    val weight: Float,
)

/** A date range (month/day, may wrap the year) during which the channel changes. */
@Entity(
    tableName = "channel_season",
    foreignKeys = [
        ForeignKey(
            entity = ChannelEntity::class,
            parentColumns = ["id"],
            childColumns = ["channelId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["channelId"])],
)
data class ChannelSeasonEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val channelId: Long,
    val name: String,
    val mode: SeasonMode,
    val startMonth: Int,
    val startDay: Int,
    val endMonth: Int,
    val endDay: Int,
)

/** Time-of-day rule: inside the window only items within the rating limit are scheduled. */
@Entity(
    tableName = "channel_daypart",
    foreignKeys = [
        ForeignKey(
            entity = ChannelEntity::class,
            parentColumns = ["id"],
            childColumns = ["channelId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["channelId"])],
)
data class ChannelDaypartEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val channelId: Long,
    val name: String,
    /** Minutes after local midnight. A window may wrap midnight (e.g. 1320–360). */
    val startMinuteOfDay: Int,
    val endMinuteOfDay: Int,
    val maxRatingScore: Int?,
    val minRatingScore: Int?,
    /** Optional extra rule group applied only inside the window. */
    val ruleLogicGroup: Int?,
)

/** Where commercials, bumpers and station IDs come from. Null [channelId] means global. */
@Entity(
    tableName = "filler_rule",
    foreignKeys = [
        ForeignKey(
            entity = ChannelEntity::class,
            parentColumns = ["id"],
            childColumns = ["channelId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = ProfileEntity::class,
            parentColumns = ["id"],
            childColumns = ["profileId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["channelId"]), Index(value = ["profileId"])],
)
data class FillerRuleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val profileId: Long,
    val channelId: Long?,
    val kind: FillerKind,
    val source: FillerSource,
    /** Library id or tag name, depending on [source]. */
    val sourceValue: String,
    val isEnabled: Boolean,
    val maxBreakSeconds: Int?,
    /** Only use filler from these years (era matching), e.g. 1980–1989. */
    val yearFrom: Int?,
    val yearTo: Int?,
    val tagFilter: String?,
)
