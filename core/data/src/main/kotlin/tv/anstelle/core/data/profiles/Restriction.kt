package tv.anstelle.core.data.profiles

import tv.anstelle.core.model.ProfileAccess
import tv.anstelle.core.model.RatingLimits

/**
 * Why the watching profile may not watch a program. Channels are the same for every profile,
 * so such a program still airs; for this profile its picture, artwork and overview are blacked
 * out and it can't be played on demand. The profile is told only [title]: never the program's
 * rating, the profile's own limit or where the program comes from.
 */
sealed interface Restriction {
    /** "Age restricted" or "No access". */
    val title: String

    /** Over the profile's parental rating. */
    data object Age : Restriction {
        override val title: String get() = AGE_TITLE
    }

    /** The profile's own server user can't see it: a library not shared with that user, or the user's parental controls. */
    data object NoAccess : Restriction {
        override val title: String get() = NO_ACCESS_TITLE
    }

    companion object {
        const val AGE_TITLE = "Age restricted"
        const val NO_ACCESS_TITLE = "No access"

        /** The title for item [itemId] when the profile with [access] may not watch it. */
        fun titleFor(itemId: Long, access: ProfileAccess): String = if (access.isHidden(itemId)) NO_ACCESS_TITLE else AGE_TITLE

        /** The restriction on item [itemId], rated [score], for a profile with [access], or null when the profile may watch it. */
        fun of(itemId: Long, score: Int?, access: ProfileAccess): Restriction? = when {
            access.isHidden(itemId) -> NoAccess
            !RatingLimits.allows(access.limit, score) -> Age
            else -> null
        }
    }
}
