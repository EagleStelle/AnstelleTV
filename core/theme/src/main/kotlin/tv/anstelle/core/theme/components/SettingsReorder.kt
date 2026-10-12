package tv.anstelle.core.theme.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import tv.anstelle.core.theme.ControlShape
import tv.anstelle.core.theme.LocalAnstelleTheme
import tv.anstelle.core.theme.LocalIsTv

/*
 * A list the viewer puts in order (the audio and subtitle languages, Live TV's groups), the way
 * Android's own language list does it: every row ends in a drag handle.
 *
 * - Touch: the handle drags the row through the list. The others make way, and the list scrolls
 *   on when the row reaches its edge. Let go, the row settles into its place.
 * - TV: the handle is a key of its own, right of the row. OK picks the row up, up and down carry
 *   it, and OK or Back sets it down.
 *
 * Pressing the row itself still opens its menu (remove, rename). Only setting a row down changes
 * the order, through [rememberReorderList]'s onMove.
 */

/** Rows in hand are drawn on top; the rest glide out of the way this fast, as does a row let go. */
private const val SETTLE_MS = 160

@Stable
class ReorderList<T : Any> internal constructor(
    private val upstream: State<List<T>>,
    internal val keyOf: (T) -> Any,
    private val listState: LazyListState,
    private val scope: CoroutineScope,
    private val onMove: State<(item: T, from: Int, to: Int) -> Unit>,
) {
    /** The order being made since a row was picked up, and the caller's list it started from. */
    private var moved by mutableStateOf<List<T>?>(null)
    private var base: List<T>? = null

    /**
     * The rows as shown: the caller's list, or the order being made while a row is in hand and
     * until the caller's list catches up with it.
     */
    val order: List<T>
        get() {
            val made = moved ?: return upstream.value
            return if (held != null || upstream.value == base) made else upstream.value
        }

    /** The row in hand, by finger or by D-pad, and where it was picked up. */
    internal var held by mutableStateOf<T?>(null)
        private set
    internal var byTouch by mutableStateOf(false)
        private set
    private var from = -1

    // Touch: where the row lay when picked up, and how far the finger has gone since, on the screen.
    private var startOffset by mutableIntStateOf(0)
    private var dragged by mutableFloatStateOf(0f)

    /** The layout a swap was made in: the next swap waits for the list to lay out the last one. */
    private var swappedIn: LazyListLayoutInfo? = null

    /** A row just let go, gliding from where the finger left it into its place. */
    internal var settling by mutableStateOf<Any?>(null)
        private set
    internal var settleOffset by mutableFloatStateOf(0f)
        private set
    private var settleJob: Job? = null

    internal fun isHeld(key: Any): Boolean = held?.let(keyOf) == key

    private fun indexOf(list: List<T>, key: Any) = list.indexOfFirst { keyOf(it) == key }

    private fun info(key: Any) = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == key }

    /** How far the row under the finger is drawn from its place in the list. */
    internal val dragOffset: Float
        get() {
            val item = held?.takeIf { byTouch } ?: return 0f
            val here = info(keyOf(item)) ?: return 0f
            return startOffset + dragged - here.offset
        }

    internal fun pickUp(key: Any, touch: Boolean) {
        if (held != null) return
        val now = order
        val at = indexOf(now, key)
        if (at < 0) return
        settleJob?.cancel()
        settling = null
        base = upstream.value
        moved = now
        from = at
        byTouch = touch
        if (touch) {
            startOffset = info(key)?.offset ?: 0
            dragged = 0f
            swappedIn = null
        }
        held = now[at]
    }

    /** Touch: the finger moved [dy] down the screen. */
    internal fun drag(dy: Float) {
        val item = held ?: return
        dragged += dy
        val key = keyOf(item)
        val layout = listState.layoutInfo
        val here = layout.visibleItemsInfo.firstOrNull { it.key == key } ?: return
        val list = moved ?: return
        val top = here.offset + dragOffset
        val middle = top + here.size / 2f
        val target = layout.visibleItemsInfo.firstOrNull { other ->
            other.key != key && indexOf(list, other.key) >= 0 && middle >= other.offset && middle < other.offset + other.size
        }
        if (target != null) {
            if (layout === swappedIn) return
            swappedIn = layout
            holdScroll(here.index, target.index)
            moved = list.toMutableList().apply { add(indexOf(list, target.key), removeAt(indexOf(list, key))) }
        } else {
            // At the list's edge, the list scrolls on under the row.
            val over = when {
                dy > 0f -> (top + here.size - layout.viewportEndOffset).coerceAtLeast(0f)
                dy < 0f -> (top - layout.viewportStartOffset).coerceAtMost(0f)
                else -> 0f
            }
            if (over != 0f) listState.dispatchRawDelta(over)
        }
    }

    /** D-pad: carries the row in hand one place up (-1) or down (+1). */
    internal fun step(delta: Int) {
        val item = held ?: return
        val list = moved ?: return
        val key = keyOf(item)
        val at = indexOf(list, key)
        val to = at + delta
        if (at < 0 || to !in list.indices) return
        val here = info(key)
        val next = info(keyOf(list[to]))
        if (here != null && next != null) holdScroll(here.index, next.index)
        moved = list.toMutableList().apply { add(to, removeAt(at)) }
    }

    /** Sets the row in hand down where it is now. */
    internal fun drop() {
        val item = held ?: return
        val key = keyOf(item)
        val glide = dragOffset
        held = null
        if (byTouch && glide != 0f) {
            settling = key
            settleOffset = glide
            settleJob = scope.launch {
                animate(glide, 0f, animationSpec = tween(SETTLE_MS)) { value, _ -> settleOffset = value }
                settling = null
            }
        }
        byTouch = false
        dragged = 0f
        val to = moved?.let { indexOf(it, key) } ?: -1
        if (to >= 0 && to != from) onMove.value(item, from, to)
    }

    /** Moves a row at once, for a screen reader's Move up and Move down. */
    internal fun nudge(key: Any, delta: Int) {
        if (held != null) return
        val list = order
        val at = indexOf(list, key)
        val to = at + delta
        if (at < 0 || to !in list.indices) return
        base = upstream.value
        moved = list.toMutableList().apply { add(to, removeAt(at)) }
        onMove.value(list[at], at, to)
    }

    /** The caller's list changed with no row in hand: it is the order now. */
    internal fun follow(items: List<T>) {
        if (held == null && moved != null && items != base) {
            moved = null
            base = null
        }
    }

    // A lazy list keeps its first visible row where it is, so moving that row would scroll the
    // list along with it: hold the scroll where it stands instead.
    private fun holdScroll(a: Int, b: Int) {
        val first = listState.firstVisibleItemIndex
        if (a == first || b == first) listState.requestScrollToItem(first, listState.firstVisibleItemScrollOffset)
    }
}

