package tv.anstelle.core.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import tv.anstelle.core.model.SlotKind

/**
 * One scheduled slot on a generated channel.
 *
 * PROGRAM rows point at a media item; a program split at chapter markers becomes several
 * rows with increasing [mediaOffsetMs]. FILLER rows are one whole commercial break whose
 * items are expanded at tune time from [fillerSeed], so a 30-day schedule stays small.
 * All rows of one guide cell share [airingStartUtcMs] / [airingEndUtcMs].
 *
 * No foreign key on [mediaItemId]: deleted items are handled by regenerating the
 * affected future slots, which is cheaper than cascading on every sync.
 */
@Entity(
    tableName = "schedule_slot",
    foreignKeys = [
        ForeignKey(
            entity = ChannelEntity::class,
            parentColumns = ["id"],
            childColumns = ["channelId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["channelId", "startUtcMs"], unique = true)],
)
data class ScheduleSlotEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val channelId: Long,
    val kind: SlotKind,
    val mediaItemId: Long?,
    val startUtcMs: Long,
    val endUtcMs: Long,
    val mediaOffsetMs: Long,
    val airingStartUtcMs: Long,
    val airingEndUtcMs: Long,
    val fillerSeed: Long,
)

/** Resumable engine state so extending a schedule equals generating it in one run. */
@Entity(
    tableName = "schedule_state",
    foreignKeys = [
        ForeignKey(
            entity = ChannelEntity::class,
            parentColumns = ["id"],
            childColumns = ["channelId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class ScheduleStateEntity(
    @PrimaryKey val channelId: Long,
    val generatedUntilUtcMs: Long,
    val stateBlob: ByteArray,
    /** Hash of the inputs (rules, library, settings); a change triggers regeneration. */
    val inputsHash: Long,
    val updatedAtUtcMs: Long,
) {
    override fun equals(other: Any?): Boolean =
        other is ScheduleStateEntity &&
            channelId == other.channelId &&
            generatedUntilUtcMs == other.generatedUntilUtcMs &&
            stateBlob.contentEquals(other.stateBlob) &&
            inputsHash == other.inputsHash &&
            updatedAtUtcMs == other.updatedAtUtcMs

    override fun hashCode(): Int {
        var result = channelId.hashCode()
        result = 31 * result + generatedUntilUtcMs.hashCode()
        result = 31 * result + stateBlob.contentHashCode()
        result = 31 * result + inputsHash.hashCode()
        return result
    }
}
