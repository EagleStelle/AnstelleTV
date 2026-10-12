package tv.anstelle.core.data.ondemand

import java.text.DateFormat
import java.util.Date

/** Where an airing sits against the clock. */
enum class AiringTense {
    ON_NOW,
    LATER,
    AIRED,
    ;

    companion object {
        fun of(startUtcMs: Long, endUtcMs: Long, nowUtcMs: Long): AiringTense = when {
            nowUtcMs < startUtcMs -> LATER
            nowUtcMs < endUtcMs -> ON_NOW
            else -> AIRED
        }
    }
}

/**
 * What a choice does: [LIVE] tunes the channel; [RESTART] tunes it and takes its program back
 * to the start, moving the schedule like the player's Restart key; [PLAY] opens the on-demand
 * player; [ADD_TO_CHANNEL] opens Add to channel.
 */
enum class WatchKind { LIVE, RESTART, PLAY, ADD_TO_CHANNEL }

/**
 * One way to watch a program: what the control says, one line on what happens, and for
 * [WatchKind.PLAY] where on-demand playback starts. [leavesChannel] marks *Watch on Demand* and
 * *Resume on Demand*, the program on now leaving its channel, so they wear On Demand's icon.
 */
data class WatchChoice(val kind: WatchKind, val title: String, val detail: String, val positionMs: Long = 0, val leavesChannel: Boolean = false)

/**
 * The words for every way to watch, shared by the guide's program actions and a channel's
 * page in On demand, so the same choice reads the same everywhere. Live and Start over stay on
 * the channel; the rest play on demand, where pause and rewind leave the channel as it is.
 * Each line fits one row beside its title, so none repeats the program's name.
 */
object WatchChoices {
    fun live(channelNumber: Int, channelName: String, endUtcMs: Long, nowUtcMs: Long) =
        WatchChoice(WatchKind.LIVE, "Watch live", "$channelNumber $channelName · ${span(endUtcMs - nowUtcMs, roundUp = true)} left")

    fun liveOffAir(untilUtcMs: Long) = WatchChoice(WatchKind.LIVE, "Watch live", "Off air until ${clockTime(untilUtcMs)}.")

    /** A program the profile may not watch ([title] says why): the channel stays dark until it ends. */
    fun liveRestricted(untilUtcMs: Long, title: String) = WatchChoice(WatchKind.LIVE, "Watch live", "$title until ${clockTime(untilUtcMs)}.")

    /** The program on now, from its start on the channel. */
    val restart = WatchChoice(WatchKind.RESTART, "Start over", "Rewinds the channel to 0:00.")

    /**
     * The program on now in the on-demand player, from its start. Each on-demand line says
     * where it starts and what follows, the one thing that differs from the channel: an episode
     * runs on into the show, a movie leaves the schedule behind.
     */
    fun onDemand(isEpisode: Boolean) =
        WatchChoice(WatchKind.PLAY, "Watch on Demand", "From the start, ${onDemandAfter(isEpisode)}.", leavesChannel = true)

    /** The program on now in the on-demand player, from the point the channel has reached. */
    fun resumeOnDemand(elapsedMs: Long, isEpisode: Boolean) =
        WatchChoice(WatchKind.PLAY, "Resume on Demand", "From ${span(elapsedMs)} in, ${onDemandAfter(isEpisode)}.", elapsedMs, leavesChannel = true)

    private fun onDemandAfter(isEpisode: Boolean) = if (isEpisode) "then the next episodes" else "off the schedule"

    /**
     * The choices for a program on now, in order: the channel live, the channel from the start,
     * on demand from the start, and on demand from where the channel is once it has run long
     * enough for that to differ.
     */
    fun onNow(channelNumber: Int, channelName: String, startUtcMs: Long, endUtcMs: Long, nowUtcMs: Long, isEpisode: Boolean) = listOfNotNull(
        live(channelNumber, channelName, endUtcMs, nowUtcMs),
        restart,
        onDemand(isEpisode),
        (nowUtcMs - startUtcMs).takeIf { it >= MIN_LIVE_POINT_MS }?.let { resumeOnDemand(it, isEpisode) },
    )

    /** A program that isn't on now, from its start on demand; the words follow when it airs. */
    fun fromStart(tense: AiringTense, startUtcMs: Long) = when (tense) {
        AiringTense.LATER -> WatchChoice(WatchKind.PLAY, "Watch now", "Don't wait for ${clockTime(startUtcMs)}. On Demand from 0:00.")
        else -> WatchChoice(WatchKind.PLAY, "Watch again", "Aired at ${clockTime(startUtcMs)}. On Demand from 0:00.")
    }

    /** Plays from Jellyfin's resume point. */
    fun resume(positionMs: Long) = WatchChoice(WatchKind.PLAY, "Resume from ${span(positionMs)} in", "Where you stopped last time.", positionMs)

    /** An episode, from its start on demand. */
    val playFromStart = WatchChoice(WatchKind.PLAY, "Play from the start", "On Demand from 0:00.")

    fun addToChannel(isMovie: Boolean) = WatchChoice(
        WatchKind.ADD_TO_CHANNEL,
        if (isMovie) "Add movie to a channel" else "Add show to a channel",
        "Pick a channel to always air it.",
    )

    /** An episode list's word on where it was left, "12 min watched", or null; its still says when it was watched through. */
    fun watchedFact(resumeMs: Long): String? = resumeMs.takeIf { it > 0 }?.let { "${span(it)} watched" }

    /** "18 min", "1 h 4 min"; [roundUp] counts a started minute as whole, for time left. */
    fun span(ms: Long, roundUp: Boolean = false): String {
        val minutes = if (roundUp) ((ms + 59_999) / 60_000).coerceAtLeast(1) else (ms / 60_000).coerceAtLeast(0)
        return if (minutes < 60) "$minutes min" else if (minutes % 60 == 0L) "${minutes / 60} h" else "${minutes / 60} h ${minutes % 60} min"
    }

    /** The wall-clock time in the device's format, "8:02 PM" or "20:02". */
    fun clockTime(utcMs: Long): String = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(utcMs))

    /** "8:02 PM – 8:26 PM". */
    fun clockRange(startUtcMs: Long, endUtcMs: Long) = "${clockTime(startUtcMs)} – ${clockTime(endUtcMs)}"

    /** Under this far in, resuming on demand would be the same as starting on demand. */
    const val MIN_LIVE_POINT_MS = 60_000L
}
