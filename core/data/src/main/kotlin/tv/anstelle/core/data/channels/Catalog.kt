package tv.anstelle.core.data.channels

import tv.anstelle.core.database.dao.CatalogRow
import tv.anstelle.core.model.MediaType
import tv.anstelle.core.model.RuleField
import java.text.Normalizer
import java.util.Locale

/**
 * One playable item, prepared for rule matching: list fields are split and lowercased once,
 * so testing a rule is a few set lookups.
 */
class CatalogItem internal constructor(
    val row: CatalogRow,
    /** Its show's kind: a media type's key ("anime"), [MediaKinds.UNCLEAR] or [MediaKinds.NONE]. */
    val kind: String = MediaKinds.NONE,
) {
    val id: Long get() = row.id
    internal val genres = tokens(row.genres)
    internal val studios = tokens(row.studios)
    internal val tags = tokens(row.tags)
    internal val collections = tokens(row.collections)
    internal val rating: String? = row.officialRating?.lowercase(Locale.ROOT)
    internal val typeName: String = row.type.name.lowercase(Locale.ROOT)

    /** Title, show title and tags as space-separated words with a space at each end, for keyword matching. */
    internal val words: String = " " + normalizeWords(listOfNotNull(row.title, row.showTitle, row.tags).joinToString(" ")) + " "

    /** Show key for counting and scheduling; a standalone item is a show of its own. */
    val showKey: String get() = showKeyOf(row)

    private companion object {
        fun tokens(value: String): Array<String> =
            value.split('|').filter { it.isNotBlank() }.map { it.trim().lowercase(Locale.ROOT) }.toTypedArray()
    }
}

/** A show the builder can include or exclude. */
data class CatalogShow(val remoteId: String, val title: String, val itemCount: Int)

/** A value seen in the library for a list field, with how many items carry it. */
data class ValueCount(val value: String, val count: Int)

/**
 * The library as channel rules see it: every playable item of every server, loaded with one
 * query ([tv.anstelle.core.database.dao.MediaItemDao.catalog]). Series rows only name shows.
 * Each show's kind is decided from [kinds] (see [MediaKinds]).
 */
class Catalog(rows: List<CatalogRow>, kinds: List<MediaKind> = emptyList()) {
    val items: List<CatalogItem> = rows.filter { it.type in PlayableTypes }.let { playable ->
        val kindOf = MediaKinds.classify(playable, kinds)
        playable.map { CatalogItem(it, kindOf.getValue(showKeyOf(it))) }
    }

    private val seriesTitles: Map<String, String> =
        rows.filter { it.type == MediaType.SERIES }.associate { it.remoteId to it.title }

    private val byId: Map<Long, CatalogItem> by lazy { items.associateBy { it.id } }

    val isEmpty: Boolean get() = items.isEmpty()

    operator fun get(id: Long): CatalogItem? = byId[id]

    /** Shows (series, then movies and other standalone items), by title. */
    val shows: List<CatalogShow> by lazy {
        val counts = items.groupingBy { it.showKey }.eachCount()
        items.distinctBy { it.showKey }.map { item ->
            val series = item.row.seriesRemoteId
            CatalogShow(
                remoteId = series ?: item.row.remoteId,
                title = series?.let { seriesTitles[it] ?: item.row.showTitle } ?: item.row.title,
                itemCount = counts.getValue(item.showKey),
            )
        }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })
    }

    /** True when [remoteId] is a series (rather than a single item). */
    fun isSeries(remoteId: String): Boolean = remoteId in seriesTitles || items.any { it.row.seriesRemoteId == remoteId }

    /** The title of a show or item for display, or null when it isn't in the library. */
    fun titleOf(remoteId: String): String? =
        seriesTitles[remoteId] ?: items.firstOrNull { it.row.remoteId == remoteId }?.row?.title

    /** Distinct values of a list field (genre, studio, tag, collection, rating, kind), most common first. */
    fun values(field: RuleField): List<ValueCount> {
        if (field == RuleField.KIND) {
            return items.groupingBy { it.kind }.eachCount().entries
                .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
                .map { ValueCount(it.key, it.value) }
        }
        val counts = HashMap<String, Int>()
        val spelling = HashMap<String, String>()
        for (item in items) {
            val raw = when (field) {
                RuleField.GENRE -> item.row.genres
                RuleField.STUDIO -> item.row.studios
                RuleField.TAG -> item.row.tags
                RuleField.COLLECTION -> item.row.collections
                RuleField.OFFICIAL_RATING -> item.row.officialRating?.let { "|$it|" }.orEmpty()
                else -> return emptyList()
            }
            for (value in raw.split('|')) {
                if (value.isBlank()) continue
                val key = value.lowercase(Locale.ROOT)
                counts[key] = (counts[key] ?: 0) + 1
                spelling.putIfAbsent(key, value)
            }
        }
        return counts.entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .map { ValueCount(spelling.getValue(it.key), it.value) }
    }

    companion object {
        private val PlayableTypes = Rule.PlayableTypes.toSet()
    }
}

