package tv.anstelle.feature.settings.channels

import tv.anstelle.core.data.channels.MatchResult
import tv.anstelle.core.data.channels.Rule
import tv.anstelle.core.model.MediaType
import tv.anstelle.core.model.PlaybackOrder
import tv.anstelle.core.model.RuleField
import tv.anstelle.core.model.RuleOp
import java.text.DateFormat
import java.util.Date

/** The playback orders offered, as (order, block size). */
internal val PlaybackChoices = listOf(
    PlaybackOrder.SHUFFLE to 1,
    PlaybackOrder.EPISODE_ORDER to 1,
    PlaybackOrder.BLOCKS to 2,
    PlaybackOrder.BLOCKS to 3,
    PlaybackOrder.BLOCKS to 4,
)

internal fun playbackLabel(order: PlaybackOrder, blockSize: Int): String = when (order) {
    PlaybackOrder.SHUFFLE -> "Shuffle"
    PlaybackOrder.EPISODE_ORDER -> "Episode order"
    PlaybackOrder.BLOCKS -> "Blocks of $blockSize"
}

internal fun playbackHint(order: PlaybackOrder, blockSize: Int): String = when (order) {
    PlaybackOrder.SHUFFLE -> "Everything once per round, in random order."
    PlaybackOrder.EPISODE_ORDER -> "Picks a show, then plays its next episode."
    PlaybackOrder.BLOCKS -> "Picks a show, then plays $blockSize episodes in a row."
}

/** Fields the lineup builder offers. Libraries and single items come in through picks. */
internal val BuilderFields = listOf(
    RuleField.GENRE,
    RuleField.STUDIO,
    RuleField.TAG,
    RuleField.COLLECTION,
    RuleField.KEYWORD,
    RuleField.OFFICIAL_RATING,
    RuleField.YEAR,
    RuleField.DECADE,
    RuleField.MEDIA_TYPE,
    RuleField.SERIES,
)

internal fun fieldLabel(field: RuleField): String = when (field) {
    RuleField.GENRE -> "Genre"
    RuleField.STUDIO -> "Studio or network"
    RuleField.TAG -> "Tag"
    RuleField.COLLECTION -> "Collection"
    RuleField.OFFICIAL_RATING -> "Rating"
    RuleField.YEAR -> "Year"
    RuleField.DECADE -> "Decade"
    RuleField.KEYWORD -> "Keyword"
    RuleField.LIBRARY -> "Library"
    RuleField.SERIES -> "Show"
    RuleField.ITEM -> "Item"
    RuleField.MEDIA_TYPE -> "Type"
}

internal fun fieldHint(field: RuleField): String = when (field) {
    RuleField.GENRE -> "Comedy, Animation, Western…"
    RuleField.STUDIO -> "NBC, Cartoon Network, Studio Ghibli…"
    RuleField.TAG -> "Tags from your Jellyfin metadata"
    RuleField.COLLECTION -> "Box sets, like a movie series"
    RuleField.OFFICIAL_RATING -> "TV-Y, PG-13, or a rating score limit"
    RuleField.YEAR -> "Release year"
    RuleField.DECADE -> "1980s, 1990s…"
    RuleField.KEYWORD -> "Words in titles and tags, like Christmas"
    RuleField.LIBRARY -> "A Jellyfin library"
    RuleField.SERIES -> "Specific shows"
    RuleField.ITEM -> "A specific episode or movie"
    RuleField.MEDIA_TYPE -> "Episodes, movies or music videos"
}

/** True when the rule compares numbers (a rating comparison is by score). */
internal fun isNumeric(field: RuleField, op: RuleOp): Boolean = when (field) {
    RuleField.YEAR, RuleField.DECADE -> true
    RuleField.OFFICIAL_RATING -> op == RuleOp.AT_LEAST || op == RuleOp.AT_MOST || op == RuleOp.BETWEEN
    else -> false
}

internal fun opLabel(field: RuleField, op: RuleOp): String = when (op) {
    RuleOp.EQUALS -> "is"
    RuleOp.CONTAINS -> "contains"
    RuleOp.ANY_OF -> if (field == RuleField.KEYWORD) "mentions any of" else "is any of"
    RuleOp.NOT_ANY_OF -> if (field == RuleField.KEYWORD) "mentions none of" else "is none of"
    RuleOp.AT_LEAST -> if (field == RuleField.OFFICIAL_RATING) "score is at least" else "is at least"
    RuleOp.AT_MOST -> if (field == RuleField.OFFICIAL_RATING) "score is at most" else "is at most"
    RuleOp.BETWEEN -> if (field == RuleField.OFFICIAL_RATING) "score is between" else "is between"
    RuleOp.INCLUDE -> "always included"
    RuleOp.EXCLUDE -> "never included"
}

internal fun mediaTypeLabel(name: String): String = when (name.uppercase()) {
    MediaType.EPISODE.name -> "episodes"
    MediaType.MOVIE.name -> "movies"
    MediaType.MUSIC_VIDEO.name -> "music videos"
    else -> name.lowercase()
}

internal fun valueLabel(field: RuleField, value: String, titleOf: (String) -> String?): String = when (field) {
    RuleField.DECADE -> "${value}s"
    RuleField.MEDIA_TYPE -> mediaTypeLabel(value)
    RuleField.SERIES, RuleField.ITEM -> titleOf(value) ?: "a show not in your library"
    else -> value
}

/** "Genre is any of Comedy, Sitcom", "Decade is 1980s". */
internal fun describe(rule: Rule, titleOf: (String) -> String?): String {
    val values = rule.values.map { valueLabel(rule.field, it, titleOf) }
    val joined = if (rule.op == RuleOp.BETWEEN && values.size == 2) "${values[0]} and ${values[1]}" else values.joinToString(", ")
    return "${fieldLabel(rule.field)} ${opLabel(rule.field, rule.op)} $joined"
}

/** "182 episodes and 12 movies · 9 shows · 64 hours". */
internal fun matchSummary(result: MatchResult): String {
    if (result.itemCount == 0) return "Nothing in your library matches yet"
    val counts = result.matches.groupingBy { it.item.row.type }.eachCount()
    val parts = listOfNotNull(
        counts[MediaType.EPISODE]?.let { "%,d %s".format(it, if (it == 1) "episode" else "episodes") },
        counts[MediaType.MOVIE]?.let { "%,d %s".format(it, if (it == 1) "movie" else "movies") },
        counts[MediaType.MUSIC_VIDEO]?.let { "%,d %s".format(it, if (it == 1) "music video" else "music videos") },
    )
    val shows = result.matches.mapNotNullTo(HashSet()) { it.item.row.seriesRemoteId }.size
    val hours = result.totalDurationMs / 3_600_000L
    return buildList {
        add(parts.joinToString(" and "))
        if (shows > 0) add("%,d %s".format(shows, if (shows == 1) "show" else "shows"))
        add(if (hours < 1) "under an hour" else "%,d %s".format(hours, if (hours == 1L) "hour" else "hours"))
    }.joinToString(" · ")
}

internal fun weightLabel(weight: Float): String =
    if (weight == weight.toInt().toFloat()) "×${weight.toInt()}" else "×$weight"

/** Weights offered when cycling: normal, more often, rarely. */
internal fun nextWeight(weight: Float): Float {
    val steps = listOf(1f, 2f, 3f, 5f, 0.5f)
    val index = steps.indexOf(weight)
    return steps[(index + 1).mod(steps.size)]
}

internal fun timeLabel(utcMs: Long): String = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(utcMs))
