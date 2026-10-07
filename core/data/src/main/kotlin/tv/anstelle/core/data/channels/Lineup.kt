package tv.anstelle.core.data.channels

import tv.anstelle.core.database.entity.ChannelRuleEntity
import tv.anstelle.core.model.MediaType
import tv.anstelle.core.model.RuleField
import tv.anstelle.core.model.RuleOp

/**
 * One condition on an item's metadata, e.g. "genre is any of Comedy, Sitcom". Text compares
 * ignoring case. Numeric fields take whole numbers; BETWEEN takes a minimum and a maximum.
 */
data class Rule(val field: RuleField, val op: RuleOp, val values: List<String>) {
    constructor(field: RuleField, op: RuleOp, vararg values: Any) : this(field, op, values.map { it.toString() })

    /** Why the rule can't be evaluated, or null when it is valid. */
    fun problem(): String? {
        if (op !in opsFor(field)) return "$op does not apply to $field"
        val expected = when (op) {
            RuleOp.BETWEEN -> 2..2
            RuleOp.ANY_OF, RuleOp.NOT_ANY_OF -> 1..Int.MAX_VALUE
            else -> 1..1
        }
        if (values.size !in expected) return "$op takes ${if (expected.first == expected.last) expected.first else "one or more"} values, not ${values.size}"
        if (values.any { it.isBlank() }) return "Empty value"
        if (isNumeric()) {
            val numbers = values.map { it.trim().toIntOrNull() ?: return "Not a whole number: $it" }
            if (op == RuleOp.BETWEEN && numbers[0] > numbers[1]) return "BETWEEN ${numbers[0]} and ${numbers[1]} is empty"
            if (field == RuleField.DECADE && numbers.any { it % 10 != 0 }) return "A decade is written as its first year, like 1980"
        }
        if (field == RuleField.MEDIA_TYPE) {
            val names = PlayableTypes.map { it.name }
            values.firstOrNull { it.trim().uppercase() !in names }?.let { return "Unknown media type $it" }
        }
        return null
    }

    /** True when the values are numbers: numeric fields, and rating comparisons (by score). */
    internal fun isNumeric(): Boolean = when (field) {
        RuleField.YEAR, RuleField.DECADE -> true
        RuleField.OFFICIAL_RATING -> op in RangeOps
        else -> false
    }

    companion object {
        /** Item types that can air. Series rows only carry metadata. */
        val PlayableTypes = listOf(MediaType.EPISODE, MediaType.MOVIE, MediaType.MUSIC_VIDEO)

        private val RangeOps = setOf(RuleOp.AT_LEAST, RuleOp.AT_MOST, RuleOp.BETWEEN)
        private val ListOps = listOf(RuleOp.EQUALS, RuleOp.ANY_OF, RuleOp.NOT_ANY_OF)

        /** The operators a rule on [field] may use. */
        fun opsFor(field: RuleField): List<RuleOp> = when (field) {
            RuleField.GENRE, RuleField.STUDIO, RuleField.TAG, RuleField.COLLECTION ->
                listOf(RuleOp.ANY_OF, RuleOp.NOT_ANY_OF, RuleOp.EQUALS, RuleOp.CONTAINS)
            RuleField.OFFICIAL_RATING ->
                listOf(RuleOp.AT_MOST, RuleOp.AT_LEAST, RuleOp.BETWEEN, RuleOp.ANY_OF, RuleOp.NOT_ANY_OF, RuleOp.EQUALS, RuleOp.CONTAINS)
            RuleField.YEAR, RuleField.DECADE ->
                listOf(RuleOp.BETWEEN, RuleOp.AT_LEAST, RuleOp.AT_MOST) + ListOps
            RuleField.KEYWORD -> listOf(RuleOp.ANY_OF, RuleOp.NOT_ANY_OF)
            RuleField.LIBRARY, RuleField.SERIES, RuleField.ITEM, RuleField.MEDIA_TYPE, RuleField.KIND -> ListOps
        }
    }
}

/** Rules that must all hold (AND). Items matching the group air with [weight]. */
data class RuleGroup(val rules: List<Rule>, val weight: Float = 1f)

/** A show ([RuleField.SERIES]) or a single item ([RuleField.ITEM]), by Jellyfin id. */
data class Pick(val field: RuleField, val remoteId: String, val weight: Float = 1f) {
    init {
        require(field == RuleField.SERIES || field == RuleField.ITEM) { "A pick is a show or an item, not $field" }
    }
}

