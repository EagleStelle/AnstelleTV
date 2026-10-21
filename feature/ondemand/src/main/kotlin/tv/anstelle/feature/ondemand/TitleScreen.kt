package tv.anstelle.feature.ondemand

import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import tv.anstelle.core.data.ProfileRepository
import tv.anstelle.core.data.ondemand.CatalogRepository
import tv.anstelle.core.data.ondemand.EpisodeEntry
import tv.anstelle.core.data.ondemand.NextEpisode
import tv.anstelle.core.data.ondemand.OnDemandRepository
import tv.anstelle.core.data.ondemand.TitlePage
import tv.anstelle.core.data.ondemand.WatchChoices
import tv.anstelle.core.model.WatchKind
import tv.anstelle.core.data.ondemand.WatchMarks
import tv.anstelle.core.data.playback.PlaybackReporter
import tv.anstelle.core.model.MediaProgress
import tv.anstelle.core.theme.components.EpisodeActionsDialog
import tv.anstelle.core.theme.components.WatchIcons
import tv.anstelle.core.theme.components.splitEpisodeLabel
import javax.inject.Inject

@Immutable
data class TitleUiState(
    val page: TitlePage? = null,
    val loaded: Boolean = false,
    /** Jellyfin's resume points and played marks for the movie, or for every episode of the series. */
    val watch: WatchMarks = WatchMarks(),
)

