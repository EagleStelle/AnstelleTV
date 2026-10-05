package tv.anstelle.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import tv.anstelle.core.model.HdrType
import tv.anstelle.core.model.LibraryRole
import tv.anstelle.core.model.MediaType
import tv.anstelle.core.model.SegmentType

/**
 * A Jellyfin server. Local and remote URLs are matched by [jellyfinServerId] because a
 * server behind a reverse proxy reports a loopback `LocalAddress`. Access tokens are
 * never stored here; they live in the Keystore-backed token store.
 */
@Entity(
    tableName = "media_server",
    indices = [Index(value = ["jellyfinServerId"], unique = true)],
)
data class MediaServerEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val jellyfinServerId: String,
    val name: String,
    /** URL currently in use (local when reachable). */
    val baseUrl: String,
    val localUrl: String?,
    val remoteUrl: String?,
    val userId: String?,
    val userName: String?,
    val isLocal: Boolean,
    val serverVersion: String?,
    val createdAtUtcMs: Long,
)

@Entity(
    tableName = "library",
    foreignKeys = [
        ForeignKey(
            entity = MediaServerEntity::class,
            parentColumns = ["id"],
            childColumns = ["serverId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["serverId", "remoteId"], unique = true)],
)
data class LibraryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val serverId: Long,
    val remoteId: String,
    val name: String,
    val collectionType: String?,
    val role: LibraryRole,
    val isEnabled: Boolean,
    /** Server `DateLastSaved` high-water mark used for incremental sync. */
    val lastSyncUtcMs: Long?,
    val lastFullSyncUtcMs: Long?,
)

/**
 * A synced Jellyfin item. Multi-valued metadata (genres, studios, tags, collections) is
 * stored pipe-delimited (`|Comedy|Family|`) for compact rows and cheap `LIKE` matching;
 * rule matching happens in memory.
 */
@Entity(
    tableName = "media_item",
    foreignKeys = [
        ForeignKey(
            entity = MediaServerEntity::class,
            parentColumns = ["id"],
            childColumns = ["serverId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["serverId", "type"]),
        Index(value = ["serverId", "remoteId"], unique = true),
        Index(value = ["seriesRemoteId"]),
        Index(value = ["libraryId"]),
    ],
)
data class MediaItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val serverId: Long,
    val libraryId: Long?,
    val remoteId: String,
    val type: MediaType,
    val title: String,
    val sortTitle: String?,
    val seriesRemoteId: String?,
    val showTitle: String?,
    val season: Int?,
    val episode: Int?,
    val durationMs: Long,
    val genres: String,
    val studios: String,
    val tags: String,
    val collections: String,
    val year: Int?,
    val premiereDateUtcMs: Long?,
    val officialRating: String?,
    /** Numeric parental rating from the server's rating table; null when unrated. */
    val ratingScore: Int?,
    val overview: String?,
    val communityRating: Float?,
    val hdrType: HdrType,
    val audioFormat: String?,
    val videoCodec: String?,
    val audioCodec: String?,
    val container: String?,
    val width: Int?,
    val height: Int?,
    val videoBitDepth: Int?,
    val audioChannels: Int?,
    val mediaSourceId: String?,
    val primaryImageTag: String?,
    val chapterStartsMs: String?,
    /** Played state on the server at last sync, used by the REPORT_NO_MARK report mode. */
    val playedOnServer: Boolean,
    /** Server `DateLastSaved`. */
    val dateLastSavedUtcMs: Long,
    val updatedAtUtcMs: Long,
)

@Entity(
    tableName = "media_segment",
    foreignKeys = [
        ForeignKey(
            entity = MediaItemEntity::class,
            parentColumns = ["id"],
            childColumns = ["mediaItemId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["mediaItemId"])],
)
data class MediaSegmentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val mediaItemId: Long,
    val type: SegmentType,
    val startMs: Long,
    val endMs: Long,
)

@Entity(
    tableName = "profile",
    foreignKeys = [
        ForeignKey(
            entity = MediaServerEntity::class,
            parentColumns = ["id"],
            childColumns = ["serverId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [Index(value = ["serverId"])],
)
data class ProfileEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val avatar: String?,
    val isKid: Boolean,
    @ColumnInfo(name = "maxRating") val maxRatingScore: Int?,
    /** `pbkdf2$iterations$salt$hash`, or null when the profile has no PIN. */
    val pinHash: String?,
    val serverId: Long?,
    val jellyfinUserId: String?,
    val themeId: Long?,
    val sortOrder: Int,
    val createdAtUtcMs: Long,
)
