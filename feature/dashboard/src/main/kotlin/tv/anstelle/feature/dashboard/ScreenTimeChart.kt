package tv.anstelle.feature.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import tv.anstelle.core.theme.ControlShape
import tv.anstelle.core.theme.LocalAnstelleTheme
import tv.anstelle.core.theme.LocalIsTv

/**
 * Screen time as bars, Live TV in the accent at the foot of each and On demand muted on top, drawn
 * straight from two arrays in one draw pass with no allocation. A hairline marks every tick of
 * the scale ([chartTicks]), its figure beside it. Picking a bar dims the rest and marks its slot; on TV, left and right move the pick
 * (onto the next period at either end) and OK clears it, and the focused chart steps up from the
 * meter panel's coal. Touch picks with a tap, and a tap on the picked bar clears it.
 */
@Composable
internal fun ScreenTimeChart(
    live: LongArray,
    onDemand: LongArray,
    picked: Int?,
    axis: (Int) -> String?,
    axisPick: (Int) -> String,
    description: String,
    /** Moves the pick; false when it can't, so the D-pad leaves the chart. */
    onMove: (Int) -> Boolean,
    onPick: (Int?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val theme = LocalAnstelleTheme.current
    val isTv = LocalIsTv.current
    val bars = live.size
    val ticks = remember(live, onDemand) { chartTicks((0 until bars).maxOfOrNull { live[it] + onDemand[it] } ?: 0L) }
    val scale = ticks.topMs
    val chartHeight = if (isTv) 168.dp else 160.dp
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val currentPick by rememberUpdatedState(picked)
    val typography = androidx.compose.material3.MaterialTheme.typography
    val gutter = if (isTv) 44.dp else 40.dp

    Column(modifier) {
        Row(Modifier.fillMaxWidth().height(chartHeight)) {
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .background(if (focused) theme.surfaceHigh else theme.surface, ControlShape)
                    .semantics { contentDescription = description }
                    .onKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                        when (event.key) {
                            Key.DirectionLeft -> onMove(-1)
                            Key.DirectionRight -> onMove(1)
                            Key.DirectionCenter, Key.Enter -> (currentPick != null).also { if (it) onPick(null) }
                            else -> false
                        }
                    }
                    .focusable(interactionSource = interaction)
                    .pointerInput(bars) { detectTapGestures { offset -> onPick((offset.x / (size.width / bars.toFloat())).toInt().coerceIn(0, bars - 1)) } }
                    .drawBehind {
                        val hairline = 1.dp.toPx()
                        val top = CHART_TOP.toPx()
                        val height = size.height - top
                        val slot = size.width / bars
                        val barWidth = (slot * 0.6f).coerceIn(2.dp.toPx(), 28.dp.toPx())
                        val split = 2.dp.toPx()
                        for (k in 1..ticks.count) drawRect(theme.line, Offset(0f, top + height * (ticks.count - k) / ticks.count), Size(size.width, hairline))
                        val pick = currentPick
                        if (pick != null) drawRect(if (focused) theme.line else theme.surfaceHigh, Offset(pick * slot, 0f), Size(slot, size.height))
                        for (i in 0 until bars) {
                            val alpha = if (pick == null || pick == i) 1f else DIMMED
                            val x = i * slot + (slot - barWidth) / 2
                            val liveHeight = height * live[i] / scale
                            val demandHeight = height * onDemand[i] / scale
                            if (liveHeight > 0f) drawRect(theme.accent.copy(alpha = alpha), Offset(x, size.height - liveHeight), Size(barWidth, liveHeight))
                            if (demandHeight > 0f) {
                                // A sliver of ground parts the two, so they read apart without color.
                                val gap = if (liveHeight > 0f) split else 0f
                                drawRect(theme.textMuted.copy(alpha = alpha), Offset(x, size.height - liveHeight - gap - demandHeight), Size(barWidth, (demandHeight - gap).coerceAtLeast(hairline)))
                            }
                        }
                        drawRect(theme.line, Offset(0f, size.height - hairline), Size(size.width, hairline))
                    },
            )
            // Each tick's figure, centred on its hairline.
            Box(Modifier.width(gutter).fillMaxHeight()) {
                for (k in 1..ticks.count) {
                    val y = CHART_TOP + (chartHeight - CHART_TOP) * (ticks.count - k) / ticks.count
                    androidx.compose.material3.Text(
                        tickName(ticks.stepMs * k),
                        style = typography.labelSmall,
                        color = theme.textMuted,
                        maxLines = 1,
                        modifier = Modifier.align(Alignment.TopEnd).offset(y = y - TICK_HALF_LINE),
                    )
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(end = gutter, top = 6.dp)) {
            for (i in 0 until bars) {
                val isPick = i == picked
                val near = picked != null && !isPick && kotlin.math.abs(i - picked) <= hideNear(bars)
                val text = if (isPick) axisPick(i) else axis(i)?.takeUnless { near }
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    if (text != null) {
                        androidx.compose.material3.Text(
                            text.uppercase(),
                            style = typography.labelSmall,
                            color = when {
                                isPick && focused -> theme.onAccent
                                isPick -> theme.text
                                else -> theme.textMuted
                            },
                            maxLines = 1,
                            softWrap = false,
                            modifier = Modifier
                                .wrapContentWidth(unbounded = true)
                                .then(if (isPick && focused) Modifier.background(theme.accent, ControlShape) else Modifier)
                                .padding(horizontal = 4.dp, vertical = 1.dp),
                        )
                    }
                }
            }
        }
    }
}

/** How many neighbours of the picked bar give up their mark to it, so marks never collide. */
private fun hideNear(bars: Int) = when {
    bars > 12 -> 2
    else -> 0
}

private const val DIMMED = 0.45f

/** Room above the top tick for its figure. */
private val CHART_TOP = 8.dp

/** Half a label-small line, to centre a figure on its hairline. */
private val TICK_HALF_LINE = 7.dp
