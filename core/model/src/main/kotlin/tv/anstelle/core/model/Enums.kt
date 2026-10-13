package tv.anstelle.core.model

import kotlin.enums.enumEntries

/**
 * Enums persisted in Room use a stable [code] instead of their ordinal or name,
 * so reordering or renaming a constant never corrupts stored data.
 */
interface CodedEnum {
    val code: Int
}

/** Looks up an enum by its stored code. Uses the cached entries list, so it does not allocate. */
inline fun <reified E> codeOf(code: Int): E where E : Enum<E>, E : CodedEnum {
    val entries = enumEntries<E>()
    for (i in entries.indices) {
        val entry = entries[i]
        if (entry.code == code) return entry
    }
    throw IllegalArgumentException("Unknown ${E::class.simpleName} code $code")
}

/** What a synced Jellyfin item is used for. */
enum class MediaType(override val code: Int) : CodedEnum {
    SERIES(0),
    EPISODE(1),
    MOVIE(2),
    MUSIC(3),
    MUSIC_VIDEO(4),
}

/** How a channel plays one kind of item: its series, or its movies. */
enum class PlaybackOrder(override val code: Int) : CodedEnum {
    /** Everything once per round, in random order. */
    SHUFFLE(0),

    /** Series: pick a series (weighted), then play its next episode. Movies: the next movie by release date. */
    EPISODE_ORDER(1),

    /** As [EPISODE_ORDER], but 2–4 in a row: a series' next episodes, or the next movies by release date. */
    BLOCKS(2),
}

enum class SlotKind(override val code: Int) : CodedEnum {
    PROGRAM(0),

    /** Nothing may air (e.g. no item passes a daypart's rating limit); the TV shows an off-air screen. */
    OFF_AIR(1),
}

enum class ChannelKind(override val code: Int) : CodedEnum {
    GENERATED(0),
    MUSIC(1),
}

/** The item metadata a channel rule tests. */
enum class RuleField(override val code: Int) : CodedEnum {
    GENRE(0),

    /** Studios, and the networks of TV series. */
    STUDIO(1),
    TAG(2),

    /** The rating text with text operators; its numeric score with AT_LEAST, AT_MOST and BETWEEN. */
    OFFICIAL_RATING(3),
    YEAR(4),

    /** The first year of the item's decade: 1980 for 1980–1989. */
    DECADE(5),

    /** Whole words or phrases in the title, the show title or the tags. */
    KEYWORD(6),

    /** A library, by its Jellyfin id. */
    LIBRARY(7),

    /** A show, by its Jellyfin series id. */
    SERIES(8),

    /** A single episode, movie or video, by its Jellyfin id. */
    ITEM(9),

    /** EPISODE, MOVIE or MUSIC_VIDEO. */
    MEDIA_TYPE(10),

    /**
     * What kind of show the item belongs to: a media type's key ("anime", "cartoons"), "unclear"
     * for animation whose genres and audio disagree, or "none". Decided per show by the catalog.
     */
    KIND(11),
}

enum class RuleOp(override val code: Int) : CodedEnum {
    EQUALS(0),
    CONTAINS(1),
    ANY_OF(2),
    NOT_ANY_OF(3),
    AT_LEAST(4),
    AT_MOST(5),
    BETWEEN(6),

    /** Always include a specific show or item. */
    INCLUDE(7),

    /** Never include a specific show or item. */
    EXCLUDE(8),
}

enum class SeasonMode(override val code: Int) : CodedEnum {
    /** The channel only exists inside the date range. */
    VISIBLE_IN_RANGE(0),

    /** The channel uses the season's rules instead of its own inside the date range. */
    OVERRIDE_RULES(1),
}

enum class LiveSourceType(override val code: Int) : CodedEnum {
    JELLYFIN(0),
    M3U(1),
    XMLTV(2),
    HDHOMERUN(3),
}

enum class HdrType(override val code: Int) : CodedEnum {
    SDR(0),
    HDR10(1),
    HDR10_PLUS(2),
    HLG(3),
    DOLBY_VISION(4),
}

enum class WatchSource(override val code: Int) : CodedEnum {
    CHANNEL(0),
    ON_DEMAND(1),
    LIVE(2),
}

/**
 * What playback leaves in Jellyfin, chosen apart for Live TV and On demand: the item's watched
 * status, its progress (the resume point where the user stopped), both, or nothing.
 */
enum class WatchSync(override val code: Int, val marksWatched: Boolean, val savesProgress: Boolean) : CodedEnum {
    OFF(0, marksWatched = false, savesProgress = false),
    STATUS(1, marksWatched = true, savesProgress = false),
    PROGRESS(2, marksWatched = false, savesProgress = true),
    BOTH(3, marksWatched = true, savesProgress = true),
}

/**
 * What a profile may do in the app. There is always exactly one [OWNER]: the first profile,
 * made from the first server's user, until it hands the role to another profile. Only the owner and admins change
 * app-wide settings (servers, channels, web control).
 */
enum class ProfileRole(override val code: Int) : CodedEnum {
    /** Changes only its own profile. */
    MEMBER(0),

    /** Adds profiles, changes members' profiles and app-wide settings. */
    ADMIN(1),

    /** Changes every profile, makes admins, hands the role on, and can't be deleted. */
    OWNER(2),
}

/** When subtitles show. */
enum class SubtitleMode(override val code: Int) : CodedEnum {
    OFF(0),
    ALWAYS(1),

    /** Only when the audio isn't in one of the subtitle languages. */
    WHEN_AUDIO_DIFFERS(2),
}

/** What the on-demand player does when a kind of [MediaSegment] plays, as Jellyfin's own apps offer. */
enum class SegmentAction(override val code: Int) : CodedEnum {
    /** A Skip key shows while it plays. */
    ASK_TO_SKIP(0),

    /** It is jumped past as it starts. */
    SKIP(1),

    /** It plays through with nothing offered. */
    PLAY(2),
}
