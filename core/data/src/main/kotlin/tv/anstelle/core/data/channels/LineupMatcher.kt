package tv.anstelle.core.data.channels

import tv.anstelle.core.model.RuleField
import tv.anstelle.core.model.RuleOp
import java.util.Locale

/** A matched item and the weight it airs with. */
class Match(val item: CatalogItem, val weight: Float)

/** What a lineup matches in a catalog. */
class MatchResult(val matches: List<Match>) {
    val itemCount: Int get() = matches.size
    val showCount: Int by lazy { matches.mapTo(HashSet()) { it.item.showKey }.size }
    val totalDurationMs: Long by lazy { matches.sumOf { it.item.row.durationMs } }
}

/**
 * Evaluates a [Lineup] against catalog items in memory. Rules are compiled once (values
 * lowercased into sets, numbers parsed), so matching a whole library is a tight loop.
 */
class LineupMatcher(lineup: Lineup) {
    private val groups = lineup.groups.map { group -> CompiledGroup(group.rules.map(::CompiledRule), group.weight) }
    private val includedShows = lineup.includes.filter { it.field == RuleField.SERIES }.associate { it.remoteId.lowercase(Locale.ROOT) to it.weight }
    private val includedItems = lineup.includes.filter { it.field == RuleField.ITEM }.associate { it.remoteId.lowercase(Locale.ROOT) to it.weight }
    private val excludedShows = lineup.excludes.filter { it.field == RuleField.SERIES }.mapTo(HashSet()) { it.remoteId.lowercase(Locale.ROOT) }
    private val excludedItems = lineup.excludes.filter { it.field == RuleField.ITEM }.mapTo(HashSet()) { it.remoteId.lowercase(Locale.ROOT) }

    /** The weight [item] airs with, or 0 when the lineup doesn't take it. */
    fun weightOf(item: CatalogItem): Float {
        val remoteId = item.row.remoteId.lowercase(Locale.ROOT)
        val seriesId = item.row.seriesRemoteId?.lowercase(Locale.ROOT)
        if (remoteId in excludedItems || (seriesId != null && seriesId in excludedShows)) return 0f
        var weight = 0f
        for (group in groups) {
            if (group.weight > weight && group.rules.all { it.matches(item) }) weight = group.weight
        }
        includedItems[remoteId]?.let { weight = maxOf(weight, it) }
        if (seriesId != null) includedShows[seriesId]?.let { weight = maxOf(weight, it) }
        return weight
    }

    fun matches(item: CatalogItem): Boolean = weightOf(item) > 0f

    fun match(catalog: Catalog): MatchResult = match(catalog.items)

    fun match(items: List<CatalogItem>): MatchResult {
        val matches = ArrayList<Match>()
        for (item in items) {
            val weight = weightOf(item)
            if (weight > 0f) matches += Match(item, weight)
        }
        return MatchResult(matches)
    }

    private class CompiledGroup(val rules: List<CompiledRule>, val weight: Float)
}

private class CompiledRule(rule: Rule) {
    private val field = rule.field
    private val op = rule.op
    private val numeric = rule.isNumeric()
    private val texts: Set<String> = rule.values.mapTo(HashSet()) { it.trim().lowercase(Locale.ROOT) }
    private val text: String = texts.firstOrNull().orEmpty()
    private val numbers: IntArray = if (numeric) IntArray(rule.values.size) { rule.values[it].trim().toInt() } else IntArray(0)

    /** Keywords as " word " patterns, so they only match whole words or phrases. */
    private val keywords: List<String> =
        if (field == RuleField.KEYWORD) rule.values.map { " " + normalizeWords(it) + " " }.filter { it.isNotBlank() } else emptyList()

    fun matches(item: CatalogItem): Boolean = when (field) {
        RuleField.GENRE -> list(item.genres)
        RuleField.STUDIO -> list(item.studios)
        RuleField.TAG -> list(item.tags)
        RuleField.COLLECTION -> list(item.collections)
        RuleField.OFFICIAL_RATING -> if (numeric) number(item.row.ratingScore) else single(item.rating)
        RuleField.YEAR -> number(item.row.year)
        RuleField.DECADE -> number(item.row.year?.let { it - Math.floorMod(it, 10) })
        RuleField.KEYWORD -> {
            val found = keywords.any { item.words.contains(it) }
            if (op == RuleOp.NOT_ANY_OF) !found else found
        }
        RuleField.LIBRARY -> single(item.row.libraryRemoteId?.lowercase(Locale.ROOT))
        RuleField.SERIES -> single(item.row.seriesRemoteId?.lowercase(Locale.ROOT))
        RuleField.ITEM -> single(item.row.remoteId.lowercase(Locale.ROOT))
        RuleField.MEDIA_TYPE -> single(item.typeName)
        RuleField.KIND -> single(item.kind)
    }

    /** A multi-valued field: any value counts, and NOT_ANY_OF needs none of them. */
    private fun list(values: Array<String>): Boolean = when (op) {
        RuleOp.EQUALS, RuleOp.ANY_OF -> values.any { it in texts }
        RuleOp.NOT_ANY_OF -> values.none { it in texts }
        RuleOp.CONTAINS -> values.any { it.contains(text) }
        else -> false
    }

    /** A single-valued field. A missing value matches only NOT_ANY_OF. */
    private fun single(value: String?): Boolean = when (op) {
        RuleOp.EQUALS, RuleOp.ANY_OF -> value != null && value in texts
        RuleOp.NOT_ANY_OF -> value == null || value !in texts
        RuleOp.CONTAINS -> value != null && value.contains(text)
        else -> false
    }

    /** A numeric field. A missing value matches only NOT_ANY_OF. */
    private fun number(value: Int?): Boolean {
        if (value == null) return op == RuleOp.NOT_ANY_OF
        return when (op) {
            RuleOp.EQUALS, RuleOp.ANY_OF -> numbers.any { it == value }
            RuleOp.NOT_ANY_OF -> numbers.none { it == value }
            RuleOp.AT_LEAST -> value >= numbers[0]
            RuleOp.AT_MOST -> value <= numbers[0]
            RuleOp.BETWEEN -> value in numbers[0]..numbers[1]
            else -> false
        }
    }
}
