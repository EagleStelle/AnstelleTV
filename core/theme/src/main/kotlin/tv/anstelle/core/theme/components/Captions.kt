package tv.anstelle.core.theme.components

import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import tv.anstelle.core.theme.LocalAnstelleTheme
import tv.anstelle.core.theme.LocalIsTv

/** The inset a tile's picture and caption sit in, which focus fills as the tile's frame. */
val TILE_FRAME = 3.dp

/** The room around a tile's caption lines, under its picture and inside its frame. */
val TileCaptionPadding = PaddingValues(start = 4.dp, end = 4.dp, top = 7.dp, bottom = 5.dp)

/** A tile's quiet ink: muted at rest, the focus fill's ink dimmed while [focused]. */
@Composable
@ReadOnlyComposable
fun quietInk(focused: Boolean): Color {
    val theme = LocalAnstelleTheme.current
    return if (focused) theme.onAccent.copy(alpha = 0.76f) else theme.textMuted
}

/** An episode's number and its title apart: "S1E4 - Pilot" reads as S1E4 and Pilot. */
fun splitEpisodeLabel(label: String): Pair<String?, String?> {
    val number = Regex("""^S\d+E\d+""").find(label)?.value
    val rest = (if (number != null) label.removePrefix(number) else label).removePrefix(" - ").trim()
    return number to rest.ifEmpty { null }
}

/**
 * Every episode line's hierarchy: the number in [quiet] ink and tabular figures, then the title
 * in the line's own ink, two spaces apart, never joined by a dash.
 */
fun numbered(number: String?, title: String?, quiet: Color) = buildAnnotatedString {
    number?.let { withStyle(SpanStyle(color = quiet, fontFeatureSettings = "tnum")) { append(it) } }
    if (number != null && title != null) append("  ")
    title?.let { append(it) }
}

private val captionStyle: TextStyle
    @Composable @ReadOnlyComposable get() = androidx.compose.material3.MaterialTheme.typography.bodyMedium

/**
 * A tile's or card's line under its name, with the episode line's hierarchy in body-medium: the
 * episode's number quiet, then its title in bone (a movie's year, then its title); [aired]
 * quiets the title too. Focus turns both to the fill's ink; on TV the focused line runs as a
 * marquee when it doesn't fit.
 */
@Composable
fun CaptionLine(number: String?, title: String?, focused: Boolean, modifier: Modifier = Modifier, aired: Boolean = false) {
    val theme = LocalAnstelleTheme.current
    val marquee = focused && LocalIsTv.current
    androidx.compose.material3.Text(
        numbered(number, title, quietInk(focused)),
        style = captionStyle,
        color = if (focused) theme.onAccent else if (aired) theme.textMuted else theme.text,
        maxLines = 1,
        overflow = if (marquee) TextOverflow.Clip else TextOverflow.Ellipsis,
        modifier = if (marquee) modifier.basicMarquee(iterations = Int.MAX_VALUE) else modifier,
    )
}

/** How tall a single [CaptionLine] stands in [TileCaptionPadding], for a layout that must leave it room. */
@Composable
@ReadOnlyComposable
internal fun tileCaptionHeight(): Dp =
    with(LocalDensity.current) { captionStyle.lineHeight.toDp() } + TileCaptionPadding.calculateTopPadding() + TileCaptionPadding.calculateBottomPadding()
