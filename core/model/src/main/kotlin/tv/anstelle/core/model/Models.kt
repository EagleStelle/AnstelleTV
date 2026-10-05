package tv.anstelle.core.model

import kotlinx.serialization.Serializable

/** Allowed slot alignment values in minutes. 0 means alignment is off. */
object SlotAlignment {
    const val OFF = 0
    val allowed = intArrayOf(OFF, 15, 30, 60)

    fun isValid(minutes: Int): Boolean = allowed.contains(minutes)
}

@Serializable
data class Profile(
    val id: Long,
    val name: String,
    val avatar: String?,
    val isKid: Boolean,
    val maxRatingScore: Int?,
    val hasPin: Boolean,
    val jellyfinUserId: String?,
    val themeId: Long?,
)

@Serializable
data class ChannelGroup(
    val id: Long,
    val profileId: Long,
    val name: String,
    val sortOrder: Int,
)

@Serializable
data class Channel(
    val id: Long,
    val profileId: Long,
    val number: Int,
    val name: String,
    val logoUri: String?,
    val kind: ChannelKind,
    val playbackOrder: PlaybackOrder,
    val blockSize: Int,
    val isHidden: Boolean,
    val isFavorite: Boolean,
    val groupId: Long?,
    val slotMinutes: Int,
    val commercialsEnabled: Boolean,
)

@Serializable
data class MediaServer(
    val id: Long,
    val jellyfinServerId: String,
    val name: String,
    val baseUrl: String,
    val userId: String?,
    val userName: String?,
    val serverVersion: String?,
    val lastSyncUtcMs: Long?,
    val lastSyncError: String?,
    /** False when no usable access token is stored, so the user must sign in again. */
    val isSignedIn: Boolean,
)

/** What `/System/Info/Public` reports for a server, plus the address that answered. */
@Serializable
data class PublicServerInfo(
    val id: String,
    val name: String,
    val version: String,
    val url: String,
)

/** Why connecting to or signing in to a Jellyfin server failed. */
enum class ServerProblem {
    INVALID_URL,
    UNREACHABLE,
    NOT_JELLYFIN,

    /** The server's version is too old for the Jellyfin SDK. */
    UNSUPPORTED_VERSION,

    /** The saved address now answers with a different server. */
    ID_MISMATCH,
    BAD_CREDENTIALS,

    /** A stored token was rejected; the user has to sign in again. */
    SIGNED_OUT,
    QUICK_CONNECT_DISABLED,
    SERVER_ERROR,
}

class ServerConnectException(
    val problem: ServerProblem,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/** Result of resolving what a channel is airing at an instant. Built from SQLite only. */
data class NowPlaying(
    val channelId: Long,
    val kind: SlotKind,
    val mediaItemId: Long?,
    val slotStartUtcMs: Long,
    val slotEndUtcMs: Long,
    /** Position inside the media to start from: slot offset plus the slot's media offset. */
    val mediaPositionMs: Long,
    val fillerSeed: Long,
)
