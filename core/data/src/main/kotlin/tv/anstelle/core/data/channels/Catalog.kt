package tv.anstelle.core.data.channels

import tv.anstelle.core.database.dao.CatalogRow
import tv.anstelle.core.model.MediaType
import tv.anstelle.core.model.RuleField
import java.util.Locale

/**
 * One playable item, prepared for rule matching: list fields are split and lowercased once,
 * so testing a rule is a few set lookups.
 */
class CatalogItem internal constructor(val row: CatalogRow) {
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
    val showKey: String get() = row.seriesRemoteId ?: "item:${row.remoteId}"

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
 */
class Catalog(rows: List<CatalogRow>) {
    val items: List<CatalogItem> = rows.filter { it.type in PlayableTypes }.map(::CatalogItem)

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

    /** Distinct values of a list field (genre, studio, tag, collection, rating), most common first. */
    fun values(field: RuleField): List<ValueCount> {
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

private val NonWord = Regex("[^\\p{L}\\p{N}]+")

/** Lowercase words separated by single spaces: `"Santa's  X-mas!"` → `"santa s x mas"`. */
internal fun normalizeWords(text: String): String = text.lowercase(Locale.ROOT).replace(NonWord, " ").trim()
