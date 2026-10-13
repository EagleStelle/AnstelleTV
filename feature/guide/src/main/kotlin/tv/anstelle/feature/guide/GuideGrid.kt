package tv.anstelle.feature.guide

import tv.anstelle.core.theme.components.obscured
import tv.anstelle.core.data.ondemand.WatchChoices
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.TvOff
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
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent

import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
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
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import tv.anstelle.core.data.guide.ProgramStrip
import tv.anstelle.core.data.profiles.Restriction
import tv.anstelle.core.model.ProfileAccess
import tv.anstelle.core.theme.LocalAnstelleTheme
import tv.anstelle.core.theme.components.ChannelLogo
import tv.anstelle.core.theme.components.PastShade
import java.text.DateFormat
import java.util.Date
import kotlin.math.max
import kotlin.math.min

/** Sizes of the grid on this device. */
internal class GridMetrics(
    val rowHeight: Dp,
    val channelWidth: Dp,
    /** How tall a channel logo's box is in the channel column. */
    val channelLogoHeight: Dp,
    /** Lines a channel's name may take under its number beside a logo. */
    val channelNameLines: Int,
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
    /** What the profile may watch: programs beyond it are blacked out. */
    access: ProfileAccess,
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
                // The past shade and the now line lie over the cells; the line continues the
                // playhead drawn in the time header.
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
                        Key.DirectionUp -> if (!grid.moveVertically(channels, -1, System.currentTimeMillis())) return@onKeyEvent false
                        Key.DirectionDown -> grid.moveVertically(channels, 1, System.currentTimeMillis())
                        Key.ChannelUp, Key.PageUp -> grid.moveVertically(
                            channels,
                            -minOf(visibleRows(), channels.indexOfFirst { it.id == grid.selectedChannel }),
                            System.currentTimeMillis(),
                        )
                        Key.ChannelDown, Key.PageDown -> grid.moveVertically(
                            channels,
                            minOf(visibleRows(), channels.lastIndex - channels.indexOfFirst { it.id == grid.selectedChannel }),
                            System.currentTimeMillis(),
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
                        access = access,
                        grid = grid,
                        now = now,
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

/** One size for every time in the header: the half-hour labels and the corner's day and clock. */
private val TIME_TEXT_SIZE = 12.sp

/** Times along the top: a label every half hour, drawn from the visible hours without recomposing. */
@Composable
private fun TimeHeader(grid: GuideGridState, metrics: GridMetrics, now: State<Long>) {
    val theme = LocalAnstelleTheme.current
    val measurer = rememberTextMeasurer(cacheSize = 16)
    val labelSize = TIME_TEXT_SIZE
    val style = TextStyle(color = theme.textMuted, fontSize = labelSize, lineHeight = labelSize * 1.15f, fontWeight = FontWeight.SemiBold, fontFamily = theme.displayFont, fontFeatureSettings = "tnum")
    val labels = remember { HashMap<Long, String>() }
    val format = remember { DateFormat.getTimeInstance(DateFormat.SHORT) }
    Row(Modifier.fillMaxWidth().height(metrics.headerHeight), verticalAlignment = Alignment.CenterVertically) {
        ClockLabel(grid, now, metrics, Modifier.width(metrics.channelWidth).padding(start = 8.dp))
        Canvas(Modifier.weight(1f).fillMaxHeight().clipToBounds()) {
            val start = grid.windowStart
            val end = start + (size.width / grid.pxPerMs).toLong()
            var mark = Math.floorDiv(start + GuideGridState.HALF_HOUR_MS - 1, GuideGridState.HALF_HOUR_MS) * GuideGridState.HALF_HOUR_MS
            val tick = 1.dp.toPx()
            while (mark < end) {
                val x = (mark - start) * grid.pxPerMs
                drawRect(theme.line, topLeft = Offset(x, size.height * 0.55f), size = Size(tick, size.height * 0.45f))
                val label = labels.getOrPut(mark) { format.format(Date(mark)) }
                // One line, clipped at the edge, so a label near the right end doesn't wrap.
                val text = measurer.measure(label, style = style, softWrap = false, maxLines = 1)
                // Centered on the header's midline, where the corner's clock sits.
                drawText(text, topLeft = Offset(x + 6.dp.toPx(), (size.height - text.size.height) / 2f))
                mark += GuideGridState.HALF_HOUR_MS
            }
            // Now: the line's head, a short bar ending in a square, above the line drawn over the rows.
            val nowX = (now.value - start) * grid.pxPerMs
            if (nowX >= 0f && nowX <= size.width) {
                val line = 2.dp.toPx()
                val head = 8.dp.toPx()
                drawRect(theme.nowLine, topLeft = Offset(nowX - line / 2, size.height * 0.5f), size = Size(line, size.height * 0.5f))
                drawRect(theme.nowLine, topLeft = Offset(nowX - head / 2, size.height - head), size = Size(head, head))
            }
        }
    }
}

/**
 * The header's corner: the day the hours show, then the current time. It reads its own state,
 * so only this label recomposes: the time once a minute (one wake-up at each minute's edge, a
 * single format call), the day when the hours cross midnight. Nothing here touches the grid.
 */
@Composable
private fun ClockLabel(grid: GuideGridState, now: State<Long>, metrics: GridMetrics, modifier: Modifier) {
    val theme = LocalAnstelleTheme.current
    val format = remember { DateFormat.getTimeInstance(DateFormat.SHORT) }
    var minute by remember { mutableLongStateOf(System.currentTimeMillis() / MINUTE_MS) }
    LaunchedEffect(Unit) {
        while (true) {
            val ms = System.currentTimeMillis()
            minute = ms / MINUTE_MS
            delay(MINUTE_MS - ms % MINUTE_MS + 50)
        }
    }
    val time by remember { derivedStateOf { format.format(Date(minute * MINUTE_MS)) } }
    val day by remember(grid) { derivedStateOf { dayLabel(grid.windowStart, now.value).uppercase() } }
    val size = TIME_TEXT_SIZE
    // Today is the clock itself; only another day gets a label.
    val dayText: @Composable () -> Unit = {
        if (day.isNotEmpty()) androidx.compose.material3.Text(day, color = theme.text, fontSize = size, lineHeight = size * 1.15f, fontWeight = FontWeight.SemiBold, fontFamily = theme.displayFont, letterSpacing = 0.06.em, maxLines = 1)
    }
    val timeText: @Composable () -> Unit = {
        androidx.compose.material3.Text(
            time.uppercase(),
            color = theme.accent,
            fontSize = size,
            lineHeight = size * 1.15f,
            fontWeight = FontWeight.SemiBold,
            fontFamily = theme.displayFont,
            style = TextStyle(fontFeatureSettings = "tnum"),
            maxLines = 1,
        )
    }
    if (metrics.touch) {
        // The narrow phone column stacks them.
        Column(modifier, verticalArrangement = Arrangement.Center) {
            dayText()
            timeText()
        }
    } else {
        Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            dayText()
            timeText()
        }
    }
}

private const val MINUTE_MS = 60_000L

@Composable
private fun GuideRow(
    channel: GuideChannel,
    strip: ProgramStrip,
    access: ProfileAccess,
    grid: GuideGridState,
    now: State<Long>,
    metrics: GridMetrics,
    onTap: (x: Float) -> Unit,
    onLongPress: (x: Float) -> Unit,
    onChannelTap: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().height(metrics.rowHeight).padding(vertical = 2.dp)) {
        ChannelCell(channel, metrics, Modifier.width(metrics.channelWidth).fillMaxHeight().clickable(onClick = onChannelTap))
        ProgramCells(
            channelId = channel.id,
            strip = strip,
            access = access,
            grid = grid,
            now = now,
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

/**
 * The channel column. A channel with a logo shows it at the start in its standard box,
 * centered, with the number over the name beside it, as set-top guides set a channel's mark.
 * Without one, on the narrow phone column the number sits above the name, so names get the
 * cell's full width instead of breaking mid-word beside the number; TV sets them in a row.
 */
@Composable
private fun ChannelCell(channel: GuideChannel, metrics: GridMetrics, modifier: Modifier) {
    val theme = LocalAnstelleTheme.current
    val stacked = metrics.touch
    if (channel.logo != null) {
        Row(
            modifier = modifier
                .padding(end = 4.dp)
                .background(theme.surface, cellShape())
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(if (stacked) 6.dp else 8.dp),
        ) {
            ChannelLogo(channel.logo, height = metrics.channelLogoHeight)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    androidx.compose.material3.Text(
                        "${channel.number}",
                        color = theme.accent,
                        fontSize = if (stacked) 15.sp else 14.sp,
                        lineHeight = if (stacked) 17.sp else 16.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = theme.displayFont,
                        style = androidx.compose.ui.text.TextStyle(fontFeatureSettings = "tnum"),
                        maxLines = 1,
                    )
                    if (channel.isFavorite) {
                        androidx.compose.material3.Icon(Icons.Filled.Star, contentDescription = "Favorite", tint = theme.accent, modifier = Modifier.size(12.dp))
                    }
                }
                androidx.compose.material3.Text(
                    channel.name,
                    color = theme.text,
                    fontSize = 12.sp,
                    lineHeight = 14.sp,
                    fontFamily = theme.bodyFont,
                    maxLines = metrics.channelNameLines,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        return
    }
    if (stacked) {
        Column(
            modifier = modifier
                .padding(end = 4.dp)
                .background(theme.surface, cellShape())
                .padding(horizontal = 8.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                androidx.compose.material3.Text(
                    "${channel.number}",
                    color = theme.accent,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = theme.displayFont,
                    style = androidx.compose.ui.text.TextStyle(fontFeatureSettings = "tnum"),
                    maxLines = 1,
                )
                if (channel.isFavorite) {
                    androidx.compose.material3.Icon(Icons.Filled.Star, contentDescription = "Favorite", tint = theme.accent, modifier = Modifier.size(12.dp))
                }
            }
            androidx.compose.material3.Text(
                channel.name,
                color = theme.text,
                fontSize = 12.sp,
                lineHeight = 14.sp,
                fontFamily = theme.bodyFont,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        return
    }
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
            color = theme.accent,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = theme.displayFont,
            style = androidx.compose.ui.text.TextStyle(fontFeatureSettings = "tnum"),
            maxLines = 1,
        )
        androidx.compose.material3.Text(
            channel.name,
            color = theme.text,
            fontSize = 12.sp,
            lineHeight = 15.sp,
            fontFamily = theme.bodyFont,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (channel.isFavorite) {
            androidx.compose.material3.Icon(Icons.Filled.Star, contentDescription = "Favorite", tint = theme.accent, modifier = Modifier.size(12.dp))
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
    access: ProfileAccess,
    grid: GuideGridState,
    now: State<Long>,
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
    // These change only when a program ends or starts, so the clock's ticks don't recompose the row.
    val upcoming by remember(strip, now) { derivedStateOf { strip.firstEndingAfter(now.value) } }
    val onAir by remember(strip, now) { derivedStateOf { strip.indexAt(now.value) } }
    val gap = with(LocalDensity.current) { 2.dp.roundToPx() }
    val theme = LocalAnstelleTheme.current
    val runs = remember(strip, theme.guideRuns.size) { strip.runSlots(theme.guideRuns.size) }
    Layout(
        content = {
            val first = low(range)
            val last = high(range)
            val showOnAir = if (onAir >= 0 && !strip.isOffAir(onAir)) strip.shows[onAir] else -1L
            for (i in first until last) {
                androidx.compose.runtime.key(strip.starts[i]) {
                    val restricted = strip.isRestricted(i, access)
                    val offAir = strip.isOffAir(i)
                    val until = "until ${WatchChoices.clockTime(strip.ends[i])}"
                    ProgramCell(
                        title = strip.titles[i],
                        // A dark stretch says when it ends; a restricted one's episode is blacked out, so it says why too.
                        detail = when {
                            !showDetail -> null
                            restricted -> "${Restriction.titleFor(strip.items[i], access)}  $until".uppercase()
                            offAir -> until.uppercase()
                            else -> strip.details[i]
                        },
                        offAir = offAir,
                        restricted = restricted,
                        // Ended programs recede; the one on air and its show's later airings keep
                        // the plain cell; every other show takes its run's color.
                        tone = when {
                            i < upcoming -> TONE_PAST
                            i == onAir || strip.shows[i] == showOnAir -> TONE_PLAIN
                            else -> runs[i]
                        },
                        selected = i == selected,
                        focused = touch || grid.focused,
                        shape = shape,
                        tv = !touch,
                    )
                }
            }
        },
        // The program on air carries a solid square in its top corner, drawn from the clock
        // without recomposing: state that reads without the accent hue.
        modifier = modifier.drawWithContent {
            drawContent()
            val nowMs = now.value
            val i = strip.indexAt(nowMs)
            if (i < 0 || strip.isOffAir(i)) return@drawWithContent
            val right = min((strip.ends[i] - grid.windowStart) * grid.pxPerMs, size.width) - gap
            val marker = 6.dp.toPx()
            val inset = 5.dp.toPx()
            if (right - marker - inset < 0f) return@drawWithContent
            val onFill = grid.focused || touch
            val filled = onFill && grid.selectedChannel == channelId && strip.indexAt(grid.selectedTime) == i
            drawRect(if (filled) theme.onAccent else theme.accent, topLeft = Offset(right - marker - inset, inset), size = Size(marker, marker))
        },
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
private fun ProgramCell(
    title: String,
    detail: String?,
    offAir: Boolean,
    restricted: Boolean,
    tone: Int,
    selected: Boolean,
    focused: Boolean,
    shape: Shape,
    tv: Boolean,
) {
    val theme = LocalAnstelleTheme.current
    // Focus is a fill: the selected cell turns the accent with ink text. Without focus (the
    // drawer or tabs have it) the selection keeps a hairline so it isn't lost.
    val highlighted = selected && focused
    val past = tone == TONE_PAST
    // Off air and restricted both drop to the ground: nothing to see there.
    val dark = offAir || restricted
    val run = !dark && tone >= 0
    // A shaped background, not a clip: clipping every cell to a rounded path costs the GPU more.
    // A cell too narrow for a word shows its bare fill rather than a stack of ellipses.
    NarrowGate(
        Modifier
            .background(
                when {
                    highlighted -> theme.accent
                    dark -> theme.background
                    past -> theme.guideCellPast
                    run -> theme.guideRuns[tone]
                    else -> theme.guideCell
                },
                shape,
            )
            .then(
                when {
                    selected && !focused -> Modifier.border(1.dp, theme.textMuted, shape)
                    dark -> Modifier.border(1.dp, theme.line, shape)
                    else -> Modifier
                },
            ),
    ) {
    Column(
        modifier = Modifier.padding(horizontal = 8.dp, vertical = if (tv) 3.dp else 4.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        val titleColor = when {
            highlighted -> theme.onAccent
            dark || past -> theme.textMuted
            else -> theme.text
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            // The icon carries the state (the stand-in marks' lock and TV-off), so it never rests on color alone.
            when {
                restricted -> androidx.compose.material3.Icon(Icons.Filled.Lock, contentDescription = "Restricted", tint = titleColor, modifier = Modifier.size(if (tv) 12.dp else 13.dp))
                offAir -> androidx.compose.material3.Icon(Icons.Filled.TvOff, contentDescription = null, tint = titleColor, modifier = Modifier.size(if (tv) 12.dp else 13.dp))
            }
            androidx.compose.material3.Text(
                // Off air, the cell's title is the state itself, set like the stand-in mark's.
                if (offAir) title.uppercase() else title,
                color = titleColor,
                fontSize = if (tv) 14.sp else 15.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = if (offAir) theme.displayFont else theme.bodyFont,
                letterSpacing = if (offAir) FIELD_TRACKING else TextUnit.Unspecified,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.obscured(restricted, titleColor, radius = 5.dp),
            )
        }
        if (detail != null) {
            androidx.compose.material3.Text(
                detail,
                // Full ink: the past shade dims the focused cell too, and the detail must stay legible over it.
                // On a run's color the detail is the title's bone thinned, so it takes on the cell's hue.
                color = when {
                    highlighted -> theme.onAccent
                    run -> theme.text.copy(alpha = RUN_DETAIL_ALPHA)
                    else -> theme.textMuted
                },
                fontSize = if (tv) 11.sp else 12.sp,
                // A dark stretch (off air, restricted) speaks in the field voice, uppercase, like its mark everywhere else.
                fontWeight = if (dark) FontWeight.SemiBold else null,
                fontFamily = if (dark) theme.displayFont else theme.bodyFont,
                letterSpacing = if (dark) FIELD_TRACKING else TextUnit.Unspecified,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
    }
}

/** Fills its box; places [content] only when the box is wide enough for a few letters. */
@Composable
private fun NarrowGate(modifier: Modifier, content: @Composable () -> Unit) {
    val minWidth = with(LocalDensity.current) { MIN_TEXT_CELL.roundToPx() }
    Layout(content = content, modifier = modifier) { measurables, constraints ->
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        if (width < minWidth) {
            layout(width, height) {}
        } else {
            val placeable = measurables.first().measure(Constraints.fixed(width, height))
            layout(width, height) { placeable.place(0, 0) }
        }
    }
}

private val MIN_TEXT_CELL = 44.dp

/** A cell's tone: ended, plain (on air and its show's later airings), or a run color's index from 0. */
private const val TONE_PAST = -2
private const val TONE_PLAIN = -1
private const val RUN_DETAIL_ALPHA = 0.76f

/** The field voice's tracking, for a dark stretch's uppercase text. */
private val FIELD_TRACKING = 0.06.em

/** "Today", "Tomorrow" or the weekday and date of the left edge. */
private fun dayLabel(windowStart: Long, now: Long): String {
    val zone = java.time.ZoneId.systemDefault()
    val day = java.time.Instant.ofEpochMilli(windowStart).atZone(zone).toLocalDate()
    val today = java.time.Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    return when (day) {
        today -> ""
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

private const val MARGIN_MS = 30 * 60_000L
private const val PAGE_MS = 180

private val ConfirmKeys = setOf(Key.DirectionCenter, Key.Enter, Key.NumPadEnter, Key.Menu, Key.Info)
