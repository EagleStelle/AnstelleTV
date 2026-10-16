package tv.anstelle.core.model

/**
 * What a choice does: [LIVE] tunes the channel; [RESTART] tunes it and takes its program back
 * to the start, moving the schedule like the player's Restart key; [PLAY] opens the on-demand
 * player; [ADD_TO_CHANNEL] opens Add to channel.
 */
enum class WatchKind { LIVE, RESTART, PLAY, ADD_TO_CHANNEL }

/**
 * One way to watch a program: what the control says, one line on what happens, and for
 * [WatchKind.PLAY] where on-demand playback starts. [fromAiring] marks *Watch on Demand* and
 * *Resume on Demand*, opened from a scheduled airing, so they wear On Demand's icon.
 */
data class WatchChoice(
    val kind: WatchKind,
    val title: String,
    val detail: String,
    val positionMs: Long = 0,
    val fromAiring: Boolean = false,
    val startsOver: Boolean = false,
)
