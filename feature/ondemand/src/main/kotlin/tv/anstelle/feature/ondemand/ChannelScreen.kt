package tv.anstelle.feature.ondemand

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
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
import tv.anstelle.core.data.ondemand.WatchChoice
import tv.anstelle.core.data.ondemand.WatchChoices
import tv.anstelle.core.data.ondemand.WatchKind
import tv.anstelle.core.data.ondemand.WatchMarks
import tv.anstelle.core.data.playback.PlaybackReporter
import tv.anstelle.core.model.Channel
import tv.anstelle.core.theme.LocalAnstelleTheme
import tv.anstelle.core.theme.LocalIsTv
import tv.anstelle.core.theme.components.ActionDialog
import tv.anstelle.core.theme.components.ChannelLogo
import tv.anstelle.core.theme.components.DialogAction
import tv.anstelle.core.theme.components.NumberBlock
import tv.anstelle.core.theme.components.RestrictedMark
import tv.anstelle.core.theme.components.StandInSize
import tv.anstelle.core.theme.components.WatchIcons
import tv.anstelle.core.theme.components.obscured
import tv.anstelle.core.theme.components.requestWhenReady
import tv.anstelle.core.theme.components.Notice
import tv.anstelle.core.theme.components.StatusLine
import tv.anstelle.core.theme.components.WatchStrip
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
    val watchEditor = WatchedEditor(repository, viewModelScope)

    fun setWatched(watched: Boolean) {
        val page = _state.value.page ?: return
        if (page.restriction != null) return
        watchEditor.setWatched(page.item.id, profileId, watched, ::refreshWatch) { ids ->
            _state.update { it.copy(watch = it.watch.withPlayed(ids, watched)) }
        }
    }

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
        val revision = watchEditor.revision
        viewModelScope.launch {
            val watch = repository.watchMarks(page, profileId)
            if (revision == watchEditor.revision && !watchEditor.state.value.saving) {
                _state.update { it.copy(watch = if (watch.known) watch else it.watch.copy(known = false)) }
            }
        }
    }
}

/**
 * A channel in On demand, as the listing card of the program it airs now (or next, while it is
 * off air): the picture with the airing's ladder, the channel's ident, the logo, a readout of when
 * it airs and what it is, the ways to watch as bars, the whole synopsis, and for a show its
 * episodes as a carousel, opened at the one on the channel. Only the sheet says what the channel
 * airs; the carousel says what was watched on demand. OK on an episode opens its own page;
 * holding it (or Menu) offers the quick ways to play it.
 */
