package tv.anstelle.feature.ondemand

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.aspectRatio
import tv.anstelle.core.model.MediaProgress
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import tv.anstelle.core.data.ondemand.CatalogWatch
import tv.anstelle.core.data.ondemand.Shelf
import tv.anstelle.core.data.ondemand.Title
import tv.anstelle.core.data.ondemand.WatchCard
import tv.anstelle.core.theme.ControlShape
import tv.anstelle.core.theme.LocalAnstelleTheme
import tv.anstelle.core.theme.LocalIsTv
import tv.anstelle.core.theme.components.AirMarker
import tv.anstelle.core.theme.components.Backdrop
import tv.anstelle.core.theme.components.TV_BANNER_ASPECT
import tv.anstelle.core.theme.components.FocusSurface
import tv.anstelle.core.theme.components.LocalRailFocus
import tv.anstelle.core.theme.components.Poster
import tv.anstelle.core.theme.components.TitleLogo
import tv.anstelle.core.theme.components.WatchStrip
import tv.anstelle.core.theme.components.screenGap
import tv.anstelle.core.theme.components.screenSide
import kotlin.math.abs

/** The inset that focus fills round a tile's, card's or poster's picture. */
internal val TILE_FRAME = 3.dp

/** How far a swipe has to travel to step a banner. */
private val SWIPE = 48.dp

/** How long a banner holds a slide while focus is elsewhere. */
private const val ROTATE_MS = 8_000L

/** Shared mobile height leaves room for channel details and the progress/pager footer. */
private val MOBILE_BANNER_HEIGHT = 320.dp

/** Keep mobile pagination from squeezing out the progress ladder and remaining time. */
private val MOBILE_PAGER_MAX_WIDTH = 128.dp

/**
 * A banner of [count] slides, one at a time: the slide's backdrop under the wash, [content]
 * set at its foot, then [footer] beside the squares that count the slides. It cuts to the next
 * slide every few seconds while focus is elsewhere and holds while focused; left and right (or a
 * swipe) step through it, and left at the first slide goes on to the rail. OK opens the slide.
 * On TV focus shows as an accent frame round the picture; phones run it edge to edge.
 */
@Composable
internal fun RotatingBanner(
    count: Int,
    backdropUrl: (Int) -> String?,
    onOpen: (Int) -> Unit,
    modifier: Modifier = Modifier,
    footer: @Composable RowScope.(Int) -> Unit = {},
    content: @Composable ColumnScope.(Int) -> Unit,
) {
    val isTv = LocalIsTv.current
    val mobileHeight = MOBILE_BANNER_HEIGHT * LocalDensity.current.fontScale.coerceAtLeast(1f)
    val rail = LocalRailFocus.current
    var index by rememberSaveable { mutableIntStateOf(0) }
    var focused by remember { mutableStateOf(false) }
    val shown = index.coerceIn(0, count - 1)
    LaunchedEffect(shown, focused, count) {
        if (!focused && count > 1) {
            delay(ROTATE_MS)
            index = (shown + 1) % count
        }
    }
    val swipe = with(LocalDensity.current) { SWIPE.toPx() }
    FocusSurface(
        onClick = { onOpen(shown) },
        color = Color.Transparent,
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged { focused = it.isFocused }
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionLeft -> {
                        if (shown > 0) index = shown - 1 else if (isTv) rail?.rail?.requestFocus()
                        isTv || shown > 0
                    }
                    Key.DirectionRight -> true.also { index = (shown + 1) % count }
                    else -> false
                }
            }
            .pointerInput(count) {
                var dragged = 0f
                detectHorizontalDragGestures(
                    onDragStart = { dragged = 0f },
                    onHorizontalDrag = { change, amount ->
                        change.consume()
                        dragged += amount
                    },
                    onDragEnd = {
                        if (abs(dragged) > swipe) index = (index.coerceIn(0, count - 1) + if (dragged < 0) 1 else count - 1) % count
                    },
                )
            },
    ) { isFocused ->
        Backdrop(
            url = backdropUrl(shown),
            modifier = Modifier
                .fillMaxWidth()
                .then(if (isTv) Modifier.aspectRatio(TV_BANNER_ASPECT) else Modifier.height(mobileHeight)),
            alignment = Alignment.Center,
        ) {
            // Edge to edge, so focus shows as a frame drawn inside the picture.
            if (isTv && isFocused) Box(Modifier.matchParentSize().border(TILE_FRAME, LocalAnstelleTheme.current.accent))
            Column(
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    // The text lines up with the row headers under it.
                    .padding(horizontal = screenSide, vertical = if (isTv) 22.dp else 16.dp),
                verticalArrangement = Arrangement.spacedBy(if (isTv) 6.dp else 4.dp),
            ) {
                content(shown)
                Row(
                    Modifier.fillMaxWidth().padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.End),
                ) {
                    // Progress, remaining time, and pagination share one row on every device.
                    footer(shown)
                    Box(
                        if (isTv) Modifier else Modifier.widthIn(max = MOBILE_PAGER_MAX_WIDTH),
                        contentAlignment = Alignment.CenterEnd,
                    ) {
                        Pager(count, shown, isFocused)
                    }
                }
            }
        }
    }
}

