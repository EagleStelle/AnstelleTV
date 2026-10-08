package tv.anstelle.feature.ondemand

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.text.TextStyle
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import tv.anstelle.core.theme.ControlShape
import tv.anstelle.core.theme.LocalAnstelleTheme
import tv.anstelle.core.theme.LocalIsTv
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import tv.anstelle.core.theme.components.BackBar
import tv.anstelle.core.theme.components.Backdrop
import tv.anstelle.core.theme.components.OsdBadge
import tv.anstelle.core.theme.components.screenEdge
import tv.anstelle.core.theme.components.screenGap
import tv.anstelle.core.theme.components.screenSide
import tv.anstelle.core.theme.components.TitleLogo


/** An image from the server, or the title's first letter on the surface step when there is none. */
@Composable
internal fun Poster(imageUrl: String?, title: String, modifier: Modifier) {
    val theme = LocalAnstelleTheme.current
    Box(modifier.clip(ControlShape).background(theme.surfaceHigh), contentAlignment = Alignment.Center) {
        androidx.compose.material3.Text(
            title.take(1).uppercase(),
            style = androidx.compose.material3.MaterialTheme.typography.displayMedium,
            color = theme.line,
        )
        if (imageUrl != null) {
            AsyncImage(model = imageUrl, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
    }
}

/**
 * A scrolling page headed by a hero: the show's or movie's backdrop under the flat wash, edge
 * to edge, with [hero] on it (title or logo, facts, overview). The rest of the page follows on
 * the plain ground. Phones get a back arrow over the hero; it takes the ground's color once the
 * hero has scrolled away from under it, with [topAction] (an action about the whole page) at
 * its end. TV has no back bar; its hero carries the action itself.
 */
@Composable
internal fun Page(
    backdropUrl: String?,
    hero: @Composable ColumnScope.() -> Unit,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
    topAction: (@Composable () -> Unit)? = null,
    content: LazyListScope.() -> Unit,
) {
    val theme = LocalAnstelleTheme.current
    val isTv = LocalIsTv.current
    val back = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
    // The guide's margins and gaps, so moving between the two never shifts the edges.
    val side = screenSide
    val top = if (isTv) screenEdge else 0.dp
    Box(modifier.fillMaxSize().background(theme.background)) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = side, end = side, top = top, bottom = screenEdge),
            verticalArrangement = Arrangement.spacedBy(screenGap),
        ) {
            item(key = "hero") {
                Backdrop(backdropUrl, Modifier.bleed(side, top).fillMaxWidth()) {
                    Column(
                        Modifier.padding(start = side, end = side, top = if (isTv) 24.dp else BAR_HEIGHT + 4.dp, bottom = if (isTv) 20.dp else 16.dp),
                        verticalArrangement = Arrangement.spacedBy(screenGap),
                        content = hero,
                    )
                }
            }
            content()
        }
        if (!isTv) {
            val barPx = with(LocalDensity.current) { BAR_HEIGHT.roundToPx() }
            val overHero by remember {
                derivedStateOf {
                    val first = listState.layoutInfo.visibleItemsInfo.firstOrNull()
                    first != null && first.index == 0 && first.offset + first.size >= barPx
                }
            }
            BackBar(
                onBack = { back?.onBackPressed() },
                modifier = Modifier.background(if (overHero) Color.Transparent else theme.background),
                actions = { topAction?.invoke() },
            )
        }
    }
}

/** The phone back bar's height, which the hero leaves clear at its top. */
private val BAR_HEIGHT = 56.dp

/**
 * Lets an item of a padded list run out to the list's edges: [horizontal] past each side and
 * [top] above it, while the list still lays it out at its padded size.
 */
private fun Modifier.bleed(horizontal: Dp, top: Dp) = layout { measurable, constraints ->
    val extraWidth = (horizontal * 2).roundToPx()
    val extraTop = top.roundToPx()
    val placeable = measurable.measure(
        constraints.copy(minWidth = constraints.minWidth + extraWidth, maxWidth = constraints.maxWidth + extraWidth),
    )
    layout(placeable.width - extraWidth, placeable.height - extraTop) {
        placeable.place(-extraWidth / 2, -extraTop)
    }
}

