package tv.anstelle.feature.ondemand

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.layout.Column
import tv.anstelle.core.model.MediaProgress
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import tv.anstelle.core.data.ondemand.EpisodeEntry
import tv.anstelle.core.data.ondemand.WatchMarks
import tv.anstelle.core.data.profiles.Restriction
import tv.anstelle.core.theme.ControlShape
import tv.anstelle.core.theme.LocalAnstelleTheme
import tv.anstelle.core.theme.LocalIsTv
import tv.anstelle.core.theme.components.WatchProgress
import tv.anstelle.core.theme.components.osdKeyHeight
import tv.anstelle.core.theme.components.osdKeyScale
import tv.anstelle.core.theme.components.screenSide
import tv.anstelle.core.theme.components.ActionDialog
import tv.anstelle.core.theme.components.DialogAction
import tv.anstelle.core.theme.components.Notice
import tv.anstelle.core.theme.components.RestrictedMark
import tv.anstelle.core.theme.components.StandInSize
import tv.anstelle.core.theme.components.TitleLogo
import tv.anstelle.core.theme.components.obscured
import tv.anstelle.core.theme.components.requestWhenReady
import tv.anstelle.core.theme.components.TILE_FRAME
import tv.anstelle.core.theme.components.EpisodeCard
import tv.anstelle.core.theme.components.SeasonTabs
import tv.anstelle.core.theme.components.episodeCardSpacing
import tv.anstelle.core.theme.components.numbered
import tv.anstelle.core.theme.components.splitEpisodeLabel
import kotlinx.coroutines.launch

/**
 * A compact readout: identity, facts, description, then the watch keys on a row of their own.
 * [progress] shows once something has played, the sheet's gap clear above and below it and
 * none of the watch strip's wash; whether it was watched is the keys' to say.
 */
@Composable
internal fun ProgrammeSheet(
    title: String,
    logoUrl: String?,
    readout: @Composable () -> Unit,
    bars: @Composable () -> Unit,
    detail: String? = null,
    overview: String? = null,
    error: String? = null,
    restriction: Restriction? = null,
    navigation: EpisodeNavigation? = null,
    ident: @Composable () -> Unit = {},
    progress: MediaProgress? = null,
) {
    val theme = LocalAnstelleTheme.current
    val restricted = restriction != null
    val readoutHeight = LocalReadoutHeight.current
    val descriptionLine = androidx.compose.material3.MaterialTheme.typography.bodySmall.lineHeight
    val played = progress?.takeIf { it.hasProgress }
    SubcomposeLayout(Modifier.fillMaxWidth()) { constraints ->
        val natural = constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity)
        val identity = subcompose("identity") {
            Column(verticalArrangement = Arrangement.spacedBy(SHEET_GAP)) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    ident()
                    // A restricted program's title, logo and episode are blurred.
                    TitleLogo(logoUrl, title, maxLines = 2, modifier = Modifier.obscured(restricted, theme.textMuted, radius = 12.dp))
                    detail?.let { EpisodeLine(it, Modifier.obscured(restricted, theme.textMuted, radius = 6.dp)) }
                }
                readout()
                error?.let { Notice(it, isError = true) }
                restriction?.let { RestrictedMark(it.title, size = StandInSize.LINE, alignStart = true) }
            }
        }.single().measure(natural)
        val actions = subcompose("actions") {
            Column(verticalArrangement = Arrangement.spacedBy(SHEET_GAP)) {
                played?.let { WatchProgress(it, compact = true) }
                bars()
            }
        }.single().measure(natural)
        val gap = SHEET_GAP.roundToPx()
        // The progress row stands as far under the synopsis' words as above the keys: the synopsis' inset counts toward it.
        val actionsGap = if (played != null && overview != null) gap - SYNOPSIS_INSET.roundToPx() else gap
        val description = overview?.let { text ->
            val budget = if (readoutHeight == Dp.Infinity) Constraints.Infinity else {
                (readoutHeight.roundToPx() - identity.height - actions.height - gap - actionsGap)
                    .coerceAtLeast((descriptionLine.toDp() + SYNOPSIS_INSET * 2).roundToPx())
            }
            subcompose("description") { Synopsis(text, navigation = navigation) }
                .single().measure(natural.copy(maxHeight = budget))
        }
        val height = identity.height + actions.height + actionsGap + (description?.let { it.height + gap } ?: 0)
        layout(constraints.maxWidth, height) {
            identity.placeRelative(0, 0)
            description?.placeRelative(0, identity.height + gap)
            actions.placeRelative(0, height - actions.height)
        }
    }
}

/** The sheet's one gap: between its identity, synopsis, progress row and keys. */
private val SHEET_GAP = 8.dp

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

// Material's 8 dp between buttons in a row; the narrowest slot a key and its two-line label take.
private val KEY_GAP = 8.dp
private val KEY_WIDTH = 64.dp

/** The most keys a row shows; past that, the rest wait behind More. */
private const val MAX_KEYS = 5

/** A full row of keys, which sets how wide a wide page's readout is. */
@Composable
internal fun watchKeysWidth(): Dp = KEY_WIDTH * osdKeyScale * MAX_KEYS + KEY_GAP * (MAX_KEYS - 1)

/**
 * The ways to watch, then Add to a channel, as one row of equal rectangular keys, flush left. The
 * row is cut into slots for a full row (five, or fewer when the keys would get too narrow), so
 * three or four keys keep the width five would have. The first takes focus on TV and is the rose
 * primary on touch. While focus is on a key the page rests at its top, the whole sheet in sight.
 */
