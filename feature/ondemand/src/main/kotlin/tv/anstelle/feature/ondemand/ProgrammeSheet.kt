package tv.anstelle.feature.ondemand

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
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
import androidx.compose.foundation.layout.Column
import tv.anstelle.core.model.MediaProgress
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import tv.anstelle.core.theme.components.WatchIcons
import tv.anstelle.core.data.ondemand.EpisodeEntry
import tv.anstelle.core.data.ondemand.WatchMarks
import tv.anstelle.core.data.profiles.Restriction
import tv.anstelle.core.theme.ControlShape
import tv.anstelle.core.theme.LocalAnstelleTheme
import tv.anstelle.core.theme.LocalIsTv
import tv.anstelle.core.theme.components.WatchProgress
import tv.anstelle.core.theme.components.OSD_KEY_ICON_SIZE
import tv.anstelle.core.theme.components.OsdKeyIcon
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
 * A compact readout: identity, facts, description, then the watch keys in a band of their own.
 * [progress] shows over the keys once something has played, with none of the watch strip's wash;
 * whether it was watched is the keys' to say.
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
    val descriptionLine = descriptionStyle.lineHeight
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
        // The keys, with any progress over them, start a band of their own; the synopsis' inset counts toward the break.
        val actionsGap = ACTIONS_GAP.roundToPx() - if (overview != null) SYNOPSIS_INSET.roundToPx() else 0
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

/**
 * The sheet's steps, each wider than the last: 4 dp inside the title group, [SHEET_GAP] to the
 * facts and on to the synopsis (whose inset adds its own 4), [ACTIONS_GAP] before the keys.
 */
private val SHEET_GAP = 8.dp
private val ACTIONS_GAP = 16.dp

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

// Material's 8 dp between buttons in a row.
private val KEY_GAP = 8.dp

/** The lead key's room for its words at the base type size, beside a full row of icon keys. */
private val LEAD_WIDTH = 128.dp

/** The most keys beside the lead on TV; past that, the rest wait behind [MORE_OPTIONS]. */
private const val MAX_ICON_KEYS = 4

/** The keys under the lead on touch: one line of two, the rest behind [MORE_OPTIONS] beside the lead. */
private const val TOUCH_KEYS = 2

/**
 * How a key speaks: on TV the OSD's condensed caps beside the player's normalized glyph; on touch
 * the app's own button voice (`OsdButton`'s label-large in sentence case and 20 dp glyph) in
 * Material's outlined set, so no solid glyph outweighs the line ones beside it. The key's width
 * check and its drawing both read it, so they never disagree.
 */
private class KeyVoice(val style: TextStyle, val caps: Boolean, val glyph: Dp, val inset: Dp, val gap: Dp) {
    fun words(label: String) = if (caps) label.uppercase() else label
}

@Composable
private fun keyVoice(): KeyVoice {
    val typography = androidx.compose.material3.MaterialTheme.typography
    return if (LocalIsTv.current) KeyVoice(typography.labelMedium, caps = true, OSD_KEY_ICON_SIZE, inset = 16.dp, gap = KEY_GAP)
    else KeyVoice(typography.labelLarge, caps = false, glyph = 20.dp, inset = 12.dp, gap = 10.dp)
}

/** The key, and its dialog, for the ways that don't fit the row: Material's name for an overflow. */
private const val MORE_OPTIONS = "More options"

/** A full row of keys, which sets how wide a wide page's readout is. */
@Composable
internal fun watchKeysWidth(): Dp = LEAD_WIDTH * osdKeyScale + (osdKeyHeight + KEY_GAP) * MAX_ICON_KEYS

/**
 * How a key shows: [Labelled] holds its glyph and words side by side across its width, as the
 * player's banner keys do (the lead everywhere, and every key on touch); [Icon] is a square
 * holding its glyph alone, named by the row's caption while focused (TV).
 */
private enum class KeyForm { Labelled, Icon }

/**
 * The ways to watch, then Add to a channel. The first leads. On TV: one row of a wide lead key
 * and square icon keys, as many as leave the lead room for its words, with a caption under the
 * row naming the focused key and what it does; keys past the room fold into More options at the
 * row's end. On touch: the rose lead across the readout, then one line of two coal keys of the
 * same height and form as Settings' buttons share theirs (one alone, or any whose words won't fit
 * half, spans the line); the rest fold into More options, a square beside the lead. The lead
 * takes focus first on TV. While focus is on a key the page rests at its top, the whole sheet in
 * sight.
 */
