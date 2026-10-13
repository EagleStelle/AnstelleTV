package tv.anstelle.feature.ondemand

import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.clickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.ripple
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import tv.anstelle.core.theme.components.screenGap
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import tv.anstelle.core.data.ondemand.EpisodeEntry
import tv.anstelle.core.data.ondemand.WatchChoices
import tv.anstelle.core.data.ondemand.WatchMarks
import tv.anstelle.core.theme.ControlShape
import tv.anstelle.core.theme.LocalAnstelleTheme
import tv.anstelle.core.theme.LocalIsTv
import tv.anstelle.core.theme.components.AirMarker
import tv.anstelle.core.theme.components.FocusSurface
import tv.anstelle.core.theme.components.OsdBadge
import tv.anstelle.core.theme.components.OsdTab
import tv.anstelle.core.theme.components.PastShade
import tv.anstelle.core.theme.components.SectionLabel
import tv.anstelle.core.theme.components.TitleLogo
import tv.anstelle.core.theme.components.WatchStrip
import tv.anstelle.core.theme.components.WatchedMarker
import tv.anstelle.core.theme.components.screenSide

/*
 * An item page (a channel's, a movie's or series', an episode's) as a programme's listing card:
 * the picture, then the logo, a ruled readout of where, when and what, and the ways to watch as
 * labeled bars; the whole synopsis under it; for a show, its episodes as a carousel of stills.
 */

/** What the picture slot holds: an episode's 16:9 still, or a movie's or series' 2:3 poster. */
internal enum class PictureShape(val aspect: Float) { STILL(16f / 9f), POSTER(2f / 3f) }

/** The gap between the parts of the sheet's text group, on TV. */
private val SHEET_GAP = 14.dp

/** A still's height on TV; the right side keeps the room it needs. */
private val TV_STILL_HEIGHT = 180.dp

/** A poster's height on TV: tall, since a movie or series has the room beside it. */
private val TV_POSTER_HEIGHT = 272.dp

private fun PictureShape.tvHeight(): Dp = if (this == PictureShape.STILL) TV_STILL_HEIGHT else TV_POSTER_HEIGHT

/** A poster's width on a phone, beside the title block; a still runs across the width. */
private val PHONE_POSTER_WIDTH = 124.dp

/** The picture's width for its shape: one height on TV, the phone's own widths. */
@Composable
private fun pictureWidth(shape: PictureShape): Dp = if (LocalIsTv.current) shape.tvHeight() * shape.aspect else PHONE_POSTER_WIDTH

/**
 * The page's top on its backdrop. TV: the picture group at the start (the picture, [underPicture]
 * under it, the airing's ladder, then [timeLeft] centered under both), beside it the [title] block
 * (channel ident, logo, episode line), then the [readout] and the [bars]. Phones stack them: a
 * still across the width (a poster beside the title block), the readout, the bars.
 */
@Composable
internal fun ProgrammeSheet(
    shape: PictureShape,
    picture: @Composable (Modifier) -> Unit,
    title: @Composable ColumnScope.() -> Unit,
    readout: @Composable () -> Unit,
    bars: @Composable () -> Unit,
    underPicture: (@Composable () -> Unit)? = null,
    /** What is left of the airing, or of the movie or episode once started: "23 min left". */
    timeLeft: String? = null,
) {
    val isTv = LocalIsTv.current
    Column(verticalArrangement = Arrangement.spacedBy(if (isTv) 20.dp else 14.dp)) {
        if (isTv) {
            // Two groups centered on each other, each keeping one gap between its parts: the picture
            // with its time left, and beside it the ident, logo, episode line, readout and keys.
            Row(horizontalArrangement = Arrangement.spacedBy(28.dp), verticalAlignment = Alignment.CenterVertically) {
                val width = pictureWidth(shape)
                Column(Modifier.width(width), verticalArrangement = Arrangement.spacedBy(SHEET_GAP)) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        picture(Modifier.width(width).height(shape.tvHeight()))
                        underPicture?.invoke()
                    }
                    timeLeft?.let { TimeLeft(it) }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(SHEET_GAP)) {
                    title()
                    readout()
                    bars()
                }
            }
        } else {
            if (shape == PictureShape.STILL) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    picture(Modifier.fillMaxWidth().aspectRatio(shape.aspect))
                    underPicture?.invoke()
                    timeLeft?.let { TimeLeft(it) }
                }
                Column(verticalArrangement = Arrangement.spacedBy(6.dp), content = title)
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.Bottom) {
                    val width = pictureWidth(shape)
                    Column(Modifier.width(width), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        picture(Modifier.width(width).aspectRatio(shape.aspect))
                        underPicture?.invoke()
                        timeLeft?.let { TimeLeft(it) }
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp), content = title)
                }
            }
            readout()
            bars()
        }
    }
}

