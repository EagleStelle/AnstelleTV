package tv.anstelle.feature.ondemand

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
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
import tv.anstelle.core.data.ProfileRepository
import tv.anstelle.core.data.ondemand.EpisodeEntry
import tv.anstelle.core.data.ondemand.EpisodePage
import tv.anstelle.core.data.ondemand.OnDemandRepository
import tv.anstelle.core.data.ondemand.WatchChoices
import tv.anstelle.core.data.ondemand.WatchKind
import tv.anstelle.core.data.ondemand.WatchMarks
import tv.anstelle.core.data.playback.PlaybackReporter
import tv.anstelle.core.theme.LocalIsTv
import tv.anstelle.core.theme.components.ActionDialog
import tv.anstelle.core.theme.components.DialogAction
import tv.anstelle.core.theme.components.WatchIcons
import tv.anstelle.core.theme.components.WatchStrip
import javax.inject.Inject

@Immutable
data class EpisodeUiState(
    val page: EpisodePage? = null,
    val loaded: Boolean = false,
    /** Jellyfin's resume points and played marks for every episode of the show. */
    val watch: WatchMarks = WatchMarks(),
)

@HiltViewModel
class EpisodeViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val profiles: ProfileRepository,
    private val time: tv.anstelle.core.common.TimeSource,
    private val repository: OnDemandRepository,
    reporter: PlaybackReporter,
) : ViewModel() {
    /** Matches the `itemId` argument of the navigation route. */
    private val itemId: Long = checkNotNull(savedState.get<Long>("itemId"))

    private val _state = MutableStateFlow(EpisodeUiState())
    val state: StateFlow<EpisodeUiState> = _state.asStateFlow()

    private var profileId = 0L

    init {
        viewModelScope.launch {
            val profile = profiles.activeProfile()
            profileId = profile.id
            _state.value = EpisodeUiState(repository.episodePage(itemId, profiles.access(profile.id)), loaded = true)
            refreshWatch()
        }
        // Coming back from the player can beat its last report to Jellyfin: read again once that lands.
        viewModelScope.launch {
            reporter.reported.collect { id ->
                val page = _state.value.page ?: return@collect
                if (page.episodes.any { it.id == id }) refreshWatch()
            }
        }
    }

    /** The channel airing episode [itemId] now, whose page opens in place of the episode's; null when none does. */
    suspend fun airingChannel(itemId: Long): Long? =
        repository.airingChannel(itemId, time.nowUtcMs(), profiles.access(profiles.activeProfile().id))

    /** Reads what was watched again, e.g. after watching some of it. */
    fun refreshWatch() {
        val page = _state.value.page ?: return
        viewModelScope.launch {
            val watch = repository.watchMarks(page.item.id, page.episodes, profileId)
            _state.update { it.copy(watch = watch) }
        }
    }
}

/**
 * An episode's own page, opened from its show's carousel on a channel's or series' page: the
 * channel page's sheet without the channel. Its still with what was watched at its foot, the
 * show's logo, the episode, a readout of what was watched and what it is, Resume (or Play) and
 * From the start as bars, the whole synopsis, then the season's carousel opened on this episode,
 * so the next one is a press away. OK on another episode opens its page in this one's place, so
 * Back still returns to the page that opened the first.
 */