/**
 * A title as its logo, or as text when there is none; long titles take two lines. The logo
 * sits on the backdrop the way the server's art was drawn to be shown.
 */
@Composable
internal fun HeroTitle(logoUrl: String?, title: String) {
    val isTv = LocalIsTv.current
    TitleLogo(
        logoUrl = logoUrl,
        title = title,
        height = if (isTv) 84.dp else 56.dp,
        maxLogoWidth = if (isTv) 420.dp else 240.dp,
        style = if (isTv) {
            androidx.compose.material3.MaterialTheme.typography.headlineLarge
        } else {
            androidx.compose.material3.MaterialTheme.typography.headlineMedium
        },
        maxLines = 2,
    )
}

/** A long overview: up to [tvLines] on TV, all of it on a phone, where the page scrolls. */
@Composable
internal fun Overview(text: String, modifier: Modifier = Modifier, tvLines: Int = 6) {
    val isTv = LocalIsTv.current
    androidx.compose.material3.Text(
        text,
        style = androidx.compose.material3.MaterialTheme.typography.bodyLarge,
        color = LocalAnstelleTheme.current.text,
        maxLines = if (isTv) tvLines else Int.MAX_VALUE,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier.widthIn(max = 720.dp),
    )
}

/** On TV, focuses the requester's element once it is laid out. */
@Composable
internal fun rememberInitialFocus(key: Any? = Unit): FocusRequester {
    val requester = remember { FocusRequester() }
    if (LocalIsTv.current) LaunchedEffect(key) { runCatching { requester.requestFocus() } }
    return requester
}

@Composable
internal fun Badge(text: String) = OsdBadge(text)

/** Rating, year, genres and length, then the picture and sound badges. */
@Composable
internal fun Facts(facts: String, badges: List<String>) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        if (facts.isNotEmpty()) Text(facts, size = 15, color = LocalAnstelleTheme.current.textMuted, modifier = Modifier.weight(1f, fill = false))
        badges.forEach { Badge(it) }
    }
}

@Composable
internal fun Text(
    text: String,
    size: Int,
    modifier: Modifier = Modifier,
    weight: FontWeight = FontWeight.Normal,
    color: Color = LocalAnstelleTheme.current.text,
    maxLines: Int = Int.MAX_VALUE,
) {
    androidx.compose.material3.Text(
        text,
        modifier = modifier,
        color = color,
        fontSize = size.sp,
        fontWeight = weight,
        fontFamily = LocalAnstelleTheme.current.bodyFont,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}

/** The wall clock, updated twice a minute, for time left and progress ladders. */
@Composable
internal fun rememberClock(): State<Long> {
    val clock = remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            clock.longValue = System.currentTimeMillis()
            delay(CLOCK_MS)
        }
    }
    return clock
}

private const val CLOCK_MS = 30_000L

/** How far [nowMs] is through an airing, 0 to 1. */
internal fun progress(startUtcMs: Long, endUtcMs: Long, nowMs: Long): Float =
    if (endUtcMs <= startUtcMs) 0f else ((nowMs - startUtcMs).toFloat() / (endUtcMs - startUtcMs)).coerceIn(0f, 1f)

/**
 * A channel number as a small OSD block: ink on the accent, or the accent on ink while the
 * tile around it is focused, so the block still reads on the focus fill.
 */
@Composable
internal fun ChannelNumber(number: Int, focused: Boolean, style: TextStyle, modifier: Modifier = Modifier) {
    val theme = LocalAnstelleTheme.current
    androidx.compose.material3.Text(
        "$number",
        style = style.merge(TextStyle(fontFamily = theme.displayFont, fontWeight = FontWeight.Bold, fontFeatureSettings = "tnum")),
        color = if (focused) theme.accent else theme.onAccent,
        maxLines = 1,
        modifier = modifier
            .background(if (focused) theme.onAccent else theme.accent, ControlShape)
            .padding(horizontal = 6.dp, vertical = 1.dp),
    )
}
