package tv.anstelle.core.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import tv.anstelle.core.model.ChannelKind
import tv.anstelle.core.model.PlaybackOrder
import tv.anstelle.core.model.RuleField
import tv.anstelle.core.model.RuleOp
import tv.anstelle.core.model.SeasonMode

/** A guide group. Channels and their groups are the same for every profile. */
@Entity(
    tableName = "channel_group",
    indices = [Index(value = ["sortOrder"])],
)
data class ChannelGroupEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val sortOrder: Int,
    /** For a segment someone made: its channels are numbered up from here. Null for other groups. */
    val numberStart: Int? = null,
)

/**
 * A channel. Every profile sees every channel; what a profile may not watch is blacked out
 * while it airs, not left out of the schedule.
 */
@Entity(
    tableName = "channel",
    foreignKeys = [
        ForeignKey(
            entity = ChannelGroupEntity::class,
            parentColumns = ["id"],
            childColumns = ["groupId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [
        Index(value = ["number"]),
        Index(value = ["groupId"]),
    ],
)
data class ChannelEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
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
    /** The library channel this channel was made from (e.g. `genre:comedy`), or null for a channel built by hand. */
    val autoKey: String?,
    /**
     * True while a library channel follows the library: renamed, updated, renumbered and
     * removed with it. Editing the channel clears it.
     */
    val isManaged: Boolean,
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

/** A library channel someone deleted: its key isn't made into a channel again. */
@Entity(tableName = "dismissed_channel")
data class DismissedChannelEntity(
    @PrimaryKey val autoKey: String,
)
