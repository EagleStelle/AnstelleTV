package tv.anstelle.core.theme.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Update
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * One icon for each way to watch, so a choice looks the same wherever it is offered: a player
 * key, the guide's program actions, a channel page or a setting. Live TV and On Demand take
 * the nav bar's own.
 */
object WatchIcons {
    val Live: ImageVector get() = Icons.Filled.LiveTv
    val OnDemand: ImageVector get() = Icons.Filled.Movie
    val Details: ImageVector get() = Icons.Filled.Info
    val StartOver: ImageVector get() = Icons.Filled.Replay
    val Resume: ImageVector get() = Icons.Filled.Update
    val Play: ImageVector get() = Icons.Filled.PlayArrow
    val AddToChannel: ImageVector get() = Icons.AutoMirrored.Filled.PlaylistAdd

    /**
     * An on-demand choice: picking up partway [Resume]s, a scheduled program ([fromAiring])
     * uses [OnDemand], and anything else [Play]s from the start.
     */
    fun play(positionMs: Long, fromAiring: Boolean): ImageVector = when {
        positionMs > 0 -> Resume
        fromAiring -> OnDemand
        else -> Play
    }
}
