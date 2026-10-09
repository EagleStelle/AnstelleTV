package tv.anstelle.feature.ondemand

import tv.anstelle.core.theme.components.obscured
import androidx.compose.material.icons.filled.Lock
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Update
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import tv.anstelle.core.common.TimeSource
import tv.anstelle.core.data.ProfileRepository
import tv.anstelle.core.data.lineUnderLogo
import tv.anstelle.core.data.ondemand.AiringTense
import tv.anstelle.core.data.ondemand.ChannelPage
import tv.anstelle.core.data.ondemand.EpisodeEntry
import tv.anstelle.core.data.ondemand.OnDemandRepository
import tv.anstelle.core.data.ondemand.WatchChoice
import tv.anstelle.core.data.ondemand.WatchChoices
import tv.anstelle.core.data.ondemand.WatchKind
import tv.anstelle.core.theme.ControlShape
import tv.anstelle.core.theme.LocalAnstelleTheme
import tv.anstelle.core.theme.LocalIsTv
import tv.anstelle.core.theme.components.ActionDialog
import tv.anstelle.core.theme.components.AirMarker
import tv.anstelle.core.theme.components.DialogAction
import tv.anstelle.core.theme.components.FocusSurface
import tv.anstelle.core.theme.components.NumberBlock
import tv.anstelle.core.theme.components.OsdTab
import tv.anstelle.core.theme.components.SectionLabel
import tv.anstelle.core.theme.components.SegmentLadder
import tv.anstelle.core.theme.components.StatusLine
import tv.anstelle.core.theme.components.screenGap
import javax.inject.Inject

@Immutable
data class ChannelUiState(
    val page: ChannelPage? = null,
    val loaded: Boolean = false,
    /** When the page was read: the program stays the page's topic even after it ends. */
    val openedAtMs: Long = 0,
    /** Jellyfin's resume points for the program and its show's episodes, by item id. */
    val resume: Map<Long, Long> = emptyMap(),
)

@HiltViewModel
class ChannelViewModel @Inject constructor(
    savedState: SavedStateHandle,
    profiles: ProfileRepository,
    private val repository: OnDemandRepository,
    time: TimeSource,
) : ViewModel() {
    /** Matches the `channelId` argument of the navigation route. */
    private val channelId: Long = checkNotNull(savedState.get<Long>("channelId"))

    private val _state = MutableStateFlow(ChannelUiState())
    val state: StateFlow<ChannelUiState> = _state.asStateFlow()

    private var profileId = 0L

    init {
        viewModelScope.launch {
            val profile = profiles.ensureActiveProfile()
            profileId = profile.id
            val access = profiles.access(profile.id)
            val now = time.nowUtcMs()
            _state.value = ChannelUiState(repository.channelPage(channelId, now, access), loaded = true, openedAtMs = now)
            refreshResume()
        }
    }

    /** Reads the resume points again, e.g. after watching some of it. */
    fun refreshResume() {
        val page = _state.value.page ?: return
        viewModelScope.launch {
            val resume = repository.resumePoints(page, profileId)
            _state.update { it.copy(resume = resume) }
        }
    }
}

/**
 * A channel in On demand, about the program it airs now (or next, while it is off air): that
 * show or movie at the top, the ways to watch it, and for a show every episode, opened at the
 * one on the channel. OK on an episode plays it, from where it was left when Jellyfin has a
 * resume point; holding it (or Menu) offers the rest.
 */
