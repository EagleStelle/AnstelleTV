package tv.anstelle.core.data.playback

/**
 * A channel program's real length, as the player finds it. A file can run far longer or shorter
 * than the runtime its server reports (a broken duration in its header gives a 24-minute
 * episode a 6-hour runtime), so when a program ends, the schedule follows where it really did.
 * Pure, so it is unit-tested.
 */
internal object ProgramLength {
    /** A real end or length this close to the scheduled one leaves the schedule alone. */
    const val TOLERANCE_MS = 5_000L

    /** How much faster than the wall clock a position may move and still count as playing. */
    private const val SLACK_MS = 500L

    /** How old the last sample may be when the program ends for it to tell the program's length. */
    private const val MAX_AGE_MS = 1_500L

    /**
     * Where the program on now was at [atUtcMs]. [advancing] when it moved on since the sample
     * before at about the wall clock's pace, as playing does; a seek or a stall at a spot the
     * file never reaches (a start past its end) doesn't.
     */
    class Sample(val positionMs: Long, val atUtcMs: Long, val advancing: Boolean)

    /** The sample at [positionMs] now, following [previous] (null for the program's first). */
    fun sample(previous: Sample?, positionMs: Long, nowUtcMs: Long): Sample {
        val advancing = previous != null && positionMs > previous.positionMs &&
            positionMs - previous.positionMs <= nowUtcMs - previous.atUtcMs + SLACK_MS
        return Sample(positionMs, nowUtcMs, advancing)
    }

    /**
     * How long the program that ended at [nowUtcMs] runs, from its [last] sample; null when it
     * wasn't seen playing up to its end.
     */
    fun ended(last: Sample?, nowUtcMs: Long): Long? {
        if (last == null || !last.advancing) return null
        val since = nowUtcMs - last.atUtcMs
        return if (since in 0..MAX_AGE_MS) last.positionMs + since else null
    }

    /**
     * Where the airing from [startUtcMs] to [endUtcMs] really ended, when its program ended at
     * [nowUtcMs] after playing [lengthMs] of its file (null when unknown) and that is off the
     * airing's end: now, when the file was shorter than the airing, or [lengthMs] after the start,
     * when it was longer. Null when it ended on time, or late only because playback stalled.
     */
    fun realEnd(startUtcMs: Long, endUtcMs: Long, nowUtcMs: Long, lengthMs: Long?): Long? = when {
        nowUtcMs in (startUtcMs + 1)..<endUtcMs - TOLERANCE_MS -> nowUtcMs
        lengthMs != null && lengthMs > endUtcMs - startUtcMs + TOLERANCE_MS -> startUtcMs + lengthMs
        else -> null
    }

    /** True when a file found to run [lengthMs] is off the [reportedMs] its server gives it. */
    fun differs(lengthMs: Long, reportedMs: Long): Boolean = kotlin.math.abs(lengthMs - reportedMs) > TOLERANCE_MS
}
