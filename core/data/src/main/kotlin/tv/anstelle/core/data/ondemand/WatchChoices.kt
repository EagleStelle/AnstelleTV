package tv.anstelle.core.data.ondemand

import java.text.DateFormat
import java.util.Date
import tv.anstelle.core.model.MediaProgress.Companion.span
import tv.anstelle.core.model.MediaProgress
import tv.anstelle.core.model.WatchChoice
import tv.anstelle.core.model.WatchKind

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
 * The words for every way to watch, shared by the guide's program actions and a channel's
 * page in On demand, so the same choice reads the same everywhere. Live and Start over stay on
 * the channel; the rest play on demand, where pause and rewind leave the channel as it is.
 * Each line fits one row beside its title, so none repeats the program's name.
 */
object WatchChoices {
    private const val WATCH_ON_DEMAND = "Watch on Demand"

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
        WatchChoice(WatchKind.PLAY, WATCH_ON_DEMAND, "From the start, ${onDemandAfter(isEpisode)}.", fromAiring = true)

    /** The program on now in the on-demand player, from the point the channel has reached. */
    fun resumeOnDemand(elapsedMs: Long, isEpisode: Boolean) =
        WatchChoice(WatchKind.PLAY, "Resume on Demand", "From ${span(elapsedMs)} in, ${onDemandAfter(isEpisode)}.", elapsedMs, fromAiring = true)

    private fun onDemandAfter(isEpisode: Boolean) = if (isEpisode) "then the next episodes" else "off the schedule"

    /** On-demand playback choices for an airing, with one shared resume threshold. */
    fun onDemandChoices(elapsedMs: Long, isEpisode: Boolean) = listOfNotNull(
        onDemand(isEpisode),
        elapsedMs.takeIf { it >= MIN_LIVE_POINT_MS }?.let { resumeOnDemand(it, isEpisode) },
    )

    /**
     * The choices for a program on now, in order: the channel live, the channel from the start,
     * on demand from the start, and on demand from where the channel is once it has run long
     * enough for that to differ.
     */
    fun onNow(channelNumber: Int, channelName: String, startUtcMs: Long, endUtcMs: Long, nowUtcMs: Long, isEpisode: Boolean) = listOf(
        live(channelNumber, channelName, endUtcMs, nowUtcMs),
        restart,
    ) + onDemandChoices(nowUtcMs - startUtcMs, isEpisode)

    /** Shared airing rules for guide actions and channel pages, including unavailable programs. */
    fun airing(
        tense: AiringTense,
        channelNumber: Int,
        channelName: String,
        startUtcMs: Long,
        endUtcMs: Long,
        nowUtcMs: Long,
        isEpisode: Boolean,
        offAir: Boolean = false,
        restrictionTitle: String? = null,
    ): List<WatchChoice> = when {
        offAir -> listOfNotNull(liveOffAir(endUtcMs).takeIf { tense == AiringTense.ON_NOW })
        restrictionTitle != null -> listOfNotNull(liveRestricted(endUtcMs, restrictionTitle).takeIf { tense == AiringTense.ON_NOW })
        tense == AiringTense.ON_NOW -> onNow(channelNumber, channelName, startUtcMs, endUtcMs, nowUtcMs, isEpisode)
        else -> listOf(fromStart(tense, startUtcMs))
    }

    /** A program that isn't on now: one play action, with airing context in its detail. */
    fun fromStart(tense: AiringTense, startUtcMs: Long) = WatchChoice(
        WatchKind.PLAY,
        WATCH_ON_DEMAND,
        when (tense) {
            AiringTense.LATER -> "Don't wait for ${clockTime(startUtcMs)}. On Demand from 0:00."
            else -> "Aired at ${clockTime(startUtcMs)}. On Demand from 0:00."
        },
        fromAiring = true,
    )

    /** Plays from Jellyfin's resume point. */
    fun resume(positionMs: Long) = WatchChoice(WatchKind.PLAY, "Resume from ${span(positionMs)} in", "Where you stopped last time.", positionMs)

    /** Resumes a named episode, keeping episode identity and time together in the action. */
    fun resumeEpisode(number: String?, positionMs: Long) = WatchChoice(
        WatchKind.PLAY,
        "Resume ${number ?: "episode"} from ${span(positionMs)} in",
        "Where you stopped last time.",
        positionMs,
    )

    /** Plays a named episode that has no saved progress. */
    fun playEpisode(number: String?) = WatchChoice(
        WatchKind.PLAY,
        "Play ${number ?: "episode"}",
        "On Demand from 0:00.",
    )

    /** Plays a series from a named first episode. */
    fun playSeries(firstNumber: String = "S1E1") = WatchChoice(
        WatchKind.PLAY,
        "Play",
        "On Demand from $firstNumber.",
    )

    /** Starts a series at its first episode, rather than at the current episode's 0:00. */
    fun playSeriesFromStart(firstNumber: String = "S1E1") = WatchChoice(
        WatchKind.PLAY,
        "Play from the start",
        "On Demand from $firstNumber.",
        startsOver = true,
    )

    /** An episode, from its start on demand. */
    val playFromStart = WatchChoice(WatchKind.PLAY, "Play from the start", "On Demand from 0:00.", startsOver = true)

    /** Shared quick actions for an episode on a title, episode, or channel page. */
    fun episodeActions(positionMs: Long, canAddToChannel: Boolean) = listOfNotNull(
        positionMs.takeIf { it > 0 }?.let(::resume),
        playFromStart,
        addToChannel(isMovie = false).takeIf { canAddToChannel },
    )

    fun addToChannel(isMovie: Boolean) = WatchChoice(
        WatchKind.ADD_TO_CHANNEL,
        if (isMovie) "Add movie to a channel" else "Add show to a channel",
        ADD_TO_CHANNEL_DETAIL,
    )

    /** What adding to a channel does, in the guide's dialog and a detail page's More alike. */
    const val ADD_TO_CHANNEL_DETAIL = "Pick a channel to always air it."

    /**
     * An episode list's word on where it was left on demand, "12 min left", in place of its
     * length; null when it has no resume point. Its still says when it was watched through.
     */
    fun timeLeft(durationMs: Long, resumeMs: Long): String? =
        MediaProgress(durationMs, resumeMs).takeIf { it.hasProgress }?.let { "${span(it.remainingMs, roundUp = true)} left" }

    /** The wall-clock time in the device's format, "8:02 PM" or "20:02". */
    fun clockTime(utcMs: Long): String = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(utcMs))

    /** "8:02 PM – 8:26 PM". */
    fun clockRange(startUtcMs: Long, endUtcMs: Long) = "${clockTime(startUtcMs)} – ${clockTime(endUtcMs)}"

    /** Under this far in, resuming on demand would be the same as starting on demand. */
    const val MIN_LIVE_POINT_MS = 60_000L
}
