package tv.anstelle.feature.ondemand

import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.text.TextStyle
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.layout
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.relocation.BringIntoViewModifierNode
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import tv.anstelle.core.theme.ControlShape
import tv.anstelle.core.theme.LocalAnstelleTheme
import tv.anstelle.core.theme.LocalIsTv
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import tv.anstelle.core.theme.components.BACKDROP_WASH
import tv.anstelle.core.theme.components.BackBar
import coil3.compose.AsyncImage
import tv.anstelle.core.theme.components.osdTabHeight
import tv.anstelle.core.theme.components.tvEpisodeCardHeight
import tv.anstelle.core.theme.components.screenEdge
import tv.anstelle.core.theme.components.screenGap
import tv.anstelle.core.theme.components.screenSide
import kotlin.math.floor

private const val DETAIL_ARTWORK_WIDTH = 0.72f
private const val DETAIL_ARTWORK_ASPECT = 16f / 9f

/** Where the artwork starts fading into the ground, as fractions of its box: across (wide only) and down. */
private const val DETAIL_FADE_SIDE_FROM = 0.3f
private const val DETAIL_FADE_BOTTOM_FROM = 0.5f

/** The picture sets the readout's budget; compact layouts retain natural-height descriptions. */
internal val LocalReadoutHeight = staticCompositionLocalOf { Dp.Infinity }

/**
 * An edge-to-edge hero with artwork confined to the left and a readout on the right.
 * The artwork fades into the page ground; phones overlap the readout below its top image. On TV
 * a page with [seasonTabs] fits in one screen: the hero ends where the tabs and a whole row of
 * episodes still fit under it, and the artwork, always one size, runs on under them.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun Page(
    backdropUrl: String?,
    hero: @Composable ColumnScope.() -> Unit,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
    seasonTabs: (@Composable () -> Unit)? = null,
    content: LazyListScope.() -> Unit,
) {
    val theme = LocalAnstelleTheme.current
    val isTv = LocalIsTv.current
    val back = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
    // The guide's margins and gaps, so moving between the two never shifts the edges.
    val side = screenSide
    val edge = screenEdge
    val top = 0.dp
    // Wide layouts give the readout just the width of a full row of keys, so none goes behind More.
    val readoutWidth = watchKeysWidth() + side * 2
    val episodesBelow = if (isTv && seasonTabs != null) screenGap * 2 + osdTabHeight(compact = true) + tvEpisodeCardHeight() + edge else null
    BoxWithConstraints(modifier.fillMaxSize().background(theme.background)) {
        // On TV the hero ends early so the season tabs and a whole row of episodes show unscrolled; the artwork keeps its size, its faded foot running under them.
        val heroMax = episodesBelow?.let { Dp(floor((maxHeight - it).value)) } ?: Dp.Infinity
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = side, end = side, top = top, bottom = screenEdge),
            verticalArrangement = Arrangement.spacedBy(screenGap),
        ) {
            item(key = "hero") {
                BoxWithConstraints(Modifier.bleed(side, top).fillMaxWidth()) {
                    val wide = isTv || maxWidth >= 840.dp
                    val pictureWidth = maxWidth * if (wide) DETAIL_ARTWORK_WIDTH else 1f
                    val heroHeight = minOf(pictureWidth / DETAIL_ARTWORK_ASPECT, heroMax)
                    Box(Modifier.fillMaxWidth().then(if (wide) Modifier.height(heroHeight) else Modifier)) {
                        Box(Modifier.wrapContentHeight(Alignment.Top, unbounded = true).width(pictureWidth).aspectRatio(DETAIL_ARTWORK_ASPECT)
                            .then(if (wide) Modifier.absoluteOffset(x = -edge) else Modifier)) {
                            AsyncImage(
                                model = backdropUrl,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                alignment = Alignment.TopCenter,
                                modifier = Modifier.matchParentSize().detailArtworkFade(wide, theme.background),
                            )
                        }
                        Column(
                            Modifier.align(if (wide) Alignment.BottomEnd else Alignment.TopStart)
                                .then(if (wide) Modifier.width(readoutWidth) else Modifier.fillMaxWidth())
                                .then(if (wide) Modifier.heightIn(max = heroHeight) else Modifier)
                                // No bottom padding: the list's band gap alone parts the keys from the season tabs.
                                .padding(start = side, end = side, top = if (wide) edge else BAR_HEIGHT + 48.dp)
                                .then(if (wide) Modifier.verticalScroll(rememberScrollState()) else Modifier),
                            verticalArrangement = Arrangement.spacedBy(screenGap),
                        ) {
                            CompositionLocalProvider(LocalReadoutHeight provides if (wide) heroHeight - edge else Dp.Infinity) {
                                hero()
                            }
                        }
                    }
                }
            }
            if (seasonTabs != null) {
                // Under the hero on every device, so the watch keys keep their row to themselves.
                stickyHeader(key = "episodes-heading") { seasonTabs() }
            }
            content()
        }
        if (!isTv) {
            BackBar(
                onBack = { back?.onBackPressed() },
                modifier = Modifier.background(Color.Transparent),
            )
        }
    }
}

/**
 * The one fade every detail page's artwork takes, fixed to its box so channel, title and episode
 * pages match: the top half and (wide) the left stay clear, then each eases into the ground. On a
 * phone, where the readout lies over the picture, the whole picture first takes the banners' flat
 * wash ([BACKDROP_WASH]) so the text keeps its contrast.
 */