@Composable
fun ChannelScreen(
    onWatchLive: (channelNumber: Int) -> Unit,
    onStartOver: (channelNumber: Int) -> Unit,
    onPlay: (itemId: Long, positionMs: Long) -> Unit,
    onOpenEpisode: (itemId: Long) -> Unit,
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
    val isTv = LocalIsTv.current
    val edit by viewModel.watchEditor.state.collectAsStateWithLifecycle()
    val clock = rememberClock()
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val firstFocus = remember { FocusRequester() }
    // The episode last opened or held, so Back and a closed dialog land on it again.
    val returnFocus = remember { FocusRequester() }
    var lastTouched by rememberSaveable { mutableStateOf<Long?>(null) }
    var holding by remember { mutableStateOf<EpisodeEntry?>(null) }
    if (isTv) {
        LaunchedEffect(page.channel.id, page.startUtcMs) {
            listState.scrollToItem(0)
            firstFocus.requestWhenReady()
        }
    }

    val item = page.item
    val restricted = page.restriction != null
    val topicEpisode = page.episodes.firstOrNull { it.id == item.id }
    val seasons = remember(page) { page.episodes.groupBy { it.seasonLabel } }
    val navigation = rememberEpisodeNavigation(listState, restricted || item.overview != null || edit.error != null, seasons.size, firstFocus)
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
        SheetAction(choice.title, choice.icon(), pinned = choice.leavesChannel && choice.positionMs > 0) {
            lastTouched = null
            act(choice, item.id)
        }
    } + listOfNotNull(
        SheetAction("Add to a channel", WatchIcons.AddToChannel) { onAddToChannel?.invoke(item.id) }.takeIf { onAddToChannel != null },
    )

    Page(
        backdropUrl = item.artwork.backdropUrl,
        hero = {
            val shape = if (item.isEpisode) PictureShape.STILL else PictureShape.POSTER
            val tense = if (page.onNow) AiringTense.of(page.startUtcMs, page.endUtcMs, clock.value) else AiringTense.LATER
            ProgrammeSheet(
                shape = shape,
                // A restricted program's placeholder shows no letter of its title.
                picture = { slot ->
                    SheetPicture(
                        item.imageUrl, item.title.takeUnless { restricted }.orEmpty(), slot,
                        foot = if (!restricted && tense == AiringTense.ON_NOW) ({
                            WatchStrip(
                                progress(page.startUtcMs, page.endUtcMs, clock.value),
                                watched = false,
                                remaining = remainingTime(page.endUtcMs - clock.value),
                            )
                        }) else null,
                    )
                },
                title = {
                    ChannelIdent(page.channel)
                    // A restricted program's title, logo and episode are blurred.
                    val theme = LocalAnstelleTheme.current
                    SheetLogo(item.artwork.logoUrl, item.title, Modifier.obscured(restricted, theme.textMuted, radius = 12.dp))
                    SheetTitle(item.title, Modifier.obscured(restricted, theme.textMuted, radius = 12.dp))
                    if (item.isEpisode) item.detail?.let { EpisodeLine(it, Modifier.obscured(restricted, theme.textMuted, radius = 6.dp)) }
                },
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
                bars = { WatchKeys(actions, listState, firstFocus, navigation = navigation) },
            )
        },
        modifier = modifier,
        listState = listState,
    ) {
        if (restricted || item.overview != null || edit.error != null) {
            item(key = "synopsis") {
                Column(Modifier.padding(top = if (isTv) 12.dp else 8.dp)) {
                    edit.error?.let { Notice(it, isError = true) }
                    // For a program the profile may not watch, the synopsis is blacked out with the pictures.
                    page.restriction?.let { RestrictedMark(it.title, size = StandInSize.LINE, alignStart = true) }
                    item.overview?.let { Synopsis(it) }
                }
            }
        }
        if (page.episodes.isNotEmpty()) {
            item(key = "episodes-heading") {
                EpisodesHeading(seasons, season, WatchMarks(), Modifier.padding(top = if (isTv) 20.dp else 14.dp), navigation) { season = it }
            }
            item(key = "episodes") {
                EpisodeCarousel(
                    episodes = seasons[season].orEmpty(),
                    watch = state.watch,
                    showWatched = false,
                    target = lastTouched ?: topicEpisode?.id,
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
        val resume = state.watch.resumeMs(episode.id, episode.durationMs)
        // The episode on the channel offers what the page's bars do, live included.
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
                    act(choice, episode.id)
                }
            },
            onDismiss = dismiss,
        ) { EpisodeDialogHeader(item.title, episode) }
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

/**
 * The readout of the page's program: when it airs on the channel (the square and the label as
 * the banner's NOW line), what is left, then what it is. A program the profile may not watch says
 * only when the channel is dark: never its rating.
 */
private fun channelReadout(page: ChannelPage, tense: AiringTense, nowMs: Long): List<Segment> {
    val item = page.item
    val range = WatchChoices.clockRange(page.startUtcMs, page.endUtcMs)
    val status = when (tense) {
        AiringTense.ON_NOW -> Segment("On now", range, SegmentMark.ON_AIR)
        // Off air, the page is about what comes next; opened from the guide, about any airing to come.
        AiringTense.LATER -> Segment(if (page.onNow) "Later" else "Up next", range, SegmentMark.UP_NEXT)
        AiringTense.AIRED -> Segment("Aired", range, SegmentMark.AIRED)
    }
    if (page.restriction != null) return listOf(status)
    return listOfNotNull(
        status,
        item.year?.let { Segment("Year", "$it") },
        item.rating?.let { Segment("Rated", it) },
        item.genres.take(2).takeIf { it.isNotEmpty() }?.let { Segment("Genre", it.joinToString(", ")) },
    )
}

internal fun WatchChoice.icon() = when {
    startsOver -> WatchIcons.StartOver
    else -> when (kind) {
    WatchKind.LIVE -> WatchIcons.Live
    WatchKind.RESTART -> WatchIcons.StartOver
    WatchKind.ADD_TO_CHANNEL -> WatchIcons.AddToChannel
    WatchKind.PLAY -> WatchIcons.play(positionMs, leavesChannel)
    }
}

/** The header of a held episode's choices: the show, then the episode with its length. */
@Composable
internal fun EpisodeDialogHeader(show: String, episode: EpisodeEntry) {
    val theme = LocalAnstelleTheme.current
    val typography = androidx.compose.material3.MaterialTheme.typography
    androidx.compose.material3.Text(show, style = typography.headlineSmall, color = theme.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
    androidx.compose.material3.Text(
        listOfNotNull(episode.label.replace(" - ", "  "), episode.facts).joinToString("  "),
        style = typography.bodyMedium,
        color = theme.textMuted,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
}
