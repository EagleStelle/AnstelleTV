package tv.anstelle.core.data.channels

/** A template the library has enough content for. */
class PlannedChannel(val template: ChannelTemplate, val match: MatchResult)

/** Decides which template channels a profile gets. Pure, so it is tested without a database. */
object ChannelPlanner {
    /**
     * Templates, in template order, that match enough of [catalog] and are not in
     * [existingTemplateIds]. With [maxRatingScore] (a kid profile), only items rated at or
     * below it count, the same filter its schedules use.
     */
    fun plan(
        templates: List<ChannelTemplate>,
        catalog: Catalog,
        existingTemplateIds: Set<String>,
        maxRatingScore: Int? = null,
    ): List<PlannedChannel> {
        val items = catalog.items.filter { allowedFor(it, maxRatingScore) }
        return templates.mapNotNull { template ->
            if (template.id in existingTemplateIds) return@mapNotNull null
            val match = LineupMatcher(template.lineup).match(items)
            if (match.itemCount >= template.minItems && match.showCount >= template.minShows) PlannedChannel(template, match) else null
        }
    }

    /** Unrated items never pass a rating limit, like the SQL `ratingScore <= :max` filter. */
    fun allowedFor(item: CatalogItem, maxRatingScore: Int?): Boolean {
        if (maxRatingScore == null) return true
        val score = item.row.ratingScore ?: return false
        return score <= maxRatingScore
    }
}
