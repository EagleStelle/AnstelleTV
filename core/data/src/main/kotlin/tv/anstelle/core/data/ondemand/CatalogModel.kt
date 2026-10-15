package tv.anstelle.core.data.ondemand

import tv.anstelle.core.data.Artwork
import tv.anstelle.core.model.Channel
import tv.anstelle.core.model.MediaType

/**
 * A kind of title On Demand gives a tab of its own, with a banner and rows of its titles. Each
 * kind says which items are its titles, which items its channels air of them (the title itself,
 * or its episodes), and its rows that aren't channels. Music joins as one more kind.
 */
enum class CatalogKind(
    /** Stable: settings are stored under it. */
    val key: String,
    val label: String,
    /** The item type a title of this kind is. */
    val titleType: MediaType,
    /** The item type a channel airs of such a title: the title itself, or its episodes. */
    val airedType: MediaType,
    /** The rows that aren't a channel's, in their default order, ahead of the channels. */
    val fixedShelves: List<ShelfType>,
    private val one: String,
    private val many: String,
) {
    MOVIES("movies", "Movies", MediaType.MOVIE, MediaType.MOVIE, listOf(ShelfType.CONTINUE, ShelfType.RECENT), "movie", "movies"),
    SERIES("series", "Series", MediaType.SERIES, MediaType.EPISODE, listOf(ShelfType.CONTINUE, ShelfType.NEXT_UP, ShelfType.RECENT), "series", "series"),
    ;

    /** "1 movie", "12 series". */
    fun count(n: Int): String = if (n == 1) "1 $one" else "$n $many"

    /** Whether a channel airs this kind's titles through their episodes. */
    val episodic: Boolean get() = airedType != titleType

    companion object {
        fun of(type: MediaType): CatalogKind? = entries.firstOrNull { it.titleType == type }
    }
}

/** What a catalog row holds. [key] is stable: row orders are stored by it. */
enum class ShelfType(val key: String, val label: String) {
    /** Left part-way, last played first. */
    CONTINUE("continue", "Continue watching"),

    /** The episode after the last one watched through, for each series being watched. */
    NEXT_UP("nextup", "Next up"),

    /** Newest in the library first; a series counts from its newest episode. */
    RECENT("recent", "Recently added"),

    /** Everything one library channel airs of the kind. */
    CHANNEL("channel", ""),
}

/** One row of a catalog tab: a fixed row, or a library channel's titles. */
data class Shelf(val type: ShelfType, val channel: Channel? = null) {
    val key: String get() = if (channel != null) "${ShelfType.CHANNEL.key}:${channel.id}" else type.key
    val label: String get() = channel?.name ?: type.label
}

/** A movie or series as the catalog shows it. */
data class Title(
    val id: Long,
    val serverId: Long,
    val remoteId: String,
    val kind: CatalogKind,
    val title: String,
    /** Lowercase, without accents or a leading article's punctuation: what titles sort by. */
    val sortKey: String,
    val year: Int?,
    /** The official rating, "PG-13". */
    val rating: String?,
    val genres: List<String>,
    val overview: String?,
    /** A movie's length; 0 for a series. */
    val durationMs: Long,
    val posterUrl: String?,
    val artwork: Artwork,
    /** When it joined the library (a series: its newest episode); 0 when unknown. */
    val addedUtcMs: Long,
)

/**
 * Where the profile's Jellyfin user stands with one title: how far into a movie
 * ([resumeMs]), whether it is watched through, and for a series how many episodes are left.
 */
data class TitleProgress(val resumeMs: Long, val played: Boolean, val unwatched: Int?)

/** The next or resumable episode represented by a series catalog card. */
data class SeriesProgress(
    val itemId: Long,
    val number: String?,
    val episodeTitle: String?,
    val positionMs: Long,
)

/**
 * What the profile's Jellyfin user watched, by local title id. [known] is false when the server
 * can't be reached or the profile keeps no watch history there: then posters say nothing.
 */