private fun Modifier.detailArtworkFade(wide: Boolean, ground: Color): Modifier =
    drawWithCache {
        val colors = IntArray(65) { index ->
            val progress = index / 64f
            // Smootherstep has zero slope and curvature at both ends of the fade.
            val opacity = progress * progress * progress * (progress * (progress * 6f - 15f) + 10f)
            ground.copy(alpha = ground.alpha * opacity).toArgb()
        }
        fun fade(x0: Float, y0: Float, x1: Float, y1: Float) = Paint().apply {
            // Native dithering prevents contour bands when dark artwork meets the ground.
            isDither = true
            shader = LinearGradient(x0, y0, x1, y1, colors, null, Shader.TileMode.CLAMP)
        }
        val bottomFade = fade(0f, size.height * DETAIL_FADE_BOTTOM_FROM, 0f, size.height.coerceAtLeast(1f))
        val sideFade = if (wide) fade(size.width * DETAIL_FADE_SIDE_FROM, 0f, size.width.coerceAtLeast(1f), 0f) else null
        onDrawWithContent {
            drawContent()
            if (!wide) drawRect(ground.copy(alpha = ground.alpha * BACKDROP_WASH))
            drawIntoCanvas { canvas ->
                canvas.nativeCanvas.drawRect(0f, 0f, size.width, size.height, bottomFade)
                sideFade?.let { canvas.nativeCanvas.drawRect(0f, 0f, size.width, size.height, it) }
            }
        }
    }

/** The phone back bar's height, which the hero leaves clear at its top. */
private val BAR_HEIGHT = 56.dp

/**
 * Lets an item of a padded list run out to the list's edges: [horizontal] past each side and
 * [top] above it, while the list still lays it out at its padded size.
 */
internal fun Modifier.bleed(horizontal: Dp, top: Dp = 0.dp) = layout { measurable, constraints ->
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
 * For a page's bars: they belong to the page's top, so while focus is on any of them the page
 * rests at its top when it fits. A hero taller than the viewport reveals its action row instead,
 * so a whole synopsis or enlarged text never leaves the focused keys below the screen.
 */
internal fun Modifier.restsAtTop(listState: LazyListState): Modifier = this then RestsAtTopElement(listState)

private data class RestsAtTopElement(val listState: LazyListState) : ModifierNodeElement<RestsAtTopNode>() {
    override fun create() = RestsAtTopNode(listState)

    override fun update(node: RestsAtTopNode) {
        node.listState = listState
    }
}

private class RestsAtTopNode(var listState: LazyListState) : Modifier.Node(), BringIntoViewModifierNode {
    override suspend fun bringIntoView(childCoordinates: LayoutCoordinates, boundsProvider: () -> Rect?) {
        val info = listState.layoutInfo
        val hero = info.visibleItemsInfo.firstOrNull { it.index == 0 }
        val offset = ((hero?.size ?: 0) - (info.viewportEndOffset - info.viewportStartOffset)).coerceAtLeast(0)
        if (listState.firstVisibleItemIndex != 0 || listState.firstVisibleItemScrollOffset != offset) listState.animateScrollToItem(0, offset)
    }
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