@Composable
internal fun WatchKeys(actions: List<SheetAction>, listState: LazyListState, firstFocus: FocusRequester, modifier: Modifier = Modifier, navigation: EpisodeNavigation? = null, onFocused: () -> Unit = {}) {
    if (actions.isEmpty()) return
    val isTv = LocalIsTv.current
    var more by remember { mutableStateOf(false) }
    var rowFocused by remember { mutableStateOf(false) }
    val moreFocus = remember { FocusRequester() }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val measurer = rememberTextMeasurer()
    val voice = keyVoice()
    val density = LocalDensity.current
    val lead = actions.first()
    val rest = actions.drop(1)
    BoxWithConstraints(modifier.fillMaxWidth()) {
        // How wide a labelled key must be to show its glyph and words whole.
        fun needs(label: String) = voice.inset * 2 + voice.glyph + voice.gap +
            with(density) { measurer.measure(voice.words(label), voice.style).size.width.toDp() }
        val room = if (isTv) {
            // Icon keys stand beside the lead only while its words still fit it whole.
            // A hair of slack, so pixel rounding never drops a key from a readout sized for the full row.
            ((maxWidth - needs(lead.label)) / (osdKeyHeight + KEY_GAP) + 0.01f).toInt().coerceIn(1, MAX_ICON_KEYS)
        } else TOUCH_KEYS
        val overflow = rest.size > room
        // On TV More options takes a slot of the row; on touch it stands beside the lead instead.
        val visible = if (overflow) rest.take(if (isTv) room - 1 else room).toMutableList().also { shown ->
            rest.firstOrNull { it.pinned }?.takeIf { it !in shown && shown.isNotEmpty() }?.let { shown[shown.lastIndex] = it }
        } else rest
        val extras = rest.filter { it !in visible }
        // Named for what it holds, so its caption answers "more what?".
        val inside = extras.filter { it.enabled }.map { it.label }
        val holds = if (inside.size > 1) "${inside.dropLast(1).joinToString(", ")} and ${inside.last()}." else "${inside.firstOrNull().orEmpty()}."
        val others = visible + if (overflow) listOf(SheetAction(MORE_OPTIONS, Icons.Filled.MoreHoriz, detail = holds) { more = true }) else emptyList()
        // Touch shares its line between two keys, unless one's words wouldn't fit half of it.
        val perLine = if (visible.all { needs(it.label) <= (maxWidth - KEY_GAP) / 2 }) 2 else 1
        val rowFocus = rememberTvRowFocus((listOf(lead) + others).filter { it.enabled }.map { it.label })
        fun Modifier.inRow(action: SheetAction) = tvFocusItem(rowFocus, action.label)
            .let { if (action.label == rowFocus.selected) it.focusRequester(firstFocus) else it }
            .let { if (overflow && action === others.last()) it.focusRequester(moreFocus) else it }
        Column(
            Modifier.fillMaxWidth().restsAtTop(listState)
                .onFocusChanged {
                    rowFocused = it.hasFocus
                    if (isTv && it.hasFocus) onFocused()
                }
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
            verticalArrangement = Arrangement.spacedBy(if (isTv) CAPTION_GAP else KEY_GAP),
        ) {
            if (isTv) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(KEY_GAP)) {
                    WatchKey(lead, KeyForm.Labelled, Modifier.weight(1f).inRow(lead))
                    others.forEach { WatchKey(it, KeyForm.Icon, Modifier.inRow(it)) }
                }
                val focused = rowFocus.selected.takeIf { rowFocused }?.let { label -> (listOf(lead) + others).firstOrNull { it.label == label } }
                KeyCaption(focused?.label, focused?.detail)
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(KEY_GAP)) {
                    WatchKey(lead, KeyForm.Labelled, Modifier.weight(1f).inRow(lead), primary = true)
                    if (overflow) others.last().let { WatchKey(it, KeyForm.Icon, Modifier.inRow(it)) }
                }
                visible.chunked(perLine).forEach { line ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(KEY_GAP)) {
                        line.forEach { WatchKey(it, KeyForm.Labelled, Modifier.weight(1f).inRow(it)) }
                    }
                }
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
                ) { androidx.compose.material3.Text(MORE_OPTIONS, style = androidx.compose.material3.MaterialTheme.typography.headlineSmall) }
            }
        }
    }
}