/** One square per banner slide: the one showing solid in the accent, the rest hollow. */
@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun Pager(count: Int, shown: Int, focused: Boolean) {
    if (count < 2) return
    val theme = LocalAnstelleTheme.current
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(5.dp, Alignment.End),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        for (i in 0 until count) {
            AirMarker(onAir = i == shown, color = if (i == shown) (if (focused) theme.text else theme.accent) else theme.textMuted)
        }
    }
}

/**
 * A movie's or series' banner slide: its logo (or title), its facts, what was watched of it,
 * and two lines of overview.
 */
@Composable
internal fun ColumnScope.TitleSlide(title: Title, watch: CatalogWatch) {
    val theme = LocalAnstelleTheme.current
    val isTv = LocalIsTv.current
    val typography = androidx.compose.material3.MaterialTheme.typography
    TitleLogo(
        logoUrl = title.artwork.logoUrl,
        title = title.title,
        height = if (isTv) 72.dp else 48.dp,
        maxLogoWidth = if (isTv) 420.dp else 260.dp,
        style = if (isTv) typography.headlineLarge else typography.headlineMedium,
        maxLines = 2,
    )
    TitleFacts(title.year, title.rating, title.genres, if (title.durationMs > 0) MediaProgress.span(title.durationMs) else null, Modifier.padding(top = 2.dp))
    WatchLine(title, watch)
    title.overview?.let {
        androidx.compose.material3.Text(
            it,
            style = typography.bodyMedium,
            color = theme.text,
            minLines = 2,
            maxLines = if (isTv) 5 else 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 620.dp),
        )
    }
}

/**
 * A series' watch status as how many episodes are left; movie progress belongs in the banner
 * footer beside its pager. Nothing when nothing is known; the banner never deals one watched through.
 */
@Composable
private fun WatchLine(title: Title, watch: CatalogWatch) {
    val theme = LocalAnstelleTheme.current
    val label = androidx.compose.material3.MaterialTheme.typography.labelMedium
    val unwatched = watch.unwatched(title)
    if (unwatched != null && unwatched > 0) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            UnwatchedCount(unwatched)
            androidx.compose.material3.Text(if (unwatched == 1) "EPISODE TO WATCH" else "EPISODES TO WATCH", style = label, color = theme.textMuted)
        }
    }
}

/**
 * A title's facts set apart by space rather than dots: the year, the rating as an OSD badge,
 * the length (or how many seasons), then the genres, wrapping when the theme needs room.
 */
@Composable
internal fun TitleFacts(year: Int?, rating: String?, genres: List<String>, length: String?, modifier: Modifier = Modifier) {
    MediaFactRow(year, rating, listOfNotNull(length), genres.take(3), modifier)
}

