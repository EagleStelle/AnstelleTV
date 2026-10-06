package tv.anstelle.feature.guide

import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.core.tween
import androidx.compose.animation.rememberSplineBasedDecay
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import kotlinx.coroutines.flow.collectLatest
import tv.anstelle.core.data.guide.ProgramStrip
import tv.anstelle.core.theme.LocalAnstelleTheme
import java.text.DateFormat
import java.util.Date
import kotlin.math.max
import kotlin.math.min

/** Sizes of the grid on this device. */
internal class GridMetrics(
    val rowHeight: Dp,
    val channelWidth: Dp,
    val headerHeight: Dp,
    val dpPerMinute: Float,
    val showDetailLine: Boolean,
    /** Touch screens have no focus, so the selected cell is always highlighted. */
    val touch: Boolean,
)

/**
 * The guide grid: a time header over a lazy column of channel rows that share the visible
 * hours in [grid]. Only the cells inside the visible hours (plus a margin) are composed; the
 * hours scrolling only relays them out. The now line and the dimmed past are drawn over the
 * rows, so the per-minute clock never recomposes anything.
 *
 * D-pad: the whole grid is one focus target and moves the selection itself. Touch: drag or
 * fling sideways to move the hours, tap a cell to select it ([onCellTap] says whether it was
 * already selected), long-press for its actions.
 */
@Composable
internal fun GuideGrid(
    channels: List<GuideChannel>,
    strips: State<Map<Long, ProgramStrip>>,
    grid: GuideGridState,
    listState: LazyListState,
    now: State<Long>,
    metrics: GridMetrics,
    focusRequester: FocusRequester,
    onActivate: () -> Unit,
    onActions: () -> Unit,
    onCellTap: (again: Boolean) -> Unit,
    onChannelTap: (GuideChannel) -> Unit,
    modifier: Modifier = Modifier,
) {
    val theme = LocalAnstelleTheme.current
    val density = LocalDensity.current
    val channelWidthPx = with(density) { metrics.channelWidth.toPx() }
    val nowLinePx = with(density) { 2.dp.toPx() }
    val decay = rememberSplineBasedDecay<Float>()

    // Key navigation sets the target; the hours glide there.
    LaunchedEffect(grid) {
        snapshotFlow { grid.targetStart }.collectLatest { target ->
            val from = grid.windowStart
            if (from == target) return@collectLatest
            animate(0f, 1f, animationSpec = tween(PAGE_MS)) { fraction, _ ->
                grid.windowStart = from + ((target - from) * fraction).toLong()
            }
        }
    }

    fun revealRow() {
        val index = channels.indexOfFirst { it.id == grid.selectedChannel }
        if (index < 0) return
        val info = listState.layoutInfo
        val visible = info.visibleItemsInfo
        val firstFull = visible.firstOrNull { it.offset >= info.viewportStartOffset }?.index ?: return
        val lastFull = visible.lastOrNull { it.offset + it.size <= info.viewportEndOffset }?.index ?: return
        when {
            index < firstFull -> listState.requestScrollToItem(index)
            index > lastFull -> listState.requestScrollToItem((index - (lastFull - firstFull)).coerceAtLeast(0))
        }
    }

    fun visibleRows(): Int = listState.layoutInfo.visibleItemsInfo.size.coerceAtLeast(2) - 1

    var longPressed by remember { mutableStateOf(false) }
    val dragState = rememberDraggableState { delta -> grid.scrollByPx(-delta, now.value) }

    Column(modifier) {
        TimeHeader(grid, metrics, now)
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .onSizeChanged { grid.widthPx = (it.width - channelWidthPx).toInt().coerceAtLeast(0) }
                .drawWithContent {
                    drawContent()
                    val x = channelWidthPx + (now.value - grid.windowStart) * grid.pxPerMs
                    if (x > channelWidthPx) {
                        // The part of the hours that has already aired.
                        drawRect(PastShade, topLeft = Offset(channelWidthPx, 0f), size = Size(min(x, size.width) - channelWidthPx, size.height))
                        if (x < size.width) drawRect(theme.nowLine, topLeft = Offset(x - nowLinePx / 2, 0f), size = Size(nowLinePx, size.height))
                    }
                }
                .testTag("guide-grid")
                .focusRequester(focusRequester)
                .onFocusChanged { grid.focused = it.isFocused }
                .onKeyEvent { event ->
                    val key = event.key
                    if (key in ConfirmKeys) {
                        if (event.type == KeyEventType.KeyDown) {
                            // Holding OK opens the actions, like Menu.
                            if (event.nativeKeyEvent.repeatCount == 1 && !longPressed) {
                                longPressed = true
                                onActions()
                            }
                        } else if (event.type == KeyEventType.KeyUp) {
                            when {
                                longPressed -> longPressed = false
                                key == Key.Menu || key == Key.Info -> onActions()
                                else -> onActivate()
                            }
                        }
                        return@onKeyEvent true
                    }
                    if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                    val strip = strips.value[grid.selectedChannel]
                    when (key) {
                        Key.DirectionRight -> grid.moveRight(strip, now.value)
                        Key.DirectionLeft -> return@onKeyEvent grid.moveLeft(strip, now.value)
                        Key.DirectionUp -> if (!grid.moveVertically(channels, -1)) return@onKeyEvent false
                        Key.DirectionDown -> grid.moveVertically(channels, 1)
                        Key.ChannelUp, Key.PageUp -> grid.moveVertically(channels, -minOf(visibleRows(), channels.indexOfFirst { it.id == grid.selectedChannel }))
                        Key.ChannelDown, Key.PageDown -> grid.moveVertically(
                            channels,
                            minOf(visibleRows(), channels.lastIndex - channels.indexOfFirst { it.id == grid.selectedChannel }),
                        )
                        else -> return@onKeyEvent false
                    }
                    revealRow()
                    true
                }
                .focusable()
                .draggable(
                    state = dragState,
                    orientation = Orientation.Horizontal,
                    onDragStopped = { velocity ->
                        var last = 0f
                        AnimationState(0f, velocity).animateDecay(decay) {
                            val delta = value - last
                            last = value
                            if (!grid.scrollByPx(-delta, now.value)) cancelAnimation()
                        }
                    },
                ),
        ) {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                items(channels, key = { it.id }, contentType = { "row" }) { channel ->
                    GuideRow(
                        channel = channel,
                        strip = strips.value[channel.id] ?: ProgramStrip.EMPTY,
                        grid = grid,
                        metrics = metrics,
                        onTap = { x ->
                            val before = grid.selectedChannel to (strips.value[channel.id]?.indexAt(grid.selectedTime) ?: -1)
                            grid.tap(channel.id, x)
                            val after = grid.selectedChannel to (strips.value[channel.id]?.indexAt(grid.selectedTime) ?: -1)
                            if (after.second >= 0) onCellTap(before == after)
                        },
                        onLongPress = { x ->
                            grid.tap(channel.id, x)
                            onActions()
                        },
                        onChannelTap = { onChannelTap(channel) },
                    )
                }
            }
        }
    }
}

