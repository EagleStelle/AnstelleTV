package tv.anstelle.feature.player

import android.content.pm.ActivityInfo
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Dns
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import tv.anstelle.core.data.playback.StreamFacts
import tv.anstelle.core.data.playback.TrackOption
import tv.anstelle.core.theme.LocalAnstelleTheme
import tv.anstelle.core.theme.LocalIsTv
import tv.anstelle.core.theme.components.FocusSurface
import tv.anstelle.core.theme.components.OsdBadge
import tv.anstelle.core.theme.components.SegmentLadder

/**
 * On phones and tablets, a player turns the screen sideways and hides the system bars while
 * it is open (a swipe from the edge shows them for a moment). Leaving puts both back. A TV is
 * already landscape and has no bars.
 */
@Composable
internal fun LandscapeFullScreen() {
    if (LocalIsTv.current) return
    val activity = LocalActivity.current ?: return
    DisposableEffect(activity) {
        val previous = activity.requestedOrientation
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        val bars = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
        bars.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        bars.hide(WindowInsetsCompat.Type.systemBars())
        onDispose {
            bars.show(WindowInsetsCompat.Type.systemBars())
            activity.requestedOrientation = previous
        }
    }
}

/**
 * The stream's technical line, as a set-top box prints it under its banner, read from the top
 * of the chain down: the server it comes from, then how it plays (DIRECT PLAY or TRANSCODING),
 * then the picture and sound as outlined badges.
 */
@Composable
internal fun StreamLine(facts: StreamFacts, modifier: Modifier = Modifier) {
    val theme = LocalAnstelleTheme.current
    val typography = androidx.compose.material3.MaterialTheme.typography
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        // The source leads, set apart by a hairline from what it does with the file.
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            androidx.compose.material3.Icon(Icons.Filled.Dns, contentDescription = "Server", tint = theme.bannerText, modifier = Modifier.size(14.dp))
            androidx.compose.material3.Text(
                facts.server,
                style = typography.labelMedium,
                color = theme.bannerText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 240.dp),
            )
            Box(Modifier.padding(start = 2.dp).width(1.dp).height(12.dp).background(theme.line))
        }
        androidx.compose.material3.Text(facts.method.uppercase(), style = typography.labelMedium, color = theme.bannerText, maxLines = 1)
        listOfNotNull(facts.quality, facts.video, facts.audio).forEach { OsdBadge(it.uppercase()) }
    }
}

/**
 * The banner's first row: the field [label] (NOW and the time, ON DEMAND) on the left and the
 * stream's technical line on the right, where a set-top banner prints its format icons, so it
 * costs the banner no row of its own. The label holds still; a technical line too long for
 * the room left scrolls through it as a marquee for as long as the banner is up.
 */
@Composable
internal fun BannerHeader(stream: StreamFacts?, label: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        label()
        if (stream != null) {
            Box(Modifier.padding(start = 16.dp).weight(1f), contentAlignment = Alignment.CenterEnd) {
                StreamLine(stream, Modifier.basicMarquee(iterations = Int.MAX_VALUE))
            }
        }
    }
}

/**
 * The banner's keys, under a hairline, inside the column they act on: the number block and the
 * NEXT column beside it stay as they are. The row runs [KEY_PADDING] past the column on both
 * sides, so the first and last keys' labels line up with the text above while their fills
 * use the margin. Callers anchor groups with a weighted spacer: tracks on the left, the
 * player's own actions on the right. Touch keys are tall enough to need no gap under the
 * hairline: their own padding is the air.
 */
@Composable
internal fun OsdKeys(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    val theme = LocalAnstelleTheme.current
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(if (LocalIsTv.current) 6.dp else 0.dp)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(theme.line))
        Row(
            Modifier.bleed(KEY_PADDING),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
    }
}

/** A key's side padding, which [OsdKeys] lets run into the column's margin. */
private val KEY_PADDING = 12.dp

/** Lays the element out [horizontal] wider on each side than its parent allows, centered on it. */
private fun Modifier.bleed(horizontal: Dp) = layout { measurable, constraints ->
    if (!constraints.hasBoundedWidth) {
        val placeable = measurable.measure(constraints)
        return@layout layout(placeable.width, placeable.height) { placeable.place(0, 0) }
    }
    val extra = (horizontal * 2).roundToPx()
    val placeable = measurable.measure(constraints.copy(minWidth = constraints.maxWidth + extra, maxWidth = constraints.maxWidth + extra))
    layout(constraints.maxWidth, placeable.height) { placeable.place(-extra / 2, 0) }
}

/**
 * One key of the banner: an icon and a condensed label. A key with a [value] (the track in use)
 * shows the icon and the value alone, "♪ JAPANESE", so a row of them fits the program's column;
 * the label stays for screen readers. Flat on the banner; focus fills it with the accent.
 */