/** The time left under the picture, centered on it, in the status line's voice. */
@Composable
private fun TimeLeft(text: String) {
    androidx.compose.material3.Text(
        text.uppercase(),
        style = androidx.compose.material3.MaterialTheme.typography.labelMedium.merge(TextStyle(fontFeatureSettings = "tnum")),
        color = LocalAnstelleTheme.current.textMuted,
        textAlign = TextAlign.Center,
        maxLines = 1,
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * The picture: [url] cropped into the slot, the title's first letter on the surface step while
 * there is none, [foot] (the watch strip) at its foot; [shaded] lies the past shade over it, for
 * an episode watched through.
 */
@Composable
internal fun SheetPicture(url: String?, title: String, modifier: Modifier, shaded: Boolean = false, foot: (@Composable () -> Unit)? = null) {
    Box(modifier.clip(ControlShape)) {
        Poster(url, title, Modifier.matchParentSize())
        if (shaded) Box(Modifier.matchParentSize().background(PastShade))
        if (foot != null) Box(Modifier.align(Alignment.BottomStart)) { foot() }
    }
}

/** The logo, or the title when there is none, sized for the sheet; long titles take two lines. */
@Composable
internal fun SheetLogo(logoUrl: String?, title: String, modifier: Modifier = Modifier) {
    val isTv = LocalIsTv.current
    val typography = androidx.compose.material3.MaterialTheme.typography
    TitleLogo(
        logoUrl = logoUrl,
        title = title,
        height = if (isTv) 72.dp else 48.dp,
        maxLogoWidth = if (isTv) 360.dp else 240.dp,
        style = if (isTv) typography.headlineLarge else typography.headlineMedium,
        maxLines = 2,
        modifier = modifier,
    )
}

/** An episode's number and its title apart: "S1E4 - Pilot" reads as S1E4 and Pilot. */
internal fun splitEpisodeLabel(label: String): Pair<String?, String?> {
    val number = Regex("""^S\d+E\d+""").find(label)?.value
    val rest = (if (number != null) label.removePrefix(number) else label).removePrefix(" - ").trim()
    return number to rest.ifEmpty { null }
}

/**
 * Every episode line's hierarchy: the number in [quiet] ink and tabular figures, then the title
 * in the line's own ink, two spaces apart, never joined by a dash.
 */
internal fun numbered(number: String?, title: String?, quiet: Color) = buildAnnotatedString {
    number?.let { withStyle(SpanStyle(color = quiet, fontFeatureSettings = "tnum")) { append(it) } }
    if (number != null && title != null) append("  ")
    title?.let { append(it) }
}

/** The line under the logo: an episode's number in the quiet ink, then its title in bone. */
@Composable
internal fun EpisodeLine(label: String, modifier: Modifier = Modifier) {
    val theme = LocalAnstelleTheme.current
    val typography = androidx.compose.material3.MaterialTheme.typography
    val (number, title) = splitEpisodeLabel(label)
    androidx.compose.material3.Text(
        numbered(number, title, theme.textMuted),
        style = if (LocalIsTv.current) typography.titleLarge else typography.titleMedium,
        color = theme.text,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

/**
 * A tile's or card's line under its name, with the episode line's hierarchy in body-small: the
 * episode's number quiet, then its title in bone (a movie's year, then its title). Focus turns
 * both to the fill's ink; on TV the focused line runs as a marquee when it doesn't fit.
 */
@Composable
internal fun CaptionLine(number: String?, title: String?, focused: Boolean, modifier: Modifier = Modifier) {
    val theme = LocalAnstelleTheme.current
    val marquee = focused && LocalIsTv.current
    androidx.compose.material3.Text(
        numbered(number, title, if (focused) theme.onAccent.copy(alpha = 0.76f) else theme.textMuted),
        style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
        color = if (focused) theme.onAccent else theme.text,
        maxLines = 1,
        overflow = if (marquee) TextOverflow.Clip else TextOverflow.Ellipsis,
        modifier = if (marquee) modifier.basicMarquee(iterations = Int.MAX_VALUE) else modifier,
    )
}

/** What marks a readout segment: the air squares for when it airs, the watched square for done. */
internal enum class SegmentMark { NONE, ON_AIR, UP_NEXT, AIRED, WATCHED }

/**
 * One segment of the readout: its field [label] over its [value], or over [badges] (the picture
 * and sound, as the banner's badges). An airing's segment carries its square and its label in
 * the accent, as the banner's NOW line does.
 */
internal class Segment(val label: String, val value: String = "", val mark: SegmentMark = SegmentMark.NONE, val badges: List<String> = emptyList())

/**
 * The sheet's readout: segments in a row, parted by hairlines, wrapping to another line when the
 * column runs out, the way the set's info panel lists a programme. Figures are tabular.
 */
@Composable
internal fun Readout(segments: List<Segment>, modifier: Modifier = Modifier) {
    if (segments.isEmpty()) return
    val isTv = LocalIsTv.current
    RuledFlow(segments.size, modifier, gap = if (isTv) 28.dp else 20.dp, lineGap = if (isTv) 12.dp else 10.dp) {
        segments.forEach { ReadoutSegment(it) }
    }
}

@Composable
private fun ReadoutSegment(segment: Segment) {
    val theme = LocalAnstelleTheme.current
    val isTv = LocalIsTv.current
    val typography = androidx.compose.material3.MaterialTheme.typography
    val airing = segment.mark == SegmentMark.ON_AIR || segment.mark == SegmentMark.UP_NEXT
    Column(Modifier.semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (airing) AirMarker(onAir = segment.mark == SegmentMark.ON_AIR)
            androidx.compose.material3.Text(
                segment.label.uppercase(),
                style = typography.labelSmall,
                color = if (airing) theme.accent else theme.textMuted,
                maxLines = 1,
            )
        }
        Row(Modifier.height(if (isTv) 22.dp else 20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (segment.mark == SegmentMark.WATCHED) WatchedMarker(description = null)
            if (segment.badges.isNotEmpty()) {
                segment.badges.forEach { OsdBadge(it) }
            } else {
                androidx.compose.material3.Text(
                    segment.value,
                    style = (if (isTv) typography.titleMedium else typography.titleSmall).merge(TextStyle(fontFeatureSettings = "tnum")),
                    color = if (segment.mark == SegmentMark.AIRED) theme.textMuted else theme.text,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * The first [count] children laid out in lines, [gap] apart and wrapping at the width, with a
 * hairline (the rest of the children) standing in the middle of each gap inside a line, so a
 * wrapped line starts flush with the column like the first.
 */
@Composable
private fun RuledFlow(count: Int, modifier: Modifier, gap: Dp, lineGap: Dp, content: @Composable () -> Unit) {
    val line = LocalAnstelleTheme.current.line
    Layout(
        content = {
            content()
            repeat((count - 1).coerceAtLeast(0)) { Box(Modifier.background(line)) }
        },
        modifier = modifier,
    ) { measurables, constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val items = measurables.take(count).map { it.measure(loose) }
        val rules = measurables.drop(count)
        val gapPx = gap.roundToPx()
        val lineGapPx = lineGap.roundToPx()
        val ruleWidth = 1.dp.roundToPx().coerceAtLeast(1)
        val maxWidth = if (constraints.hasBoundedWidth) constraints.maxWidth else Int.MAX_VALUE
        val lines = ArrayList<MutableList<Int>>()
        var x = 0
        items.forEachIndexed { i, item ->
            val current = lines.lastOrNull()
            if (current == null || x + gapPx + item.width > maxWidth) {
                lines.add(mutableListOf(i))
                x = item.width
            } else {
                current.add(i)
                x += gapPx + item.width
            }
        }
        val heights = lines.map { it.maxOf { i -> items[i].height } }
        val widths = lines.map { it.sumOf { i -> items[i].width } + gapPx * (it.size - 1) }
        var next = 0
        val placedRules = lines.zip(heights).flatMap { (indices, height) ->
            List(indices.size - 1) { rules[next++].measure(Constraints.fixed(ruleWidth, height)) }
        }
        val width = (widths.maxOrNull() ?: 0).coerceIn(constraints.minWidth, maxWidth)
        val height = heights.sum() + lineGapPx * (lines.size - 1).coerceAtLeast(0)
        layout(width, height.coerceAtLeast(constraints.minHeight)) {
            var y = 0
            var rule = 0
            lines.forEachIndexed { l, indices ->
                var lx = 0
                indices.forEachIndexed { j, i ->
                    if (j > 0) {
                        placedRules[rule++].place(lx - gapPx / 2 - ruleWidth / 2, y)
                    }
                    items[i].place(lx, y)
                    lx += items[i].width + gapPx
                }
                y += heights[l] + lineGapPx
            }
        }
    }
}

/** One way to watch, or Add to a channel: its words, its icon, what it does. */
internal class SheetAction(val label: String, val icon: ImageVector, val onClick: () -> Unit)

/** The most keys the row holds; touch keys share the width as if all were there. */
private const val KEY_COLUMNS = 5

// Keys keep one shape on every device (72 by 44 on TV, 64 by 40 on touch), so TV and phone rows read alike.
private val TV_KEY_WIDTH = 72.dp
private val TOUCH_KEY_WIDTH = 64.dp

/**
 * The ways to watch, then Add to a channel, as one row of keys of one width. The first takes
 * focus on TV and is the rose primary on touch. While focus is on a key the page rests at its
 * top, the whole sheet in sight.
 */
@Composable
internal fun WatchKeys(actions: List<SheetAction>, listState: LazyListState, firstFocus: FocusRequester, modifier: Modifier = Modifier) {
    if (actions.isEmpty()) return
    val isTv = LocalIsTv.current
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val keyWidth = if (isTv) TV_KEY_WIDTH else minOf(TOUCH_KEY_WIDTH, (maxWidth - screenGap * (KEY_COLUMNS - 1)) / KEY_COLUMNS)
        Row(Modifier.restsAtTop(listState), horizontalArrangement = Arrangement.spacedBy(screenGap)) {
            actions.forEachIndexed { i, action ->
                WatchKey(
                    label = action.label,
                    icon = action.icon,
                    onClick = action.onClick,
                    // Touch has no focus to lead with: the first key is the primary instead.
                    primary = i == 0 && !isTv,
                    modifier = Modifier.width(keyWidth).let { if (i == 0) it.focusRequester(firstFocus) else it },
                )
            }
        }
    }
}

/**
 * One key: the icon on a flat key with no outline, its label under it in the OSD's condensed
 * voice. The whole key and label take the press; focus fills the key with the accent and turns
 * the label rose. A [primary] key is rose with ink, turning bone when focused.
 */
@Composable
private fun WatchKey(label: String, icon: ImageVector, onClick: () -> Unit, modifier: Modifier = Modifier, primary: Boolean = false) {
    val theme = LocalAnstelleTheme.current
    val isTv = LocalIsTv.current
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val pressed by interaction.collectIsPressedAsState()
    val fill = when {
        focused -> if (primary) theme.text else theme.accent
        primary -> theme.accent
        pressed && !isTv -> theme.surfaceHigh
        else -> theme.surface
    }
    Column(
        modifier.clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(if (isTv) 8.dp else 6.dp),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(if (isTv) 44.dp else 40.dp)
                .clip(ControlShape)
                .background(fill)
                .indication(interaction, if (isTv) null else ripple(color = if (primary) theme.onAccent else theme.text)),
            contentAlignment = Alignment.Center,
        ) {
            androidx.compose.material3.Icon(
                icon,
                contentDescription = null,
                tint = if (focused || primary) theme.onAccent else theme.text,
                modifier = Modifier.size(if (isTv) 22.dp else 20.dp),
            )
        }
        androidx.compose.material3.Text(
            label.uppercase(),
            style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
            color = if (focused) theme.accent else theme.text,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** The whole synopsis, never cut short: a reading column in body type on the plain ground. */
@Composable
internal fun Synopsis(text: String, modifier: Modifier = Modifier) {
    androidx.compose.material3.Text(
        text,
        style = androidx.compose.material3.MaterialTheme.typography.bodyLarge,
        color = LocalAnstelleTheme.current.text,
        modifier = modifier.widthIn(max = 760.dp),
    )
}

/**
 * The show's seasons as tabs, with their episode counts; one watched through carries the
 * watched square in place of its count.
 */
@Composable
internal fun SeasonTabs(seasons: Map<String, List<EpisodeEntry>>, open: String?, done: Set<String>, onSelect: (String) -> Unit) {
    val theme = LocalAnstelleTheme.current
    Box {
        Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(1.dp).background(theme.line))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            items(seasons.keys.toList(), key = { it }) { key ->
                OsdTab(key, selected = key == open, onClick = { onSelect(key) }, count = seasons.getValue(key).size, done = key in done)
            }
        }
    }
}

/** The carousel's heading: the season tabs for a show of several seasons, else its field label. */
@Composable
internal fun EpisodesHeading(seasons: Map<String, List<EpisodeEntry>>, open: String?, watch: WatchMarks, modifier: Modifier = Modifier, onSelect: (String) -> Unit) {
    Box(modifier) {
        if (seasons.size > 1) {
            val done = seasons.filterValues { list -> list.all { watch.watched(it.id, it.durationMs) } }.keys
            SeasonTabs(seasons, open, done, onSelect)
        } else {
            SectionLabel("Episodes", top = 0.dp)
        }
    }
}

/**
 * The open season's episodes as a sideways carousel of stills, the on-demand player's strip
 * at page size, running out to the screen's edges and opened on [target]. [current] is the page's
 * own episode, its still underlined in the accent like an open tab. OK opens an episode's page;
 * holding it (or Menu) offers the quick ways to play it.
 */
@Composable
internal fun EpisodeCarousel(
    episodes: List<EpisodeEntry>,
    watch: WatchMarks,
    target: Long?,
    onOpen: (EpisodeEntry) -> Unit,
    onHold: (EpisodeEntry) -> Unit,
    modifier: Modifier = Modifier,
    current: Long? = null,
    returnTo: Long? = null,
    returnFocus: FocusRequester? = null,
) {
    val side = screenSide
    val state = rememberLazyListState()
    LaunchedEffect(episodes, target) {
        val index = episodes.indexOfFirst { it.id == target }
        if (index > 0) state.scrollToItem(index)
    }
    LazyRow(
        state = state,
        modifier = modifier.bleed(side),
        contentPadding = PaddingValues(horizontal = side),
        horizontalArrangement = Arrangement.spacedBy(if (LocalIsTv.current) 8.dp else 6.dp),
    ) {
        items(episodes, key = { it.id }) { episode ->
            EpisodeCard(
                episode = episode,
                resumeMs = watch.resumeMs(episode.id, episode.durationMs),
                watched = watch.watched(episode.id, episode.durationMs),
                current = episode.id == current,
                onClick = { onOpen(episode) },
                onHold = { onHold(episode) },
                modifier = if (returnFocus != null && episode.id == returnTo) Modifier.focusRequester(returnFocus) else Modifier,
            )
        }
    }
}

/**
 * One episode in the carousel: the still in the focus inset with what was watched at its foot
 * ([WatchStrip]), its number and length (the time left once started) in the field voice, then
 * its title on two lines, so every card keeps one height. One watched through reads as aired:
 * the still under the past shade and the title quiet. Focus fills the whole card rose.
 */
@Composable
private fun EpisodeCard(
    episode: EpisodeEntry,
    resumeMs: Long,
    watched: Boolean,
    current: Boolean,
    onClick: () -> Unit,
    onHold: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val theme = LocalAnstelleTheme.current
    val isTv = LocalIsTv.current
    val typography = androidx.compose.material3.MaterialTheme.typography
    val (number, title) = splitEpisodeLabel(episode.label)
    FocusSurface(
        onClick = onClick,
        onLongClick = onHold,
        color = Color.Transparent,
        modifier = modifier
            .width(if (isTv) 232.dp else 168.dp)
            .onPreviewKeyEvent { e -> if (e.key == Key.Menu && e.type == KeyEventType.KeyUp) onHold().let { true } else e.key == Key.Menu },
    ) { focused ->
        val muted = if (focused) theme.onAccent.copy(alpha = 0.76f) else theme.textMuted
        val aired = watched && !current
        Column(Modifier.padding(TILE_FRAME)) {
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(ControlShape)) {
                Poster(episode.stillUrl, episode.label, Modifier.matchParentSize())
                if (aired) Box(Modifier.matchParentSize().background(PastShade))
                val fraction = if (episode.durationMs > 0) resumeMs.toFloat() / episode.durationMs else 0f
                if (fraction > 0f || watched) WatchStrip(fraction, watched, Modifier.align(Alignment.BottomStart))
            }
            // The page's own episode is underlined like an open tab: here.
            Box(Modifier.fillMaxWidth().height(2.dp).background(if (current && !focused) theme.accent else Color.Transparent))
            Column(Modifier.padding(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.Text(number.orEmpty(), style = typography.labelMedium, color = muted, maxLines = 1)
                    Spacer(Modifier.weight(1f))
                    (WatchChoices.timeLeft(episode.durationMs, resumeMs) ?: episode.facts)?.let {
                        androidx.compose.material3.Text(it.uppercase(), style = typography.labelMedium, color = muted, maxLines = 1)
                    }
                }
                androidx.compose.material3.Text(
                    title ?: episode.label,
                    style = typography.titleSmall,
                    color = if (aired && !focused) theme.textMuted else Color.Unspecified,
                    minLines = 2,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
