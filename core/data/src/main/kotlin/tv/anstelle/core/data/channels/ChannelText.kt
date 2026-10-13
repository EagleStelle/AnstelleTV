package tv.anstelle.core.data.channels

import tv.anstelle.core.model.MediaType
import tv.anstelle.core.model.Playback
import tv.anstelle.core.model.PlaybackOrder
import tv.anstelle.core.model.RuleField
import tv.anstelle.core.model.RuleOp

/** The playbacks offered, the same for series and movies. */
val PlaybackChoices = listOf(
    Playback(PlaybackOrder.SHUFFLE),
    Playback(PlaybackOrder.EPISODE_ORDER),
    Playback(PlaybackOrder.BLOCKS, 2),
    Playback(PlaybackOrder.BLOCKS, 3),
    Playback(PlaybackOrder.BLOCKS, 4),
)

fun seriesPlaybackLabel(playback: Playback): String = when (playback.order) {
    PlaybackOrder.SHUFFLE -> "Shuffle"
    PlaybackOrder.EPISODE_ORDER -> "Episode order"
    PlaybackOrder.BLOCKS -> "Blocks of ${playback.blockSize}"
}

fun seriesPlaybackHint(playback: Playback): String = when (playback.order) {
    PlaybackOrder.SHUFFLE -> "Every episode once per round, in random order."
    PlaybackOrder.EPISODE_ORDER -> "Picks a series, then plays its next episode."
    PlaybackOrder.BLOCKS -> "Picks a series, then plays its next ${playback.blockSize} episodes."
}

fun moviePlaybackLabel(playback: Playback): String = when (playback.order) {
    PlaybackOrder.SHUFFLE -> "Shuffle"
    PlaybackOrder.EPISODE_ORDER -> "Release order"
    PlaybackOrder.BLOCKS -> "Blocks of ${playback.blockSize}"
}

fun moviePlaybackHint(playback: Playback): String = when (playback.order) {
    PlaybackOrder.SHUFFLE -> "Every movie once per round, in random order."
    PlaybackOrder.EPISODE_ORDER -> "Plays the next movie by release date."
    PlaybackOrder.BLOCKS -> "Plays the next ${playback.blockSize} movies by release date."
}

/**
 * Fields the lineup builder offers, broadest first: what an item is, what it is about, who made
 * it, its rating and year, then the narrow ones down to single series and movies ([RuleField.ITEM]
 * picks movies here). Libraries come in through picks. Anime or cartoon and decade aren't offered
 * (genres, tags, keywords and years already say them); rules on them still read and match.
 */
val BuilderFields = listOf(
    RuleField.MEDIA_TYPE,
    RuleField.GENRE,
    RuleField.STUDIO,
    RuleField.OFFICIAL_RATING,
    RuleField.YEAR,
    RuleField.TAG,
    RuleField.KEYWORD,
    RuleField.SERIES,
    RuleField.ITEM,
)

/** Operators answered by one pick from the list (two for BETWEEN, from and to). */
val SinglePickOps = setOf(RuleOp.EQUALS, RuleOp.AT_LEAST, RuleOp.AT_MOST, RuleOp.BETWEEN)

fun fieldLabel(field: RuleField): String = when (field) {
    RuleField.GENRE -> "Genre"
    RuleField.STUDIO -> "Studio or network"
    RuleField.TAG -> "Tag"
    RuleField.OFFICIAL_RATING -> "Rating"
    RuleField.YEAR -> "Year"
    RuleField.DECADE -> "Decade"
    RuleField.KEYWORD -> "Keyword"
    RuleField.LIBRARY -> "Library"
    RuleField.SERIES -> "Series"
    RuleField.ITEM -> "Movie"
    RuleField.MEDIA_TYPE -> "Type"
    RuleField.KIND -> "Anime or cartoon"
}

fun fieldHint(field: RuleField): String = when (field) {
    RuleField.GENRE -> "Comedy, Animation, Western…"
    RuleField.STUDIO -> "NBC, Cartoon Network, Studio Ghibli…"
    RuleField.TAG -> "Tags from your Jellyfin metadata"
    RuleField.OFFICIAL_RATING -> "TV-Y, PG-13, R…"
    RuleField.YEAR -> "Release year"
    RuleField.DECADE -> "1980s, 1990s…"
    RuleField.KEYWORD -> "Words in titles and tags, like Christmas"
    RuleField.LIBRARY -> "A Jellyfin library"
    RuleField.SERIES -> "Specific series"
    RuleField.ITEM -> "Specific movies"
    RuleField.MEDIA_TYPE -> "Episodes, movies or music videos"
    RuleField.KIND -> "Anime, cartoons, unclear animation or neither, decided per show"
}

