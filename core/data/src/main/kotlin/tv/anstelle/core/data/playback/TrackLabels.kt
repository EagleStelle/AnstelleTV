package tv.anstelle.core.data.playback

/** What a track says about itself, already in words: "English", "Signs", "5.1", "DD+". */
data class TrackFacts(
    val language: String?,
    /** The track's own title from the file, such as "Signs" or "Commentary". */
    val name: String? = null,
    val forced: Boolean = false,
    /** "stereo", "5.1"… */
    val channels: String? = null,
    /** "DD", "TrueHD", "image"… */
    val codec: String? = null,
)

/**
 * Names for a player's track list. A track is called by its language alone. When two share
 * a language, each adds only the facts that tell that language's tracks apart ("English ·
 * Signs" and "English · Full", not every field the file carries); when nothing does, they are
 * numbered. A track with no language is named by its facts, or "Track 3".
 */
object TrackLabels {
    fun of(tracks: List<TrackFacts>): List<String> {
        val labels = arrayOfNulls<String>(tracks.size)
        tracks.indices.groupBy { tracks[it].language }.forEach { (language, members) ->
            if (language != null && members.size == 1) {
                labels[members[0]] = language
                return@forEach
            }
            val group = members.map { tracks[it] }
            val facets = FACETS.filter { facet -> group.map(facet).distinct().size > 1 }
            val named = members.map { i ->
                val parts = listOfNotNull(language) + facets.mapNotNull { it(tracks[i]) }
                if (parts.isEmpty()) "Track ${i + 1}" else parts.distinct().joinToString(" · ")
            }
            // Facts that still leave two alike get a number, within the language.
            val repeated = named.groupingBy { it }.eachCount()
            var n = 0
            members.forEachIndexed { k, i ->
                labels[i] = when {
                    repeated.getValue(named[k]) == 1 -> named[k]
                    language != null -> "${named[k]} ${++n}"
                    else -> "Track ${i + 1}"
                }
            }
        }
        @Suppress("UNCHECKED_CAST")
        return labels.toList() as List<String>
    }

    private val FACETS: List<(TrackFacts) -> String?> = listOf(
        { it.name },
        { if (it.forced) "forced" else null },
        { it.channels },
        { it.codec },
    )
}