/**
 * A list the viewer puts in order: [items] as the caller holds them, each told apart by [key]
 * (unique in the whole settings list, as it is the lazy list's key too). [onMove] gets a row set
 * down somewhere new, with its place before and after. Give the same [listState] to the page.
 */
@Composable
fun <T : Any> rememberReorderList(
    items: List<T>,
    listState: LazyListState,
    key: (T) -> Any,
    onMove: (item: T, from: Int, to: Int) -> Unit,
): ReorderList<T> {
    val upstream = rememberUpdatedState(items)
    val move = rememberUpdatedState(onMove)
    val scope = rememberCoroutineScope()
    val list = remember(listState) { ReorderList(upstream, key, listState, scope, move) }
    LaunchedEffect(items) { list.follow(items) }
    // Back sets a row the D-pad is carrying down where it is.
    BackHandler(enabled = list.held != null && !list.byTouch) { list.drop() }
    return list
}

/** The rows of [list] in the order shown, each drawn by [row] (a [ReorderRow]) with its place. */
fun <T : Any> LazyListScope.reorderItems(list: ReorderList<T>, row: @Composable (index: Int, item: T) -> Unit) {
    itemsIndexed(list.order, key = { _, item -> list.keyOf(item) }) { index, item ->
        val key = list.keyOf(item)
        val place = when {
            list.isHeld(key) && list.byTouch -> Modifier.zIndex(1f).graphicsLayer { translationY = list.dragOffset }
            // Carried by the D-pad, the row snaps to each place; the others glide.
            list.isHeld(key) -> Modifier.zIndex(1f)
            list.settling == key -> Modifier.zIndex(1f).graphicsLayer { translationY = list.settleOffset }
            else -> Modifier.animateItem(fadeInSpec = null, placementSpec = tween(SETTLE_MS), fadeOutSpec = null)
        }
        Box(place.fillMaxWidth()) { row(index, item) }
    }
}

/**
 * A row of a [ReorderList]: the settings row [row] draws (a [MenuRow], a [TextRow]; it gets
 * [modifier] and the room left of the handle), then the drag handle named for [title]. In hand,
 * the row steps up off its ground, a fill and never a shadow.
 */
