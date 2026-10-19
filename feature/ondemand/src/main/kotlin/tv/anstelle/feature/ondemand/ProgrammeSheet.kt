package tv.anstelle.feature.ondemand

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.clickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.material3.ripple
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import tv.anstelle.core.theme.components.screenGap
import androidx.compose.foundation.layout.Column
import tv.anstelle.core.model.MediaProgress
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import tv.anstelle.core.data.ondemand.EpisodeEntry
import tv.anstelle.core.data.ondemand.WatchMarks
import tv.anstelle.core.theme.ControlShape
import tv.anstelle.core.theme.LocalAnstelleTheme
import tv.anstelle.core.theme.LocalIsTv
import tv.anstelle.core.theme.components.FocusSurface
import tv.anstelle.core.theme.components.LocalRailFocus
import tv.anstelle.core.theme.components.OsdTab
import tv.anstelle.core.theme.components.OsdTabIndicator
import tv.anstelle.core.theme.components.Poster
import tv.anstelle.core.theme.components.SectionLabel
import tv.anstelle.core.theme.components.WatchStrip
import tv.anstelle.core.theme.components.screenSide
import tv.anstelle.core.theme.components.ActionDialog
import tv.anstelle.core.theme.components.DialogAction
import tv.anstelle.core.theme.components.requestWhenReady
import kotlinx.coroutines.launch

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
 * under it), beside it the [title] block, with the watch strip on the picture's foot,
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
) {
    val isTv = LocalIsTv.current
    Column(verticalArrangement = Arrangement.spacedBy(if (isTv) 20.dp else 14.dp)) {
        if (isTv) {
            // Two groups centered on each other, each keeping one gap between its parts: the picture
            // and beside it the ident, logo, media title, episode line, facts and keys.
            Row(horizontalArrangement = Arrangement.spacedBy(28.dp), verticalAlignment = Alignment.CenterVertically) {
                val width = pictureWidth(shape)
                Column(Modifier.width(width), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        picture(Modifier.width(width).height(shape.tvHeight()))
                        underPicture?.invoke()
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp), content = title)
                    readout()
                    bars()
                }
            }
        } else {
            if (shape == PictureShape.STILL) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    picture(Modifier.fillMaxWidth().aspectRatio(shape.aspect))
                    underPicture?.invoke()
                }
                Column(verticalArrangement = Arrangement.spacedBy(6.dp), content = title)
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.Bottom) {
                    val width = pictureWidth(shape)
                    Column(Modifier.width(width), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        picture(Modifier.width(width).aspectRatio(shape.aspect))
                        underPicture?.invoke()
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp), content = title)
                }
            }
            readout()
            bars()
        }
    }
}