data class CatalogWatch(
    val byId: Map<Long, TitleProgress> = emptyMap(),
    val known: Boolean = false,
    val seriesProgress: Map<Long, SeriesProgress> = emptyMap(),
) {
    operator fun get(id: Long): TitleProgress? = byId[id]

    /** Only the server's played flag counts; progress alone never implies watched. */
    fun watched(title: Title): Boolean {
        val progress = byId[title.id] ?: return false
        return progress.played
    }

    /** How many of a series' episodes are still to watch; null for a movie or when unknown. */
    fun unwatched(title: Title): Int? = byId[title.id]?.unwatched?.takeIf { title.kind.episodic && known }

}

/** A movie or episode on Continue watching or Next up: what plays, and the page it belongs to. */
data class WatchCard(
    /** The movie or episode that plays. */
    val itemId: Long,
    /** The movie's or series' page. */
    val titleId: Long,
    val kind: CatalogKind,
    /** The movie's or series' title. */
    val title: String,
    /** "S2E3", or null for a movie. */
    val number: String?,
    /** The episode's own title, or null for a movie. */
    val episodeTitle: String?,
    /** The episode's still, else the backdrop. */
    val imageUrl: String?,
    /** A movie's official rating; null for an episode or when it has none. */
    val rating: String? = null,
    val durationMs: Long,
    /** Where it was left; 0 for one not started. */
    val positionMs: Long,
)

/** A movie's or series' page: its hero, and for a series every episode the profile may watch. */
data class TitlePage(
    val id: Long,
    val kind: CatalogKind,
    val title: String,
    val year: Int?,
    val rating: String?,
    val genres: List<String>,
    /** A movie's length; 0 for a series. */
    val durationMs: Long,
    val overview: String?,
    /** A movie's picture and sound, as the guide's badges. */
    val badges: List<String>,
    val posterUrl: String?,
    val artwork: Artwork,
    /** In order; empty for a movie. */
    val episodes: List<EpisodeEntry>,
) {
    val seasonCount: Int get() = episodes.mapNotNullTo(HashSet()) { it.season?.takeIf { s -> s > 0 } }.size
}

/** The episode a series page leads with. */
object NextEpisode {
    /**
     * The episode to go on with: after the last one watched through, the first left part-way,
     * else the first after it; once every episode after it is watched, the first not watched,
     * else the first. Specials count only for a show that has nothing else.
     */
    fun of(episodes: List<EpisodeEntry>, watch: WatchMarks): EpisodeEntry? {
        val list = episodes.filter { it.season != 0 }.ifEmpty { episodes }
        if (list.isEmpty()) return null
        val last = list.indexOfLast { watch.watched(it.id, it.durationMs) }
        val after = list.drop(last + 1)
        return after.firstOrNull { watch.resumeMs(it.id, it.durationMs) > 0 }
            ?: after.firstOrNull()
            ?: list.firstOrNull { !watch.watched(it.id, it.durationMs) }
            ?: list.first()
    }
}

/**
 * A row order as stored ([tv.anstelle.core.data.Settings.catalogShelves]): the rows' keys,
 * joined by commas. Rows the order doesn't name (a new channel) follow in their default order.
 */
object ShelfOrder {
    fun decode(raw: String): List<String> = raw.split(',').map(String::trim).filter(String::isNotEmpty)

    fun encode(keys: List<String>): String = keys.joinToString(",")

    /** [available] (in default order) arranged by [stored]: the rows it names in its order, then the rest. */
    fun arrange(available: List<Shelf>, stored: List<String>): List<Shelf> {
        val byKey = available.associateBy { it.key }
        val placed = stored.distinct().mapNotNull(byKey::get)
        val keys = placed.mapTo(HashSet()) { it.key }
        return placed + available.filter { it.key !in keys }
    }

    /** The order to store after the row at [from] in [shown] is set down at [to]. */
    fun moved(shown: List<Shelf>, from: Int, to: Int): List<String> {
        val keys = shown.map { it.key }.toMutableList()
        if (from !in keys.indices || to !in keys.indices) return keys
        keys.add(to, keys.removeAt(from))
        return keys
    }
}
