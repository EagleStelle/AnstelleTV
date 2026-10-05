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

enum class PlaybackOrder(override val code: Int) : CodedEnum {
    SHUFFLE(0),

    /** Pick a show (weighted), then play its next episode in season/episode order. */
    EPISODE_ORDER(1),

    /** Pick a show, then play a block of 1–4 consecutive episodes. */
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

enum class RuleField(override val code: Int) : CodedEnum {
    GENRE(0),
    STUDIO(1),
    TAG(2),
    COLLECTION(3),
    OFFICIAL_RATING(4),
    YEAR(5),
    DECADE(6),
    KEYWORD(7),
    LIBRARY(8),
    SERIES(9),
    ITEM(10),
    MEDIA_TYPE(11),
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

enum class SegmentType(override val code: Int) : CodedEnum {
    INTRO(0),
    OUTRO(1),
    RECAP(2),
    PREVIEW(3),
    UNKNOWN(9),
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

/** How channel playback is reported back to Jellyfin. */
enum class WatchReportMode(override val code: Int) : CodedEnum {
    OFF(0),

    /** Report playback, but never leave an item marked watched after a partial channel view. */
    REPORT_NO_MARK(1),
    FULL(2),
}
