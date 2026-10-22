package tv.anstelle.core.theme.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch
import tv.anstelle.core.model.MediaProgress
import tv.anstelle.core.model.WatchChoice
import tv.anstelle.core.theme.ControlShape
import tv.anstelle.core.theme.LocalAnstelleTheme
import tv.anstelle.core.theme.LocalIsTv

/** An episode card's width on TV, on detail pages and in the on-demand player alike. */
private val TV_EPISODE_CARD_WIDTH = 174.dp

private const val STILL_ASPECT = 16f / 9f

/** The space between episode cards in a rail. */
val episodeCardSpacing: Dp
    @Composable @ReadOnlyComposable get() = if (LocalIsTv.current) 8.dp else 6.dp

/** How tall an [EpisodeCard] stands on TV, so a page can keep a whole row of them in sight. */
@Composable
@ReadOnlyComposable
fun tvEpisodeCardHeight(): Dp = TILE_FRAME * 2 + (TV_EPISODE_CARD_WIDTH - TILE_FRAME * 2) / STILL_ASPECT + tileCaptionHeight()

/**
 * One episode in a season's rail, on detail pages and in the on-demand player alike: the still in
 * the tile frame with what was watched at its foot ([WatchStrip]), then the caption line. The
 * [current] one (the episode a page is about, or the one playing) is ringed in the accent round its
 * still until focus fills it like any card. One watched through reads as aired unless it is current: the still under the past shade
 * and the title quiet. Focus fills the whole card rose. OK or a tap opens it; holding it (or Menu)
 * offers the quick ways to play it. Phones and tablets size it [touchWidth].
 */
@Composable
fun EpisodeCard(
    label: String,
    stillUrl: String?,
    progress: MediaProgress,
    watched: Boolean,
    current: Boolean,
    onClick: () -> Unit,
    onHold: () -> Unit,
    touchWidth: Dp,
    modifier: Modifier = Modifier,
) {
    val theme = LocalAnstelleTheme.current
    val (number, title) = splitEpisodeLabel(label)
    val aired = watched && !current
    FocusSurface(
        onClick = onClick,
        onLongClick = onHold,
        color = Color.Transparent,
        modifier = modifier
            .width(if (LocalIsTv.current) TV_EPISODE_CARD_WIDTH else touchWidth)
            .onPreviewKeyEvent { e -> if (e.key == Key.Menu && e.type == KeyEventType.KeyUp) onHold().let { true } else e.key == Key.Menu },
    ) { focused ->
        Column(Modifier.padding(TILE_FRAME)) {
            Poster(
                stillUrl, label,
                Modifier.fillMaxWidth().aspectRatio(STILL_ASPECT)
                    .then(if (current) Modifier.currentRing(theme.accent, shown = !focused) else Modifier),
                shaded = aired,
            ) {
                WatchStrip(progress, watched, Modifier.align(Alignment.BottomStart))
            }
            CaptionLine(number, title, focused, Modifier.padding(TileCaptionPadding), aired = aired)
        }
    }
}

/**
 * The current episode's mark: a ring the whole width of the tile frame round its still, outside
 * the picture so none of it is covered. It is a fill the frame wider than the still, drawn behind
 * it the way focus fills the card, so the rim keeps the cards' near-square corners inside and out.
 * Drawn in [ink] only while [shown]: focus fills the frame anyway, so a focused current card looks
 * like any other focused card.
 */
private fun Modifier.currentRing(ink: Color, shown: Boolean): Modifier =
    semantics { stateDescription = "Current" }.drawBehind {
        if (!shown) return@drawBehind
        val ring = TILE_FRAME.toPx()
        val corner = ControlShape.topStart.toPx(size, this)
        drawRoundRect(ink, Offset(-ring, -ring), Size(size.width + ring * 2, size.height + ring * 2), CornerRadius(corner))
    }

/**
 * A show's seasons as tabs, in order, each with its episode count from [seasons]; one in [done]
 * (watched through) carries the watched square in place of its count. Every season gets a tab,
 * a lone one included. [state] and [openFocus] let a page steer the row from the D-pad; on TV the
 * row ends at its last tab and hands Left from its first to the rail.
 */
@Composable
fun SeasonTabs(
    seasons: Map<String, Int>,
    open: String?,
    done: Set<String>,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(initialFirstVisibleItemIndex = seasons.keys.indexOf(open).coerceAtLeast(0)),
    openFocus: FocusRequester? = null,
) {
    val isTv = LocalIsTv.current
    val rail = LocalRailFocus.current
    val keys = seasons.keys.toList()
    LazyRow(state = state, modifier = modifier, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        items(keys, key = { it }) { key ->
            OsdTab(
                key, selected = key == open, onClick = { onSelect(key) },
                count = seasons.getValue(key), done = key in done, compact = isTv,
                modifier = Modifier
                    .let { if (key == open && openFocus != null) it.focusRequester(openFocus) else it }
                    .focusProperties {
                        if (isTv && key == keys.first()) left = rail?.rail ?: FocusRequester.Cancel
                        if (isTv && key == keys.last()) right = FocusRequester.Cancel
                    },
            )
        }
    }
}

/**
 * A held episode's quick ways to play it, headed by its [show] and the episode with its length,
 * on detail pages and in the on-demand player alike. It opens in a window of its own, so no
 * screen's keys reach it; Back puts focus on the held card again ([returnFocus]) on TV, while a
 * choice closes it and does [onAction].
 */
@Composable
fun EpisodeActionsDialog(
    show: String,
    label: String,
    facts: String?,
    choices: List<WatchChoice>,
    returnFocus: FocusRequester,
    onDismiss: () -> Unit,
    onAction: (WatchChoice) -> Unit,
) {
    val theme = LocalAnstelleTheme.current
    val isTv = LocalIsTv.current
    val scope = rememberCoroutineScope()
    val dismiss = {
        onDismiss()
        if (isTv) scope.launch { returnFocus.requestWhenReady() }
        Unit
    }
    Dialog(onDismissRequest = dismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        ActionDialog(
            actions = choices.dialogActions(isEpisode = true) { choice ->
                onDismiss()
                onAction(choice)
            },
            onDismiss = dismiss,
        ) {
            val typography = androidx.compose.material3.MaterialTheme.typography
            androidx.compose.material3.Text(show, style = typography.headlineSmall, color = theme.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            androidx.compose.material3.Text(
                listOfNotNull(label.replace(" - ", "  "), facts).joinToString("  "),
                style = typography.bodyMedium,
                color = theme.textMuted,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
