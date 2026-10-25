package tv.anstelle.core.theme.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import tv.anstelle.core.model.MediaProgress
import tv.anstelle.core.model.ProgressStyle
import tv.anstelle.core.theme.ControlShape
import tv.anstelle.core.theme.LocalAnstelleTheme
import tv.anstelle.core.theme.LocalIsTv
import tv.anstelle.core.theme.PanelShape
import kotlin.math.floor
import kotlin.math.roundToInt

/** Side padding of a full page. */
val pagePadding: Dp @Composable get() = if (LocalIsTv.current) 48.dp else 16.dp

/** The guide's side margin. On demand keeps the same, so the two screens line up as one set. */
val screenSide: Dp @Composable get() = if (LocalIsTv.current) 32.dp else 12.dp

/** The guide's space above and below its content. */
val screenEdge: Dp @Composable get() = if (LocalIsTv.current) 12.dp else 8.dp

/** The guide's gap between its bands (preview, tabs, grid); On demand's between its rows. */
val screenGap: Dp @Composable get() = if (LocalIsTv.current) 8.dp else 6.dp

/** The width the app's navigation rail takes from the window; the rail shell provides it. */
val LocalRailWidth = compositionLocalOf { 0.dp }

/**
 * The screen's own size: the window less the navigation rail beside it. Every screen's
 * breakpoints read this one size, so a window lays out alike on every screen. The keyboard
 * never changes it.
 */
val screenSize: DpSize
    @Composable get() {
        val window = LocalWindowInfo.current.containerSize
        return with(LocalDensity.current) { DpSize(window.width.toDp() - LocalRailWidth.current, window.height.toDp()) }
    }

/** Narrower than Material's medium width: a phone held upright. */
val screenNarrow: Boolean @Composable get() = screenSize.width < MEDIUM_WIDTH

/** At least Material's expanded width: a tablet, or a large phone held sideways. */
val screenWide: Boolean @Composable get() = screenSize.width >= EXPANDED_WIDTH

/** Lower than Material's medium height: a phone held sideways, where each screen keeps its lead within one view. */
val screenShort: Boolean @Composable get() = screenSize.height < MEDIUM_HEIGHT

private val MEDIUM_WIDTH = 600.dp
private val EXPANDED_WIDTH = 840.dp
private val MEDIUM_HEIGHT = 480.dp

/** How much keys grow: with the system font and the theme's type scale, never below their own size. */
val osdKeyScale: Float @Composable get() = maxOf(1f, LocalDensity.current.fontScale * LocalAnstelleTheme.current.spec.typography.scale)

/** One key height for the player's banner and every detail page's watch keys, so the two always match. */
val osdKeyHeight: Dp @Composable get() = 48.dp * osdKeyScale

/** How far every key's glyph reaches at its widest, on touch and TV; text scale enlarges the key, not its icon. */
val OSD_KEY_ICON_SIZE = 18.dp

/**
 * How much of its 24-unit viewport a Material glyph spans at its widest. Every glyph is scaled
 * as if it spanned 20 units, so one spanning them fills [OSD_KEY_ICON_SIZE] and the rest keep
 * Material's own balance (it draws the solid transport glyphs small on purpose). The few listed
 * fall just short of 20 and are scaled to fill it too.
 */
private fun glyphSpan(icon: ImageVector) = when (icon) {
    Icons.Filled.FastForward -> 17.5f
    Icons.Filled.Check -> 17.6f
    Icons.Filled.Audiotrack -> 18f
    else -> 20f
}

/** A key's glyph on the player's banner and a detail page's watch keys alike, scaled until its widest span fills [OSD_KEY_ICON_SIZE]. */
@Composable
fun OsdKeyIcon(icon: ImageVector, tint: Color) {
    val scale = 24f / glyphSpan(icon)
    androidx.compose.material3.Icon(
        icon,
        contentDescription = null,
        tint = tint,
        modifier = Modifier.size(OSD_KEY_ICON_SIZE).graphicsLayer {
            scaleX = scale
            scaleY = scale
        },
    )
}

