package tv.anstelle.core.scheduler

/**
 * Slot boundary math. Boundaries are aligned in local time, so a 60-minute slot starts
 * on the local hour even in zones with a 30- or 45-minute UTC offset.
 */
object SlotMath {
    const val MINUTE_MS = 60_000L

    /**
     * Smallest local slot boundary at or after [timeUtcMs].
     * [zoneOffsetMs] is the zone's UTC offset at that instant. [slotMinutes] 0 disables alignment.
     */
    fun alignUp(timeUtcMs: Long, slotMinutes: Int, zoneOffsetMs: Long): Long {
        if (slotMinutes == 0) return timeUtcMs
        val slotMs = slotMinutes * MINUTE_MS
        val local = timeUtcMs + zoneOffsetMs
        return Math.floorDiv(local + slotMs - 1, slotMs) * slotMs - zoneOffsetMs
    }

    /** Largest local slot boundary at or before [timeUtcMs]. */
    fun alignDown(timeUtcMs: Long, slotMinutes: Int, zoneOffsetMs: Long): Long {
        if (slotMinutes == 0) return timeUtcMs
        val slotMs = slotMinutes * MINUTE_MS
        val local = timeUtcMs + zoneOffsetMs
        return Math.floorDiv(local, slotMs) * slotMs - zoneOffsetMs
    }
}