/** The show an item belongs to; a standalone item is a show of its own. */
internal fun showKeyOf(row: CatalogRow): String = row.seriesRemoteId ?: "item:${row.remoteId}"

/**
 * Decides what kind of show each show is, so anime and cartoons never share a channel made
 * from the library. The genres and tags name a media type (anime before cartoons); a type with
 * a language must hear it, and a type without one must not hear another type's language by
 * default:
 *
 * - Anime: its genres or tags say anime, and its audio has Japanese (or no language is known).
 * - Cartoons: its genres say animation and nothing says anime, and no episode or movie of it
 *   plays Japanese by default.
 * - [UNCLEAR]: animation whose signals disagree (an anime genre with only English audio, an
 *   untagged movie that plays Japanese). It airs on no anime, cartoon or plain channel.
 * - [NONE]: everything else.
 *
 * The whole show gets one kind: its episodes' genres, tags and audio are taken together.
 */
object MediaKinds {
    const val NONE = "none"
    const val UNCLEAR = "unclear"

    fun classify(rows: List<CatalogRow>, kinds: List<MediaKind>): Map<String, String> {
        class Show {
            var type = Int.MAX_VALUE
            val languages = HashSet<String>()
            val defaults = HashSet<String>()
        }
        val shows = HashMap<String, Show>()
        // Episodes repeat their show's lists, so each distinct list is read once.
        val typeOf = HashMap<Pair<String, String>, Int>()
        for (row in rows) {
            val show = shows.getOrPut(showKeyOf(row)) { Show() }
            show.type = minOf(show.type, typeOf.getOrPut(row.genres to row.tags) { typeOf(row, kinds) })
            val languages = row.audioLanguages.split('|').filter(String::isNotEmpty)
            show.languages += languages
            languages.firstOrNull()?.let { show.defaults += it }
        }
        val languages = kinds.mapNotNullTo(HashSet()) { it.language }
        return shows.mapValues { (_, show) ->
            val kind = kinds.getOrNull(show.type) ?: return@mapValues NONE
            val confirmed = when (val language = kind.language) {
                null -> show.defaults.none { it in languages }
                else -> show.languages.isEmpty() || language in show.languages
            }
            if (confirmed) kind.key else UNCLEAR
        }
    }

    /** The index of the first type the row's genres or tags name, or [Int.MAX_VALUE]. */
    private fun typeOf(row: CatalogRow, kinds: List<MediaKind>): Int {
        val genres = row.genres.split('|').flatMap { it.split('&') }.map(::valueKey).filter(String::isNotEmpty)
        val tags = row.tags.split('|').map(::valueKey).filter(String::isNotEmpty)
        val index = kinds.indexOfFirst { kind -> genres.any(kind.genreKeys::contains) || tags.any(kind.tagKeys::contains) }
        return if (index < 0) Int.MAX_VALUE else index
    }
}

private val NonWord = Regex("[^\\p{L}\\p{N}]+")
private val Marks = Regex("\\p{M}+")

/** Lowercase words separated by single spaces: `"Santa's  X-mas!"` → `"santa s x mas"`. */
internal fun normalizeWords(text: String): String = text.lowercase(Locale.ROOT).replace(NonWord, " ").trim()

/** A metadata value as a comparison key: words without accents, so `"Shōnen"` → `"shonen"` and `"Sci-Fi"` → `"sci fi"`. */
internal fun valueKey(text: String): String = normalizeWords(Normalizer.normalize(text, Normalizer.Form.NFD).replace(Marks, ""))