@Composable
fun <T : Any> ReorderRow(
    list: ReorderList<T>,
    item: T,
    title: String,
    modifier: Modifier = Modifier,
    row: @Composable (Modifier) -> Unit,
) {
    val theme = LocalAnstelleTheme.current
    val key = list.keyOf(item)
    val order = list.order
    val index = order.indexOfFirst { list.keyOf(it) == key }
    val lifted = list.isHeld(key) || list.settling == key
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (lifted) theme.surfaceHigh else Color.Transparent, ControlShape),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        row(modifier.weight(1f))
        if (LocalIsTv.current) {
            MoveKey(list, key, title, canUp = index > 0, canDown = index in 0 until order.lastIndex, index = index)
        } else {
            DragHandle(list, key, title, canUp = index > 0, canDown = index in 0 until order.lastIndex)
        }
    }
}

/** Touch: the handle a row is dragged by, which also gives a screen reader Move up and Move down. */
@Composable
private fun <T : Any> DragHandle(list: ReorderList<T>, key: Any, title: String, canUp: Boolean, canDown: Boolean) {
    val theme = LocalAnstelleTheme.current
    // The handle moves with the row under the finger, so the finger is measured on the screen.
    val place = remember { arrayOfNulls<LayoutCoordinates>(1) }
    Box(
        Modifier
            .size(48.dp)
            .onPlaced { place[0] = it }
            .semantics(mergeDescendants = true) {
                contentDescription = "Reorder $title"
                customActions = buildList {
                    if (canUp) add(CustomAccessibilityAction("Move up") { list.nudge(key, -1).let { true } })
                    if (canDown) add(CustomAccessibilityAction("Move down") { list.nudge(key, 1).let { true } })
                }
            }
            .pointerInput(list, key) {
                fun onScreen(local: Offset) = place[0]?.takeIf { it.isAttached }?.localToRoot(local) ?: local
                awaitEachGesture {
                    // A handle is only for dragging, so the row lifts at the touch, with no slop to wait out.
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    var last = onScreen(down.position)
                    list.pickUp(key, touch = true)
                    try {
                        while (true) {
                            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                            change.consume()
                            if (!change.pressed) break
                            val now = onScreen(change.position)
                            if (now.y != last.y) list.drag(now.y - last.y)
                            last = now
                        }
                    } finally {
                        list.drop()
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Filled.DragHandle, contentDescription = null, tint = theme.textMuted, modifier = Modifier.size(24.dp))
    }
}

/**
 * TV: the handle as a key of its own, right of the row. OK picks the row up and its mark turns
 * to up and down arrows (one that can't go further at 45%); up and down carry it, left and right
 * go nowhere, and OK, Back or leaving the key sets it down.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun <T : Any> MoveKey(list: ReorderList<T>, key: Any, title: String, canUp: Boolean, canDown: Boolean, index: Int) {
    val theme = LocalAnstelleTheme.current
    val held = list.isHeld(key)
    val view = remember { BringIntoViewRequester() }
    // Carried, the row stays in view: once it has its new place, the list scrolls to it.
    LaunchedEffect(held, index) {
        if (held) {
            withFrameNanos { }
            view.bringIntoView()
        }
    }
    FocusSurface(
        onClick = { if (held) list.drop() else list.pickUp(key, touch = false) },
        color = Color.Transparent,
        modifier = Modifier
            .size(56.dp)
            .bringIntoViewRequester(view)
            .onFocusChanged { if (!it.isFocused && list.isHeld(key)) list.drop() }
            .onPreviewKeyEvent { event ->
                if (!list.isHeld(key)) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionUp, Key.DirectionDown -> {
                        if (event.type == KeyEventType.KeyDown) list.step(if (event.key == Key.DirectionUp) -1 else 1)
                        true
                    }
                    Key.DirectionLeft, Key.DirectionRight -> true
                    else -> false
                }
            },
    ) { focused ->
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            val tint = if (focused) theme.onAccent else theme.textMuted
            if (held) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Filled.KeyboardArrowUp, contentDescription = null, tint = tint.copy(alpha = if (canUp) 1f else 0.45f), modifier = Modifier.size(22.dp))
                    Icon(Icons.Filled.KeyboardArrowDown, contentDescription = null, tint = tint.copy(alpha = if (canDown) 1f else 0.45f), modifier = Modifier.size(22.dp))
                }
            } else {
                Icon(Icons.Filled.DragHandle, contentDescription = "Move $title", tint = tint, modifier = Modifier.size(24.dp))
            }
        }
    }
}
