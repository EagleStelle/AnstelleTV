package tv.anstelle.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import tv.anstelle.core.model.HdrType
import tv.anstelle.core.model.MediaTracks
import tv.anstelle.core.model.MediaType
import tv.anstelle.core.model.ProfileRole

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
    /** Device time of the last ID-only reconciliation (deletions). */
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
 * A synced Jellyfin item. Multi-valued metadata (genres, studios, tags) is
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
    /** Device time of the sync that last wrote this row. */
    val updatedAtUtcMs: Long,
)

@Entity(tableName = "profile")
data class ProfileEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** The Jellyfin user's name, read again at every library sync. */
    val name: String,
    val role: ProfileRole,
    /** The highest rating score (about an age) the profile may watch; null for no limit. */
    @ColumnInfo(name = "maxRating") val maxRatingScore: Int?,
    /** `pbkdf2$iterations$salt$hash`, or null when the profile has no PIN. */
    val pinHash: String?,
    /** The Jellyfin user's picture, read again at every library sync. */
    val pictureUrl: String?,
    val themeId: Long?,
    val sortOrder: Int,
    val createdAtUtcMs: Long,
)

/**
 * A profile's own Jellyfin user on one server, signed in for the profile alone: the profile
 * plays as that user there and its watch history goes to it. The token is in the token store,
 * never here.
 */
@Entity(
    tableName = "profile_account",
    primaryKeys = ["profileId", "serverId"],
    foreignKeys = [
        ForeignKey(entity = ProfileEntity::class, parentColumns = ["id"], childColumns = ["profileId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = MediaServerEntity::class, parentColumns = ["id"], childColumns = ["serverId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index(value = ["serverId"])],
)
data class ProfileAccountEntity(
    val profileId: Long,
    val serverId: Long,
    val userId: String,
    /** The user's name, read again at every library sync. */
    val userName: String,
    /** The device the sign-in speaks as, so it never replaces the server's own session or another profile's. */
    val device: String,
)

/**
 * An item the profile's own Jellyfin user can't see (a library it has no access to, or what the
 * user's parental controls block). Found at every library sync; the profile never plays it.
 */
@Entity(
    tableName = "profile_hidden_item",
    primaryKeys = ["profileId", "mediaItemId"],
    foreignKeys = [
        ForeignKey(entity = ProfileEntity::class, parentColumns = ["id"], childColumns = ["profileId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = MediaItemEntity::class, parentColumns = ["id"], childColumns = ["mediaItemId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index(value = ["mediaItemId"])],
)
data class HiddenItemEntity(
    val profileId: Long,
    val mediaItemId: Long,
)
