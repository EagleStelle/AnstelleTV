package tv.anstelle.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import tv.anstelle.core.model.HdrType
import tv.anstelle.core.model.MediaTracks
import tv.anstelle.core.model.MediaType

/**
 * A Jellyfin server, identified by [jellyfinServerId]: adding the same server again under
 * another address updates this row instead of creating a second one. Access tokens are
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
    /** The address the user connected with, e.g. `http://192.168.1.20:8096` or `https://jellyfin.example.com`. */
    val baseUrl: String,
    val userId: String?,
    val userName: String?,
    val serverVersion: String?,
    val createdAtUtcMs: Long,
    /** Device time of the last finished sync, successful or not. */
    val lastSyncUtcMs: Long? = null,
    /** Why the last sync failed, or null when it succeeded. */
    val lastSyncError: String? = null,
    /** Device time of the last ID-only reconciliation (deletions and played state). */
    val lastReconcileUtcMs: Long? = null,
)

/**
 * A library (Jellyfin collection folder) the signed-in user can see. Grouped views such as
 * a combined "TV Shows" view are resolved to their underlying libraries, so each one can
 * be turned on or off by itself.
 */
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
    /** False when the library isn't synced: its type holds nothing to air, or the user turned it off. */
    val isEnabled: Boolean,
    /**
     * Server clock at the start of the last successful sync of this library. The next sync
     * asks only for items with `DateLastSaved` after it. Null forces a full fetch.
     */
    val lastSyncUtcMs: Long?,
)

/**
 * A synced Jellyfin item. Multi-valued metadata (genres, studios, tags, collections) is
 * stored pipe-delimited (`|Comedy|Family|`) for compact rows and cheap `LIKE` matching;
 * rule matching happens in memory. Episodes without their own genres, studios, tags or
 * rating inherit the series' values at sync time.
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
    /** Audio and subtitle streams and font attachments, for picking tracks without a server call. */
    val tracks: MediaTracks,
    /** Audio languages as `|jpn|eng|` (ISO 639-2/T), the default track's first; `""` when none is known. */
    val audioLanguages: String,
    val primaryImageTag: String?,
    /** The first backdrop's tag; null when the item has none (episodes usually use their series'). */
    val backdropImageTag: String?,
    /** The title logo's tag (a transparent image of the title); null when there is none. */
    val logoImageTag: String?,
    /** Played state on the server at last sync, used by the REPORT_NO_MARK report mode. */
    val playedOnServer: Boolean,
    /** Device time of the sync that last wrote this row. */
    val updatedAtUtcMs: Long,
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