/**
 * What a channel airs. An item belongs to the lineup when it matches any group (OR) or is
 * included, and is not excluded. Its weight is the highest of the groups it matches and the
 * picks that include it; show-based playback orders pick shows by weight.
 */
data class Lineup(
    val groups: List<RuleGroup>,
    val includes: List<Pick> = emptyList(),
    val excludes: List<Pick> = emptyList(),
) {
    val isEmpty: Boolean get() = groups.isEmpty() && includes.isEmpty()

    /** Why the lineup can't be used, or null when it is valid. */
    fun problem(): String? {
        for (group in groups) {
            if (group.rules.isEmpty()) return "A rule group has no rules"
            if (!(group.weight > 0f)) return "Weights must be above 0"
            group.rules.firstNotNullOfOrNull { it.problem() }?.let { return it }
        }
        if ((includes + excludes).any { !(it.weight > 0f) || it.remoteId.isBlank() }) return "Bad show or item pick"
        return null
    }

    companion object {
        val EMPTY = Lineup(emptyList())
    }
}

/**
 * Stores lineups as `channel_rule` rows. Each group's rules share a `logicGroup` and carry
 * the group's weight; picks are rows with the INCLUDE or EXCLUDE operator. Lists are stored
 * `|`-separated, like the item metadata they are compared with.
 */
object LineupRows {
    /** `logicGroup` of pick rows, which belong to no group. */
    const val PICK_GROUP = -1

    fun encode(channelId: Long, seasonId: Long?, lineup: Lineup, firstGroup: Int = 0): List<ChannelRuleEntity> = buildList {
        lineup.groups.forEachIndexed { i, group ->
            for (rule in group.rules) {
                add(row(channelId, seasonId, firstGroup + i, rule.field, rule.op, encodeValues(rule.values), group.weight))
            }
        }
        for (pick in lineup.includes) add(row(channelId, seasonId, PICK_GROUP, pick.field, RuleOp.INCLUDE, clean(pick.remoteId), pick.weight))
        for (pick in lineup.excludes) add(row(channelId, seasonId, PICK_GROUP, pick.field, RuleOp.EXCLUDE, clean(pick.remoteId), 1f))
    }

    /** Groups by `logicGroup` plus picks, from the rows of one lineup (same season). Invalid rows are skipped. */
    fun decode(rows: List<ChannelRuleEntity>): DecodedRules {
        val groups = sortedMapOf<Int, MutableList<Pair<Rule, Float>>>()
        val includes = ArrayList<Pick>()
        val excludes = ArrayList<Pick>()
        for (row in rows) {
            when (row.op) {
                RuleOp.INCLUDE, RuleOp.EXCLUDE -> {
                    if (row.field != RuleField.SERIES && row.field != RuleField.ITEM) continue
                    val pick = Pick(row.field, row.value, row.weight)
                    if (row.op == RuleOp.INCLUDE) includes += pick else excludes += pick
                }
                else -> {
                    val rule = Rule(row.field, row.op, decodeValues(row.value))
                    if (rule.problem() == null) groups.getOrPut(row.logicGroup) { ArrayList() } += rule to row.weight
                }
            }
        }
        return DecodedRules(
            groups = groups.mapValues { (_, rules) -> RuleGroup(rules.map { it.first }, rules.maxOf { it.second }) },
            includes = includes,
            excludes = excludes,
        )
    }

    fun encodeValues(values: List<String>): String = values.joinToString("|") { clean(it) }

    fun decodeValues(value: String): List<String> = value.split('|').map { it.trim() }.filter { it.isNotEmpty() }

    private fun clean(value: String) = value.replace('|', '/').trim()

    private fun row(channelId: Long, seasonId: Long?, group: Int, field: RuleField, op: RuleOp, value: String, weight: Float) =
        ChannelRuleEntity(channelId = channelId, seasonId = seasonId, logicGroup = group, field = field, op = op, value = value, weight = weight)
}

/** Rule rows read back: groups keyed by their `logicGroup`, and picks. */
data class DecodedRules(
    val groups: Map<Int, RuleGroup>,
    val includes: List<Pick>,
    val excludes: List<Pick>,
) {
    /** The lineup without the groups in [except] (groups that only filter a daypart). */
    fun lineup(except: Set<Int> = emptySet()): Lineup =
        Lineup(groups.filterKeys { it !in except }.values.toList(), includes, excludes)
}
