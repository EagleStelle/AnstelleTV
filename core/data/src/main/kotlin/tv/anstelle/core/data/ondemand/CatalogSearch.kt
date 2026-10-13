package tv.anstelle.core.data.ondemand

import java.text.Normalizer
import java.util.Locale

/**
 * What search results are ordered by. A sort with order labels runs either way: [descending]
 * is the way it starts when picked, [ascendingLabel] and [descendingLabel] name each way.
 */
enum class SearchSort(
    val label: String,
    val descending: Boolean = false,
    private val ascendingLabel: String? = null,
    private val descendingLabel: String? = null,
) {
    /** The title itself, then titles starting with the search, then words starting with it. It only runs one way. */
    BEST("Best match"),
    TITLE("Title", descending = false, ascendingLabel = "A to Z", descendingLabel = "Z to A"),
    YEAR("Year", descending = true, ascendingLabel = "Oldest first", descendingLabel = "Newest first"),
    ADDED("Date added", descending = true, ascendingLabel = "Oldest first", descendingLabel = "Newest first"),
    ;

    /** Whether the order can be turned around. */
    val reversible: Boolean get() = ascendingLabel != null

    /** The way it runs, "Z to A"; null for a sort that only runs one way. */
    fun orderLabel(descending: Boolean): String? = if (descending) descendingLabel else ascendingLabel
}

/** Which titles by what was watched of them. */
enum class WatchFilter(val label: String) {
    ANY("Any"),
    UNWATCHED("Not watched"),
    WATCHED("Watched"),
}

/** What narrows search results, and how they are ordered. Null means any. */
data class SearchFilters(
    val kind: CatalogKind? = null,
    val genre: String? = null,
    /** A decade's first year, 1990. */
    val decade: Int? = null,
    val watch: WatchFilter = WatchFilter.ANY,
    val sort: SearchSort = SearchSort.BEST,
    /** Largest first (Z to A, newest first); ignored by [SearchSort.BEST]. */
    val descending: Boolean = sort.descending,
) {
    /** Whether anything narrows the results. */
    val narrows: Boolean get() = kind != null || genre != null || decade != null || watch != WatchFilter.ANY

    /** Sorted by [sort], starting the way it starts. */
    fun sortedBy(sort: SearchSort) = copy(sort = sort, descending = sort.descending)

    /** The same sort, the other way round. */
    fun reversed() = copy(descending = !descending)
}

/** The titles that answer a search, and what can narrow them further. */
data class SearchResults(
    val titles: List<Title> = emptyList(),
    /** Every title the words found, before filters: what the counts and choices come from. */
    val found: Int = 0,
    /** The genres among what the words found, most common first. */
    val genres: List<String> = emptyList(),
    /** The decades among what the words found, newest first. */
    val decades: List<Int> = emptyList(),
)

/**
 * Searching the catalog on the device: every word typed must appear in the title (case and
 * accents ignored), then the filters narrow what that found and the sort orders it.
 */
object CatalogSearch {
    fun search(titles: List<Title>, query: String, filters: SearchFilters, watch: CatalogWatch): SearchResults {
        val words = normalize(query).split(' ').filter(String::isNotEmpty)
        if (words.isEmpty()) return SearchResults()
        val phrase = words.joinToString(" ")
        val found = titles.mapNotNull { title ->
            val name = normalize(title.title)
            if (words.all { it in name }) title to rank(name, phrase, words.first()) else null
        }
        val narrowed = found.filter { (title, _) ->
            (filters.kind == null || title.kind == filters.kind) &&
                (filters.genre == null || title.genres.any { it.equals(filters.genre, ignoreCase = true) }) &&
                (filters.decade == null || title.year?.let { it / 10 * 10 } == filters.decade) &&
                when (filters.watch) {
                    WatchFilter.ANY -> true
                    WatchFilter.UNWATCHED -> !watch.watched(title)
                    WatchFilter.WATCHED -> watch.watched(title)
                }
        }
        val byTitle = compareBy<Pair<Title, Int>> { it.first.sortKey }
        val sorted = if (!filters.sort.reversible) {
            narrowed.sortedWith(compareBy<Pair<Title, Int>> { it.second }.then(byTitle))
        } else {
            // Titles without the value sort last either way; ties go A to Z either way.
            val known = narrowed.filter { value(it.first, filters.sort) != null }
            val unknown = narrowed.filter { value(it.first, filters.sort) == null }.sortedWith(byTitle)
            val primary = compareBy<Pair<Title, Int>> { value(it.first, filters.sort) }
            known.sortedWith((if (filters.descending) primary.reversed() else primary).then(byTitle)) + unknown
        }
        val all = found.map { it.first }
        return SearchResults(
            titles = sorted.map { it.first },
            found = all.size,
            genres = all.flatMap { it.genres }.groupingBy { it }.eachCount().entries
                .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
                .map { it.key },
            decades = all.mapNotNull { it.year?.let { year -> year / 10 * 10 } }.distinct().sortedDescending(),
        )
    }

    /** What [sort] orders [title] by, or null when it has none (no year, no date added). */
    private fun value(title: Title, sort: SearchSort): Comparable<*>? = when (sort) {
        SearchSort.BEST -> 0
        SearchSort.TITLE -> title.sortKey
        SearchSort.YEAR -> title.year
        SearchSort.ADDED -> title.addedUtcMs.takeIf { it > 0 }
    }

    /** 0 for the title itself, 1 when it starts with the search, 2 when one of its words does, 3 otherwise. */
    private fun rank(name: String, phrase: String, first: String): Int = when {
        name == phrase -> 0
        name.startsWith(phrase) -> 1
        name.split(' ').any { it.startsWith(first) } -> 2
        else -> 3
    }

    /** Lowercase words without accents or punctuation, one space apart: `"Amélie (2001)"` → `"amelie 2001"`. */
    fun normalize(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFD).replace(Marks, "").lowercase(Locale.ROOT).replace(NonWord, " ").trim()

    private val Marks = Regex("\\p{M}+")
    private val NonWord = Regex("[^\\p{L}\\p{N}]+")
}