/** Times along the top: a label every half hour, drawn from the visible hours without recomposing. */
@Composable
private fun TimeHeader(grid: GuideGridState, metrics: GridMetrics, now: State<Long>) {
    val theme = LocalAnstelleTheme.current
    val measurer = rememberTextMeasurer(cacheSize = 16)
    val style = TextStyle(color = theme.textMuted, fontSize = 13.sp, fontFamily = theme.bodyFont)
    val labels = remember { HashMap<Long, String>() }
    val format = remember { DateFormat.getTimeInstance(DateFormat.SHORT) }
    val day by remember(grid) { derivedStateOf { dayLabel(grid.windowStart, now.value) } }
    Row(Modifier.fillMaxWidth().height(metrics.headerHeight), verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.material3.Text(
            day,
            color = theme.text,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = theme.bodyFont,
            modifier = Modifier.width(metrics.channelWidth).padding(start = 8.dp),
        )
        Canvas(Modifier.weight(1f).fillMaxHeight().clipToBounds()) {
            val start = grid.windowStart
            val end = start + (size.width / grid.pxPerMs).toLong()
            var mark = Math.floorDiv(start + GuideGridState.HALF_HOUR_MS - 1, GuideGridState.HALF_HOUR_MS) * GuideGridState.HALF_HOUR_MS
            val tick = 1.dp.toPx()
            while (mark < end) {
                val x = (mark - start) * grid.pxPerMs
                drawRect(theme.textMuted, topLeft = Offset(x, size.height * 0.55f), size = Size(tick, size.height * 0.45f))
                val label = labels.getOrPut(mark) { format.format(Date(mark)) }
                // One line, clipped at the edge, so a label near the right end doesn't wrap.
                drawText(measurer, label, topLeft = Offset(x + 6.dp.toPx(), size.height * 0.15f), style = style, softWrap = false, maxLines = 1)
                mark += GuideGridState.HALF_HOUR_MS
            }
        }
    }
}

@Composable
private fun GuideRow(
    channel: GuideChannel,
    strip: ProgramStrip,
    grid: GuideGridState,
    metrics: GridMetrics,
    onTap: (x: Float) -> Unit,
    onLongPress: (x: Float) -> Unit,
    onChannelTap: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().height(metrics.rowHeight).padding(vertical = 2.dp)) {
        ChannelCell(channel, Modifier.width(metrics.channelWidth).fillMaxHeight().clickable(onClick = onChannelTap))
        ProgramCells(
            channelId = channel.id,
            strip = strip,
            grid = grid,
            showDetail = metrics.showDetailLine,
            touch = metrics.touch,
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .clipToBounds()
                .pointerInput(channel.id) {
                    detectTapGestures(onTap = { onTap(it.x) }, onLongPress = { onLongPress(it.x) })
                },
        )
    }
}

