package tv.anstelle.core.data.profiles

import tv.anstelle.core.model.ProfileAccess
import tv.anstelle.core.model.RatingLimits

/**
 * Why the watching profile may not watch a program. Channels are the same for every profile,
 * so such a program still airs; for this profile its picture, artwork and overview are blacked
 * out and it can't be played on demand.
 */
sealed interface Restriction {
    /** "Age restricted" or "No access". */
    val title: String

    /** "Rated R-16 · Limit PG-13", or "Not shared on Jellyfin". */
    val detail: String

    /** A tile's line under [title]: the program's rating, or why. */
    val short: String

    /** Over the profile's parental rating. */
    data class Age(
        /** The program's rating as the library names it ("R-16"); null when it has none. */
        val rating: String?,
        /** The profile's limit, named the same way ("PG-13"). */
        val limit: String,
    ) : Restriction {
        override val title: String get() = AGE_TITLE
        override val detail: String get() = "$short · Limit $limit"
        override val short: String get() = rating?.let { "Rated $it" } ?: "Not rated"
    }

    /** The profile's own Jellyfin user can't see it: a library not shared with that user, or the user's parental controls. */
    data object NoAccess : Restriction {
        override val title: String get() = NO_ACCESS_TITLE
        override val detail: String get() = "Not shared on Jellyfin"
        override val short: String get() = detail
    }

    companion object {
        const val AGE_TITLE = "Age restricted"
        const val NO_ACCESS_TITLE = "No access"

        /** The title for item [itemId] when the profile with [access] may not watch it. */
        fun titleFor(itemId: Long, access: ProfileAccess): String = if (access.isHidden(itemId)) NO_ACCESS_TITLE else AGE_TITLE

        /**
         * The restriction on item [itemId], rated [rating] with [score], for a profile with
         * [access] (its limit named [limitName]), or null when the profile may watch it.
         */
        fun of(itemId: Long, rating: String?, score: Int?, access: ProfileAccess, limitName: String): Restriction? = when {
            access.isHidden(itemId) -> NoAccess
            !RatingLimits.allows(access.limit, score) -> Age(rating, limitName)
            else -> null
        }
    }
}
