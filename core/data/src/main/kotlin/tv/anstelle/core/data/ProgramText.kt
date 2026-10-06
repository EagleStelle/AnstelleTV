package tv.anstelle.core.data

/** What a guide cell, banner or tile calls a program: the show for an episode, else the item. */
fun programTitle(title: String, showTitle: String?): String = showTitle ?: title

/** "S1E4 · Pilot" for an episode, a movie's year, or null. */
fun programDetail(title: String, showTitle: String?, season: Int?, episode: Int?, year: Int?): String? {
    if (showTitle == null) return year?.toString()
    val number = if (season != null && episode != null) "S${season}E$episode" else null
    return listOfNotNull(number, title.ifBlank { null }).joinToString(" · ").ifEmpty { null }
}