@HiltViewModel
class TitleViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val profiles: ProfileRepository,
    private val time: tv.anstelle.core.common.TimeSource,
    catalog: CatalogRepository,
    private val repository: OnDemandRepository,
    reporter: PlaybackReporter,
) : ViewModel() {
    /** Matches the `titleId` argument of the navigation route. */
    private val titleId: Long = checkNotNull(savedState.get<Long>("titleId"))

    private val _state = MutableStateFlow(TitleUiState())
    val state: StateFlow<TitleUiState> = _state.asStateFlow()

    private var profileId = 0L
    val watchEditor = WatchedEditor(repository, viewModelScope)

    fun setWatched(watched: Boolean) {
        val page = _state.value.page ?: return
        watchEditor.setWatched(page.id, profileId, watched, ::refreshWatch) { ids ->
            _state.update { it.copy(watch = it.watch.withPlayed(ids, watched)) }
        }
    }

    init {
        viewModelScope.launch {
            val profile = profiles.activeProfile()
            profileId = profile.id
            _state.value = TitleUiState(catalog.titlePage(titleId, profiles.access(profile.id)), loaded = true)
            refreshWatch()
        }
        // Coming back from the player can beat its last report to Jellyfin: read again once that lands.
        viewModelScope.launch {
            reporter.reported.collect { id ->
                val page = _state.value.page ?: return@collect
                if (id == page.id || page.episodes.any { it.id == id }) refreshWatch()
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
            val watch = repository.watchMarks(page.id, page.episodes, profileId)
            if (revision == watchEditor.revision && !watchEditor.state.value.saving) {
                _state.update { it.copy(watch = if (watch.known) watch else it.watch.copy(known = false)) }
            }
        }
    }
}

/**
 * A movie's or series' details over its backdrop: logo, facts, whole synopsis, watch strip and
 * action keys. A movie resumes where it was left or plays from the start; a series goes on
 * with its next episode, and shows every episode as a carousel by season, opened at that
 * episode. OK on an episode opens its own page; holding it (or Menu) offers the quick ways to
 * play it.
 */
@Composable
fun TitleScreen(
    onPlay: (itemId: Long, positionMs: Long) -> Unit,
    onOpenEpisode: (itemId: Long) -> Unit,
    onOpenChannel: (channelId: Long) -> Unit,
    /** Null for a member: adding to a channel is the owner's and admins'. */
    onAddToChannel: ((itemId: Long) -> Unit)?,
    onGone: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: TitleViewModel = hiltViewModel(),
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
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    // An episode airing on a channel right now opens that channel's page, not its own.
    val openEpisode: (Long) -> Unit = { id ->
        scope.launch {
            val channel = viewModel.airingChannel(id)
            if (channel != null) onOpenChannel(channel) else onOpenEpisode(id)
        }
    }
    val watch = state.watch
    val edit by viewModel.watchEditor.state.collectAsStateWithLifecycle()
    val watched = if (page.kind.episodic) page.episodes.isNotEmpty() && page.episodes.all { watch.watched(it.id, it.durationMs) } else watch.watched(page.id, page.durationMs)
    val resume = watch.resumeMs(page.id, page.durationMs)
    val listState = rememberLazyListState()
    // The episode last opened or held, so Back and a closed dialog land on it again.
    val returnFocus = remember { FocusRequester() }
    var lastTouched by rememberSaveable { mutableStateOf<Long?>(null) }
    var holding by remember { mutableStateOf<EpisodeEntry?>(null) }
    val firstFocus = rememberProgrammeFocus(page.id, listState, lastTouched, returnFocus)

    val next = remember(page, watch) { NextEpisode.of(page.episodes, watch) }
    val seasons = remember(page) { page.episodes.groupBy { it.seasonLabel } }
    val navigation = rememberEpisodeNavigation(listState, seasons.size, firstFocus, hasDescription = page.overview != null)
    var season by rememberSaveable { mutableStateOf<String?>(null) }
    val openSeason = season ?: next?.seasonLabel ?: seasons.keys.firstOrNull()
    val actions = titleActions(page, next, watch) { itemId, positionMs ->
        lastTouched = null
        onPlay(itemId, positionMs)
    } + listOfNotNull(
        watchedAction(watched, edit.saving, watch.known) { viewModel.setWatched(!watched) }.takeIf { !page.kind.episodic || page.episodes.isNotEmpty() },
        addToChannelAction(page.id, onAddToChannel),
    )

    Page(
        backdropUrl = page.artwork.backdropUrl,
        hero = {
            ProgrammeSheet(
                title = page.title,
                logoUrl = page.artwork.logoUrl,
                readout = { MediaFacts(page.year, page.rating, page.genres, page.durationMs,
                    seasonCount = page.seasonCount.takeIf { page.kind.episodic }, episodeCount = page.episodes.size.takeIf { page.kind.episodic }) },
                overview = page.overview,
                error = edit.error,
                navigation = navigation,
                progress = MediaProgress(if (page.kind.episodic) 0 else page.durationMs, resume),
                bars = { WatchKeys(actions, listState, firstFocus, navigation = navigation, onFocused = { lastTouched = null }) },
            )
        },
        modifier = modifier,
        listState = listState,
        seasonTabs = if (page.episodes.isNotEmpty()) {
            { EpisodesHeading(seasons, openSeason, watch, navigation = navigation) { season = it } }
        } else null,
    ) {
        if (page.episodes.isNotEmpty()) {
            item(key = "episodes") {
                EpisodeCarousel(
                    episodes = seasons[openSeason].orEmpty(),
                    watch = watch,
                    target = lastTouched ?: next?.id,
                    onOpen = {
                        lastTouched = it.id
                        openEpisode(it.id)
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
            show = page.title,
            label = episode.label,
            facts = episode.facts,
            choices = WatchChoices.episodeActions(watch.resumeMs(episode.id, episode.durationMs), canAddToChannel = onAddToChannel != null),
            returnFocus = returnFocus,
            onDismiss = { holding = null },
            onAction = { choice ->
                when (choice.kind) {
                    WatchKind.ADD_TO_CHANNEL -> onAddToChannel?.invoke(episode.id)
                    else -> onPlay(episode.id, choice.positionMs)
                }
            },
        )
    }
}

/**
 * The page's ways to watch: a movie's Resume (or Play) and From the start; a series' next
 * episode, resumed or played, and its first episode from the start.
 */
private fun titleActions(page: TitlePage, next: EpisodeEntry?, watch: WatchMarks, onPlay: (Long, Long) -> Unit): List<SheetAction> {
    if (!page.kind.episodic) {
        val resume = watch.resumeMs(page.id, page.durationMs)
        return playActions(page.id, resume, onPlay)
    }
    next ?: return emptyList()
    val resume = watch.resumeMs(next.id, next.durationMs)
    val number = splitEpisodeLabel(next.label).first
    val first = page.episodes.firstOrNull { it.season != 0 } ?: page.episodes.first()
    return listOfNotNull(
        SheetAction(listOfNotNull(if (resume > 0) "Resume" else "Play", number).joinToString(" "), WatchIcons.play(resume, false)) { onPlay(next.id, resume) },
        // The show from its first episode, once going on with it would start somewhere else.
        SheetAction("From the start", WatchIcons.StartOver) { onPlay(first.id, 0) }.takeIf { next.id != first.id || resume > 0 },
    )
}