/** A short tag in the field voice on a flat fill: a profile's role, the default theme. */
@Composable
fun OsdChip(text: String, fill: Color, ink: Color, modifier: Modifier = Modifier) {
    androidx.compose.material3.Text(
        text,
        style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
        color = ink,
        maxLines = 1,
        modifier = modifier.background(fill, ControlShape).padding(horizontal = 5.dp, vertical = 1.dp),
    )
}

/** A browse row's or grid's heading, in the OSD's field voice. */
val browseHeading: TextStyle
    @Composable get() {
        val theme = LocalAnstelleTheme.current
        return androidx.compose.material3.MaterialTheme.typography.labelMedium.copy(fontSize = theme.sp(if (LocalIsTv.current) 15f else 13f), lineHeight = theme.sp(18f))
    }

/** A screen's title, an optional line under it and a hairline that closes the header. */
@Composable
fun PageHeader(title: String, modifier: Modifier = Modifier, subtitle: String? = null) {
    val theme = LocalAnstelleTheme.current
    val isTv = LocalIsTv.current
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        androidx.compose.material3.Text(
            title,
            style = if (isTv) {
                androidx.compose.material3.MaterialTheme.typography.headlineLarge
            } else {
                androidx.compose.material3.MaterialTheme.typography.headlineMedium
            },
            color = theme.text,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (subtitle != null) {
            androidx.compose.material3.Text(
                subtitle,
                style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                color = theme.textMuted,
                modifier = Modifier.widthIn(max = 720.dp),
            )
        }
        Spacer(Modifier.height(6.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(theme.line))
    }
}

/**
 * A phone screen's top bar: a back arrow and, when given, the screen's title, with the page's
 * own [actions] at its end, where Android puts a top bar's actions. Flat on the page's ground;
 * TV screens don't use it, the remote's Back does the job.
 */
@Composable
fun BackBar(onBack: () -> Unit, modifier: Modifier = Modifier, title: String? = null, actions: @Composable RowScope.() -> Unit = {}) {
    val theme = LocalAnstelleTheme.current
    Row(
        modifier.fillMaxWidth().height(56.dp).padding(start = 4.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.material3.IconButton(onClick = onBack) {
            androidx.compose.material3.Icon(
                androidx.compose.material.icons.Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back",
                tint = theme.text,
            )
        }
        if (title != null) {
            androidx.compose.material3.Text(
                title,
                style = androidx.compose.material3.MaterialTheme.typography.headlineSmall,
                color = theme.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 4.dp).weight(1f),
            )
        }
        actions()
    }
}

/** The heading of a group of rows, set like an OSD field label, [top] clear of what is above. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier, top: Dp = 16.dp) {
    androidx.compose.material3.Text(
        text.uppercase(),
        style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
        color = LocalAnstelleTheme.current.textMuted,
        modifier = modifier.padding(top = top, bottom = 2.dp),
    )
}

/**
 * A tab's label: the theme's display font and scale, SemiBold, tabular figures. Anything set in a
 * tab row beside the tabs (On demand's search slab) takes it too, so the row reads as one line.
 */
@Composable
@ReadOnlyComposable
fun osdTabLabel(compact: Boolean): TextStyle {
    val typography = androidx.compose.material3.MaterialTheme.typography
    return (if (compact) typography.titleSmall else typography.titleMedium).merge(
        TextStyle(fontFamily = LocalAnstelleTheme.current.displayFont, fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum"),
    )
}

/**
 * A tab: flat text, the open one underlined in the accent; focus fills it. [count] follows the
 * label as a coal chip in the field voice, inverted on the focus fill; a [done] tab (a season
 * watched through) carries the watched square in its place. [compact] tabs (the guide's, on TV) take a smaller label and less
 * height, so the grid under them keeps its rows.
 */
@Composable
fun OsdTab(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    count: Int? = null,
    compact: Boolean = LocalIsTv.current,
    done: Boolean = false,
) {
    val theme = LocalAnstelleTheme.current
    val isTv = LocalIsTv.current
    FocusSurface(onClick = onClick, color = Color.Transparent, modifier = modifier) { focused ->
        // As wide as the label, so the underline never stretches across the row.
        Column(Modifier.width(IntrinsicSize.Max), horizontalAlignment = Alignment.CenterHorizontally) {
            val color = when {
                focused -> theme.onAccent
                selected -> theme.text
                else -> theme.textMuted
            }
            Row(
                Modifier.height(osdTabContentHeight(compact)).padding(
                    horizontal = if (isTv) (if (compact) 14.dp else 16.dp) else 12.dp,
                ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                androidx.compose.material3.Text(label, style = osdTabLabel(compact), color = color, maxLines = 1)
                // The square follows the label's ink, its tick cut in whatever the tab sits on.
                if (done) {
                    WatchedMarker(color = color, tick = if (focused) theme.accent else theme.background)
                } else if (count != null) {
                    // A chip a step down in the field voice, so the count never reads as the label's next word.
                    OsdChip("$count", fill = if (focused) theme.onAccent else theme.surface, ink = if (focused) theme.accent else theme.textMuted)
                }
            }
            OsdTabIndicator(selected, focused)
        }
    }
}

/** A fact such as 4K, HDR10 or 5.1: condensed, outlined, never filled. */
@Composable
fun OsdBadge(text: String, modifier: Modifier = Modifier) {
    val theme = LocalAnstelleTheme.current
    androidx.compose.material3.Text(
        text,
        style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
        color = theme.text,
        maxLines = 1,
        modifier = modifier
            .border(1.dp, theme.textMuted.copy(alpha = 0.6f), ControlShape)
            .padding(horizontal = 5.dp, vertical = 1.dp),
    )
}

/** A channel number as the OSD shows it: tabular digits on an accent block. */
@Composable
fun NumberBlock(number: String, style: TextStyle, modifier: Modifier = Modifier, inset: Dp = 10.dp) {
    val theme = LocalAnstelleTheme.current
    Box(modifier.background(theme.accent, ControlShape), contentAlignment = Alignment.Center) {
        androidx.compose.material3.Text(
            number,
            style = style,
            color = theme.onAccent,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = inset),
        )
    }
}

/**
 * A short code (the pairing PIN, a Quick Connect code) as one row of number blocks. The blocks
 * share the available width up to a cap, so a narrow phone never pushes the code off screen;
 * a null [code] draws hollow slots while it loads.
 */
@Composable
fun CodeBlocks(code: String?, modifier: Modifier = Modifier, length: Int = 6) {
    val theme = LocalAnstelleTheme.current
    val isTv = LocalIsTv.current
    val style = if (isTv) {
        androidx.compose.material3.MaterialTheme.typography.displayMedium
    } else {
        androidx.compose.material3.MaterialTheme.typography.headlineLarge
    }
    Row(
        modifier.fillMaxWidth().widthIn(max = if (isTv) 366.dp else 320.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val slot = Modifier.weight(1f).aspectRatio(56f / 72f)
        for (i in 0 until (code?.length ?: length)) {
            val char = code?.getOrNull(i)
            if (char != null) {
                NumberBlock(char.toString(), style = style, modifier = slot, inset = 2.dp)
            } else {
                Box(slot.border(1.dp, theme.line, ControlShape))
            }
        }
    }
}

/**
 * Progress as a ladder of segments, the way an OSD draws a volume or signal bar. Filled
 * segments carry the accent, the rest the line color, so the reading survives without hue.
 */
@Composable
fun SegmentLadder(fraction: Float, modifier: Modifier = Modifier, color: Color = LocalAnstelleTheme.current.accent, emptyColor: Color = LocalAnstelleTheme.current.line) {
    val empty = emptyColor
    val style = LocalAnstelleTheme.current.spec.progress
    val progress = fraction.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f
    Canvas(modifier.fillMaxWidth().height(6.dp).semantics { progressBarRangeInfo = ProgressBarRangeInfo(progress, 0f..1f) }) {
        val gap = ProgressStyle.GAP_DP.dp.toPx().roundToInt().toFloat()
        val width = style.blockWidthDp.dp.toPx().roundToInt().coerceAtLeast(1).toFloat()
        val count = ((size.width + gap) / (width + gap)).toInt()
        if (count == 0) return@Canvas
        // Repeat whole, equal-width blocks; center spare space instead of stretching or clipping a block.
        val start = ((size.width - (count * width + (count - 1) * gap)) / 2).roundToInt().toFloat()
        val filled = (progress * count).roundToInt()
        for (i in 0 until count) {
            drawRect(
                if (i < filled) color else empty,
                topLeft = Offset(start + i * (width + gap), 0f),
                size = Size(width, size.height),
            )
        }
    }
}

/**
 * A wait with no end known yet (a channel tuning, a program loading): the ladder bent into a
 * ring of [segments], square-ended, with a lit run in the accent over the line-colored rest.
 * The run keeps Material's indeterminate rhythm (the head reaches ahead, the tail catches up, and
 * the next sweep sets off from where the last ended), stepped into whole segments the way the ladder
 * fills, so it snaps rather than glides. It has no track, plate or shade of its own: the
 * line-colored segments are its edge over a bright picture, the lit run over a dark one.
 */
@Composable
fun SegmentRing(description: String, modifier: Modifier = Modifier, segments: Int = 16) {
    val theme = LocalAnstelleTheme.current
    // Four sweeps of three quarters each make three whole turns, so the loop closes seamlessly.
    val loop by rememberInfiniteTransition(label = "ring").animateFloat(
        initialValue = 0f,
        targetValue = RING_SWEEPS.toFloat(),
        animationSpec = infiniteRepeatable(tween(durationMillis = RING_SWEEP_MS * RING_SWEEPS, easing = LinearEasing)),
        label = "loop",
    )
    Canvas(
        modifier.size(48.dp).semantics {
            contentDescription = description
            progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate
        },
    ) {
        val stroke = 6.dp.toPx()
        val radius = (size.minDimension - stroke) / 2
        val slot = 360f / segments
        // A 2 px gap at the stroke's middle, as between the ladder's segments.
        val sweep = slot - Math.toDegrees(2.dp.toPx() / radius.toDouble()).toFloat()
        val sweepIndex = loop.toInt()
        val t = loop - sweepIndex
        val reach = segments * RING_REACH
        val base = segments * (sweepIndex * RING_ADVANCE + RING_DRIFT * t)
        val head = base + reach * FastOutSlowInEasing.transform((t * 2).coerceAtMost(1f))
        val tail = base + reach * FastOutSlowInEasing.transform((t * 2 - 1).coerceAtLeast(0f))
        // Whole segments only: at least one lit, the head's own included.
        val first = floor(tail).toInt()
        val lit = floor(head).toInt() + 1 - first
        val arc = Size(radius * 2, radius * 2)
        val topLeft = Offset(center.x - radius, center.y - radius)
        for (i in 0 until segments) {
            drawArc(
                color = if ((i - first).mod(segments) < lit) theme.accent else theme.line,
                // Segment 0 starts at twelve o'clock, half a gap in.
                startAngle = -90f + i * slot + (slot - sweep) / 2,
                sweepAngle = sweep,
                useCenter = false,
                topLeft = topLeft,
                size = arc,
                style = Stroke(stroke, cap = StrokeCap.Butt),
            )
        }
    }
}

/** One reach and catch-up of the ring's run, Material's indeterminate cycle. */
private const val RING_SWEEP_MS = 1_333

/** Sweeps before the run is back where it began. */
private const val RING_SWEEPS = 4

/** How far the head reaches ahead of the tail at most, as a share of the ring. */
private const val RING_REACH = 0.625f

/** How far the whole run drifts round during a sweep, as a share of the ring. */
private const val RING_DRIFT = 0.125f

/** Where each sweep starts relative to the last: reach plus drift, three quarters round. */
private const val RING_ADVANCE = RING_REACH + RING_DRIFT

/** On air: a solid square. Up next: the same square, hollow. */
@Composable
fun AirMarker(onAir: Boolean, modifier: Modifier = Modifier, color: Color = LocalAnstelleTheme.current.accent) {
    Canvas(modifier.size(8.dp)) {
        if (onAir) {
            drawRect(color)
        } else {
            val stroke = 1.5.dp.toPx()
            drawRect(color, topLeft = Offset(stroke / 2, stroke / 2), size = Size(size.width - stroke, size.height - stroke), style = Stroke(stroke))
        }
    }
}

/**
 * The guide's dark wash over the hours that have aired, and over the stills of episodes
 * watched through: time that has gone by, for the channel or for you.
 */
val PastShade = Color(0x33000000)

/**
 * Watched through: the third of the air marker's squares, solid in [color] with a tick cut
 * through it in [tick]. Never the accent: the accent means here and now, and this is done.
 * [description] names it for screen readers; null where a word beside it already does.
 */
@Composable
fun WatchedMarker(
    modifier: Modifier = Modifier,
    color: Color = LocalAnstelleTheme.current.text,
    tick: Color = LocalAnstelleTheme.current.background,
    description: String? = "Watched",
) {
    Canvas(modifier.size(10.dp).let { if (description != null) it.semantics { contentDescription = description } else it }) {
        drawRect(color)
        val w = size.width
        val h = size.height
        val path = Path().apply {
            moveTo(w * 0.2f, h * 0.52f)
            lineTo(w * 0.42f, h * 0.74f)
            lineTo(w * 0.8f, h * 0.3f)
        }
        drawPath(path, tick, style = Stroke(width = w * 0.17f, cap = StrokeCap.Square, join = StrokeJoin.Miter))
    }
}

/**
 * What was watched of an episode, on a strip of the wash across its still's foot: the minutes
 * watched as a ladder in the accent (where you left it is where you are), and once watched
 * through the watched square with WATCHED in the field voice, both in the accent so they
 * follow the theme. Watching one again shows the square before the ladder. One height in every state,
 * so a list of stills keeps one line. Show it only when [progress] has started or [watched].
 */
@Composable
fun WatchStrip(progress: MediaProgress, watched: Boolean, modifier: Modifier = Modifier, remaining: String? = progress.remainingLabel) {
    if (!progress.hasProgress && !watched) return
    val theme = LocalAnstelleTheme.current
    WatchProgress(
        progress = progress, watched = watched, remaining = remaining,
        modifier = modifier
            .fillMaxWidth()
            .background(theme.background.copy(alpha = 0.8f))
            .padding(horizontal = 6.dp),
        compact = true,
    )
}

/** One watch row everywhere: themed check, ladder, then remaining time. */
@Composable
@OptIn(ExperimentalLayoutApi::class)
fun WatchProgress(
    progress: MediaProgress,
    watched: Boolean = false,
    remaining: String? = progress.remainingLabel,
    modifier: Modifier = Modifier,
    focused: Boolean = false,
    compact: Boolean = false,
) {
    val theme = LocalAnstelleTheme.current
    val fraction = progress.fraction
    val spot = progress.hasProgress
    val completed = watched && (!spot || fraction >= 1f)
    val time = remaining.takeUnless { completed }
    val ink = if (focused) theme.onAccent else theme.accent
    FlowRow(
        modifier
            .fillMaxWidth()
            .heightIn(min = 16.dp),
        maxItemsInEachRow = 2,
        horizontalArrangement = Arrangement.spacedBy(if (compact) 5.dp else 8.dp, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(
            Modifier.weight(1f).heightIn(min = 16.dp).align(Alignment.CenterVertically),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(if (compact) 5.dp else 8.dp),
        ) {
            if (watched) WatchedMarker(color = ink, tick = if (focused) theme.accent else theme.onAccent, description = if (spot) "Watched" else null)
            if (spot || !watched) {
                Box(Modifier.weight(1f)) {
                    SegmentLadder(fraction,
                        color = ink,
                        emptyColor = if (focused) theme.onAccent.copy(alpha = 0.25f) else theme.line)
                }
            } else if (watched) {
                androidx.compose.material3.Text(
                    "WATCHED",
                    style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                    color = ink,
                    maxLines = 1,
                )
            }
        }
        if (time != null) androidx.compose.material3.Text(
            time,
            style = (if (compact) androidx.compose.material3.MaterialTheme.typography.labelSmall else androidx.compose.material3.MaterialTheme.typography.labelMedium)
                .merge(TextStyle(fontFeatureSettings = "tnum")),
            color = quietInk(focused),
            maxLines = 1,
            modifier = Modifier.align(Alignment.CenterVertically).semantics { contentDescription = "$time remaining" },
        )
    }
}

/**
 * When a program airs, as the banner's NOW line says it: the marker (solid on now, hollow for
 * later, none once it has aired), the field [label] in the accent, then [text], the times. An
 * aired program's line is quiet throughout. [ink] recolors it all, for a line on a focus fill.
 */
@Composable
fun StatusLine(onAir: Boolean?, label: String, text: String, modifier: Modifier = Modifier, ink: Color? = null) {
    val theme = LocalAnstelleTheme.current
    val typography = androidx.compose.material3.MaterialTheme.typography
    val aired = onAir == null
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (onAir != null) AirMarker(onAir, color = ink ?: theme.accent)
        androidx.compose.material3.Text(
            buildAnnotatedString {
                withStyle(SpanStyle(color = ink ?: if (aired) theme.textMuted else theme.accent)) { append(label.uppercase()) }
                if (text.isNotEmpty()) {
                    append("  ")
                    append(text.uppercase())
                }
            },
            style = typography.labelMedium,
            color = ink ?: theme.textMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** A flat panel over video or the guide: the banner tone, a hairline edge, square corners. */
@Composable
fun OsdPanel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val theme = LocalAnstelleTheme.current
    Column(
        modifier
            .background(theme.surface, PanelShape)
            .border(1.dp, theme.line, PanelShape)
            .padding(if (LocalIsTv.current) 24.dp else 18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        content = content,
    )
}

/**
 * The AnstelleTV channel bug: an ink A on an accent tile, its crossbar running on past the
 * right leg like the guide's now line running out of frame. Drawn on the 108-unit grid that
 * `tools/brand/generate_brand.py` uses for the launcher icon.
 */
@Composable
fun AnstelleMark(modifier: Modifier = Modifier, tile: Color = LocalAnstelleTheme.current.accent, ink: Color = LocalAnstelleTheme.current.onAccent) {
    Canvas(modifier) {
        val unit = size.minDimension / 108f
        drawRoundRect(tile, cornerRadius = CornerRadius(6 * unit))
        scale(unit, unit, pivot = Offset.Zero) {
            drawPath(MarkA, ink)
            drawRect(ink, topLeft = Offset(BAR_LEFT, BAR_TOP), size = Size(BAR_RIGHT - BAR_LEFT, BAR_BOTTOM - BAR_TOP))
        }
    }
}

/** "Anstelle" in the text color, "TV" in the accent. */
@Composable
fun Wordmark(style: TextStyle, modifier: Modifier = Modifier) {
    val theme = LocalAnstelleTheme.current
    androidx.compose.material3.Text(
        buildAnnotatedString {
            append("Anstelle")
            withStyle(SpanStyle(color = theme.accent)) { append("TV") }
        },
        style = style.merge(TextStyle(fontFamily = theme.displayFont)),
        color = theme.text,
        maxLines = 1,
        modifier = modifier,
    )
}

/** A wordmark beside the bug, as the drawer and empty screens show it. */
@Composable
fun Brand(modifier: Modifier = Modifier, markSize: Dp = 32.dp) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        AnstelleMark(Modifier.size(markSize))
        Wordmark(androidx.compose.material3.MaterialTheme.typography.headlineSmall)
    }
}

private val MarkA = Path().apply {
    moveTo(45f, 28f)
    lineTo(55f, 28f)
    lineTo(73f, 80f)
    lineTo(63f, 80f)
    lineTo(50f, 41.5f)
    lineTo(37f, 80f)
    lineTo(27f, 80f)
    close()
}
private const val BAR_LEFT = 38f
private const val BAR_RIGHT = 82f
private const val BAR_TOP = 58f
private const val BAR_BOTTOM = 66f
