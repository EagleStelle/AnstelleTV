package tv.anstelle.feature.ondemand

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import tv.anstelle.core.theme.LocalIsTv
import tv.anstelle.core.theme.components.LocalRailFocus
import tv.anstelle.core.theme.components.requestWhenReady
import tv.anstelle.core.theme.components.screenEdge
import tv.anstelle.core.theme.components.screenGap

/** A horizontal row keeps its item by identity, even when data or its lazy composition changes. */
internal class TvRowFocus(
    val items: List<String>,
    private val last: MutableState<String?>,
    val list: LazyListState,
    val requester: FocusRequester,
    val rail: FocusRequester?,
    val isTv: Boolean,
    private val onFocused: () -> Unit,
) {
    val selected: String? get() = last.value?.takeIf { it in items } ?: items.firstOrNull()
    val hasFocused: Boolean get() = last.value != null

    fun focused(id: String) {
        last.value = id
        onFocused()
    }

    suspend fun restore(scroll: Boolean = true): Boolean {
        val index = items.indexOf(selected)
        if (index < 0) return false
        if (scroll) list.scrollToItem(index)
        return requester.requestWhenReady()
    }
}

internal fun Modifier.tvFocusItem(row: TvRowFocus, id: String, horizontalEdges: Boolean = true): Modifier {
    if (!row.isTv) return this
    return this
        .then(if (id == row.selected) Modifier.focusRequester(row.requester) else Modifier)
        .onFocusChanged { if (it.isFocused) row.focused(id) }
        .focusProperties {
            if (horizontalEdges) {
                if (id == row.items.firstOrNull()) left = row.rail ?: FocusRequester.Cancel
                if (id == row.items.lastOrNull()) right = FocusRequester.Cancel
            }
        }
}

internal fun Modifier.tvFocusGroup(row: TvRowFocus): Modifier = if (!row.isTv) this else this
    .focusProperties { onEnter = { row.requester.requestFocus() } }
    .focusGroup()

/**
 * A sideways row of [row]'s items, [padding] in from its edges and [spacing] apart. On TV the
 * focused item stops on the padding's keyline, under the row's heading, as Leanback, Netflix and
 * Jellyfin rows keep it; the platform's pivot would park it 30% in.
 */
@Composable
internal fun FocusRow(
    row: TvRowFocus,
    padding: Dp,
    spacing: Dp,
    modifier: Modifier = Modifier,
    verticalAlignment: Alignment.Vertical = Alignment.Top,
    content: LazyListScope.() -> Unit,
) {
    val keyline = with(LocalDensity.current) { padding.toPx() }
    TvScroll(remember(keyline) { KeylineSpec(keyline) }) {
        LazyRow(
            state = row.list,
            modifier = modifier.tvFocusGroup(row),
            contentPadding = PaddingValues(horizontal = padding),
            horizontalArrangement = Arrangement.spacedBy(spacing),
            verticalAlignment = verticalAlignment,
            content = content,
        )
    }
}

/**
 * A browse tab's page of banner and rows. The gap under the tabs is the list's own edge, not its
 * padding, so it holds while scrolling. On TV [navigation] puts each row at the top itself, so
 * focus never scrolls the page after it: the platform's pivot would pull the row back down.
 */
@Composable
internal fun BrowseList(navigation: TvBrowseNavigation, modifier: Modifier = Modifier, content: LazyListScope.() -> Unit) {
    val isTv = LocalIsTv.current
    TvScroll(Placed) {
        LazyColumn(
            state = navigation.page,
            modifier = modifier.padding(top = screenGap).fillMaxSize().then(if (isTv) Modifier.tvBrowseNavigation(navigation) else Modifier),
            contentPadding = PaddingValues(bottom = screenEdge),
            verticalArrangement = Arrangement.spacedBy(screenGap),
            content = content,
        )
    }
}

/** On TV, [spec] decides where focus scrolls the lists in [content]; touch keeps the platform's. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TvScroll(spec: BringIntoViewSpec, content: @Composable () -> Unit) {
    if (LocalIsTv.current) CompositionLocalProvider(LocalBringIntoViewSpec provides spec, content = content) else content()
}

/** Brings the focused item's leading edge to [keyline]; the list stops at its ends on its own. */
private class KeylineSpec(private val keyline: Float) : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float) = offset - keyline
}

/** Leaves the list where its navigation put it. */
private val Placed = object : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float) = 0f
}

