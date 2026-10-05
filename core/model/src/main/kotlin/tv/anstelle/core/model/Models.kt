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
    val localUrl: String?,
    val remoteUrl: String?,
    val userId: String?,
    val isLocal: Boolean,
)

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
