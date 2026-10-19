package tv.anstelle.feature.ondemand

import androidx.compose.foundation.background
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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.layout
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.relocation.BringIntoViewModifierNode
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import tv.anstelle.core.theme.ControlShape
import tv.anstelle.core.theme.LocalAnstelleTheme
import tv.anstelle.core.theme.LocalIsTv
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import tv.anstelle.core.theme.components.BackBar
import tv.anstelle.core.theme.components.Backdrop
import tv.anstelle.core.theme.components.TV_BANNER_ASPECT
import tv.anstelle.core.theme.components.screenEdge
import tv.anstelle.core.theme.components.screenGap
import tv.anstelle.core.theme.components.screenSide


/**
 * A scrolling page headed by a hero: the show's or movie's backdrop under the flat wash, edge
 * to edge, with [hero] on it (the programme sheet). The rest of the page follows on the plain
 * ground. Phones get a back arrow over the hero; it takes the ground's color once the hero has scrolled away
 * from under it. TV has no back bar.
 */
@Composable
internal fun Page(
    backdropUrl: String?,
    hero: @Composable ColumnScope.() -> Unit,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
    content: LazyListScope.() -> Unit,
) {
    val theme = LocalAnstelleTheme.current
    val isTv = LocalIsTv.current
    val back = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
    // The guide's margins and gaps, so moving between the two never shifts the edges.
    val side = screenSide
    val top = 0.dp
    Box(modifier.fillMaxSize().background(theme.background)) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = side, end = side, top = top, bottom = screenEdge),
            verticalArrangement = Arrangement.spacedBy(screenGap),
        ) {
            item(key = "hero") {
                Backdrop(
                    backdropUrl,
                    Modifier.bleed(side, top).fillMaxWidth().then(if (isTv) Modifier.aspectRatio(TV_BANNER_ASPECT) else Modifier),
                ) {
                    Column(
                        Modifier.align(Alignment.CenterStart).padding(start = side, end = side, top = if (isTv) 24.dp else BAR_HEIGHT + 4.dp, bottom = if (isTv) 20.dp else 16.dp),
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
 * rests at its top, the whole sheet and its backdrop in sight as when it opened. A bar asking to
 * be shown (up from the episodes, or across the row) moves the page there and asks the list
 * nothing, so TV's pivot, which holds focus 30% down the screen, never scrolls the sheet away.
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
        if (listState.firstVisibleItemIndex != 0 || listState.firstVisibleItemScrollOffset != 0) listState.animateScrollToItem(0)
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
