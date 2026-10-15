package tv.anstelle.feature.ondemand

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import tv.anstelle.core.model.MediaProgress
import tv.anstelle.core.theme.LocalAnstelleTheme
import tv.anstelle.core.theme.LocalIsTv

/** The readable media name always appears, including when a graphical logo is available. */
@Composable
internal fun SheetTitle(title: String, modifier: Modifier = Modifier) {
    val typography = androidx.compose.material3.MaterialTheme.typography
    androidx.compose.material3.Text(
        title,
        style = if (LocalIsTv.current) typography.headlineSmall else typography.titleLarge,
        color = LocalAnstelleTheme.current.text,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

/** Familiar values speak for themselves: year, rating, duration/counts, then genre. */
@Composable
internal fun MediaFacts(
    year: Int?, rating: String?, genres: List<String>, durationMs: Long,
    seasonCount: Int? = null, episodeCount: Int? = null,
) {
    val lengths = listOfNotNull(
        durationMs.takeIf { it > 0 }?.let { MediaProgress.span(it) },
        seasonCount?.let { "$it ${if (it == 1) "season" else "seasons"}" },
        episodeCount?.let { "$it ${if (it == 1) "episode" else "episodes"}" },
    )
    MediaFactRow(year, rating, lengths, genres.take(2))
}

/** Shared hierarchy on banners and detail pages; longer values wrap at the theme's natural size. */
@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun MediaFactRow(year: Int?, rating: String?, lengths: List<String>, genres: List<String>, modifier: Modifier = Modifier) {
    val theme = LocalAnstelleTheme.current
    val typography = androidx.compose.material3.MaterialTheme.typography
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        year?.let {
            androidx.compose.material3.Text(it.toString(), style = typography.bodyMedium.merge(TextStyle(fontFeatureSettings = "tnum")), color = theme.text, maxLines = 1, modifier = Modifier.align(Alignment.CenterVertically))
        }
        rating?.let { RatingBadge(it, theme.text, Modifier.align(Alignment.CenterVertically)) }
        lengths.forEach { value ->
            androidx.compose.material3.Text(value, style = typography.bodyMedium.merge(TextStyle(fontFeatureSettings = "tnum", fontWeight = FontWeight.SemiBold)), color = theme.text, maxLines = 1, modifier = Modifier.align(Alignment.CenterVertically))
        }
        if (genres.isNotEmpty()) androidx.compose.material3.Text(
            genres.joinToString(", "), style = typography.bodyMedium, color = theme.textMuted,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.align(Alignment.CenterVertically).widthIn(max = if (LocalIsTv.current) 240.dp else 260.dp),
        )
    }
}

internal fun watchedAction(watched: Boolean, saving: Boolean, known: Boolean, onClick: () -> Unit) = SheetAction(
    label = if (saving) "Saving" else if (watched) "Watched" else "Mark watched",
    icon = if (watched) Icons.Filled.CheckBox else Icons.Filled.CheckBoxOutlineBlank,
    enabled = known,
    saving = saving,
    pinned = true,
    watched = watched,
    onClick = onClick,
)
