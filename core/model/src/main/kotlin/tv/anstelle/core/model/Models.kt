package tv.anstelle.core.model

import kotlinx.serialization.Serializable

@Serializable
data class Profile(
    val id: Long,
    /** The Jellyfin user's name, like [pictureUrl]: profiles mirror their user and nothing else names them. */
    val name: String,
    /** The Jellyfin user's picture; null when there is none, and the tile shows the name's initial. */
    val pictureUrl: String?,
    val role: ProfileRole,
    /**
     * The parental rating limit as a score (about an age); null for none. What airs over it is
     * blacked out. Unrated items never pass a limit.
     */
    val maxRatingScore: Int?,
    val hasPin: Boolean,
    val themeId: Long?,
)

/**
 * A profile's own Jellyfin user on one server: the profile plays as that user there, and its
 * watched marks and resume points go to it. [signedIn] is false once the token is gone.
 */
data class ProfileAccount(val serverId: Long, val userId: String, val userName: String, val signedIn: Boolean)

/**
 * What profile [profileId] may watch: up to its parental rating [limit] (null: no limit), and
 * nothing its own Jellyfin users can't see there ([hidden], local item ids). Channels are the
 * same for every profile, so what this keeps from the profile still airs, blacked out.
 */
class ProfileAccess(val profileId: Long, val limit: Int?, hidden: Collection<Long> = emptyList()) {
    /** Sorted, for a lookup that allocates nothing (the guide asks for every cell it draws). */
    private val hidden: LongArray = hidden.toLongArray().apply { sort() }

    /** Nothing is kept from the profile. */
    val isOpen: Boolean get() = limit == null && hidden.isEmpty()

    /** The profile's Jellyfin users can't see some items. */
    val hidesItems: Boolean get() = hidden.isNotEmpty()

    /** Whether Jellyfin keeps item [itemId] from the profile's own user. */
    fun isHidden(itemId: Long): Boolean = hidden.isNotEmpty() && hidden.binarySearch(itemId) >= 0

    /** Whether the profile may watch item [itemId], rated [score]. */
    fun allows(itemId: Long, score: Int?): Boolean = RatingLimits.allows(limit, score) && !isHidden(itemId)

    override fun equals(other: Any?): Boolean =
        other is ProfileAccess && other.profileId == profileId && other.limit == limit && other.hidden.contentEquals(hidden)

    override fun hashCode(): Int = (profileId.hashCode() * 31 + (limit ?: -1)) * 31 + hidden.contentHashCode()

    companion object {
        /** Before the profile is read: nothing kept back. */
        val OPEN = ProfileAccess(0, null)
    }
}

/**
 * A parental rating level: its name ("PG-13", or "R / TV-MA" for two ratings of one score)
 * with the score (about an age) that limits compare.
 */
data class RatingLevel(val score: Int, val name: String)

/** A rating the library's items carry, with its score and how many items carry it. */
data class CarriedRating(val name: String, val score: Int, val count: Int)

/**
 * Parental rating limits, named with Jellyfin's own ratings ("G", "PG-13"), not ages.
 */
object RatingLimits {
    /**
     * The levels a limit can be set to. They come from the server's own rating table
     * ([table], Jellyfin's parental ratings for its metadata country), one level per score,
     * and a level is kept only when some item in the library ([carried]) scores above the
     * level under it and up to this one, so every step changes what plays. Items rated in
     * another country's system ("NC16", "12+") count by their score. A level is named with
     * the table's ratings the library carries most, at most two ("R / TV-MA"), else with the
     * table's first rating for that score. Without a table the library's own ratings stand
     * in, one level per score, each named as most items carry it.
     */
    fun levels(table: List<RatingLevel>, carried: List<CarriedRating>): List<RatingLevel> {
        if (table.isEmpty()) {
            return carried.groupBy { it.score }
                .map { (score, rows) -> RatingLevel(score, rows.maxBy { it.count }.name) }
                .sortedBy { it.score }
        }
        val counts = carried.groupingBy { key(it.name) }.fold(0) { sum, row -> sum + row.count }
        val scores = carried.map { it.score }
        var below = Int.MIN_VALUE
        return table.groupBy { it.score }.toSortedMap().mapNotNull { (score, ratings) ->
            val reached = scores.any { it > below && it <= score }
            below = score
            if (!reached) return@mapNotNull null
            val names = ratings.distinctBy { key(it.name) }
            val worn = names.filter { (counts[key(it.name)] ?: 0) > 0 }.sortedByDescending { counts[key(it.name)] }.take(2)
            RatingLevel(score, worn.ifEmpty { names.take(1) }.joinToString(" / ") { it.name })
        }
    }