/** An official rating as an OSD badge, in [color], so it reads on a focus fill too. */
@Composable
internal fun RatingBadge(text: String, color: Color, modifier: Modifier = Modifier) {
    androidx.compose.material3.Text(
        text,
        style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
        color = color,
        maxLines = 1,
        modifier = modifier
            .border(1.dp, color.copy(alpha = 0.6f), ControlShape)
            .padding(horizontal = 5.dp, vertical = 1.dp),
    )
}

/**
 * How many episodes of a series are still to watch, as the OSD sets a number: a number block,
 * the theme's accent with its ink and tabular figures in the condensed cut, like a channel's.
 */
@Composable
internal fun UnwatchedCount(count: Int, modifier: Modifier = Modifier) {
    val theme = LocalAnstelleTheme.current
    androidx.compose.material3.Text(
        "$count",
        style = androidx.compose.material3.MaterialTheme.typography.labelLarge
            .merge(TextStyle(fontFamily = theme.displayFont, fontWeight = FontWeight.Bold, fontFeatureSettings = "tnum")),
        color = theme.onAccent,
        maxLines = 1,
        modifier = modifier
            .semantics { contentDescription = if (count == 1) "1 episode to watch" else "$count episodes to watch" }
            .background(theme.accent, ControlShape)
            .padding(horizontal = 6.dp, vertical = 1.dp),
    )
}

/**
 * A movie or series as a tall poster: the picture in a focus inset, the title and its rating
 * under it, nothing else. What was watched reads as it does on an episode's still: the watch
 * strip at its foot, and once watched through the past shade over it with the title quiet; a
 * series being watched carries how many episodes are left in its top-end corner.
 */
@Composable
internal fun PosterCard(title: Title, watch: CatalogWatch, onClick: () -> Unit, onHold: () -> Unit, modifier: Modifier = Modifier) {
    val watched = watch.watched(title)
    val unwatched = watch.unwatched(title)
    PosterCardSurface(
        posterUrl = title.posterUrl,
        title = title.title,
        rating = title.rating,
        progress = MediaProgress(title.durationMs, watch[title.id]?.resumeMs ?: 0L),
        watched = watched,
        unwatched = unwatched,
        onClick = onClick,
        onHold = onHold,
        modifier = modifier,
    )
}

/** Movie Continue Watching card, using same poster surface as catalog titles. */
@Composable
internal fun PosterCard(card: WatchCard, onClick: () -> Unit, onHold: () -> Unit, modifier: Modifier = Modifier) {
    PosterCardSurface(
        posterUrl = card.imageUrl,
        title = card.title,
        rating = card.rating,
        progress = MediaProgress(card.durationMs, card.positionMs),
        watched = false,
        unwatched = null,
        onClick = onClick,
        onHold = onHold,
        modifier = modifier,
    )
}

