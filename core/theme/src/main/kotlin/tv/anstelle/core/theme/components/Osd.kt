package tv.anstelle.core.theme.components

import androidx.compose.foundation.Canvas
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import tv.anstelle.core.theme.ControlShape
import tv.anstelle.core.theme.LocalAnstelleTheme
import tv.anstelle.core.theme.LocalIsTv
import tv.anstelle.core.theme.PanelShape
import kotlin.math.roundToInt

/** Side padding of a full page. */
val pagePadding: Dp @Composable get() = if (LocalIsTv.current) 48.dp else 16.dp

/** The guide's side margin. On demand keeps the same, so the two screens line up as one set. */
val screenSide: Dp @Composable get() = if (LocalIsTv.current) 32.dp else 12.dp

/** The guide's space above and below its content. */
val screenEdge: Dp @Composable get() = if (LocalIsTv.current) 12.dp else 8.dp

/** The guide's gap between its bands (preview, tabs, grid); On demand's between its rows. */
val screenGap: Dp @Composable get() = if (LocalIsTv.current) 8.dp else 6.dp

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
                modifier = Modifier.padding(start = 4.dp).weight(1f, fill = false),
            )
        }
        Spacer(Modifier.weight(1f))
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
 * A tab: flat text, the open one underlined in the accent; focus fills it. [count] is set in
 * tabular figures after the label. [compact] tabs (the guide's, on TV) take a smaller label
 * and less height, so the grid under them keeps its rows.
 */
@Composable
fun OsdTab(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, count: Int? = null, compact: Boolean = false) {
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
            val typography = androidx.compose.material3.MaterialTheme.typography
            androidx.compose.material3.Text(
                buildAnnotatedString {
                    append(label)
                    if (count != null) {
                        withStyle(SpanStyle(color = color.copy(alpha = 0.7f))) { append("  $count") }
                    }
                },
                style = (if (compact) typography.titleSmall else typography.titleMedium).merge(
                    TextStyle(fontFamily = theme.displayFont, fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum"),
                ),
                color = color,
                maxLines = 1,
                modifier = Modifier.padding(
                    horizontal = if (isTv) (if (compact) 14.dp else 16.dp) else 12.dp,
                    vertical = if (isTv) (if (compact) 6.dp else 9.dp) else 10.dp,
                ),
            )
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(2.dp)
                    .background(if (selected && !focused) theme.accent else Color.Transparent),
            )
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
fun NumberBlock(number: String, style: TextStyle, modifier: Modifier = Modifier) {
    val theme = LocalAnstelleTheme.current
    Box(modifier.background(theme.accent, ControlShape), contentAlignment = Alignment.Center) {
        androidx.compose.material3.Text(
            number,
            style = style,
            color = theme.onAccent,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 10.dp),
        )
    }
}

/**
 * Progress as a ladder of segments, the way an OSD draws a volume or signal bar. Filled
 * segments carry the accent, the rest the line color, so the reading survives without hue.
 */
@Composable
fun SegmentLadder(fraction: Float, modifier: Modifier = Modifier, segments: Int = 24, color: Color = LocalAnstelleTheme.current.accent) {
    val empty = LocalAnstelleTheme.current.line
    Canvas(modifier.fillMaxWidth().height(6.dp)) {
        val gap = 2.dp.toPx()
        val width = (size.width - gap * (segments - 1)) / segments
        val filled = (fraction.coerceIn(0f, 1f) * segments).roundToInt()
        for (i in 0 until segments) {
            drawRect(
                if (i < filled) color else empty,
                topLeft = Offset(i * (width + gap), 0f),
                size = Size(width, size.height),
            )
        }
    }
}

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
            .background(theme.banner, PanelShape)
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