/** The space between the TV key row and its caption. */
private val CAPTION_GAP = 6.dp

/**
 * One key in its [form], flat with no outline and [osdKeyHeight] tall, speaking the device's
 * [KeyVoice]. Every key rests coal and focus fills it rose with ink; a [primary] key (touch's
 * lead, since rose only ever means focus on TV) rests rose and turns bone under focus. A touch
 * press steps coal up to coal high. Screen readers hear the words as the key's name, and on the
 * watched key what pressing it does.
 */
@Composable
private fun WatchKey(action: SheetAction, form: KeyForm, modifier: Modifier = Modifier, primary: Boolean = false) {
    val theme = LocalAnstelleTheme.current
    val isTv = LocalIsTv.current
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val pressed by interaction.collectIsPressedAsState()
    val glyph = if (action.watched == true) theme.accent else theme.text
    val (fill, ink) = when {
        primary && focused -> theme.text to theme.background
        focused || primary -> theme.accent to theme.onAccent
        pressed && !isTv -> theme.surfaceHigh to glyph
        else -> theme.surface to glyph
    }
    val voice = keyVoice()
    val key = modifier
        .alpha(if (action.enabled) 1f else 0.45f)
        .semantics {
            if (action.saving) disabled()
            if (form == KeyForm.Icon) contentDescription = action.label
        }
        .clickable(
            interactionSource = interaction,
            indication = null,
            role = Role.Button,
            enabled = action.enabled,
            onClickLabel = action.watched?.let { if (it) "Mark unwatched" else "Mark watched" },
            onClick = { if (!action.saving) action.onClick() },
        )
        .clip(ControlShape)
        .background(fill)
        .indication(interaction, if (isTv) null else ripple(color = if (primary) theme.onAccent else theme.text))
    when (form) {
        KeyForm.Labelled -> Row(
            key.height(osdKeyHeight).padding(horizontal = voice.inset),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(voice.gap, Alignment.CenterHorizontally),
        ) {
            KeyGlyph(action.icon, ink)
            // Bone, not the watched glyph's accent: words keep the ink of their fill.
            androidx.compose.material3.Text(voice.words(action.label), style = voice.style, color = if (focused || primary) ink else theme.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        KeyForm.Icon -> Box(key.size(osdKeyHeight), contentAlignment = Alignment.Center) { KeyGlyph(action.icon, ink) }
    }
}

/** A key's glyph in its [KeyVoice]: the player's normalized one on TV, the outlined one at the button size on touch. */
@Composable
private fun KeyGlyph(icon: ImageVector, tint: Color) {
    if (LocalIsTv.current) OsdKeyIcon(icon, tint)
    else androidx.compose.material3.Icon(WatchIcons.outlined(icon), contentDescription = null, tint = tint, modifier = Modifier.size(keyVoice().glyph))
}

/**
 * The TV key row's help line, the OSD's own, the same for every key: the focused key's [name] in
 * bone, then what it does in muted words, on one baseline. It keeps its one line while empty, so
 * the page never moves as focus does. Screen readers hear the key itself, so the line is silent.
 */
@Composable
private fun KeyCaption(name: String?, detail: String?) {
    val theme = LocalAnstelleTheme.current
    val typography = androidx.compose.material3.MaterialTheme.typography
    val line = with(LocalDensity.current) { typography.labelMedium.lineHeight.toDp() }
    Row(Modifier.fillMaxWidth().height(line).clearAndSetSemantics {}, horizontalArrangement = Arrangement.spacedBy(KEY_GAP)) {
        name?.let { androidx.compose.material3.Text(it.uppercase(), style = typography.labelMedium, color = theme.text, maxLines = 1, modifier = Modifier.alignByBaseline()) }
        detail?.let {
            androidx.compose.material3.Text(it, style = typography.bodySmall, color = theme.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.alignByBaseline().weight(1f, fill = false))
        }
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