@Composable
fun EpisodeScreen(
    onPlay: (itemId: Long, positionMs: Long) -> Unit,
    onOpenEpisode: (itemId: Long) -> Unit,
    onOpenChannel: (channelId: Long) -> Unit,
    /** Null for a member: adding to a channel is the owner's and admins'. */
    onAddToChannel: ((itemId: Long) -> Unit)?,
    onGone: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: EpisodeViewModel = hiltViewModel(),
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
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    // An episode airing on a channel right now opens that channel's page, not its own.
    val openEpisode: (Long) -> Unit = { id ->
        scope.launch {
            val channel = viewModel.airingChannel(id)
            if (channel != null) onOpenChannel(channel) else onOpenEpisode(id)
        }
        Unit
    }
    val watch = state.watch
    val item = page.item
    val listState = rememberLazyListState()
    val firstFocus = remember { FocusRequester() }
    // The episode last held, so a closed dialog lands on it again.
    val returnFocus = remember { FocusRequester() }
    var lastTouched by rememberSaveable { mutableStateOf<Long?>(null) }
    var holding by remember { mutableStateOf<EpisodeEntry?>(null) }
    if (isTv) {
        LaunchedEffect(Unit) {
            withFrameNanos { }
            runCatching { firstFocus.requestFocus() }
        }
    }

    val self = page.episodes.firstOrNull { it.id == item.id }
    val seasons = remember(page) { page.episodes.groupBy { it.seasonLabel } }
    var season by rememberSaveable { mutableStateOf(self?.seasonLabel ?: seasons.keys.firstOrNull()) }
    val resume = watch.resumeMs(item.id, item.durationMs)
    val watched = watch.watched(item.id, item.durationMs)
    val actions = listOfNotNull(
        if (resume > 0) {
            SheetAction("Resume", WatchIcons.play(resume, false)) { onPlay(item.id, resume) }
        } else {
            SheetAction("Play", WatchIcons.play(0, false)) { onPlay(item.id, 0) }
        },
        SheetAction("From the start", WatchIcons.StartOver) { onPlay(item.id, 0) }.takeIf { resume > 0 },
        SheetAction("Add to a channel", WatchIcons.AddToChannel) { onAddToChannel?.invoke(item.id) }.takeIf { onAddToChannel != null },
    )

    Page(
        backdropUrl = item.artwork.backdropUrl,
        hero = {
            ProgrammeSheet(
                shape = PictureShape.STILL,
                picture = { slot ->
                    val fraction = if (item.durationMs > 0) resume.toFloat() / item.durationMs else 0f
                    SheetPicture(
                        item.imageUrl,
                        item.title,
                        slot,
                        shaded = watched,
                        foot = if (fraction > 0f || watched) ({ WatchStrip(fraction, watched) }) else null,
                    )
                },
                title = {
                    SheetLogo(item.artwork.logoUrl, item.title)
                    item.detail?.let { EpisodeLine(it) }
                },
                readout = { Readout(episodeReadout(page)) },
                timeLeft = WatchChoices.timeLeft(item.durationMs, resume),
                bars = { WatchKeys(actions, listState, firstFocus) },
            )
        },
        modifier = modifier,
        listState = listState,
    ) {
        item.overview?.let { overview ->
            item(key = "synopsis") { Synopsis(overview, Modifier.padding(top = if (isTv) 12.dp else 8.dp)) }
        }
        if (page.episodes.isNotEmpty()) {
            item(key = "episodes-heading") {
                EpisodesHeading(seasons, season, watch, Modifier.padding(top = if (isTv) 20.dp else 14.dp)) { season = it }
            }
            item(key = "episodes") {
                EpisodeCarousel(
                    episodes = seasons[season].orEmpty(),
                    watch = watch,
                    target = lastTouched ?: item.id,
                    current = item.id,
                    onOpen = {
                        if (it.id == item.id) {
                            // This page's own episode: back up to its bars.
                            scope.launch {
                                listState.animateScrollToItem(0)
                                runCatching { firstFocus.requestFocus() }
                            }
                        } else {
                            openEpisode(it.id)
                        }
                    },
                    onHold = {
                        lastTouched = it.id
                        holding = it
                    },
                    returnTo = lastTouched,
                    returnFocus = returnFocus,
                )
            }
        }
    }

    holding?.let { episode ->
        val held = watch.resumeMs(episode.id, episode.durationMs)
        val choices = listOfNotNull(
            held.takeIf { it > 0 }?.let(WatchChoices::resume),
            WatchChoices.playFromStart,
            WatchChoices.addToChannel(isMovie = false).takeIf { onAddToChannel != null },
        )
        val dismiss = {
            holding = null
            runCatching { returnFocus.requestFocus() }
            Unit
        }
        ActionDialog(
            actions = choices.map { choice ->
                DialogAction(choice.title, choice.detail, choice.icon()) {
                    holding = null
                    when (choice.kind) {
                        WatchKind.ADD_TO_CHANNEL -> onAddToChannel?.invoke(episode.id)
                        else -> onPlay(episode.id, choice.positionMs)
                    }
                }
            },
            onDismiss = dismiss,
        ) { EpisodeDialogHeader(item.title, episode) }
    }
}

/** An episode's readout, as its channel page's without the airing: year, rated, genre. */
private fun episodeReadout(page: EpisodePage): List<Segment> {
    val item = page.item
    return listOfNotNull(
        item.year?.let { Segment("Year", "$it") },
        item.rating?.let { Segment("Rated", it) },
        item.genres.take(2).takeIf { it.isNotEmpty() }?.let { Segment("Genre", it.joinToString(", ")) },
    )
}