fun opLabel(field: RuleField, op: RuleOp): String = when (op) {
    RuleOp.EQUALS -> "is"
    RuleOp.CONTAINS -> "contains"
    RuleOp.ANY_OF -> if (field == RuleField.KEYWORD) "mentions any of" else "is any of"
    RuleOp.NOT_ANY_OF -> if (field == RuleField.KEYWORD) "mentions none of" else "is none of"
    RuleOp.AT_LEAST -> "is at least"
    RuleOp.AT_MOST -> "is at most"
    RuleOp.BETWEEN -> "is between"
    RuleOp.INCLUDE -> "always included"
    RuleOp.EXCLUDE -> "never included"
}

fun mediaTypeLabel(name: String): String = when (name.uppercase()) {
    MediaType.EPISODE.name -> "episodes"
    MediaType.MOVIE.name -> "movies"
    MediaType.MUSIC_VIDEO.name -> "music videos"
    else -> name.lowercase()
}

fun valueLabel(field: RuleField, value: String, titleOf: (String) -> String?, ratingOf: (String) -> String? = { null }): String = when (field) {
    RuleField.DECADE -> "${value}s"
    RuleField.OFFICIAL_RATING -> ratingOf(value) ?: value
    RuleField.MEDIA_TYPE -> mediaTypeLabel(value)
    RuleField.KIND -> kindLabel(value)
    RuleField.SERIES -> titleOf(value) ?: "a series not in your library"
    RuleField.ITEM -> titleOf(value) ?: "a movie not in your library"
    else -> value
}

/** "anime" → "anime"; the two kinds that aren't media types get words. */
fun kindLabel(value: String): String = when (value) {
    MediaKinds.NONE -> "neither"
    MediaKinds.UNCLEAR -> "unclear animation"
    else -> value
}

/** "Genre is any of Comedy, Sitcom", "Rating is at most TV-14, PG-13". [ratingOf] names a rating score. */
fun describeRule(rule: Rule, titleOf: (String) -> String?, ratingOf: (String) -> String? = { null }): String {
    val ranged = rule.op == RuleOp.AT_LEAST || rule.op == RuleOp.AT_MOST || rule.op == RuleOp.BETWEEN
    val values = rule.values.map { valueLabel(rule.field, it, titleOf, if (ranged) ratingOf else { _ -> null }) }
    val joined = if (rule.op == RuleOp.BETWEEN && values.size == 2) "${values[0]} and ${values[1]}" else values.joinToString(", ")
    return "${fieldLabel(rule.field)} ${opLabel(rule.field, rule.op)} $joined"
}

/** "182 episodes and 12 movies, 9 series, 64 hours". */
fun matchSummary(result: MatchResult): String {
    if (result.itemCount == 0) return "Nothing in your library matches yet"
    val counts = result.matches.groupingBy { it.item.row.type }.eachCount()
    val parts = listOfNotNull(
        counts[MediaType.EPISODE]?.let { "%,d %s".format(it, if (it == 1) "episode" else "episodes") },
        counts[MediaType.MOVIE]?.let { "%,d %s".format(it, if (it == 1) "movie" else "movies") },
        counts[MediaType.MUSIC_VIDEO]?.let { "%,d %s".format(it, if (it == 1) "music video" else "music videos") },
    )
    val series = result.matches.mapNotNullTo(HashSet()) { it.item.row.seriesRemoteId }.size
    val hours = result.totalDurationMs / 3_600_000L
    return buildList {
        add(parts.joinToString(" and "))
        if (series > 0) add("%,d series".format(series))
        add(if (hours < 1) "under an hour" else "%,d %s".format(hours, if (hours == 1L) "hour" else "hours"))
    }.joinToString(", ")
}

fun weightLabel(weight: Float): String =
    if (weight == weight.toInt().toFloat()) "×${weight.toInt()}" else "×$weight"

/** The weights offered: rarely, normal, then more and more often. */
val WeightSteps = listOf(0.5f, 1f, 2f, 3f, 5f)

fun weightHint(weight: Float): String = when {
    weight < 1f -> "Picked half as often."
    weight == 1f -> "Picked as often as the rest."
    else -> "Picked ${weightLabel(weight).removePrefix("×")} times as often."
}