@Composable
fun ChannelScreen(
    onWatchLive: (channelNumber: Int) -> Unit,
    onStartOver: (channelNumber: Int) -> Unit,
    onPlay: (itemId: Long, positionMs: Long) -> Unit,
    /** Null for a member: adding to a channel is the owner's and admins'. */
    onAddToChannel: ((itemId: Long) -> Unit)?,
    onGone: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ChannelViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LifecycleResumeEffect(viewModel) {
        viewModel.refreshResume()
        onPauseOrDispose {}
    }
    val page = state.page
    if (page == null) {
        if (state.loaded) LaunchedEffect(Unit) { onGone() }
        return
    }
    val isTv = LocalIsTv.current
    val clock = rememberClock()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val firstFocus = remember { FocusRequester() }
    val topicFocus = remember { FocusRequester() }
    // The episode last played or held, so Back and a closed dialog land on it again.
    val returnFocus = remember { FocusRequester() }
    var lastTouched by rememberSaveable { mutableStateOf<Long?>(null) }
    var holding by remember { mutableStateOf<EpisodeEntry?>(null) }
    if (isTv) {
        LaunchedEffect(Unit) {
            withFrameNanos { }
            val returned = lastTouched != null && runCatching { returnFocus.requestFocus() }.isSuccess
            if (!returned) {
                runCatching { firstFocus.requestFocus() }
                // Focusing the first action scrolls it into view; the program above it stays in sight.
                withFrameNanos { }
                listState.scrollToItem(0)
            }
        }
    }

    val item = page.item
    val topicEpisode = page.episodes.firstOrNull { it.id == item.id }
    val seasons = remember(page) { page.episodes.groupBy { seasonKey(it.season) } }
    var season by rememberSaveable { mutableStateOf(topicEpisode?.let { seasonKey(it.season) } ?: seasons.keys.firstOrNull()) }
    val shown = seasons[season].orEmpty()
    val choices = topicChoices(page, state.openedAtMs)

    // Items above the episodes: the hero, the keys, the season tabs or heading.
    val firstEpisodeIndex = 3
    /** Down from the keys skips to the episode on the channel, not the season's first. */
    fun toTopic(): Boolean {
        val index = shown.indexOfFirst { it.id == item.id }.takeIf { it >= 0 } ?: return false
        scope.launch {
            listState.animateScrollToItem((firstEpisodeIndex + index - 1).coerceAtLeast(0))
            withFrameNanos { }
            runCatching { topicFocus.requestFocus() }
        }
        return true
    }
    // The ways to watch, then adding the show to a channel, as one row of keys.
    val keys = choices + listOfNotNull(WatchChoice(WatchKind.ADD_TO_CHANNEL, "Add to a channel", detail = "").takeIf { onAddToChannel != null })

    val hero: @Composable ColumnScope.() -> Unit = { Hero(page, clock.value) }
    Page(item.artwork.backdropUrl, hero, modifier, listState = listState) {
        item(key = "keys") {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                // Keys keep one width whatever the count, so they line up from page to page.
                val keyWidth = if (isTv) TV_KEY_WIDTH else minOf(TOUCH_KEY_WIDTH, (maxWidth - screenGap * (KEY_COLUMNS - 1)) / KEY_COLUMNS)
                Row(
                    Modifier.let { if (topicEpisode != null && isTv) it.onPreviewKeyEvent { e -> e.type == KeyEventType.KeyDown && e.key == Key.DirectionDown && toTopic() } else it },
                    horizontalArrangement = Arrangement.spacedBy(screenGap),
                ) {
                    keys.forEachIndexed { i, choice ->
                        WatchKey(
                            label = choice.title,
                            icon = choice.icon(),
                            onClick = {
                                lastTouched = null
                                when (choice.kind) {
                                    WatchKind.LIVE -> onWatchLive(page.channel.number)
                                    WatchKind.RESTART -> onStartOver(page.channel.number)
                                    WatchKind.PLAY -> onPlay(item.id, choice.positionMs)
                                    WatchKind.ADD_TO_CHANNEL -> onAddToChannel?.invoke(item.id)
                                }
                            },
                            // Touch has no focus to lead with: the first key is the primary instead.
                            primary = i == 0 && !isTv,
                            modifier = Modifier.width(keyWidth).let { if (i == 0) it.focusRequester(firstFocus) else it },
                        )
                    }
                }
            }
        }
        if (seasons.size > 1) {
            item(key = "seasons") {
                SeasonTabs(seasons, season, topicSeason = topicEpisode?.let { seasonKey(it.season) }) { season = it }
            }
        } else if (page.episodes.isNotEmpty()) {
            // The keys sit one gap above it, as the hero sits above them.
            item(key = "seasons") { SectionLabel("Episodes", top = 0.dp) }
        }
        items(shown, key = { it.id }) { episode ->
            val isTopic = episode.id == item.id
            val resume = state.resume[episode.id]?.takeIf { it < episode.durationMs * RESUME_LIMIT_PERCENT / 100 } ?: 0
            EpisodeRow(
                episode = episode,
                airing = if (isTopic) airingStatus(page, clock.value) else null,
                resumeMs = resume,
                onClick = {
                    lastTouched = episode.id
                    onPlay(episode.id, resume)
                },
                onHold = {
                    lastTouched = episode.id
                    holding = episode
                },
                modifier = Modifier
                    .let { if (isTopic) it.focusRequester(topicFocus) else it }
                    .let { if (episode.id == lastTouched) it.focusRequester(returnFocus) else it },
            )
        }
    }

    holding?.let { episode ->
        val resume = state.resume[episode.id]?.takeIf { it < episode.durationMs * RESUME_LIMIT_PERCENT / 100 } ?: 0
        // The episode on the channel offers what the page's actions do, live included.
        val episodeChoices = if (episode.id == item.id) {
            choices + listOfNotNull(WatchChoices.addToChannel(isMovie = false).takeIf { onAddToChannel != null })
        } else {
            listOfNotNull(
                resume.takeIf { it > 0 }?.let(WatchChoices::resume),
                WatchChoices.playFromStart,
                WatchChoices.addToChannel(isMovie = false).takeIf { onAddToChannel != null },
            )
        }
        val dismiss = {
            holding = null
            runCatching { returnFocus.requestFocus() }
            Unit
        }
        ActionDialog(
            actions = episodeChoices.map { choice ->
                DialogAction(choice.title, choice.detail, choice.icon()) {
                    holding = null
                    when (choice.kind) {
                        WatchKind.LIVE -> onWatchLive(page.channel.number)
                        WatchKind.RESTART -> onStartOver(page.channel.number)
                        WatchKind.PLAY -> onPlay(episode.id, choice.positionMs)
                        WatchKind.ADD_TO_CHANNEL -> onAddToChannel?.invoke(episode.id)
                    }
                }
            },
            onDismiss = dismiss,
        ) {
            val theme = LocalAnstelleTheme.current
            androidx.compose.material3.Text(
                item.title,
                style = androidx.compose.material3.MaterialTheme.typography.headlineSmall,
                color = theme.text,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            androidx.compose.material3.Text(
                listOfNotNull(episode.label, episode.facts).joinToString("  ·  "),
                style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                color = theme.textMuted,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * The ways to watch the page's program, in the guide's own words: live on the channel, the
 * channel from the program's start, on demand from the start or from where the channel is.
 * A program the profile may not watch only offers the channel, which stays dark.
 */
private fun topicChoices(page: ChannelPage, nowMs: Long): List<WatchChoice> {
    val item = page.item
    val tense = if (page.onNow) AiringTense.of(page.startUtcMs, page.endUtcMs, nowMs) else AiringTense.LATER
    page.restriction?.let { restriction ->
        return listOfNotNull(WatchChoices.liveRestricted(page.endUtcMs, restriction.title).takeIf { tense == AiringTense.ON_NOW })
    }
    return if (tense == AiringTense.ON_NOW) {
        WatchChoices.onNow(page.channel.number, page.channel.name, page.startUtcMs, page.endUtcMs, nowMs, item.isEpisode)
    } else {
        // Off air now, or the program has ended since the page opened: the channel is elsewhere.
        listOfNotNull(
            WatchChoices.liveOffAir(page.startUtcMs).takeIf { !page.onNow },
            WatchChoices.fromStart(tense, page.startUtcMs),
        )
    }
}

private fun WatchChoice.icon() = when (kind) {
    WatchKind.LIVE -> Icons.Filled.LiveTv
    WatchKind.RESTART -> Icons.Filled.Replay
    WatchKind.ADD_TO_CHANNEL -> Icons.AutoMirrored.Filled.PlaylistAdd
    // Picking up partway reads apart from playing from the start.
    WatchKind.PLAY -> if (positionMs > 0) Icons.Filled.Update else Icons.Filled.PlayArrow
}

/**
 * One key of the page's row: the icon on a flat key with no outline, its label under it in the
 * OSD's condensed voice. The whole key and label take the press; focus fills the key with the
 * accent and turns the label rose. A [primary] key is rose with ink, turning bone when focused,
 * like the primary button.
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
                .height(if (isTv) 52.dp else 44.dp)
                .clip(ControlShape)
                .background(fill)
                .indication(interaction, if (isTv) null else ripple(color = if (primary) theme.onAccent else theme.text)),
            contentAlignment = Alignment.Center,
        ) {
            androidx.compose.material3.Icon(
                icon,
                contentDescription = null,
                tint = if (focused || primary) theme.onAccent else theme.text,
                modifier = Modifier.size(if (isTv) 26.dp else 24.dp),
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

/**
 * The program at the top: the channel's number and name as its banner shows them, the still
 * or poster beside the logo, what it is, when it airs on the channel and how far it has got,
 * then the overview.
 */
@Composable
private fun ColumnScope.Hero(page: ChannelPage, nowMs: Long) {
    val theme = LocalAnstelleTheme.current
    val isTv = LocalIsTv.current
    val typography = androidx.compose.material3.MaterialTheme.typography
    val item = page.item
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        NumberBlock(
            "${page.channel.number}",
            style = typography.titleLarge.merge(androidx.compose.ui.text.TextStyle(fontFamily = theme.displayFont, fontFeatureSettings = "tnum")),
        )
        androidx.compose.material3.Text(
            page.channel.name,
            style = typography.titleMedium,
            color = theme.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
    // An episode shows its own still, wide; a movie its poster.
    val wide = item.isEpisode
    Row(horizontalArrangement = Arrangement.spacedBy(if (isTv) 28.dp else 16.dp), verticalAlignment = Alignment.Bottom) {
        val restricted = page.restriction != null
        Poster(
            item.imageUrl,
            // A restricted program's placeholder shows no letter of its title.
            item.title.takeUnless { restricted }.orEmpty(),
            Modifier.width(if (wide) (if (isTv) 240.dp else 160.dp) else (if (isTv) 120.dp else 104.dp)).aspectRatio(if (wide) 16f / 9f else 2f / 3f),
        )
        Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.weight(1f)) {
            // A restricted program's title, logo and episode are blurred.
            HeroTitle(item.artwork.logoUrl, item.title, Modifier.obscured(restricted, theme.textMuted, radius = 12.dp))
            lineUnderLogo(item.detail, item.title, item.isEpisode, item.artwork.logoUrl != null)?.let {
                androidx.compose.material3.Text(it, style = typography.titleLarge, color = theme.text, modifier = Modifier.obscured(restricted, theme.textMuted, radius = 6.dp))
            }
            if (isTv) Facts(item.facts, item.badges)
        }
    }
    if (!isTv) Facts(item.facts, item.badges)
    val status = airingStatus(page, nowMs)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        StatusLine(status.onAir, status.label, status.text)
        // Phones keep the TV's segment pitch with half the segments.
        if (status.onAir == true) {
            SegmentLadder(progress(page.startUtcMs, page.endUtcMs, nowMs), Modifier.width(if (isTv) 240.dp else 120.dp), segments = if (isTv) 24 else 12)
        }
    }
    // For a program the profile may not watch, the overview is blacked out with the pictures.
    page.restriction?.let { restriction ->
        Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            androidx.compose.material3.Icon(Icons.Filled.Lock, contentDescription = null, tint = theme.textMuted, modifier = Modifier.size(18.dp))
            androidx.compose.material3.Text(
                "${restriction.title.uppercase()}  ·  ${restriction.detail.uppercase()}",
                style = typography.labelMedium,
                color = theme.textMuted,
            )
        }
    }
    // Three lines on TV keep the actions on the first screen under the program.
    item.overview?.let { Overview(it, Modifier.padding(top = 4.dp), tvLines = 3) }
}

/** A status line's parts: the marker (null once aired), the field label and the times. */
private data class AiringStatus(val onAir: Boolean?, val label: String, val text: String)

/** When the page's program airs on the channel, as its status line says it. */
private fun airingStatus(page: ChannelPage, nowMs: Long): AiringStatus {
    val range = WatchChoices.clockRange(page.startUtcMs, page.endUtcMs)
    val tense = if (page.onNow) AiringTense.of(page.startUtcMs, page.endUtcMs, nowMs) else AiringTense.LATER
    return when (tense) {
        AiringTense.ON_NOW -> AiringStatus(true, "On now", "$range  ·  ${WatchChoices.span(page.endUtcMs - nowMs, roundUp = true)} left")
        AiringTense.LATER -> AiringStatus(false, "Up next", range)
        AiringTense.AIRED -> AiringStatus(null, "Aired", range)
    }
}

/** The show's seasons as tabs, with their episode counts; the one on the channel carries the on-air square. */
@Composable
private fun SeasonTabs(seasons: Map<String, List<EpisodeEntry>>, open: String?, topicSeason: String?, onSelect: (String) -> Unit) {
    val theme = LocalAnstelleTheme.current
    Box {
        Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(1.dp).background(theme.line))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            items(seasons.keys.toList(), key = { it }) { key ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (key == topicSeason) AirMarker(onAir = true, modifier = Modifier.padding(start = 6.dp))
                    OsdTab(key, selected = key == open, onClick = { onSelect(key) }, count = seasons.getValue(key).size)
                }
            }
        }
    }
}