/** A graphical logo sized for the sheet; [SheetTitle] supplies its readable fallback and media name. */
@Composable
internal fun SheetLogo(logoUrl: String?, title: String, modifier: Modifier = Modifier) {
    val isTv = LocalIsTv.current
    var failed by remember(logoUrl) { mutableStateOf(false) }
    if (logoUrl != null && !LocalAnstelleTheme.current.spec.isLight && !failed) AsyncImage(
        model = logoUrl, contentDescription = null,
        contentScale = ContentScale.Fit, alignment = Alignment.BottomStart,
        onError = { failed = true },
        modifier = modifier.height(if (isTv) 56.dp else 48.dp).widthIn(max = if (isTv) 360.dp else 240.dp),
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
        style = if (LocalIsTv.current) typography.bodyLarge else typography.bodyMedium,
        color = theme.text,
        maxLines = if (LocalIsTv.current) 1 else 2,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

/**
 * A tile's or card's line under its name, with the episode line's hierarchy in body-medium: the
 * episode's number quiet, then its title in bone (a movie's year, then its title). Focus turns
 * both to the fill's ink; on TV the focused line runs as a marquee when it doesn't fit.
 */
@Composable
internal fun CaptionLine(number: String?, title: String?, focused: Boolean, modifier: Modifier = Modifier) {
    val theme = LocalAnstelleTheme.current
    val marquee = focused && LocalIsTv.current
    androidx.compose.material3.Text(
        numbered(number, title, if (focused) theme.onAccent.copy(alpha = 0.76f) else theme.textMuted),
        style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
        color = if (focused) theme.onAccent else theme.text,
        maxLines = 1,
        overflow = if (marquee) TextOverflow.Clip else TextOverflow.Ellipsis,
        modifier = if (marquee) modifier.basicMarquee(iterations = Int.MAX_VALUE) else modifier,
    )
}

/** One way to watch, or Add to a channel: its words, its icon, what it does. */
internal class SheetAction(
    val label: String,
    val icon: ImageVector,
    val enabled: Boolean = true,
    val saving: Boolean = false,
    val pinned: Boolean = false,
    val watched: Boolean? = null,
    val detail: String? = null,
    val onClick: () -> Unit,
) {
    constructor(action: DialogAction) : this(
        label = action.title,
        icon = requireNotNull(action.icon),
        detail = action.detail,
        onClick = action.onClick,
    )
}

// Keys keep one shape on every device (72 by 44 on TV, up to 64 by 48 on touch), so TV and phone rows read alike.
private val TV_KEY_WIDTH = 72.dp
private val TOUCH_KEY_WIDTH = 64.dp

/**
 * The ways to watch, then Add to a channel, as one row of keys of one width. The first takes
 * focus on TV and is the rose primary on touch. While focus is on a key the page rests at its
 * top, the whole sheet in sight.
 */
@Composable
internal fun WatchKeys(actions: List<SheetAction>, listState: LazyListState, firstFocus: FocusRequester, modifier: Modifier = Modifier, navigation: EpisodeNavigation? = null, dialogDetails: DialogAction? = null, onFocused: () -> Unit = {}) {
    if (actions.isEmpty()) return
    val isTv = LocalIsTv.current
    var more by remember { mutableStateOf(false) }
    val moreFocus = remember { FocusRequester() }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val minimum = if (isTv) TV_KEY_WIDTH else 48.dp
        val capacity = ((maxWidth + screenGap) / (minimum + screenGap)).toInt().coerceAtLeast(2)
        val overflow = actions.size > capacity
        val visible = if (overflow) actions.take(capacity - 1).toMutableList().also { shown ->
            actions.firstOrNull { it.pinned }?.takeIf { it !in shown && shown.size > 1 }?.let { shown[shown.lastIndex] = it }
        } else actions
        val extras = actions.filter { it !in visible }
        val shown = visible + if (overflow) listOf(SheetAction("More", Icons.Filled.MoreHoriz) { more = true }) else emptyList()
        val rowFocus = rememberTvRowFocus(shown.filter { it.enabled }.map { it.label })
        val keyWidth = minOf(if (isTv) TV_KEY_WIDTH else TOUCH_KEY_WIDTH, (maxWidth - screenGap * (shown.size - 1)) / shown.size)
        Row(
            Modifier.restsAtTop(listState).onFocusChanged { if (isTv && it.hasFocus) onFocused() }
                .tvFocusGroup(rowFocus).onPreviewKeyEvent { event ->
                if (!isTv) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionDown -> {
                        if (event.type == KeyEventType.KeyDown) navigation?.downFromKeys()
                        true
                    }
                    Key.DirectionUp -> true
                    else -> false
                }
            },
            horizontalArrangement = Arrangement.spacedBy(screenGap),
        ) {
            shown.forEachIndexed { i, action ->
                WatchKey(
                    label = action.label,
                    icon = action.icon,
                    onClick = action.onClick,
                    enabled = action.enabled,
                    saving = action.saving,
                    watched = action.watched,
                    // Touch has no focus to lead with: the first key is the primary instead.
                    primary = i == 0 && !isTv,
                    modifier = Modifier.width(keyWidth).tvFocusItem(rowFocus, action.label)
                        .let { if (action.label == rowFocus.selected) it.focusRequester(firstFocus) else it }
                        .let { if (overflow && i == shown.lastIndex) it.focusRequester(moreFocus) else it },
                )
            }
        }
        if (more && extras.isNotEmpty()) {
            val dismiss = {
                more = false
                scope.launch { moreFocus.requestWhenReady() }
                Unit
            }
            ActionDialog(
                actions = extras.filter { it.enabled }.map { action ->
                    DialogAction(action.label, action.detail, action.icon, false, onClick = {
                        dismiss()
                        action.onClick()
                    })
                } + listOfNotNull(dialogDetails?.let { details ->
                    DialogAction(details.title, details.detail, details.icon, false, onClick = {
                        dismiss()
                        details.onClick()
                    })
                }),
                onDismiss = dismiss,
            ) { androidx.compose.material3.Text("More", style = androidx.compose.material3.MaterialTheme.typography.headlineSmall) }
        }
    }
}

/**
 * One key: the icon on a flat key with no outline, its label under it in the OSD's condensed
 * voice. The whole key and label take the press; focus fills the key with the accent and turns
 * the label rose. A [primary] key is rose with ink, turning bone when focused.
 */