@Composable
private fun ChannelCell(channel: GuideChannel, modifier: Modifier) {
    val theme = LocalAnstelleTheme.current
    Row(
        modifier = modifier
            .padding(end = 4.dp)
            .background(theme.surface, cellShape())
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        androidx.compose.material3.Text(
            "${channel.number}",
            color = theme.primary,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = theme.bodyFont,
            maxLines = 1,
        )
        if (channel.logo != null) {
            AsyncImage(model = channel.logo, contentDescription = null, modifier = Modifier.size(28.dp))
        }
        androidx.compose.material3.Text(
            channel.name,
            color = theme.text,
            fontSize = 13.sp,
            fontFamily = theme.bodyFont,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (channel.isFavorite) {
            androidx.compose.material3.Icon(Icons.Filled.Star, contentDescription = "Favorite", tint = theme.secondary, modifier = Modifier.size(14.dp))
        }
    }
}

/**
 * One row's cells, composed only for the visible hours and placed by a custom layout that
 * reads the scroll position, so moving the hours relays the row out without recomposing it.
 * A cell that began before the visible hours is clipped to start at the left edge, keeping
 * its title readable.
 */
@Composable
private fun ProgramCells(
    channelId: Long,
    strip: ProgramStrip,
    grid: GuideGridState,
    showDetail: Boolean,
    touch: Boolean,
    modifier: Modifier,
) {
    val shape = cellShape()
    val range by remember(strip, grid) {
        derivedStateOf {
            val from = grid.windowStart - MARGIN_MS
            val to = grid.windowStart + grid.spanMs + MARGIN_MS
            pack(strip.firstEndingAfter(from), strip.endOfStartsBefore(to))
        }
    }
    val selected by remember(strip, grid, channelId) {
        derivedStateOf { if (grid.selectedChannel == channelId) strip.indexAt(grid.selectedTime) else -1 }
    }
    val gap = with(LocalDensity.current) { 2.dp.roundToPx() }
    Layout(
        content = {
            val first = low(range)
            val last = high(range)
            for (i in first until last) {
                androidx.compose.runtime.key(strip.starts[i]) {
                    ProgramCell(
                        title = strip.titles[i],
                        detail = if (showDetail) strip.details[i] else null,
                        offAir = strip.isOffAir(i),
                        selected = i == selected,
                        focused = touch || grid.focused,
                        shape = shape,
                    )
                }
            }
        },
        modifier = modifier,
    ) { measurables, constraints ->
        val start = grid.windowStart
        val first = low(range)
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val placeables = measurables.mapIndexed { k, measurable ->
            val i = first + k
            val left = max(((strip.starts[i] - start) * grid.pxPerMs).toInt(), 0)
            val right = min(((strip.ends[i] - start) * grid.pxPerMs).toInt(), width)
            val cellWidth = (right - left - gap).coerceAtLeast(0)
            left to measurable.measure(Constraints.fixed(cellWidth, height))
        }
        layout(width, height) {
            for ((left, placeable) in placeables) placeable.place(left, 0)
        }
    }
}

@Composable
private fun ProgramCell(title: String, detail: String?, offAir: Boolean, selected: Boolean, focused: Boolean, shape: Shape) {
    val theme = LocalAnstelleTheme.current
    val highlighted = selected && focused
    // A shaped background, not a clip: clipping every cell to a rounded path costs the GPU more.
    Column(
        modifier = Modifier
            .background(
                when {
                    highlighted -> theme.guideCellFocused
                    offAir -> theme.background
                    else -> theme.guideCell
                },
                shape,
            )
            .then(if (selected) Modifier.border(2.dp, if (focused) theme.focus else theme.textMuted, shape) else Modifier)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        androidx.compose.material3.Text(
            title,
            color = if (offAir) theme.textMuted else theme.text,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            fontFamily = theme.bodyFont,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (detail != null) {
            androidx.compose.material3.Text(
                detail,
                color = theme.textMuted,
                fontSize = 12.sp,
                fontFamily = theme.bodyFont,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** "Today", "Tomorrow" or the weekday and date of the left edge. */
private fun dayLabel(windowStart: Long, now: Long): String {
    val zone = java.time.ZoneId.systemDefault()
    val day = java.time.Instant.ofEpochMilli(windowStart).atZone(zone).toLocalDate()
    val today = java.time.Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    return when (day) {
        today -> "Today"
        today.plusDays(1) -> "Tomorrow"
        else -> day.format(java.time.format.DateTimeFormatter.ofPattern("EEE d MMM"))
    }
}

private fun pack(low: Int, high: Int): Long = (low.toLong() shl 32) or (high.toLong() and 0xFFFFFFFFL)

private fun low(packed: Long): Int = (packed shr 32).toInt()

private fun high(packed: Long): Int = packed.toInt()

/** The theme's cell corners. */
@Composable
private fun cellShape(): Shape {
    val radius = LocalAnstelleTheme.current.spec.guide.cellRadiusDp
    return remember(radius) { RoundedCornerShape(radius.dp) }
}

private val PastShade = Color(0x33000000)
private const val MARGIN_MS = 30 * 60_000L
private const val PAGE_MS = 180

private val ConfirmKeys = setOf(Key.DirectionCenter, Key.Enter, Key.NumPadEnter, Key.Menu, Key.Info)
