package tv.anstelle.core.data.profiles

import tv.anstelle.core.model.RatingLimits

/**
 * A program over the watching profile's maturity rating. Channels are the same for every
 * profile, so such a program still airs; for this profile its picture, artwork and overview
 * are blacked out and it can't be played on demand.
 */
data class AgeRestriction(
    /** The program's rating as the library names it ("R-16"); null when it has none. */
    val rating: String?,
    /** The profile's limit, named the same way ("PG-13"). */
    val limit: String,
) {
    /** "Rated R-16 · Limit PG-13", or "Not rated · Limit PG-13". */
    val detail: String get() = "${rating?.let { "Rated $it" } ?: "Not rated"} · Limit $limit"

    companion object {
        const val TITLE = "Age restricted"

        /** The restriction on a program rated [rating] with [score] for a profile limited to [limit] (named [limitName]), or null when allowed. */
        fun of(rating: String?, score: Int?, limit: Int?, limitName: String): AgeRestriction? =
            if (RatingLimits.allows(limit, score)) null else AgeRestriction(rating, limitName)
    }
}