@Composable
private fun PosterCardSurface(
    posterUrl: String?,
    title: String,
    rating: String?,
    progress: MediaProgress,
    watched: Boolean,
    unwatched: Int?,
    onClick: () -> Unit,
    onHold: () -> Unit,
    modifier: Modifier,
) {
    val theme = LocalAnstelleTheme.current
    val typography = androidx.compose.material3.MaterialTheme.typography
    FocusSurface(
        onClick = onClick,
        onLongClick = onHold,
        color = Color.Transparent,
        modifier = modifier.onPreviewKeyEvent { event ->
            if (event.key == Key.Menu && event.type == KeyEventType.KeyUp) onHold().let { true } else event.key == Key.Menu
        },
    ) { focused ->
        Column(Modifier.padding(TILE_FRAME)) {
            Poster(posterUrl, title, Modifier.fillMaxWidth().aspectRatio(2f / 3f), shaded = watched) {
                WatchStrip(progress, watched, Modifier.align(Alignment.BottomStart), remaining = null)
                if (!watched && unwatched != null && unwatched > 0) UnwatchedCount(unwatched, Modifier.align(Alignment.TopEnd).padding(6.dp))
            }
            Column(Modifier.padding(start = 4.dp, end = 4.dp, top = 7.dp, bottom = 5.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                androidx.compose.material3.Text(
                    title,
                    style = typography.titleSmall,
                    color = if (watched && !focused) theme.textMuted else Color.Unspecified,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // The rating's line holds its height when there is none, so every row lines up.
                Box(Modifier.height(16.dp), contentAlignment = Alignment.CenterStart) {
                    rating?.let { RatingBadge(it, if (focused) theme.onAccent else theme.textMuted) }
                }
            }
        }
    }
}

/**
 * An episode on Continue watching or Next up: its still with the watch strip at its foot, then
 * the title and episode label. OK plays it; holding it (or Menu) offers the rest.
 */
@Composable
internal fun WatchCardTile(card: WatchCard, onClick: () -> Unit, onHold: () -> Unit, modifier: Modifier = Modifier) {
    val isTv = LocalIsTv.current
    val typography = androidx.compose.material3.MaterialTheme.typography
    FocusSurface(
        onClick = onClick,
        onLongClick = onHold,
        color = Color.Transparent,
        modifier = modifier
            .width(if (isTv) 214.dp else 172.dp)
            .onPreviewKeyEvent { e -> if (e.key == Key.Menu && e.type == KeyEventType.KeyUp) onHold().let { true } else e.key == Key.Menu },
    ) { focused ->
        Column(Modifier.padding(TILE_FRAME)) {
            Poster(card.imageUrl, card.title, Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
                WatchStrip(MediaProgress(card.durationMs, card.positionMs), watched = false, modifier = Modifier.align(Alignment.BottomStart))
            }
            Column(Modifier.padding(start = 4.dp, end = 4.dp, top = 7.dp, bottom = 5.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                androidx.compose.material3.Text(card.title, style = typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (card.number != null || card.episodeTitle != null) CaptionLine(card.number, card.episodeTitle, focused)
            }
        }
    }
}

/**
 * A row's heading in the OSD's field voice: a channel's number block before its name;
 * a fixed row is its name alone.
 */
@Composable
internal fun ShelfHeader(shelf: Shelf, modifier: Modifier = Modifier) {
    val theme = LocalAnstelleTheme.current
    val isTv = LocalIsTv.current
    val style = androidx.compose.material3.MaterialTheme.typography.labelMedium.copy(fontSize = if (isTv) 15.sp else 13.sp, lineHeight = 18.sp)
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        shelf.channel?.let { ChannelNumber(it.number, focused = false, style = androidx.compose.material3.MaterialTheme.typography.labelLarge) }
        androidx.compose.material3.Text(shelf.label.uppercase(), style = style, color = theme.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
    }
}

/**
 * A browse row: [heading] on the side margin, then [row]'s cards, whose pictures start on that
 * margin and sit the guide's gap apart: each card's focus inset makes up the rest.
 */
@Composable
internal fun ShelfRow(row: TvRowFocus, heading: @Composable (Modifier) -> Unit, content: LazyListScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(screenGap - TILE_FRAME)) {
        // A heading takes a gap more above it than under it, so it reads with its own row.
        heading(Modifier.padding(start = screenSide, end = screenSide, top = screenGap))
        FocusRow(row, padding = screenSide - TILE_FRAME, spacing = screenGap - TILE_FRAME * 2, content = content)
    }
}

/** A tab's empty page: what isn't there yet and why, in the placeholder's quiet voice. */
@Composable
internal fun EmptyNote(title: String, line: String, modifier: Modifier = Modifier) {
    val theme = LocalAnstelleTheme.current
    val typography = androidx.compose.material3.MaterialTheme.typography
    Column(modifier.padding(horizontal = screenSide, vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        androidx.compose.material3.Text(title, style = typography.headlineSmall.merge(TextStyle(fontWeight = FontWeight.SemiBold)), color = theme.text)
        androidx.compose.material3.Text(line, style = typography.bodyMedium, color = theme.textMuted, modifier = Modifier.widthIn(max = 560.dp))
    }
}