@Composable
internal fun rememberTvRowFocus(
    items: List<String>,
    initial: String? = null,
    identity: Any? = Unit,
    onFocused: () -> Unit = {},
): TvRowFocus = key(identity) {
    val last = rememberSaveable { mutableStateOf(initial) }
    val index = items.indexOf(last.value).coerceAtLeast(0)
    val list = rememberLazyListState(initialFirstVisibleItemIndex = index)
    val requester = remember { FocusRequester() }
    TvRowFocus(items, last, list, requester, LocalRailFocus.current?.rail, LocalIsTv.current, onFocused)
}

/** Explicit entry from the app rail; registration ends with the screen that owns the target. */
@Composable
internal fun TvRailEntry(requester: FocusRequester, onEnter: (() -> Unit)? = null) {
    val rail = LocalRailFocus.current
    val isTv = LocalIsTv.current
    val enter = rememberUpdatedState(onEnter)
    DisposableEffect(rail, requester, isTv) {
        if (isTv && rail != null) {
            rail.content = requester
            rail.onEnterContent = onEnter?.let { { enter.value?.invoke() } }
        }
        onDispose {
            if (rail?.content === requester) {
                rail.content = null
            }
        }
    }
}

internal data class TvBrowseRow(val key: String, val items: List<String>, val scrollable: Boolean = true)

/** The header and all browse tabs share one entry point and one vertical navigation policy. */
internal class TabFocus(val header: FocusRequester, val autoFocus: Boolean) {
    var enter: (() -> Unit)? = null
}

internal class TvBrowseNavigation(
    val page: LazyListState,
    private val scope: CoroutineScope,
    private val last: MutableState<String?>,
) {
    var rows: List<Pair<TvBrowseRow, TvRowFocus>> = emptyList()
    var header: FocusRequester? = null
    private var moving: Job? = null

    fun row(key: String): TvRowFocus = rows.first { it.first.key == key }.second

    fun restore() = move(rows.indexOfFirst { it.first.key == last.value }.coerceAtLeast(0))

    private fun move(index: Int) {
        if (moving?.isActive == true) return
        moving = scope.launch {
            if (index < 0) {
                header?.requestWhenReady()
            } else {
                val (spec, row) = rows.getOrNull(index) ?: return@launch
                page.scrollToItem(index)
                withFrameNanos { }
                row.restore(scroll = spec.scrollable)
            }
        }
    }

    fun step(delta: Int) {
        val current = rows.indexOfFirst { it.first.key == last.value }.coerceAtLeast(0)
        move((current + delta).coerceIn(-1, rows.lastIndex))
    }
}

private fun Modifier.tvBrowseNavigation(navigation: TvBrowseNavigation): Modifier = onPreviewKeyEvent { event ->
    val delta = when (event.key) {
        Key.DirectionDown, Key.ChannelDown, Key.PageDown -> 1
        Key.DirectionUp, Key.ChannelUp, Key.PageUp -> -1
        else -> return@onPreviewKeyEvent false
    }
    if (event.type == KeyEventType.KeyDown) navigation.step(delta)
    true
}

@Composable
internal fun rememberTvBrowseNavigation(rows: List<TvBrowseRow>, focus: TabFocus): TvBrowseNavigation {
    val page = rememberLazyListState()
    val last = rememberSaveable { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val navigation = remember(page, scope) { TvBrowseNavigation(page, scope, last) }
    navigation.rows = rows.map { row ->
        row to rememberTvRowFocus(row.items, identity = row.key, onFocused = { last.value = row.key })
    }
    navigation.header = focus.header
    val isTv = LocalIsTv.current
    DisposableEffect(focus, navigation, isTv) {
        if (isTv) focus.enter = navigation::restore
        onDispose { focus.enter = null }
    }
    if (isTv) LaunchedEffect(Unit) { if (focus.autoFocus && rows.isNotEmpty()) navigation.restore() }
    return navigation
}

/** Restores a detail page's episode, or its last watch key, after a child page or player closes. */
@Composable
internal fun rememberProgrammeFocus(
    identity: Any,
    page: LazyListState,
    episode: Long?,
    returnFocus: FocusRequester,
): FocusRequester {
    val primary = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    TvRailEntry(primary) {
        scope.launch {
            page.scrollToItem(0)
            primary.requestWhenReady()
        }
    }
    if (LocalIsTv.current) LaunchedEffect(identity) {
        if (episode != null) {
            val items = snapshotFlow { page.layoutInfo.totalItemsCount }.first { it > 0 }
            page.scrollToItem(items - 1)
            if (returnFocus.requestWhenReady()) return@LaunchedEffect
        }
        page.scrollToItem(0)
        primary.requestWhenReady()
    }
    return primary
}
