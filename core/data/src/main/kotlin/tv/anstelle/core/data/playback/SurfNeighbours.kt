package tv.anstelle.core.data.playback

/**
 * The channels to keep ready around [ids]' entry at [index] while someone surfs: the one [step]
 * (1 or -1) away first, then the one the other way. [wrap] goes round the ends as channel up and
 * down do; without it an end has nothing past it, like the guide's first and last rows.
 */
fun surfNeighbours(ids: List<Long>, index: Int, step: Int, wrap: Boolean): List<Long> {
    if (index !in ids.indices) return emptyList()
    return listOf(index + step, index - step)
        .mapNotNull { if (wrap) ids[Math.floorMod(it, ids.size)] else ids.getOrNull(it) }
        .filter { it != ids[index] }
        .distinct()
}
