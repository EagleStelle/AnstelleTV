package tv.anstelle.core.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import tv.anstelle.core.model.LiveSourceType

@Entity(
    tableName = "live_source",
    foreignKeys = [
        ForeignKey(
            entity = ProfileEntity::class,
            parentColumns = ["id"],
            childColumns = ["profileId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = MediaServerEntity::class,
            parentColumns = ["id"],
            childColumns = ["serverId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["profileId"]), Index(value = ["serverId"])],
)
data class LiveSourceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val profileId: Long,
    val type: LiveSourceType,
    val name: String,
    /** Playlist URL (M3U), device base URL (HDHomeRun), or null for Jellyfin. */
    val url: String?,
    /** XMLTV guide URL, if separate from the playlist. */
    val epgUrl: String?,
    /** Jellyfin server for [LiveSourceType.JELLYFIN]. */
    val serverId: Long?,
    val deviceId: String?,
    val refreshIntervalMinutes: Int,
    val lastRefreshUtcMs: Long?,
    val isEnabled: Boolean,
)

@Entity(
    tableName = "live_channel",
    foreignKeys = [
        ForeignKey(
            entity = LiveSourceEntity::class,
            parentColumns = ["id"],
            childColumns = ["sourceId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["sourceId", "remoteId"], unique = true),
        Index(value = ["guideId"]),
    ],
)
data class LiveChannelEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sourceId: Long,
    /** Stable id inside the source (tvg-id, GuideNumber or Jellyfin channel id). */
    val remoteId: String,
    /** XMLTV channel id used to attach guide data. */
    val guideId: String?,
    val name: String,
    val number: String?,
    /** Guide position; live channels sort among generated channels by this value. */
    val sortNumber: Float,
    val logoUrl: String?,
    val streamUrl: String?,
    val groupTitle: String?,
    val isHidden: Boolean,
    val isFavorite: Boolean,
)

@Entity(
    tableName = "live_program",
    foreignKeys = [
        ForeignKey(
            entity = LiveChannelEntity::class,
            parentColumns = ["id"],
            childColumns = ["liveChannelId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["liveChannelId", "startUtcMs"])],
)
data class LiveProgramEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val liveChannelId: Long,
    val startUtcMs: Long,
    val endUtcMs: Long,
    val title: String,
    val subtitle: String?,
    val description: String?,
    val category: String?,
    val episodeNum: String?,
    val rating: String?,
    val isNew: Boolean,
)