@Composable
internal fun WatchKeys(actions: List<SheetAction>, listState: LazyListState, firstFocus: FocusRequester, modifier: Modifier = Modifier, navigation: EpisodeNavigation? = null, onFocused: () -> Unit = {}) {
    if (actions.isEmpty()) return
    val isTv = LocalIsTv.current
    var more by remember { mutableStateOf(false) }
    val moreFocus = remember { FocusRequester() }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val keyWidth = KEY_WIDTH * osdKeyScale
    BoxWithConstraints(modifier.fillMaxWidth()) {
        // A hair of slack, so pixel rounding never drops a key from a readout sized for the full row.
        val capacity = ((maxWidth + KEY_GAP) / (keyWidth + KEY_GAP) + 0.01f).toInt().coerceIn(2, MAX_KEYS)
        val slotWidth = (maxWidth - KEY_GAP * (capacity - 1)) / capacity
        val overflow = actions.size > capacity
        val visible = if (overflow) actions.take(capacity - 1).toMutableList().also { shown ->
            actions.firstOrNull { it.pinned }?.takeIf { it !in shown && shown.size > 1 }?.let { shown[shown.lastIndex] = it }
        } else actions
        val extras = actions.filter { it !in visible }
        val shown = visible + if (overflow) listOf(SheetAction("More", Icons.Filled.MoreHoriz) { more = true }) else emptyList()
        val rowFocus = rememberTvRowFocus(shown.filter { it.enabled }.map { it.label })
        Row(
            Modifier.align(Alignment.CenterStart).restsAtTop(listState).onFocusChanged { if (isTv && it.hasFocus) onFocused() }
                .horizontalScroll(rememberScrollState())
                .tvFocusGroup(rowFocus).onPreviewKeyEvent { event ->
                if (!isTv) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionDown -> {
                        if (event.type == KeyEventType.KeyDown) navigation?.downFromKeys()
                        true
                    }
                    Key.DirectionUp -> {
                        if (event.type == KeyEventType.KeyDown) navigation?.upFromKeys()
                        true
                    }
                    else -> false
                }
            },
            horizontalArrangement = Arrangement.spacedBy(KEY_GAP),
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
                    modifier = Modifier.width(slotWidth).tvFocusItem(rowFocus, action.label)
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
            // The hero has unbounded height in its lazy list; the modal needs the window's bounds.
            Dialog(onDismissRequest = dismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
                ActionDialog(
                    actions = extras.filter { it.enabled }.map { action ->
                        DialogAction(action.label, action.detail, action.icon, false, onClick = {
                            dismiss()
                            action.onClick()
                        })
                    },
                    onDismiss = dismiss,
                ) { androidx.compose.material3.Text("More", style = androidx.compose.material3.MaterialTheme.typography.headlineSmall) }
            }
        }
    }
}

/**
 * One key: the icon on a flat rectangle the width of its slot, with no outline, its label under
 * it in the OSD's condensed voice. The whole key and label take the press; focus fills the key with the accent and turns
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
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(osdKeyHeight)
                .clip(ControlShape)
                .background(fill)
                .indication(interaction, if (isTv) null else ripple(color = if (primary) theme.onAccent else theme.text)),
            contentAlignment = Alignment.Center,
        ) {
            androidx.compose.material3.Icon(
                icon,
                contentDescription = null,
                tint = if (focused || primary) theme.onAccent else if (watched == true) theme.accent else theme.text,
                modifier = Modifier.size(18.dp).graphicsLayer { scaleX = 1.2f; scaleY = 1.2f },
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

/**
 * The page's sticky [SeasonTabs] on the ground, wired to the page's D-pad [navigation]: the open
 * tab takes focus coming in, and Up and Down move between the keys, the tabs and the episodes.
 */
@Composable
internal fun EpisodesHeading(seasons: Map<String, List<EpisodeEntry>>, open: String?, watch: WatchMarks, modifier: Modifier = Modifier, navigation: EpisodeNavigation? = null, onSelect: (String) -> Unit) {
    val isTv = LocalIsTv.current
    val state = rememberLazyListState(initialFirstVisibleItemIndex = seasons.keys.indexOf(open).coerceAtLeast(0))
    DisposableEffect(navigation, state, open) {
        navigation?.seasonRow = state
        navigation?.selectedSeasonIndex = seasons.keys.indexOf(open).coerceAtLeast(0)
        onDispose { if (navigation?.seasonRow === state) navigation.seasonRow = null }
    }
    Box(modifier.fillMaxWidth().background(LocalAnstelleTheme.current.background)) {
        SeasonTabs(
            seasons.mapValues { it.value.size }, open, watch.doneSeasons(seasons), onSelect,
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
            state = state,
            openFocus = navigation?.season,
        )
    }
}

/**
 * The open season's episodes as a sideways carousel of [EpisodeCard]s, running out to the
 * screen's edges and opened on [target]. [current] is the episode the page is about: an episode
 * page's own, or the one on a channel page's channel. OK opens an episode's page; holding it (or
 * Menu) offers the quick ways to play it.
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
        spacing = episodeCardSpacing,
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
                label = episode.label,
                stillUrl = episode.stillUrl,
                progress = MediaProgress(episode.durationMs, watch.resumeMs(episode.id, episode.durationMs)),
                watched = watch.watched(episode.id, episode.durationMs),
                current = episode.id == current,
                onClick = { onOpen(episode) },
                onHold = { onHold(episode) },
                touchWidth = 168.dp,
                modifier = Modifier.tvFocusItem(rowFocus, episode.id.toString())
                    .let { if (episode.id.toString() == rowFocus.selected && navigation != null) it.focusRequester(navigation.episode) else it }
                    .let { if (returnFocus != null && episode.id == returnTo) it.focusRequester(returnFocus) else it },
            )
        }
    }
}
