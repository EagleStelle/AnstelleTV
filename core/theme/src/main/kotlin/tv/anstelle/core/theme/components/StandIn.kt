package tv.anstelle.core.theme.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.TvOff
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.em
import tv.anstelle.core.theme.LocalAnstelleTheme

/** How much room a stand-in mark has. */
enum class StandInSize {
    /** One line in the field voice: a channel tile, a details panel, a channel page. */
    LINE,

    /** The guide's preview window. */
    PANEL,

    /** The full-screen players. */
    SCREEN,
}

/**
 * What stands in for the picture while a channel shows none, on the dark ground where the
 * picture would be: an [icon] and [title] (OFF AIR, AGE RESTRICTED, NO ACCESS), then UNTIL
 * [until], when the dark stretch ends. Every reason takes this one shape.
 *
 * Every part is set uppercase in the condensed field voice and sized from the title, so the
 * mark keeps one shape at every size: the icon at [ICON_RATIO] of the title, the until line at
 * [UNTIL_RATIO] of it under the title (on a [StandInSize.LINE] it follows the title on the same
 * line instead). The icon carries the state, so it never rests on color alone.
 */
@Composable
fun StandInMark(
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
    size: StandInSize = StandInSize.PANEL,
    until: String? = null,
    alignStart: Boolean = false,
) {
    val theme = LocalAnstelleTheme.current
    val typography = androidx.compose.material3.MaterialTheme.typography
    val titleStyle = when (size) {
        StandInSize.LINE -> typography.labelMedium
        StandInSize.PANEL -> typography.headlineSmall
        StandInSize.SCREEN -> typography.displayMedium
    }
    val untilLine = until?.let { "Until $it".uppercase() }
    val line = size == StandInSize.LINE
    // The title's em in dp, so the icon and the gaps follow the font scale along with the type.
    val em = with(LocalDensity.current) { titleStyle.fontSize.toDp() }
    Column(
        modifier,
        horizontalAlignment = if (alignStart) Alignment.Start else Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(em * STACK_GAP),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(em * ICON_GAP)) {
            androidx.compose.material3.Icon(icon, contentDescription = null, tint = theme.textMuted, modifier = Modifier.size(em * ICON_RATIO))
            androidx.compose.material3.Text(
                // The OSD's two-space gap, as in the status line ("ON NOW  9:00 PM"), never a joiner.
                if (line && untilLine != null) "${title.uppercase()}  $untilLine" else title.uppercase(),
                style = titleStyle,
                color = if (line) theme.textMuted else theme.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (!line && untilLine != null) {
            androidx.compose.material3.Text(
                untilLine,
                style = titleStyle.copy(
                    fontSize = titleStyle.fontSize * UNTIL_RATIO,
                    lineHeight = titleStyle.lineHeight * UNTIL_RATIO,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = FIELD_TRACKING.em,
                ),
                color = theme.textMuted,
                maxLines = 1,
            )
        }
    }
}

/**
 * A program the profile may not watch, under a lock: [title] is AGE RESTRICTED or NO ACCESS.
 * Nothing more is said: not the program's rating, not the profile's limit, not where the
 * program comes from.
 */
@Composable
fun RestrictedMark(
    title: String,
    modifier: Modifier = Modifier,
    size: StandInSize = StandInSize.PANEL,
    until: String? = null,
    alignStart: Boolean = false,
) = StandInMark(Icons.Filled.Lock, title, modifier, size, until, alignStart)

/** A channel off air; [title] is NO SCHEDULE instead for one with nothing scheduled at all. */
@Composable
fun OffAirMark(
    modifier: Modifier = Modifier,
    size: StandInSize = StandInSize.PANEL,
    until: String? = null,
    alignStart: Boolean = false,
    title: String = OFF_AIR,
) = StandInMark(Icons.Filled.TvOff, title, modifier, size, until, alignStart)

private const val OFF_AIR = "Off air"

/** The until line against the title: half its size at every [StandInSize]. */
private const val UNTIL_RATIO = 0.5f

/** The icon against the title's size: its body sits about as tall as the capitals. */
private const val ICON_RATIO = 0.85f

/** Between the icon and the title, in the title's ems. */
private const val ICON_GAP = 0.3f

/** Between the title and the until line, in the title's ems. */
private const val STACK_GAP = 0.25f

/** The field voice's tracking (label-medium and label-small). */
private const val FIELD_TRACKING = 0.06f