@Composable
private fun WatchKey(label: String, icon: ImageVector, onClick: () -> Unit, modifier: Modifier = Modifier, primary: Boolean = false, enabled: Boolean = true, saving: Boolean = false, watched: Boolean? = null) {
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
        modifier.alpha(if (enabled) 1f else 0.45f)
            .semantics {
                if (saving) disabled()
                watched?.let {
                    stateDescription = if (it) "Watched" else "Unwatched"
                    contentDescription = if (it) "Mark unwatched" else "Mark watched"
                }
            }
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, enabled = enabled, onClick = { if (!saving) onClick() }),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(if (isTv) 8.dp else 6.dp),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(if (isTv) 44.dp else 48.dp)
                .clip(ControlShape)
                .background(fill)
                .indication(interaction, if (isTv) null else ripple(color = if (primary) theme.onAccent else theme.text)),
            contentAlignment = Alignment.Center,
        ) {
            androidx.compose.material3.Icon(
                icon,
                contentDescription = null,
                tint = if (focused || primary) theme.onAccent else if (watched == true) theme.accent else theme.text,
                modifier = Modifier.size(if (isTv) 22.dp else 20.dp),
            )
        }
        androidx.compose.material3.Text(
            label.uppercase(),
            style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
            color = if (focused) theme.accent else theme.text,
            textAlign = TextAlign.Center,
            minLines = 2,
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
internal fun SeasonTabs(seasons: Map<String, List<EpisodeEntry>>, open: String?, done: Set<String>, onSelect: (String) -> Unit, navigation: EpisodeNavigation? = null) {
    Box {
        val isTv = LocalIsTv.current
        val rail = LocalRailFocus.current
        val state = rememberLazyListState(initialFirstVisibleItemIndex = seasons.keys.indexOf(open).coerceAtLeast(0))
        DisposableEffect(navigation, state, open) {
            navigation?.seasonRow = state
            navigation?.selectedSeasonIndex = seasons.keys.indexOf(open).coerceAtLeast(0)
            onDispose { if (navigation?.seasonRow === state) navigation.seasonRow = null }
        }
        LazyRow(
            state = state,
            modifier = Modifier
                .bleed(if (isTv) 14.dp else 0.dp)
                .focusProperties { onEnter = { runCatching { navigation?.season?.requestFocus() } } }
                .focusGroup()
                .onPreviewKeyEvent { event ->
                    if (!isTv || navigation == null || event.type != KeyEventType.KeyDown) false else when (event.key) {
                        Key.DirectionDown -> { navigation.downFromSeasons(); true }
                        Key.DirectionUp -> { navigation.upFromSeasons(); true }
                        else -> false
                    }
                },
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(seasons.keys.toList(), key = { it }) { key ->
                OsdTab(
                    key, selected = key == open, onClick = { onSelect(key) },
                    count = seasons.getValue(key).size, done = key in done, compact = isTv,
                    modifier = Modifier
                        .let { if (key == open && navigation != null) it.focusRequester(navigation.season) else it }
                        .focusProperties {
                            if (isTv && key == seasons.keys.first()) left = rail?.rail ?: FocusRequester.Cancel
                            if (isTv && key == seasons.keys.last()) right = FocusRequester.Cancel
                        },
                )
            }
        }
    }
}

/** The carousel's heading: the season tabs for a show of several seasons, else its field label. */
@Composable
internal fun EpisodesHeading(seasons: Map<String, List<EpisodeEntry>>, open: String?, watch: WatchMarks, modifier: Modifier = Modifier, navigation: EpisodeNavigation? = null, onSelect: (String) -> Unit) {
    Box(modifier) {
        if (seasons.size > 1) {
            val done = seasons.filterValues { list -> list.all { watch.watched(it.id, it.durationMs) } }.keys
            SeasonTabs(seasons, open, done, onSelect, navigation)
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
    navigation: EpisodeNavigation? = null,
) {
    val side = screenSide
    val isTv = LocalIsTv.current
    // Set the opening position once per season/page, never again merely because watch data changed.
    val rowFocus = rememberTvRowFocus(episodes.map { it.id.toString() }, initial = target?.toString(), identity = episodes.firstOrNull()?.id to current)
    DisposableEffect(navigation, rowFocus.list) {
        navigation?.episodeRow = rowFocus
        onDispose { if (navigation?.episodeRow?.list === rowFocus.list) navigation.episodeRow = null }
    }
    // Stills start on the side margin, under the heading: each card's focus inset makes up the rest.
    FocusRow(
        rowFocus,
        padding = side - TILE_FRAME,
        spacing = if (isTv) 8.dp else 6.dp,
        modifier = modifier.bleed(side)
            .onPreviewKeyEvent { event ->
                if (!isTv) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionUp -> {
                        if (event.type == KeyEventType.KeyDown) navigation?.upFromEpisodes()
                        true
                    }
                    Key.DirectionDown -> true
                    else -> false
                }
            },
    ) {
        items(episodes, key = { it.id }) { episode ->
            EpisodeCard(
                episode = episode,
                resumeMs = watch.resumeMs(episode.id, episode.durationMs),
                watched = watch.watched(episode.id, episode.durationMs),
                current = episode.id == current,
                onClick = { onOpen(episode) },
                onHold = { onHold(episode) },
                modifier = Modifier.tvFocusItem(rowFocus, episode.id.toString())
                    .let { if (episode.id.toString() == rowFocus.selected && navigation != null) it.focusRequester(navigation.episode) else it }
                    .let { if (returnFocus != null && episode.id == returnTo) it.focusRequester(returnFocus) else it },
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
            Poster(
                episode.stillUrl, episode.label,
                Modifier.fillMaxWidth().aspectRatio(16f / 9f),
                shaded = aired,
            ) {
                WatchStrip(
                    MediaProgress(episode.durationMs, resumeMs), watched,
                    Modifier.align(Alignment.BottomStart),
                )
            }
            // The page's own episode is underlined like an open tab: here.
            OsdTabIndicator(current, focused)
            Column(Modifier.padding(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.Text(number.orEmpty(), style = typography.labelMedium, color = muted, maxLines = 1)
                    Spacer(Modifier.weight(1f))
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
