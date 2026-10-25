package tv.anstelle.feature.ondemand

import androidx.compose.foundation.lazy.rememberLazyListState
import tv.anstelle.core.model.MediaProgress
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
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
import tv.anstelle.core.data.ondemand.EpisodeEntry
import tv.anstelle.core.data.ondemand.EpisodePage
import tv.anstelle.core.data.ondemand.OnDemandRepository
import tv.anstelle.core.data.ondemand.WatchMarks
import tv.anstelle.core.data.playback.PlaybackReporter
import tv.anstelle.core.theme.components.goToSeriesAction
import tv.anstelle.core.theme.components.requestWhenReady
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
    private val time: TimeSource,
    private val repository: OnDemandRepository,
    reporter: PlaybackReporter,
) : ViewModel() {
    /** Matches the `itemId` argument of the navigation route. */
    private val itemId: Long = checkNotNull(savedState.get<Long>("itemId"))

    private val _state = MutableStateFlow(EpisodeUiState())
    val state: StateFlow<EpisodeUiState> = _state.asStateFlow()

    private var profileId = 0L
    val watchEditor = WatchedEditor(repository, viewModelScope)

    fun setWatched(watched: Boolean) {
        val page = _state.value.page ?: return
        watchEditor.setWatched(page.item.id, profileId, watched, ::refreshWatch) { ids ->
            _state.update { it.copy(watch = it.watch.withPlayed(ids, watched)) }
        }
    }

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
        val revision = watchEditor.revision
        viewModelScope.launch {
            val watch = repository.watchMarks(page.item.id, page.episodes, profileId)
            if (revision == watchEditor.revision && !watchEditor.state.value.saving) {
                _state.update { it.copy(watch = if (watch.known) watch else it.watch.copy(known = false)) }
            }
        }
    }
}

/**
 * An episode's own page, opened from its show's carousel on a channel's or series' page: the
 * shared detail sheet over its backdrop, usually its series'. Logo, episode title, facts, whole synopsis,
 * watch strip and action keys precede the season's carousel opened on this episode,
 * so the next one is a press away. OK on another episode opens its page in this one's place, so
 * Back still returns to the page that opened the first.
 */
@Composable
fun EpisodeScreen(
    onPlay: (itemId: Long, positionMs: Long) -> Unit,
    onOpenEpisode: (itemId: Long) -> Unit,
    onOpenChannel: (channelId: Long) -> Unit,
    onOpenSeries: (seriesId: Long) -> Unit,
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
    val scope = rememberCoroutineScope()
    val openEpisode = rememberEpisodeOpener(viewModel::airingChannel, onOpenChannel, onOpenEpisode)
    val watch = state.watch
    val edit by viewModel.watchEditor.state.collectAsStateWithLifecycle()
    val item = page.item
    val listState = rememberLazyListState()
    // The episode last held, so a closed dialog lands on it again.
    val returnFocus = remember { FocusRequester() }
    var lastTouched by rememberSaveable { mutableStateOf<Long?>(null) }
    var holding by remember { mutableStateOf<EpisodeEntry?>(null) }
    val firstFocus = rememberProgrammeFocus(item.id, listState, lastTouched, returnFocus)

    val self = page.episodes.firstOrNull { it.id == item.id }
    val seasons = remember(page) { page.episodes.groupBy { it.seasonLabel } }
    val navigation = rememberEpisodeNavigation(listState, seasons.size, firstFocus)
    var season by rememberSaveable(item.id) { mutableStateOf(self?.seasonLabel ?: seasons.keys.firstOrNull()) }
    val resume = watch.resumeMs(item.id, item.durationMs)
    val watched = watch.watched(item.id, item.durationMs)
    val actions = playActions(item.id, resume, onPlay) + listOfNotNull(
        watchedAction(watched, edit.saving, watch.known) { viewModel.setWatched(!watched) },
        page.seriesId?.let { seriesId -> SheetAction(goToSeriesAction { onOpenSeries(seriesId) }) },
        addToChannelAction(item.id, onAddToChannel),
    )

    Page(
        backdropUrl = item.artwork.backdropUrl,
        hero = {
            ProgrammeSheet(
                title = item.title,
                logoUrl = item.artwork.logoUrl,
                detail = item.detail,
                readout = { MediaFacts(item.year, item.rating, item.genres, item.durationMs) },
                overview = item.overview,
                error = edit.error,
                navigation = navigation,
                progress = MediaProgress(item.durationMs, resume),
                bars = { WatchKeys(actions, listState, firstFocus, navigation = navigation, onFocused = { lastTouched = null }) },
            )
        },
        modifier = modifier,
        listState = listState,
        seasonTabs = if (page.episodes.isNotEmpty()) {
            { EpisodesHeading(seasons, season, watch, navigation = navigation) { season = it } }
        } else null,
    ) {
        if (page.episodes.isNotEmpty()) {
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
                                firstFocus.requestWhenReady()
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
                    navigation = navigation,
                )
            }
        }
    }

    holding?.let { episode ->
        HeldEpisodeDialog(item.title, episode, watch, returnFocus, onPlay, onAddToChannel) { holding = null }
    }
}
