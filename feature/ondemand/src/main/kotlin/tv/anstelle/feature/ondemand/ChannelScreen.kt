package tv.anstelle.feature.ondemand

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
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
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
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
import tv.anstelle.core.theme.components.FocusTile
import tv.anstelle.core.theme.components.NumberBlock
import tv.anstelle.core.theme.components.OsdButton
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

    init {
        viewModelScope.launch {
            val limit = profiles.ensureActiveProfile().maxRatingScore
            val now = time.nowUtcMs()
            _state.value = ChannelUiState(repository.channelPage(channelId, now, limit), loaded = true, openedAtMs = now)
            refreshResume()
        }
    }

    /** Reads the resume points again, e.g. after watching some of it. */
    fun refreshResume() {
        val page = _state.value.page ?: return
        viewModelScope.launch {
            val resume = repository.resumePoints(page)
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
    onAddToChannel: (itemId: Long) -> Unit,
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
    val wide = isTv || with(LocalDensity.current) { LocalWindowInfo.current.containerSize.width.toDp() } >= WIDE_DP.dp
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

    // Items above the episodes: the hero, the action rows, the season tabs or heading.
    val actionRows = choices.chunked(if (wide) 2 else 1)
    val firstEpisodeIndex = 1 + actionRows.size + 1
    /** Down from the last action row skips to the episode on the channel, not the season's first. */
    fun toTopic(): Boolean {
        val index = shown.indexOfFirst { it.id == item.id }.takeIf { it >= 0 } ?: return false
        scope.launch {
            listState.animateScrollToItem((firstEpisodeIndex + index - 1).coerceAtLeast(0))
            withFrameNanos { }
            runCatching { topicFocus.requestFocus() }
        }
        return true
    }


    // Adding the show to a channel is about the show, not a way to watch it: it sits at the
    // page's top end, out of the choices' grid (the hero's first line on TV, the back bar on phones).
    val add: @Composable () -> Unit = {
        OsdButton(
            text = "Add to a channel",
            icon = Icons.AutoMirrored.Filled.PlaylistAdd,
            onClick = {
                lastTouched = null
                onAddToChannel(item.id)
            },
        )
    }
    val hero: @Composable ColumnScope.() -> Unit = { Hero(page, clock.value, action = if (isTv) add else null) }
    Page(item.artwork.backdropUrl, hero, modifier, listState = listState, topAction = add) {
        actionRows.forEachIndexed { r, row ->
            item(key = "actions-$r") {
                val last = r == actionRows.lastIndex
                Row(
                    Modifier
                        .fillMaxWidth()
                        .let { if (last && topicEpisode != null && isTv) it.onPreviewKeyEvent { e -> e.type == KeyEventType.KeyDown && e.key == Key.DirectionDown && toTopic() } else it },
                    horizontalArrangement = Arrangement.spacedBy(screenGap),
                ) {
                    row.forEachIndexed { c, choice ->
                        FocusTile(
                            title = choice.title,
                            subtitle = choice.detail,
                            icon = choice.icon(),
                            onClick = {
                                lastTouched = null
                                when (choice.kind) {
                                    WatchKind.LIVE -> onWatchLive(page.channel.number)
                                    WatchKind.RESTART -> onStartOver(page.channel.number)
                                    WatchKind.PLAY -> onPlay(item.id, choice.positionMs)
                                    WatchKind.ADD_TO_CHANNEL -> onAddToChannel(item.id)
                                }
                            },
                            // Touch has no focus to lead with: the first choice is the primary instead.
                            primary = r == 0 && c == 0 && !isTv,
                            modifier = Modifier.weight(1f).let { if (r == 0 && c == 0) it.focusRequester(firstFocus) else it },
                        )
                    }
                    // An odd last choice keeps its half, so the grid holds its columns.
                    if (wide && row.size == 1) Box(Modifier.weight(1f))
                }
            }
        }
        if (seasons.size > 1) {
            item(key = "seasons") {
                SeasonTabs(seasons, season, topicSeason = topicEpisode?.let { seasonKey(it.season) }) { season = it }
            }
        } else if (page.episodes.isNotEmpty()) {
            item(key = "seasons") { SectionLabel("Episodes") }
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
            choices + WatchChoices.addToChannel(isMovie = false)
        } else {
            listOfNotNull(
                resume.takeIf { it > 0 }?.let(WatchChoices::resume),
                WatchChoices.playFromStart,
                WatchChoices.addToChannel(isMovie = false),
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
                        WatchKind.ADD_TO_CHANNEL -> onAddToChannel(episode.id)
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
 * Adding the show to a channel isn't a way to watch it; it sits at the top of the page.
 */
private fun topicChoices(page: ChannelPage, nowMs: Long): List<WatchChoice> {
    val item = page.item
    val tense = if (page.onNow) AiringTense.of(page.startUtcMs, page.endUtcMs, nowMs) else AiringTense.LATER
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
    WatchKind.PLAY -> Icons.Filled.PlayArrow
}

/**
 * The program at the top: the channel's number and name as its banner shows them, the still
 * or poster beside the logo, what it is, when it airs on the channel and how far it has got,
 * then the overview.
 */
@Composable
private fun ColumnScope.Hero(page: ChannelPage, nowMs: Long, action: (@Composable () -> Unit)?) {
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
        action?.invoke()
    }
    // An episode shows its own still, wide; a movie its poster.
    val wide = item.isEpisode
    Row(horizontalArrangement = Arrangement.spacedBy(if (isTv) 28.dp else 16.dp), verticalAlignment = Alignment.Bottom) {
        Poster(
            item.imageUrl,
            item.title,
            Modifier.width(if (wide) (if (isTv) 240.dp else 160.dp) else (if (isTv) 120.dp else 104.dp)).aspectRatio(if (wide) 16f / 9f else 2f / 3f),
        )
        Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.weight(1f)) {
            HeroTitle(item.artwork.logoUrl, item.title)
            lineUnderLogo(item.detail, item.title, item.isEpisode, item.artwork.logoUrl != null)?.let {
                androidx.compose.material3.Text(it, style = typography.titleLarge, color = theme.text)
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
    Box(Modifier.padding(top = 8.dp)) {
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

/** From this width the actions sit two to a row. */
private const val WIDE_DP = 600
