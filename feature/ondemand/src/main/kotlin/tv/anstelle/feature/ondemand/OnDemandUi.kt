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
import androidx.compose.foundation.lazy.rememberLazyListState

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
import tv.anstelle.core.theme.components.FocusSurface
import tv.anstelle.core.theme.components.OsdBadge
import tv.anstelle.core.theme.components.TitleLogo


/**
 * A poster with its title. Focus fills the card with the accent, so the poster sits in an
 * accent frame over an accent caption; nothing grows or glows.
 */
@Composable
internal fun PosterCard(
    title: String,
    subtitle: String?,
    imageUrl: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val theme = LocalAnstelleTheme.current
    FocusSurface(onClick = onClick, modifier = modifier, color = Color.Transparent) { focused ->
        Column(Modifier.padding(3.dp)) {
            Poster(imageUrl, title, Modifier.fillMaxWidth().aspectRatio(2f / 3f))
            Column(Modifier.padding(horizontal = 5.dp, vertical = 6.dp)) {
                androidx.compose.material3.Text(
                    title,
                    style = androidx.compose.material3.MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                subtitle?.let {
                    androidx.compose.material3.Text(
                        it,
                        style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                        color = if (focused) theme.onAccent.copy(alpha = 0.76f) else theme.textMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

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
 * hero has scrolled away from under it.
 */
@Composable
internal fun Page(
    backdropUrl: String?,
    hero: @Composable ColumnScope.() -> Unit,
    modifier: Modifier = Modifier,
    content: LazyListScope.() -> Unit,
) {
    val theme = LocalAnstelleTheme.current
    val isTv = LocalIsTv.current
    val back = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
    val side = if (isTv) 48.dp else 16.dp
    val top = if (isTv) 32.dp else 0.dp
    val listState = rememberLazyListState()
    Box(modifier.fillMaxSize().background(theme.background)) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = side, end = side, top = top, bottom = if (isTv) 32.dp else 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "hero") {
                Backdrop(backdropUrl, Modifier.bleed(side, top).fillMaxWidth()) {
                    Column(
                        Modifier.padding(start = side, end = side, top = if (isTv) 40.dp else BAR_HEIGHT + 4.dp, bottom = if (isTv) 28.dp else 20.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
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

/** A long overview: up to six lines on TV, all of it on a phone, where the page scrolls. */
@Composable
internal fun Overview(text: String, modifier: Modifier = Modifier) {
    val isTv = LocalIsTv.current
    androidx.compose.material3.Text(
        text,
        style = androidx.compose.material3.MaterialTheme.typography.bodyLarge,
        color = LocalAnstelleTheme.current.text,
        maxLines = if (isTv) 6 else Int.MAX_VALUE,
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

/** "12:34" or "1:02:03". */
internal fun clock(ms: Long): String {
    val seconds = ms / 1000
    val h = seconds / 3600
    val m = seconds % 3600 / 60
    val s = seconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