@Composable
internal fun OsdKey(
    label: String,
    icon: ImageVector?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    value: String? = null,
) {
    val theme = LocalAnstelleTheme.current
    val isTv = LocalIsTv.current
    val typography = androidx.compose.material3.MaterialTheme.typography
    FocusSurface(
        onClick = onClick,
        modifier = modifier.semantics { contentDescription = listOfNotNull(label, value).joinToString(", ") },
        color = Color.Transparent,
        contentColor = theme.bannerText,
    ) { focused ->
        Row(
            Modifier.height(if (isTv) 40.dp else 48.dp).padding(horizontal = KEY_PADDING),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val ink = if (focused) theme.onAccent else theme.bannerText
            icon?.let { androidx.compose.material3.Icon(it, contentDescription = null, tint = ink, modifier = Modifier.size(18.dp)) }
            androidx.compose.material3.Text(
                (value ?: label).uppercase(),
                style = typography.labelMedium,
                color = ink,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = if (value != null) 140.dp else 240.dp),
            )
        }
    }
}

/**
 * The banner's key row while a track list is open: the field's name, then each choice as a key.
 * The one in use carries a check and takes focus first.
 */
@Composable
internal fun RowScope.TrackChoices(title: String, options: List<TrackOption>, onPick: (TrackOption) -> Unit) {
    val theme = LocalAnstelleTheme.current
    val typography = androidx.compose.material3.MaterialTheme.typography
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { first.requestFocus() }
    }
    androidx.compose.material3.Text(title.uppercase(), style = typography.labelMedium, color = theme.accent, modifier = Modifier.padding(horizontal = 10.dp))
    if (options.isEmpty()) {
        androidx.compose.material3.Text("NONE IN THIS ONE", style = typography.labelMedium, color = theme.textMuted)
        return
    }
    val focused = options.indexOfFirst { it.selected }.coerceAtLeast(0)
    Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        options.forEachIndexed { i, option ->
            OsdKey(
                label = option.label,
                icon = if (option.selected) Icons.Filled.Check else null,
                onClick = { onPick(option) },
                modifier = if (i == focused) Modifier.focusRequester(first) else Modifier,
            )
        }
    }
}

/**
 * Progress as the time in, a segment ladder and the full length, the same on every screen.
 * With [onSeek] (touch screens) the ladder is a seek bar: tap a point or drag along it, the
 * time in follows the finger, and the seek lands on release. The remote seeks with left and
 * right instead.
 */
@Composable
internal fun ProgressRow(positionMs: Long, lengthMs: Long, modifier: Modifier = Modifier, onSeek: ((positionMs: Long) -> Unit)? = null) {
    val theme = LocalAnstelleTheme.current
    val typography = androidx.compose.material3.MaterialTheme.typography
    val length = lengthMs.coerceAtLeast(1)
    var dragged by remember { mutableStateOf<Float?>(null) }
    val seek by rememberUpdatedState(onSeek)
    val fraction = dragged ?: (positionMs.toFloat() / length).coerceIn(0f, 1f)
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        androidx.compose.material3.Text(
            clock(dragged?.let { (it * length).toLong() } ?: positionMs),
            style = typography.labelMedium,
            color = theme.bannerText,
            maxLines = 1,
        )
        val touch = if (onSeek == null) {
            Modifier
        } else {
            fun Float.at(width: Int) = (this / width).coerceIn(0f, 1f)
            Modifier
                .height(SEEK_TOUCH_HEIGHT)
                .pointerInput(length) {
                    detectTapGestures { seek?.invoke((it.x.at(size.width) * length).toLong()) }
                }
                .pointerInput(length) {
                    detectHorizontalDragGestures(
                        onDragStart = { dragged = it.x.at(size.width) },
                        onHorizontalDrag = { change, _ ->
                            change.consume()
                            dragged = change.position.x.at(size.width)
                        },
                        onDragEnd = {
                            dragged?.let { seek?.invoke((it * length).toLong()) }
                            dragged = null
                        },
                        onDragCancel = { dragged = null },
                    )
                }
        }
        Box(Modifier.weight(1f).then(touch), contentAlignment = Alignment.CenterStart) {
            SegmentLadder(fraction, segments = LADDER_SEGMENTS)
        }
        androidx.compose.material3.Text(clock(lengthMs), style = typography.labelMedium, color = theme.textMuted, maxLines = 1)
    }
}

/** Segments in every progress ladder. */
private const val LADDER_SEGMENTS = 40

/**
 * How tall the ladder's touch strip is on touch screens; the ladder itself stays thin. Compose
 * widens a target this short to the 48 dp minimum when a touch lands just outside it.
 */
private val SEEK_TOUCH_HEIGHT = 24.dp

/** "12:34" or "1:02:03". */
internal fun clock(ms: Long): String {
    val seconds = ms.coerceAtLeast(0) / 1000
    val h = seconds / 3600
    val m = seconds % 3600 / 60
    val s = seconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
