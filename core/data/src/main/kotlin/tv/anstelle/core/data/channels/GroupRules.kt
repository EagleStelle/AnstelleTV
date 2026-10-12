package tv.anstelle.core.data.channels

import tv.anstelle.core.model.ChannelGroup

/**
 * What makes a group's name and start fit, shared by the repository and the screens that make
 * groups. The library's groups count before they exist: their names and default starts are
 * kept for them. Pure.
 */
object GroupRules {
    const val MIN_START = 0
    const val MAX_START = 9_999

    /** Why [name] can't name a group ([groupId] when renaming one), or null. */
    fun nameProblem(name: String, groups: List<ChannelGroup>, groupId: Long? = null): String? {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return "Give the group a name."
        val names = groups.filter { it.id != groupId }.map { it.name } + ChannelSegment.entries.map { it.groupName }
        return if (names.any { it.equals(trimmed, ignoreCase = true) }) "There is a group called $trimmed." else null
    }

    /** Why [start] can't number a group ([groupId] when changing one's), or null. */
    fun startProblem(start: Int, groups: List<ChannelGroup>, groupId: Long? = null): String? {
        if (start !in MIN_START..MAX_START) return "A start is a number from $MIN_START to $MAX_START."
        val owner = takenStarts(groups.filter { it.id != groupId })[start] ?: return null
        return "$owner starts at $start."
    }

    /**
     * Where a new group starts unless someone picks another: the thousand after every start and
     * channel number (5000 after the library's four, then 6000, 7000…).
     */
    fun nextStart(groups: List<ChannelGroup>, numbers: Collection<Int>): Int {
        val highest = (takenStarts(groups).keys + numbers).maxOrNull() ?: 0
        return ((highest / 1000 + 1) * 1000).coerceAtMost(MAX_START)
    }

    /** Every group's start with its name, the library's defaults included for its groups not made yet. */
    private fun takenStarts(groups: List<ChannelGroup>): Map<Int, String> {
        val made = groups.filter { it.fromLibrary }.mapTo(HashSet()) { it.name }
        return ChannelSegment.entries.filter { it.groupName !in made }.associate { it.defaultStart to it.groupName } +
            groups.associate { it.numberStart to it.name }
    }
}
