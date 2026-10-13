package tv.anstelle.core.data.channels

import tv.anstelle.core.model.MediaType
import tv.anstelle.core.model.RuleField
import tv.anstelle.core.model.RuleOp

/** A value to pick: what is stored, what is shown, and how many items have it. */
data class ValueOption(val value: String, val label: String, val count: Int)

/**
 * The values the lineup builder lists for a rule, from what the library has, in a fixed order
 * that picking never changes: alphabetical, or ascending for years and rating scores.
 */
class LineupChoices(private val catalog: Catalog) {
    /** The library's airtime, for a lineup's share of it. */
    val libraryMs: Long = catalog.items.sumOf { it.row.durationMs }

    /** Each rating score with the ratings the library gives it ("TV-14, PG-13"). */
    private val ratingNames: Map<Int, String> = catalog.items
        .mapNotNull { item -> item.row.ratingScore?.let { score -> item.row.officialRating?.let { score to it } } }
        .groupBy({ it.first }, { it.second })
        .mapValues { (_, names) -> names.distinct().sortedWith(String.CASE_INSENSITIVE_ORDER).take(3).joinToString(", ") }

    /** Names a rating score for a rule's description ("TV-14, PG-13"). */
    fun ratingOf(value: String): String? = value.toIntOrNull()?.let(ratingNames::get)

    /** The title of a show or movie, for picks and rules on them. */
    fun titleOf(remoteId: String): String? = catalog.titleOf(remoteId)

    /** "Genre is any of Comedy, Sitcom", in the builder's words. */
    fun describe(rule: Rule): String = describeRule(rule, catalog::titleOf, ::ratingOf)

    /** What [lineup] matches in the library. */
    fun match(lineup: Lineup): MatchResult = LineupMatcher(lineup).match(catalog)

    /** What [lineup] airs against the whole library. */
    fun reach(lineup: Lineup): LineupReach = LineupReach.of(match(lineup), libraryMs)

    /**
     * The values a rule on [field] with [op] lists. Picked values ([selected]) stay listed while
     * [query] filters the rest; a range's end lists only values from its start ([from]) on.
     */
    fun options(field: RuleField, op: RuleOp, query: String = "", selected: Set<String> = emptySet(), from: String? = null): List<ValueOption> {
        val start = from?.toIntOrNull()
        val values = choices(field, op).filter { start == null || (it.value.toIntOrNull() ?: Int.MIN_VALUE) >= start }
        return filtered(values, query, selected + listOfNotNull(from))
    }

    /** Series, or movies, to always or never include. */
    fun titles(movies: Boolean, query: String = ""): List<ValueOption> = filtered(if (movies) movies() else series(), query, emptySet())

    private fun filtered(all: List<ValueOption>, query: String, selected: Set<String>): List<ValueOption> {
        val needle = query.trim()
        return all.filter { needle.isEmpty() || it.value in selected || it.label.contains(needle, ignoreCase = true) }.take(MAX_OPTIONS)
    }

    private fun choices(field: RuleField, op: RuleOp): List<ValueOption> = when (field) {
        RuleField.MEDIA_TYPE -> Rule.PlayableTypes.map { type ->
            ValueOption(type.name, mediaTypeLabel(type.name), catalog.items.count { it.row.type == type })
        }.filter { it.count > 0 }.alphabetical()
        RuleField.SERIES -> series()
        RuleField.ITEM -> movies()
        RuleField.KIND -> catalog.values(field).map { ValueOption(it.value, kindLabel(it.value), it.count) }.alphabetical()
        RuleField.YEAR -> catalog.items.mapNotNull { it.row.year }.groupingBy { it }.eachCount().entries
            .sortedBy { it.key }
            .map { ValueOption(it.key.toString(), it.key.toString(), it.value) }
        // A rating compared by score picks a score, named by the ratings that have it.
        RuleField.OFFICIAL_RATING -> if (op == RuleOp.AT_LEAST || op == RuleOp.AT_MOST || op == RuleOp.BETWEEN) {
            catalog.items.mapNotNull { it.row.ratingScore }.groupingBy { it }.eachCount().entries
                .sortedBy { it.key }
                .map { ValueOption(it.key.toString(), ratingNames[it.key] ?: "Score ${it.key}", it.value) }
        } else {
            catalog.values(field).map { ValueOption(it.value, it.value, it.count) }.alphabetical()
        }
        else -> catalog.values(field).map { ValueOption(it.value, it.value, it.count) }.alphabetical()
    }

    private fun series(): List<ValueOption> =
        catalog.shows.filter { catalog.isSeries(it.remoteId) }.map { ValueOption(it.remoteId, it.title, it.itemCount) }.alphabetical()

    /** Movies by title, with the year when the library has one. */
    private fun movies(): List<ValueOption> = catalog.items.filter { it.row.type == MediaType.MOVIE }
        .map { item -> ValueOption(item.row.remoteId, item.row.year?.let { "${item.row.title} ($it)" } ?: item.row.title, 1) }
        .alphabetical()

    private fun List<ValueOption>.alphabetical() = sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })

    companion object {
        const val MAX_OPTIONS = 200
    }
}
