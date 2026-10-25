package tv.anstelle.feature.ondemand

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.TextStyle
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
import tv.anstelle.core.data.ondemand.AiringTense
import tv.anstelle.core.data.ondemand.ChannelPage
import tv.anstelle.core.data.ondemand.EpisodeEntry
import tv.anstelle.core.data.ondemand.OnDemandRepository
import tv.anstelle.core.model.WatchChoice
import tv.anstelle.core.data.ondemand.WatchChoices
import tv.anstelle.core.model.WatchKind
import tv.anstelle.core.data.ondemand.WatchMarks
import tv.anstelle.core.data.playback.PlaybackReporter
import tv.anstelle.core.model.Channel
import tv.anstelle.core.model.MediaProgress
import tv.anstelle.core.theme.LocalAnstelleTheme
import tv.anstelle.core.theme.LocalIsTv
import tv.anstelle.core.theme.components.ChannelLogo
import tv.anstelle.core.theme.components.EpisodeActionsDialog
import tv.anstelle.core.theme.components.goToSeriesAction
import tv.anstelle.core.theme.components.icon
import tv.anstelle.core.theme.components.onDemandNavigation
import tv.anstelle.core.theme.components.NumberBlock
import tv.anstelle.core.theme.components.StatusLine
import javax.inject.Inject

@Immutable
data class ChannelUiState(
    val page: ChannelPage? = null,
    val loaded: Boolean = false,
    /** When the page was read: the program stays the page's topic even after it ends. */
    val openedAtMs: Long = 0,
    /** Jellyfin's resume points and played marks for the program and its show's episodes. */
    val watch: WatchMarks = WatchMarks(),
)

@HiltViewModel
class ChannelViewModel @Inject constructor(
    savedState: SavedStateHandle,
    profiles: ProfileRepository,
    private val repository: OnDemandRepository,
    reporter: PlaybackReporter,
    time: TimeSource,
) : ViewModel() {
    /** Matches the `channelId` argument of the navigation route. */
    private val channelId: Long = checkNotNull(savedState.get<Long>("channelId"))

    /** Matches the route's `atUtcMs`: the airing the page is about, or 0 for the one on now. */
    private val atUtcMs: Long = savedState.get<Long>("atUtcMs") ?: 0L

    private val _state = MutableStateFlow(ChannelUiState())
    val state: StateFlow<ChannelUiState> = _state.asStateFlow()

    private var profileId = 0L

    init {
        viewModelScope.launch {
            val profile = profiles.activeProfile()
            profileId = profile.id
            val access = profiles.access(profile.id)
            val now = time.nowUtcMs()
            val at = atUtcMs.takeIf { it > 0 } ?: now
            _state.value = ChannelUiState(repository.channelPage(channelId, at, access), loaded = true, openedAtMs = now)
            refreshWatch()
        }
        // Coming back from the player can beat its last report to Jellyfin: read again once that lands.
        viewModelScope.launch {
            reporter.reported.collect { id ->
                val page = _state.value.page ?: return@collect
                if (id == page.item.id || page.episodes.any { it.id == id }) refreshWatch()
            }
        }
    }

    /** Reads what was watched again, e.g. after watching some of it. */
    fun refreshWatch() {
        val page = _state.value.page ?: return
        viewModelScope.launch {
            val watch = repository.watchMarks(page, profileId)
            _state.update { it.copy(watch = if (watch.known) watch else it.watch.copy(known = false)) }
        }
    }
}

/**
 * A channel in On demand, as the listing card of the program it airs now (or next, while it is
 * off air): backdrop, channel ident, logo, airing and media facts, whole synopsis, airing ladder
 * and action keys. A show adds its
 * episodes as a carousel, opened at the one on the channel, ringed as the page's current one. Only
 * the sheet says when the channel airs it; the carousel says what was watched on demand. OK on an episode opens its own page;
 * holding it (or Menu) offers the quick ways to play it.
 */
