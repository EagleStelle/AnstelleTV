package tv.anstelle.core.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import tv.anstelle.core.model.MediaType
import tv.anstelle.core.model.WatchSource

/** A guide theme stored as JSON. Built-in themes are re-seeded from assets by [builtInKey]. */
@Entity(
    tableName = "theme",
    indices = [Index(value = ["builtInKey"], unique = true)],
)
data class ThemeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val json: String,
    val isBuiltIn: Boolean,
    val builtInKey: String?,
    val updatedAtUtcMs: Long,
)

/**
 * One view of an item on one local day, for the profile's dashboard. A view that runs past
 * local midnight is one row per day it played on.
 */
@Entity(
    tableName = "watch_event",
    foreignKeys = [
        ForeignKey(
            entity = ProfileEntity::class,
            parentColumns = ["id"],
            childColumns = ["profileId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["profileId", "localDay"]),
        Index(value = ["channelId"]),
    ],
)
data class WatchEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val profileId: Long,
    val source: WatchSource,
    val channelId: Long?,
    val liveChannelId: Long?,
    val mediaItemId: Long?,
    /** Denormalized so stats survive library changes. */
    val title: String,
    val showTitle: String?,
    /** Episode or movie, kept after the item leaves the library. */
    val mediaType: MediaType,
    /** The first moment that played on [localDay]. */
    val startUtcMs: Long,
    /** How long it played on [localDay]; pauses and buffering don't count. */
    val durationMs: Long,
    /** The epoch day in the device's zone. */
    val localDay: Long,
    /** The view watched the item through (see `WatchRules`); set on its last day only. */
    val completed: Boolean,
)

/**
 * A dashboard row restored from a backup, waiting for its item: the library comes back with the
 * server's next sync, and the row then points at the item by its Jellyfin id again.
 */
@Entity(
    tableName = "restored_item",
    foreignKeys = [
        ForeignKey(entity = WatchEventEntity::class, parentColumns = ["id"], childColumns = ["eventId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = MediaServerEntity::class, parentColumns = ["id"], childColumns = ["serverId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index(value = ["serverId"])],
)
data class RestoredItemEntity(
    @PrimaryKey val eventId: Long,
    val serverId: Long,
    val remoteId: String,
)

/** A browser paired with the web UI. Only the SHA-256 of the token is stored. */
@Entity(
    tableName = "paired_client",
    indices = [Index(value = ["tokenHash"], unique = true)],
)
data class PairedClientEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tokenHash: String,
    val name: String,
    val userAgent: String?,
    val lastAddress: String?,
    val createdAtUtcMs: Long,
    val lastSeenUtcMs: Long,
)

@Entity(tableName = "setting")
data class SettingEntity(
    @PrimaryKey val key: String,
    val value: String,
)