    /** "PG-13", "PG13" and "pg 13" are one rating. */
    private fun key(name: String) = name.uppercase().replace(NonAlphanumeric, "")

    private val NonAlphanumeric = Regex("[^A-Z0-9]")

    /** The limit's name: the library's rating with that score, else the highest one under it. */
    fun label(limit: Int?, levels: List<RatingLevel>): String = when {
        limit == null -> "No limit"
        else -> levels.lastOrNull { it.score <= limit }?.name ?: "Age $limit"
    }

    /**
     * Whether a profile limited to [limit] (null: no limit) may watch an item rated [score].
     * Unrated items never pass a limit, like the SQL `ratingScore <= :max` filter.
     */
    fun allows(limit: Int?, score: Int?): Boolean = limit == null || (score != null && score <= limit)

    /** The choices in order, no limit first, then from the highest rating down. */
    fun choices(levels: List<RatingLevel>): List<Int?> = listOf<Int?>(null) + levels.map { it.score }.distinct().sortedDescending()
}

/**
 * Who may change what. The owner changes every profile; an admin changes members' profiles and
 * the admin's own; a member changes only the member's own. Changing one's own profile asks for
 * its PIN when one is set; the owner and admins change other profiles without their PINs.
 */
object ProfileRights {
    fun canEdit(viewer: Profile, target: Profile): Boolean = when {
        viewer.id == target.id -> true
        viewer.role == ProfileRole.OWNER -> true
        viewer.role == ProfileRole.ADMIN -> target.role == ProfileRole.MEMBER
        else -> false
    }

    fun needsPin(viewer: Profile, target: Profile): Boolean = viewer.id == target.id && target.hasPin

    /** App-wide settings: media servers, channels, web control and the dashboard. */
    fun canManageApp(viewer: Profile): Boolean = viewer.role != ProfileRole.MEMBER

    fun canAdd(viewer: Profile): Boolean = canManageApp(viewer)

    /** Parental controls belong to members, and only the owner or an admin sets them. */
    fun canSetParental(viewer: Profile, target: Profile): Boolean =
        target.role == ProfileRole.MEMBER && viewer.role != ProfileRole.MEMBER && canEdit(viewer, target)

    /** Only the owner makes admins. */
    fun canSetAdmin(viewer: Profile, target: Profile): Boolean =
        viewer.role == ProfileRole.OWNER && target.id != viewer.id

    fun canTransferOwnership(viewer: Profile, target: Profile): Boolean = canSetAdmin(viewer, target)

    /** The owner is never deleted, and no profile deletes itself. */
    fun canDelete(viewer: Profile, target: Profile): Boolean =
        target.role != ProfileRole.OWNER && viewer.id != target.id && canEdit(viewer, target)
}

@Serializable
data class ChannelGroup(
    val id: Long,
    val name: String,
    val sortOrder: Int,
    /** For a segment someone made: where its channels' numbers start. Null for other groups. */
    val numberStart: Int? = null,
)

@Serializable
data class Channel(
    val id: Long,
    val number: Int,
    val name: String,
    val logoUri: String?,
    val kind: ChannelKind,
    val playbackOrder: PlaybackOrder,
    val blockSize: Int,
    val isHidden: Boolean,
    val isFavorite: Boolean,
    val groupId: Long?,
    /** A channel made from the library that still follows it (until someone edits it). */
    val isManaged: Boolean,
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
    /** Position inside the media to start from: the time since the slot started. */
    val mediaPositionMs: Long,
)
