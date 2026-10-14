package tv.anstelle.feature.ondemand

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
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
import tv.anstelle.core.data.ondemand.WatchChoices
import tv.anstelle.core.theme.ControlShape
import tv.anstelle.core.theme.LocalAnstelleTheme
import tv.anstelle.core.theme.LocalIsTv
import tv.anstelle.core.theme.components.AirMarker
import tv.anstelle.core.theme.components.Backdrop
import tv.anstelle.core.theme.components.FocusSurface
import tv.anstelle.core.theme.components.PastShade
import tv.anstelle.core.theme.components.SegmentLadder
import tv.anstelle.core.theme.components.TitleLogo
import tv.anstelle.core.theme.components.WatchStrip
import tv.anstelle.core.theme.components.screenSide
import kotlin.math.abs

/** The inset that focus fills round a tile's, card's or poster's picture. */
internal val TILE_FRAME = 3.dp

/**
 * Every banner's height, fixed: the room a slide needs with its logo, facts and two lines of
 * overview, so Channels, Movies and Series stand alike whatever a slide holds or the order
 * the tabs are opened in.
 */
private val BANNER_HEIGHT @Composable get() = if (LocalIsTv.current) 300.dp else 256.dp

/** How far a swipe has to travel to step a banner. */
private val SWIPE = 48.dp

/** How long a banner holds a slide while focus is elsewhere. */
private const val ROTATE_MS = 8_000L

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
    val height = Modifier.height(BANNER_HEIGHT)
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
                    Key.DirectionLeft -> (shown > 0).also { if (it) index = shown - 1 }
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
                .then(height),
            alignment = Alignment.Center,
        ) {
            // Edge to edge, so focus shows as a frame drawn inside the picture.
            if (isTv && isFocused) Box(Modifier.matchParentSize().border(TILE_FRAME, LocalAnstelleTheme.current.accent))
            Column(
                Modifier
                    .align(Alignment.BottomStart)
                    // The text lines up with the row headers under it.
                    .padding(horizontal = screenSide, vertical = if (isTv) 22.dp else 16.dp),
                verticalArrangement = Arrangement.spacedBy(if (isTv) 6.dp else 4.dp),
            ) {
                content(shown)
                Row(
                    Modifier.fillMaxWidth().padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    footer(shown)
                    Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) { Pager(count, shown, isFocused) }
                }
            }
        }
    }
}

/** One square per banner slide: the one showing solid in the accent, the rest hollow. */
@Composable
private fun Pager(count: Int, shown: Int, focused: Boolean) {
    if (count < 2) return
    val theme = LocalAnstelleTheme.current
    Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
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
    TitleFacts(title.year, title.rating, title.genres, if (title.durationMs > 0) WatchChoices.span(title.durationMs) else null, Modifier.padding(top = 2.dp))
    WatchLine(title, watch)
    title.overview?.let {
        androidx.compose.material3.Text(
            it,
            style = typography.bodyMedium,
            color = theme.text,
            minLines = 2,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 620.dp),
        )
    }
}

/**
 * What was watched of a title, in the watch strip's words: a movie part-way through as its
 * ladder alone, a series as how many episodes are left. Nothing when nothing is known; the
 * banner never deals one watched through.
 */
@Composable
private fun WatchLine(title: Title, watch: CatalogWatch) {
    val theme = LocalAnstelleTheme.current
    val isTv = LocalIsTv.current
    val label = androidx.compose.material3.MaterialTheme.typography.labelMedium
    val fraction = watch.fraction(title)
    val unwatched = watch.unwatched(title)
    when {
        fraction > 0f -> SegmentLadder(fraction, Modifier.width(if (isTv) 180.dp else 120.dp), segments = if (isTv) 18 else 12)
        unwatched != null && unwatched > 0 -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            UnwatchedCount(unwatched)
            androidx.compose.material3.Text(if (unwatched == 1) "EPISODE TO WATCH" else "EPISODES TO WATCH", style = label, color = theme.textMuted)
        }
    }
}

/**
 * A title's facts on one line, set apart by space rather than dots: the year, the rating as an
 * OSD badge, the genres, then the length (or how many seasons).
 */
@Composable
internal fun TitleFacts(year: Int?, rating: String?, genres: List<String>, length: String?, modifier: Modifier = Modifier) {
    val theme = LocalAnstelleTheme.current
    val body = androidx.compose.material3.MaterialTheme.typography.bodyMedium
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        year?.let { androidx.compose.material3.Text("$it", style = body.merge(TextStyle(fontFeatureSettings = "tnum")), color = theme.text, maxLines = 1) }
        rating?.let { RatingBadge(it, theme.text) }
        genres.take(3).takeIf { it.isNotEmpty() }?.let {
            androidx.compose.material3.Text(it.joinToString(", "), style = body, color = theme.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        }
        length?.let { androidx.compose.material3.Text(it, style = body, color = theme.textMuted, maxLines = 1) }
    }
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
    val fraction = watch.fraction(title)
    val unwatched = watch.unwatched(title)
    PosterCardSurface(
        posterUrl = title.posterUrl,
        title = title.title,
        rating = title.rating,
        fraction = fraction,
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
        fraction = if (card.durationMs > 0) card.positionMs.toFloat() / card.durationMs else 0f,
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
    fraction: Float,
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
            Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(ControlShape)) {
                Poster(posterUrl, title, Modifier.fillMaxWidth().aspectRatio(2f / 3f))
                if (watched) Box(Modifier.matchParentSize().background(PastShade))
                if (fraction > 0f || watched) WatchStrip(fraction, watched, Modifier.align(Alignment.BottomStart), segments = 10)
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
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(ControlShape)) {
                Poster(card.imageUrl, card.title, Modifier.fillMaxWidth().aspectRatio(16f / 9f))
                val fraction = if (card.durationMs > 0) card.positionMs.toFloat() / card.durationMs else 0f
                if (fraction > 0f) WatchStrip(fraction, false, Modifier.align(Alignment.BottomStart))
            }
            Column(Modifier.padding(start = 4.dp, end = 4.dp, top = 7.dp, bottom = 5.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                androidx.compose.material3.Text(card.title, style = typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (card.number != null || card.episodeTitle != null) CaptionLine(card.number, card.episodeTitle, focused)
            }
        }
    }
}

/**
 * A row's heading in the OSD's field voice: a channel's number block before its name, then how
 * many titles it holds in muted; a fixed row is its name alone.
 */
@Composable
internal fun ShelfHeader(shelf: Shelf, count: String?, modifier: Modifier = Modifier) {
    val theme = LocalAnstelleTheme.current
    val isTv = LocalIsTv.current
    val style = androidx.compose.material3.MaterialTheme.typography.labelMedium.copy(fontSize = if (isTv) 15.sp else 13.sp, lineHeight = 18.sp)
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        shelf.channel?.let { ChannelNumber(it.number, focused = false, style = androidx.compose.material3.MaterialTheme.typography.labelLarge) }
        androidx.compose.material3.Text(shelf.label.uppercase(), style = style, color = theme.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        count?.let { androidx.compose.material3.Text(it.uppercase(), style = style, color = theme.textMuted, maxLines = 1) }
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
