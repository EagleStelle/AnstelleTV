package tv.anstelle.core.theme.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.outlined.PlaylistAdd
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Update
import androidx.compose.material.icons.outlined.CheckBox
import androidx.compose.material.icons.outlined.CheckBoxOutlineBlank
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.LiveTv
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Replay
import androidx.compose.material.icons.outlined.Update
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

    /**
     * The same glyph in Material's outlined set, for a row of buttons where solid shapes (the
     * clapperboard, the TV) would outweigh line ones (the arrows): a detail page's keys on touch,
     * as the nav bar draws the tabs that aren't open. Any glyph without an outline stays as it is.
     */
    fun outlined(icon: ImageVector): ImageVector = when (icon) {
        Live -> Icons.Outlined.LiveTv
        OnDemand -> Icons.Outlined.Movie
        Details -> Icons.Outlined.Info
        StartOver -> Icons.Outlined.Replay
        Resume -> Icons.Outlined.Update
        Play -> Icons.Outlined.PlayArrow
        AddToChannel -> Icons.AutoMirrored.Outlined.PlaylistAdd
        Icons.Filled.CheckBox -> Icons.Outlined.CheckBox
        Icons.Filled.CheckBoxOutlineBlank -> Icons.Outlined.CheckBoxOutlineBlank
        Icons.Filled.MoreHoriz -> Icons.Outlined.MoreHoriz
        else -> icon
    }
}
