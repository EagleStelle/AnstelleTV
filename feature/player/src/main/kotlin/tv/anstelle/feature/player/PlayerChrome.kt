package tv.anstelle.feature.player

import android.content.pm.ActivityInfo
import androidx.activity.compose.LocalActivity
import androidx.annotation.OptIn
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import kotlin.math.hypot
import tv.anstelle.core.data.playback.StreamFacts
import tv.anstelle.core.data.playback.TrackOption
import tv.anstelle.core.theme.LocalAnstelleTheme
import tv.anstelle.core.theme.LocalIsTv
import tv.anstelle.core.theme.components.FocusSurface
import tv.anstelle.core.theme.components.OsdBadge
import tv.anstelle.core.theme.components.SegmentLadder

/**
 * Focuses a banner key that may not be on screen yet: a hidden banner composes its keys only
 * once it slides in, so this tries again for a few frames until one takes focus.
 */
internal suspend fun FocusRequester.requestWhenShown() {
    repeat(20) {
        withFrameNanos { }
        if (runCatching { requestFocus() }.getOrDefault(false)) return
    }
}

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

/** The OSD's side margin: TV's title-safe 48 dp, a hand's width less on phones. */
internal val osdSide: Dp
    @Composable get() = if (LocalIsTv.current) 48.dp else 24.dp

/** The OSD's top and bottom margin on TV (title-safe); touch keys close the banner on phones instead. */
internal val osdEdge: Dp
    @Composable get() = if (LocalIsTv.current) 24.dp else 12.dp

/** How far above the banner its edge shade fades out. */
internal val shadeFade: Dp
    @Composable get() = if (LocalIsTv.current) 96.dp else 48.dp

/**
 * The banner's edge shade: [color] fading up from the screen's bottom edge, deepest at the
 * banner's foot, dark enough at its first line for text to read over a white sky, and gone
 * [fade] above it. A fade, not a panel: nothing edges it, and the picture runs on under the
 * banner the way a broadcast's own lower graphics sit on it.
 */
internal fun Modifier.edgeShade(color: Color, fade: Dp) = drawBehind {
    val above = fade.toPx()
    val total = above + size.height
    val top = above / total
    drawRect(
        Brush.verticalGradient(
            0f to color.copy(alpha = 0f),
            top * 0.45f to color.copy(alpha = 0.18f),
            top * 0.8f to color.copy(alpha = 0.5f),
            top to color.copy(alpha = 0.7f),
            top + (1f - top) * 0.35f to color.copy(alpha = 0.8f),
            1f to color.copy(alpha = SHADE_DEEPEST),
            startY = -above,
            endY = size.height,
        ),
        topLeft = Offset(0f, -above),
        size = Size(size.width, total),
    )
}

/**
 * The corner shade behind the channel ident: [color] deepest in the element's top-end corner
 * (the screen's, where the ident sits) and gone at [CORNER_REACH] times the element's
 * diagonal, a quarter ellipse a little wider than tall, whatever the ident's own shape, so a
 * short row of typed digits gets the same corner vignette as a tall logo. A broadcaster's
 * corner bug sits on a vignette like this, never on a plate.
 */
internal fun Modifier.cornerShade(color: Color) = drawBehind {
    val rx = hypot(size.width, size.height) * CORNER_REACH
    val ry = rx * 0.8f
    val rtl = layoutDirection == LayoutDirection.Rtl
    val corner = if (rtl) Offset.Zero else Offset(size.width, 0f)
    val brush = Brush.radialGradient(
        0f to color.copy(alpha = SHADE_DEEPEST),
        0.5f to color.copy(alpha = 0.72f),
        0.78f to color.copy(alpha = 0.32f),
        1f to color.copy(alpha = 0f),
        center = corner,
        radius = ry,
    )
    // A circle of radius ry around the corner, stretched sideways to rx.
    scale(scaleX = rx / ry, scaleY = 1f, pivot = corner) {
        drawRect(brush, topLeft = Offset(if (rtl) 0f else corner.x - ry, 0f), size = Size(ry, ry))
    }
}

/**
 * A soft shade behind a line of text over the picture (Tuning…, a notice): [color] deepest at
 * its center and gone well past its ends, an ellipse around the text instead of a box.
 */
internal fun Modifier.softShade(color: Color) = drawBehind {
    val rx = size.width / 2 + 80.dp.toPx()
    val ry = size.height / 2 + 40.dp.toPx()
    val brush = Brush.radialGradient(
        0f to color.copy(alpha = 0.8f),
        0.65f to color.copy(alpha = 0.65f),
        1f to color.copy(alpha = 0f),
        center = center,
        radius = ry,
    )
    scale(scaleX = rx / ry, scaleY = 1f, pivot = center) {
        drawCircle(brush, radius = ry, center = center)
    }
}

/**
 * Lifts the picture's subtitles (text cues and the libass layer inside the subtitle view)
 * [liftPx] up while the banner is up, and lets them back down after: with no panel under the
 * banner they would otherwise run through its text, so they step above it the way a
 * streaming player's captions clear its controls. An ASS layer moves whole, top signs too,
 * for the seconds the banner shows.
 */
@OptIn(UnstableApi::class)
internal fun PlayerView.liftSubtitles(liftPx: Int) {
    val view = subtitleView ?: return
    val target = -liftPx.toFloat()
    if (view.translationY != target) view.animate().translationY(target).setDuration(LIFT_MS).start()
}

/** The subtitles move with the banner's own snap. */
private const val LIFT_MS = 140L

/** How dark the shades get where they are deepest: the banner's foot and the ident's corner. */
private const val SHADE_DEEPEST = 0.88f

/** How far the corner shade reaches, in multiples of the ident's diagonal. */
private const val CORNER_REACH = 1.25f

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
 * The banner's keys, under a hairline, inside the column they act on: the NEXT column beside
 * it stays as it is. The row runs [KEY_PADDING] past the column on both
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

/** How much of the muted ink a key keeps while it can't act. */
private const val DISABLED_ALPHA = 0.45f

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
 * the label stays for screen readers. Flat on the banner; focus fills it with the accent. A key
 * that can't act now keeps its place, dimmed, and focus passes over it, so the row never shifts.
 */
@Composable
internal fun OsdKey(
    label: String,
    icon: ImageVector?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    value: String? = null,
    enabled: Boolean = true,
) {
    val theme = LocalAnstelleTheme.current
    val isTv = LocalIsTv.current
    val typography = androidx.compose.material3.MaterialTheme.typography
    FocusSurface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.semantics { contentDescription = listOfNotNull(label, value).joinToString(", ") },
        color = Color.Transparent,
        contentColor = theme.bannerText,
    ) { focused ->
        Row(
            Modifier.height(if (isTv) 40.dp else 48.dp).padding(horizontal = KEY_PADDING),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val ink = when {
                focused -> theme.onAccent
                enabled -> theme.bannerText
                else -> theme.textMuted.copy(alpha = DISABLED_ALPHA)
            }
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
