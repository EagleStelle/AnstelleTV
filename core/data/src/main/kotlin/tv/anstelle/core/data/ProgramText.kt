package tv.anstelle.core.data

/** What a guide cell, banner or tile calls a program: the show for an episode, else the item. */
fun programTitle(title: String, showTitle: String?): String = showTitle ?: title

/** "S1E4 - Pilot" for an episode, a movie's year, or null. */
fun programDetail(title: String, showTitle: String?, season: Int?, episode: Int?, year: Int?): String? {
    if (showTitle == null) return year?.toString()
    val number = if (season != null && episode != null) "S${season}E$episode" else null
    return listOfNotNull(number, title.ifBlank { null }).joinToString(" - ").ifEmpty { null }
}

/**
 * The line under a program's logo. An episode's is its number and title ("S1E4 - Pilot"). A
 * movie's logo stands for its title, so the line names it with its year ("Title (2025)");
 * without a logo the title is set as text above, and the year stays alone.
 */
fun lineUnderLogo(detail: String?, title: String, isEpisode: Boolean, hasLogo: Boolean): String? = when {
    isEpisode || !hasLogo || title.isBlank() -> detail
    detail == null -> title
    else -> "$title ($detail)"
}