@Composable
fun ChannelScreen(
    onWatchLive: (channelNumber: Int) -> Unit,
    onStartOver: (channelNumber: Int) -> Unit,
    onPlay: (itemId: Long, positionMs: Long) -> Unit,
    onOpenEpisode: (itemId: Long) -> Unit,
    /** Opens a title's page in the on-demand library: the movie on the channel, or its episode's series. */
    onOpenTitle: (titleId: Long) -> Unit,
    /** Null for a member: adding to a channel is the owner's and admins'. */
    onAddToChannel: ((itemId: Long) -> Unit)?,
    onGone: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ChannelViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LifecycleResumeEffect(viewModel) {
        viewModel.refreshWatch()
        onPauseOrDispose {}
    }
    val page = state.page
    if (page == null) {
        if (state.loaded) LaunchedEffect(Unit) { onGone() }
        return
    }
    val clock = rememberClock()
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    // The episode last opened or held, so Back and a closed dialog land on it again.
    val returnFocus = remember { FocusRequester() }
    var lastTouched by rememberSaveable { mutableStateOf<Long?>(null) }
    var holding by remember { mutableStateOf<EpisodeEntry?>(null) }
    val firstFocus = rememberProgrammeFocus(page.channel.id to page.startUtcMs, listState, lastTouched, returnFocus)

    val item = page.item
    val restricted = page.restriction != null
    val topicEpisode = page.episodes.firstOrNull { it.id == item.id }
    val seasons = remember(page) { page.episodes.groupBy { it.seasonLabel } }
    val navigation = rememberEpisodeNavigation(listState, seasons.size, firstFocus, hasDescription = item.overview != null)
    var season by rememberSaveable { mutableStateOf(topicEpisode?.seasonLabel ?: seasons.keys.firstOrNull()) }
    val choices = topicChoices(page, state.openedAtMs)

    fun act(choice: WatchChoice, itemId: Long) {
        when (choice.kind) {
            WatchKind.LIVE -> onWatchLive(page.channel.number)
            WatchKind.RESTART -> onStartOver(page.channel.number)
            WatchKind.PLAY -> onPlay(itemId, choice.positionMs)
            WatchKind.ADD_TO_CHANNEL -> onAddToChannel?.invoke(itemId)
        }
    }

    // The ways to watch, then adding the show to a channel, as one row of keys.
    val actions = choices.map { choice ->
        SheetAction(choice.title, choice.icon(), pinned = choice.fromAiring && choice.positionMs > 0, detail = choice.detail) {
            lastTouched = null
            act(choice, item.id)
        }
    } + listOfNotNull(
        // An episode leads to its series; only a movie opens on demand itself.
        if (item.isEpisode) {
            page.seriesId?.takeIf { !restricted }?.let { seriesId -> SheetAction(goToSeriesAction { onOpenTitle(seriesId) }) }
        } else {
            choices.onDemandNavigation(isEpisode = false) { onOpenTitle(item.id) }?.let { SheetAction(it) }
        },
        addToChannelAction(item.id, onAddToChannel),
    )

    Page(
        backdropUrl = item.artwork.backdropUrl,
        hero = {
            val tense = if (page.onNow) AiringTense.of(page.startUtcMs, page.endUtcMs, clock.value) else AiringTense.LATER
            ProgrammeSheet(
                title = item.title,
                logoUrl = item.artwork.logoUrl,
                detail = item.detail.takeIf { item.isEpisode },
                ident = { ChannelIdent(page.channel) },
                readout = {
                    androidx.compose.foundation.layout.Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        StatusLine(
                            onAir = when (tense) { AiringTense.ON_NOW -> true; AiringTense.LATER -> false; AiringTense.AIRED -> null },
                            label = when (tense) { AiringTense.ON_NOW -> "On now"; AiringTense.LATER -> if (page.onNow) "Later" else "Up next"; AiringTense.AIRED -> "Aired" },
                            text = WatchChoices.clockRange(page.startUtcMs, page.endUtcMs),
                        )
                        if (!restricted) MediaFacts(item.year, item.rating, item.genres, item.durationMs)
                    }
                },
                overview = item.overview,
                restriction = page.restriction,
                navigation = navigation,
                progress = MediaProgress(page.endUtcMs - page.startUtcMs, clock.value - page.startUtcMs)
                    .takeIf { !restricted && tense == AiringTense.ON_NOW },
                bars = {
                    WatchKeys(
                        actions, listState, firstFocus, navigation = navigation, onFocused = { lastTouched = null },
                    )
                },
            )
        },
        modifier = modifier,
        listState = listState,
        seasonTabs = if (page.episodes.isNotEmpty()) {
            { EpisodesHeading(seasons, season, state.watch, navigation = navigation) { season = it } }
        } else null,
    ) {
        if (page.episodes.isNotEmpty()) {
            item(key = "episodes") {
                EpisodeCarousel(
                    episodes = seasons[season].orEmpty(),
                    watch = state.watch,
                    target = lastTouched ?: topicEpisode?.id,
                    current = topicEpisode?.id,
                    onOpen = {
                        lastTouched = it.id
                        onOpenEpisode(it.id)
                    },
                    onHold = {
                        lastTouched = it.id
                        holding = it
                    },
                    returnTo = lastTouched,
                    returnFocus = returnFocus,
                    navigation = navigation,
                )
            }
        }
    }

    holding?.let { episode ->
        EpisodeActionsDialog(
            show = item.title,
            label = episode.label,
            facts = episode.facts,
            // The episode on the channel offers what the page's bars do, live included.
            choices = if (episode.id == item.id) {
                choices + listOfNotNull(WatchChoices.addToChannel(isMovie = false).takeIf { onAddToChannel != null })
            } else {
                WatchChoices.episodeActions(state.watch.resumeMs(episode.id, episode.durationMs), canAddToChannel = onAddToChannel != null)
            },
            returnFocus = returnFocus,
            onDismiss = { holding = null },
            onAction = { choice -> act(choice, episode.id) },
        )
    }
}

/**
 * The channel as its banner names it, at the top of the sheet: its logo when it has one, the
 * number block and the name.
 */
@Composable
private fun ChannelIdent(channel: Channel) {
    val theme = LocalAnstelleTheme.current
    val isTv = LocalIsTv.current
    val typography = androidx.compose.material3.MaterialTheme.typography
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        channel.logoUri?.let { ChannelLogo(it, height = if (isTv) 30.dp else 24.dp, alignment = Alignment.CenterStart) }
        NumberBlock(
            "${channel.number}",
            style = typography.titleMedium.merge(TextStyle(fontFamily = theme.displayFont, fontFeatureSettings = "tnum")),
        )
        androidx.compose.material3.Text(
            channel.name,
            style = typography.titleMedium,
            color = theme.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
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
    val watch = WatchChoices.airing(
        tense, page.channel.number, page.channel.name, page.startUtcMs, page.endUtcMs, nowMs,
        isEpisode = item.isEpisode,
        restrictionTitle = page.restriction?.title,
    )
    // Off air now, or the program has ended since the page opened: the channel is elsewhere.
    return listOfNotNull(WatchChoices.liveOffAir(page.startUtcMs).takeIf { !page.onNow && page.restriction == null }) + watch
}