/**
 * One episode: its still (with how far it was watched), its number and title, length and the
 * start of its overview. The episode on the channel puts when it airs in place of its length.
 * OK plays it; holding it (or Menu) offers the other ways.
 */
@Composable
private fun EpisodeRow(
    episode: EpisodeEntry,
    airing: AiringStatus?,
    resumeMs: Long,
    onClick: () -> Unit,
    onHold: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val theme = LocalAnstelleTheme.current
    val isTv = LocalIsTv.current
    val typography = androidx.compose.material3.MaterialTheme.typography
    FocusSurface(
        onClick = onClick,
        onLongClick = onHold,
        modifier = modifier
            .fillMaxWidth()
            .onPreviewKeyEvent { e ->
                if (e.key == Key.Menu && e.type == KeyEventType.KeyUp) onHold().let { true } else e.key == Key.Menu
            },
    ) { focused ->
        val muted = if (focused) theme.onAccent.copy(alpha = 0.76f) else theme.textMuted
        Row(
            Modifier.padding(if (isTv) 10.dp else 8.dp),
            horizontalArrangement = Arrangement.spacedBy(if (isTv) 18.dp else 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.width(if (isTv) 176.dp else 124.dp).aspectRatio(16f / 9f).clip(ControlShape)) {
                Poster(episode.stillUrl, episode.label, Modifier.fillMaxWidth().aspectRatio(16f / 9f))
                if (resumeMs > 0 && episode.durationMs > 0) {
                    SegmentLadder(
                        resumeMs.toFloat() / episode.durationMs,
                        Modifier.align(Alignment.BottomStart).background(theme.background.copy(alpha = 0.8f)).padding(horizontal = 6.dp, vertical = 5.dp),
                        segments = 12,
                    )
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                androidx.compose.material3.Text(
                    episode.label,
                    style = typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (airing != null) {
                    StatusLine(airing.onAir, airing.label, airing.text, ink = if (focused) theme.onAccent else null)
                } else {
                    val facts = listOfNotNull(episode.facts, resumeMs.takeIf { it > 0 }?.let { "${WatchChoices.span(it)} watched" }).joinToString("  ·  ")
                    if (facts.isNotEmpty()) {
                        androidx.compose.material3.Text(facts, style = typography.bodySmall, color = muted, maxLines = 1)
                    }
                }
                episode.overview?.let {
                    androidx.compose.material3.Text(
                        it,
                        style = if (isTv) typography.bodyMedium else typography.bodySmall,
                        color = muted,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** The tab a season goes under. */
private fun seasonKey(season: Int?): String = when (season) {
    null -> "Other episodes"
    0 -> "Specials"
    else -> "Season $season"
}

/** A resume point this close to the end counts as watched through. */
private const val RESUME_LIMIT_PERCENT = 95

/** The most keys the row holds; touch keys share the width as if all were there. */
private const val KEY_COLUMNS = 5

// Keys keep one shape on every device (84 by 52 on TV, 72 by 44 on touch), so TV and phone rows read alike.

/** A key's width on TV. */
private val TV_KEY_WIDTH = 84.dp

/** A touch key's widest; narrow phones shrink it to fit five. The label under it widens the target past 48. */
private val TOUCH_KEY_WIDTH = 72.dp
