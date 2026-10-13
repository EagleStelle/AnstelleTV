package tv.anstelle.feature.settings.channels

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import tv.anstelle.core.data.channels.LineupReach
import tv.anstelle.core.theme.LocalAnstelleTheme
import tv.anstelle.core.theme.LocalIsTv
import tv.anstelle.core.theme.components.Notice
import kotlin.math.roundToInt

/**
 * A lineup's reach as a readout: its hours, series, episodes and movies as figures, then a ladder
 * of the library's airtime with the lineup's share filled in, a step of the palette per kind.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ReachReadout(reach: LineupReach?, modifier: Modifier = Modifier) {
    when {
        reach == null -> Notice("Counting what it airs…", modifier)
        reach.isEmpty -> Notice("Nothing in your library matches yet.", modifier)
        else -> Column(modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            val isTv = LocalIsTv.current
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(if (isTv) 32.dp else 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Figure(if (reach.hours < 1) "<1" else "%,d".format(reach.hours), if (reach.hours == 1L) "hour" else "hours")
                if (reach.series > 0) Figure("%,d".format(reach.series), "series")
                if (reach.episodes > 0) Figure("%,d".format(reach.episodes), if (reach.episodes == 1) "episode" else "episodes")
                if (reach.movies > 0) Figure("%,d".format(reach.movies), if (reach.movies == 1) "movie" else "movies")
                if (reach.videos > 0) Figure("%,d".format(reach.videos), if (reach.videos == 1) "music video" else "music videos")
            }
            ShareLadder(reach)
        }
    }
}

/** One figure: the number in tabular figures over its name. */
@Composable
private fun Figure(value: String, name: String) {
    val theme = LocalAnstelleTheme.current
    val typography = androidx.compose.material3.MaterialTheme.typography
    Column(Modifier.semantics(mergeDescendants = true) {}) {
        androidx.compose.material3.Text(
            value,
            style = if (LocalIsTv.current) typography.headlineMedium else typography.headlineSmall,
            color = theme.text,
            maxLines = 1,
        )
        androidx.compose.material3.Text(name.uppercase(), style = typography.labelSmall, color = theme.textMuted, maxLines = 1)
    }
}

/**
 * The library's airtime as a 40-segment ladder: the lineup's episodes in the accent, its movies
 * and music videos in steps of the text, the rest empty; a key under it names each and its share.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ShareLadder(reach: LineupReach) {
    val theme = LocalAnstelleTheme.current
    val typography = androidx.compose.material3.MaterialTheme.typography
    val parts = listOf(
        Triple("Episodes", reach.episodeShare, theme.accent),
        Triple("Movies", reach.movieShare, theme.text),
        Triple("Music videos", reach.videoShare, theme.textMuted),
    ).filter { it.second > 0f }
    // Whole segments, each kind at least one so a sliver still shows.
    val counts = parts.map { (_, share, _) -> (share * SEGMENTS).roundToInt().coerceAtLeast(1) }
    val percent = (reach.share * 100).roundToInt().coerceAtLeast(1)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(10.dp)
                .clearAndSetSemantics { contentDescription = "$percent percent of your library's airtime" },
        ) {
            val gap = 2.dp.toPx()
            val width = (size.width - gap * (SEGMENTS - 1)) / SEGMENTS
            var at = 0
            val colors = ArrayList<Color>(SEGMENTS)
            parts.forEachIndexed { i, (_, _, color) -> repeat(counts[i]) { colors += color } }
            for (i in 0 until SEGMENTS) {
                drawRect(
                    colors.getOrNull(at) ?: theme.line,
                    topLeft = Offset(i * (width + gap), 0f),
                    size = Size(width, size.height),
                )
                at++
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for ((name, share, color) in parts) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Canvas(Modifier.size(8.dp)) { drawRect(color) }
                    androidx.compose.material3.Text(
                        "${name.uppercase()}  ${(share * 100).roundToInt().coerceAtLeast(1)}%",
                        style = typography.labelMedium,
                        color = theme.textMuted,
                    )
                }
            }
            androidx.compose.material3.Text("OF YOUR LIBRARY'S AIRTIME", style = typography.labelMedium, color = theme.textMuted)
        }
    }
}

private const val SEGMENTS = 40
