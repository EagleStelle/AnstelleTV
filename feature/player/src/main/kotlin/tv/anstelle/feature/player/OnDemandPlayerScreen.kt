package tv.anstelle.feature.player

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.Forward30

import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import tv.anstelle.core.data.ProfileRepository
import tv.anstelle.core.data.lineUnderLogo
import tv.anstelle.core.data.ondemand.EpisodeEntry
import tv.anstelle.core.data.ondemand.OnDemandRepository
import tv.anstelle.core.data.ondemand.WatchChoices
import tv.anstelle.core.data.ondemand.WatchMarks
import tv.anstelle.core.data.playback.ChannelPlayer
import tv.anstelle.core.data.playback.OnDemandPlayer
import tv.anstelle.core.data.playback.OnDemandState
import tv.anstelle.core.data.playback.OnDemandStatus
import tv.anstelle.core.data.playback.PlaybackReporter
import tv.anstelle.core.data.playback.TRACK_TYPE_AUDIO
import tv.anstelle.core.data.playback.TRACK_TYPE_TEXT
import tv.anstelle.core.data.playback.TrackOption
import tv.anstelle.core.data.profiles.Restriction
import tv.anstelle.core.model.ProfileAccess
import tv.anstelle.core.model.MediaProgress
import tv.anstelle.core.model.SegmentType
import tv.anstelle.core.model.WatchChoice
import tv.anstelle.core.model.WatchKind
import tv.anstelle.core.theme.LocalAnstelleTheme
import tv.anstelle.core.theme.LocalIsTv
import tv.anstelle.core.theme.components.RestrictedMark
import tv.anstelle.core.theme.components.StandInSize
import tv.anstelle.core.theme.components.TitleLogo
import tv.anstelle.core.theme.components.osdKeyHeight
import tv.anstelle.core.theme.components.EpisodeActionsDialog
import tv.anstelle.core.theme.components.EpisodeCard
import tv.anstelle.core.theme.components.SeasonTabs
import tv.anstelle.core.theme.components.episodeCardSpacing

import javax.inject.Inject

/** The show playing, for the banner's episode strip: every episode the profile may watch, and what of each it watched. */
@Immutable
internal data class ShowEpisodes(val episodes: List<EpisodeEntry>, val watch: WatchMarks = WatchMarks())

@HiltViewModel
class OnDemandPlayerViewModel @Inject constructor(
    savedState: SavedStateHandle,
    profiles: ProfileRepository,
    channels: ChannelPlayer,
    reporter: PlaybackReporter,
    private val playback: OnDemandPlayer,
    private val onDemand: OnDemandRepository,
) : ViewModel() {
    /** Match the `itemId` and `positionMs` arguments of the navigation route. */
    private val itemId: Long = checkNotNull(savedState.get<Long>("itemId"))
    private var startMs: Long = savedState.get<Long>("positionMs") ?: 0L

    val state = playback.state
    val player get() = playback.player

    private val _episodes = MutableStateFlow<ShowEpisodes?>(null)

    /** The show's episodes once the strip first opens; null before. */
    internal val episodes: StateFlow<ShowEpisodes?> = _episodes.asStateFlow()

    private var access: ProfileAccess? = null
    private var profileId = 0L

    init {
        // Many TV devices decode one video at a time: the channel the guide was previewing stops.
        channels.stop()
        viewModelScope.launch {
            val profile = profiles.activeProfile()
            profileId = profile.id
            val profileAccess = profiles.access(profile.id)
            access = profileAccess
            playback.play(itemId, startMs, profileAccess)
        }
        // An episode that ends or is left here is saved after the strip read it: read what was watched again.
        viewModelScope.launch {
            reporter.reported.collect { id ->
                val shown = _episodes.value ?: return@collect
                if (shown.episodes.any { it.id == id }) {
                    val playing = state.value.itemId.takeIf { it != 0L } ?: return@collect
                    _episodes.value = shown.copy(watch = onDemand.watchMarks(playing, shown.episodes, profileId))
                }
            }
        }
    }

    /** Reads the show playing for the episode strip: its episodes once, what was watched every time it opens. */
    fun loadEpisodes() {
        val playing = state.value.itemId.takeIf { it != 0L } ?: return
        val profileAccess = access ?: return
        viewModelScope.launch {
            val list = _episodes.value?.episodes?.takeIf { known -> known.any { it.id == playing } } ?: onDemand.episodeList(playing, profileAccess)
            _episodes.value = ShowEpisodes(list, _episodes.value?.watch ?: WatchMarks())
            _episodes.value = ShowEpisodes(list, onDemand.watchMarks(playing, list, profileId))
        }
    }

    /** Plays another episode of the show from [positionMs]. */
    fun playEpisode(itemId: Long, positionMs: Long) = playback.playEpisode(itemId, positionMs)

    val positionMs: Long get() = playback.positionMs
    val durationMs: Long get() = playback.durationMs

    fun togglePlay() = playback.togglePlay()

    fun seekBy(deltaMs: Long) = playback.seekBy(deltaMs)

    fun seekTo(positionMs: Long) = playback.seekTo(positionMs)

    fun next() = playback.skipToNext()

    fun previous() = playback.restartOrPrevious()

    fun pause() {
        if (playback.state.value.playing) playback.togglePlay()
    }

    fun skip() = playback.skipSegment()

    fun audioOptions(): List<TrackOption> = playback.trackOptions(TRACK_TYPE_AUDIO)

    fun subtitleOptions(): List<TrackOption> = playback.trackOptions(TRACK_TYPE_TEXT)

    fun selectAudio(option: TrackOption) = playback.selectTrack(TRACK_TYPE_AUDIO, option)

    fun selectSubtitles(option: TrackOption) = playback.selectTrack(TRACK_TYPE_TEXT, option)

    override fun onCleared() {
        playback.release()
    }
}

/**
 * Full-screen on-demand playback: OK on the picture pauses and brings the banner in with Play
 * highlighted, left and right jump back 10 s and ahead 30 s, the remote's previous key restarts
 * or steps back through the show's episodes, and next steps forward. The banner carries keys (audio and subtitles on
 * the left; previous, 10 s back, pause, 30 s ahead and next centered in the remote's own order,
 * as icons alone); up, down or Menu moves focus into them. Episodes sits at the right
 * and swaps the keys for the show's seasons and a strip of its episodes. Phones tap for the
 * banner and drag its bar to seek.
 *
 * While a segment the profile is asked to skip plays (an intro, an outro…), a Skip key shows for
 * the whole segment: in the banner under the technical line while it is up, and alone at the
 * bottom end while it is hidden, preselected in rose on touch as on TV. On TV it takes focus as
 * it comes, so OK skips.
 */
@Composable
fun OnDemandPlayerScreen(
    onExit: (itemId: Long, isEpisode: Boolean) -> Unit,
    /** Null for a member: adding to a channel is the owner's and admins'. */
    onAddToChannel: ((itemId: Long) -> Unit)?,
    modifier: Modifier = Modifier,
    viewModel: OnDemandPlayerViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val episodes by viewModel.episodes.collectAsStateWithLifecycle()
    val player = viewModel.player
    val tracks = rememberPlayerTracks(player)
    val audioOptions = remember(state, player, tracks) { viewModel.audioOptions() }
    val subtitleOptions = remember(state, player, tracks) { viewModel.subtitleOptions() }
    val showAudio = audioOptions.size > 1
    val showSubtitles = subtitleOptions.any { it.groupIndex >= 0 }
    fun exit() {
        val current = viewModel.state.value
        onExit(current.itemId, current.isEpisode)
    }
    LaunchedEffect(state.status) {
        if (state.status == OnDemandStatus.ENDED && state.isEpisode) exit()
    }
    val isTv = LocalIsTv.current
    val focus = remember { FocusRequester() }
    var panel by remember { mutableStateOf<PlayerPanel?>(null) }
    var overlay by remember { mutableLongStateOf(0L) }
    var overlayVisible by remember { mutableStateOf(true) }
    // Hidden by hand: stays hidden, even while paused, until the next key or tap brings it back.
    var hiddenByUser by remember { mutableStateOf(false) }
    // The banner's height, so the subtitles can step above it while it is up.
    var overlayHeight by remember { mutableIntStateOf(0) }

    LifecycleStartEffect(viewModel) {
        onStopOrDispose { viewModel.pause() }
    }
    LandscapeFullScreen()
    // Opening the player brings the banner up with a key highlighted on TV.
    LaunchedEffect(Unit) { if (isTv) panel = PlayerPanel.CONTROLS }
    // Choosing tracks or episodes takes reading: pickers stay up until a pick or Back.
    val browsingChoices = panel?.isPicker == true
    // A finger is down in the banner: it stays up until the finger lifts, then waits again.
    var overlayTouched by remember { mutableStateOf(false) }
    LaunchedEffect(overlay, state.playing, browsingChoices, overlayTouched) {
        overlayVisible = true
        if (state.playing && !browsingChoices && !overlayTouched) {
            // With no key pressed or touch for a while, the banner goes, highlighted key and all.
            delay(PLAYER_BANNER_MS)
            overlayVisible = false
            panel = null
        }
    }
    LaunchedEffect(overlay) { hiddenByUser = false }
    // Read when called, so a key handler sees the banner as it is at that moment, not as last composed.
    fun bannerUp() = !hiddenByUser && (overlayVisible || panel != null || !state.playing)
    val overlayShown = bannerUp()
    // A segment to skip: its key stays up for as long as the segment plays.
    val skip = state.skip
    val skipShown = skip != null
    val playKey = remember { FocusRequester() }
    val audioKey = remember { FocusRequester() }
    val subtitlesKey = remember { FocusRequester() }
    val episodesKey = remember { FocusRequester() }
    val previousKey = remember { FocusRequester() }
    val skipKey = remember { FocusRequester() }
    var returnKey by remember { mutableStateOf<FocusRequester?>(null) }
    fun controlsFocus(): FocusRequester = when (returnKey) {
        audioKey -> if (showAudio) audioKey else playKey
        subtitlesKey -> if (showSubtitles) subtitlesKey else playKey
        else -> returnKey ?: playKey
    }
    LaunchedEffect(showAudio, showSubtitles) {
        if (returnKey === audioKey && !showAudio || returnKey === subtitlesKey && !showSubtitles) {
            returnKey = playKey
            if (panel == PlayerPanel.CONTROLS) playKey.requestWhenShown()
        }
        if (panel == PlayerPanel.AUDIO && !showAudio || panel == PlayerPanel.SUBTITLES && !showSubtitles) {
            panel = PlayerPanel.CONTROLS
        }
    }
    fun closePanelOrBanner() {
        panel = panel.afterBack()
        if (panel == null) hiddenByUser = true
    }
    // One owner for remote and system Back: picker, banner, then player.
    BackHandler {
        if (panel != null || bannerUp()) closePanelOrBanner() else exit()
    }
    // A disabled Previous cannot retain focus after restarting the show's first episode.
    LaunchedEffect(state.canRestartOrPrevious) {
        if (!state.canRestartOrPrevious && returnKey === previousKey) {
            returnKey = playKey
            if (panel == PlayerPanel.CONTROLS) playKey.requestWhenShown()
        }
    }
    LaunchedEffect(panel) {
        when (panel) {
            null -> {
                returnKey = null
                runCatching { focus.requestFocus() }
            }
            PlayerPanel.CONTROLS -> {
                // The banner may be hidden: bring it in, then take the key once it is composed.
                overlay++
                controlsFocus().requestWhenShown()
            }
            else -> overlay++
        }
    }
    // The picture itself holds focus, not one of the banner's keys.
    var pictureFocused by remember { mutableStateOf(false) }
    // The picture or one of the player's keys holds focus.
    var playerFocused by remember { mutableStateOf(false) }
    // The Skip key in the banner holds focus.
    var skipFocused by remember { mutableStateOf(false) }
    // On TV the Skip key in the banner takes focus as it comes, and as the banner comes in on its
    // own (a seek) while it shows. With the banner hidden, focus stays on the picture: OK skips anyway.
    LaunchedEffect(skipShown) { if (isTv && skipShown && overlayShown && !browsingChoices) skipKey.requestWhenShown() }
    LaunchedEffect(overlayShown) { if (isTv && skipShown && overlayShown && panel == null) skipKey.requestWhenShown() }
    // A focused key that goes (the Skip key at its segment's end) takes focus with it: put it back.
    LaunchedEffect(playerFocused, pictureFocused, skipShown, overlayShown) {
        if (playerFocused && !(pictureFocused && panel == PlayerPanel.CONTROLS)) return@LaunchedEffect
        withFrameNanos { }
        if (playerFocused && !(pictureFocused && panel == PlayerPanel.CONTROLS)) return@LaunchedEffect
        when {
            // EpisodeStrip and TrackChoices request their own initial focus.
            panel?.isPicker == true -> Unit
            panel != null -> controlsFocus().requestWhenShown()
            isTv && skipShown && overlayShown -> skipKey.requestWhenShown()
            else -> runCatching { focus.requestFocus() }
        }
    }
    // The Skip key standing alone over the picture leaves after a few seconds, and comes back for
    // the next segment or when the banner goes away again. The one in the banner stays.
    var aloneShown by remember { mutableStateOf(false) }
    LaunchedEffect(skip, overlayShown) {
        aloneShown = skip != null && !overlayShown
        if (aloneShown) {
            delay(ALONE_SKIP_MS)
            aloneShown = false
        }
    }
    // An OK press that skips, decided on key-down and done on key-up.
    var skipHeld by remember { mutableStateOf(false) }
    // An OK press whose key-down reached the picture; only its key-up acts there.
    var okHeld by remember { mutableStateOf(false) }

    Box(
        modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focus)
            .onPreviewKeyEvent { event ->
                // Any key pressed with the banner's keys in use holds the banner up a while longer.
                if (panel != null && event.key != Key.Back && event.type == KeyEventType.KeyDown) overlay++
                if (event.key in PlayerConfirmKeys && event.type == KeyEventType.KeyDown) okHeld = false
                // OK skips while the Skip key stands alone in view or has focus, whatever else holds focus or
                // the banner does before the key comes up: one decision, from what plays right now.
                if (event.key in PlayerOkKeys) {
                    if (event.type == KeyEventType.KeyDown && event.nativeKeyEvent.repeatCount == 0) {
                        skipHeld = viewModel.state.value.skip != null && (if (bannerUp()) skipFocused else aloneShown)
                    }
                    if (!skipHeld) return@onPreviewKeyEvent false
                    if (event.type == KeyEventType.KeyUp) {
                        skipHeld = false
                        viewModel.skip()
                    }
                    return@onPreviewKeyEvent true
                }
                false
            }
            .onKeyEvent { event ->
                // OK on the picture pauses and puts focus on Play, bringing the banner in; Menu or Info only bring its keys.
                val onPicture = panel == null || panel == PlayerPanel.CONTROLS && pictureFocused
                if (onPicture && event.key in PlayerConfirmKeys) {
                    // On key-up, so a key focused by this press doesn't take its release as a click,
                    // and only for a press that began here: a release whose press went to a key does nothing.
                    if (event.type == KeyEventType.KeyDown) okHeld = true
                    if (event.type == KeyEventType.KeyUp && okHeld) {
                        okHeld = false
                        if (event.key != Key.Menu && event.key != Key.Info) {
                            viewModel.pause()
                            returnKey = null
                        }
                        if (panel == null) panel = PlayerPanel.CONTROLS else runCatching { controlsFocus().requestFocus() }
                        overlay++
                    }
                    return@onKeyEvent true
                }
                if (panel != null) return@onKeyEvent false
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (event.key) {
                    Key.DirectionLeft, Key.MediaRewind -> viewModel.seekBy(-PLAYER_BACK_MS)
                    Key.DirectionRight, Key.MediaFastForward -> viewModel.seekBy(PLAYER_AHEAD_MS)
                    Key.MediaPlayPause, Key.MediaPlay, Key.MediaPause -> viewModel.togglePlay()
                    Key.MediaNext -> viewModel.next()
                    Key.MediaPrevious -> if (event.nativeKeyEvent.repeatCount == 0) viewModel.previous()
                    Key.DirectionUp, Key.DirectionDown -> panel = PlayerPanel.CONTROLS
                    else -> return@onKeyEvent false
                }
                overlay++
                true
            }
            .onFocusChanged {
                pictureFocused = it.isFocused
                playerFocused = it.hasFocus
            }
            .focusable()
            .pointerInput(Unit) {
                // A tap hides the banner while it shows, and brings it back while it is hidden.
                detectTapGestures {
                    if (!hiddenByUser && (overlayVisible || panel != null || !state.playing)) {
                        panel = null
                        hiddenByUser = true
                    } else {
                        overlay++
                    }
                }
            }
            // Left and right seek back and ahead, as the remote's arrows do.
            .onSwipe { swipe ->
                when (swipe) {
                    Swipe.LEFT -> viewModel.seekBy(-PLAYER_BACK_MS)
                    Swipe.RIGHT -> viewModel.seekBy(PLAYER_AHEAD_MS)
                    Swipe.UP, Swipe.DOWN -> return@onSwipe
                }
                overlay++
            },
    ) {
        PlayerVideo(player, subtitleLift = if (overlayShown) overlayHeight else 0)
        when (state.status) {
            // The same dark screen as a restricted channel's, with no stretch to wait out.
            OnDemandStatus.RESTRICTED -> Box(Modifier.fillMaxSize().background(LocalAnstelleTheme.current.background), contentAlignment = Alignment.Center) {
                RestrictedMark(state.restriction?.title ?: Restriction.AGE_TITLE, size = StandInSize.SCREEN)
            }
            OnDemandStatus.ERROR -> Message(state.message ?: "This can't be played.")
            OnDemandStatus.LOADING -> LoadingSpinner("Loading")
            else -> if (state.buffering) LoadingSpinner("Loading")
        }
        if (overlayShown) {
            // Read again whenever the playback state moves: the keys stay on screen across episodes.
            val audio = audioOptions.firstOrNull { it.selected }?.short
            val subtitles = subtitleOptions.firstOrNull { it.selected }?.short
            Overlay(
                state = state,
                viewModel = viewModel,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .onSizeChanged { overlayHeight = it.height }
                    .onTouchHeld { overlayTouched = it },
                skip = skip?.let { type ->
                    {
                        // Gone with the banner or its segment, it no longer holds focus.
                        DisposableEffect(Unit) { onDispose { skipFocused = false } }
                        SkipKey(
                            type,
                            onClick = viewModel::skip,
                            modifier = Modifier
                                .onFocusChanged { skipFocused = it.hasFocus }
                                .focusRequester(skipKey),
                        )
                    }
                },
                episodes = if (panel != PlayerPanel.EPISODES) {
                    null
                } else {
                    {
                        EpisodeStrip(
                            episodes,
                            title = state.title.orEmpty(),
                            playingId = state.itemId,
                            canAddToChannel = onAddToChannel != null,
                            onPick = { episode, resumeMs ->
                                if (episode.id != state.itemId) viewModel.playEpisode(episode.id, resumeMs)
                                panel = null
                            },
                            onChoice = { episode, choice ->
                                when {
                                    choice.kind == WatchKind.ADD_TO_CHANNEL -> onAddToChannel?.invoke(episode.id)
                                    // Held, the one playing takes the spot the choice names too.
                                    episode.id == state.itemId -> viewModel.seekTo(choice.positionMs)
                                    else -> viewModel.playEpisode(episode.id, choice.positionMs)
                                }
                                panel = null
                            },
                        )
                    }
                },
                keys = when (panel) {
                    null, PlayerPanel.CONTROLS, PlayerPanel.EPISODES -> {
                        {
                            DemandKeys(
                                tracks = {
                                    if (showAudio) OsdKey("Audio", Icons.Filled.Audiotrack, onClick = {
                                        returnKey = audioKey
                                        panel = PlayerPanel.AUDIO
                                    }, Modifier.weight(1f, fill = false).focusRequester(audioKey), value = audio)
                                    if (showSubtitles) OsdKey("Subtitles", Icons.Filled.Subtitles, onClick = {
                                        returnKey = subtitlesKey
                                        panel = PlayerPanel.SUBTITLES
                                    }, Modifier.weight(1f, fill = false).focusRequester(subtitlesKey), value = subtitles)
                                },
                                transport = {
                                    // Previous restarts away from the start, then steps to the preceding episode.
                                    if (state.isEpisode) {
                                        OsdKey(if (state.restartOnPrevious) "Restart" else "Previous", Icons.Filled.SkipPrevious, enabled = state.canRestartOrPrevious, onClick = {
                                            returnKey = previousKey
                                            panel = PlayerPanel.CONTROLS
                                            viewModel.previous()
                                            overlay++
                                        }, modifier = Modifier.weight(1f).focusRequester(previousKey), iconOnly = true)
                                    } else {
                                        Spacer(Modifier.weight(1f))
                                    }
                                    OsdKey("10 s", Icons.Filled.Replay10, onClick = {
                                        viewModel.seekBy(-PLAYER_BACK_MS)
                                        overlay++
                                    }, modifier = Modifier.weight(1f), iconOnly = true)
                                    OsdKey(
                                        if (state.playing) "Pause" else "Play",
                                        if (state.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                        onClick = viewModel::togglePlay,
                                        modifier = Modifier.weight(1f).focusRequester(playKey),
                                        iconOnly = true,
                                    )
                                    OsdKey("30 s", Icons.Filled.Forward30, onClick = {
                                        viewModel.seekBy(PLAYER_AHEAD_MS)
                                        overlay++
                                    }, modifier = Modifier.weight(1f), iconOnly = true)
                                    if (state.isEpisode) {
                                        OsdKey("Next", Icons.Filled.SkipNext, enabled = state.next != null, onClick = {
                                            panel = null
                                            viewModel.next()
                                        }, modifier = Modifier.weight(1f), iconOnly = true)
                                    } else {
                                        Spacer(Modifier.weight(1f))
                                    }
                                },
                                episodes = {
                                    if (state.isEpisode) OsdKey("Episodes", Icons.Filled.VideoLibrary, onClick = {
                                        returnKey = episodesKey
                                        viewModel.loadEpisodes()
                                        panel = PlayerPanel.EPISODES
                                    }, Modifier.focusRequester(episodesKey))
                                },
                            )
                        }
                    }
                    PlayerPanel.AUDIO, PlayerPanel.SUBTITLES -> {
                        {
                            val pickingAudio = panel == PlayerPanel.AUDIO
                            TrackChoices(
                                title = if (pickingAudio) "Audio" else "Subtitles",
                                options = if (pickingAudio) audioOptions else subtitleOptions,
                                onClose = { focusControls ->
                                    panel = if (focusControls) PlayerPanel.CONTROLS else null
                                },
                                onPick = if (pickingAudio) viewModel::selectAudio else viewModel::selectSubtitles,
                            )
                        }
                    }
                },
            )
        }
        // With the banner hidden, the Skip key stands alone at the bottom end, its label on the side
        // margin, preselected, and gone after a few seconds. Focus stays on the picture, whose OK skips while
        // it shows; a tap skips too.
        if (skip != null && !overlayShown && aloneShown) {
            SkipKey(
                skip,
                onClick = viewModel::skip,
                highlighted = true,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = osdSide - KEY_PADDING, bottom = osdEdge),
            )
        }
    }
}

/** Equal side regions keep Pause centered even when tracks or episode actions disappear. */
@Composable
private fun RowScope.DemandKeys(
    tracks: @Composable RowScope.() -> Unit,
    transport: @Composable RowScope.() -> Unit,
    episodes: @Composable () -> Unit,
) {
    Row(
        Modifier.weight(1f),
        horizontalArrangement = Arrangement.spacedBy(KEY_GAP),
        verticalAlignment = Alignment.CenterVertically,
        content = tracks,
    )
    Row(
        Modifier.width(osdKeyHeight * 5 + KEY_GAP * 4),
        horizontalArrangement = Arrangement.spacedBy(KEY_GAP),
        verticalAlignment = Alignment.CenterVertically,
        content = transport,
    )
    Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) { episodes() }
}

/**
 * The key that skips the segment playing: SKIP INTRO and the like behind a fast-forward mark. In
 * the banner it is a key like the rest; standing alone over the picture it is [highlighted].
 */
@Composable
private fun SkipKey(type: SegmentType, onClick: () -> Unit, modifier: Modifier = Modifier, highlighted: Boolean = false) {
    val label = when (type) {
        SegmentType.INTRO -> "Skip Intro"
        SegmentType.OUTRO -> "Skip Outro"
        SegmentType.RECAP -> "Skip Recap"
        SegmentType.PREVIEW -> "Skip Preview"
        SegmentType.COMMERCIAL -> "Skip Commercial"
    }
    OsdKey(label, Icons.Filled.FastForward, onClick = onClick, modifier = modifier, highlighted = highlighted)
}

/**
 * The banner's keys while Episodes is open, the way a track list takes their place: EPISODES in
 * rose with the show's [SeasonTabs], then its [EpisodeCard]s, opened on the one playing (focused
 * on TV), the same rail a detail page shows. OK or a tap plays one from where it was left; the
 * one playing just closes it. Holding one (or Menu) offers the quick ways to play it, as on a
 * detail page; [onChoice] does the one picked.
 */
@Composable
private fun EpisodeStrip(
    show: ShowEpisodes?,
    title: String,
    playingId: Long,
    canAddToChannel: Boolean,
    onPick: (episode: EpisodeEntry, resumeMs: Long) -> Unit,
    onChoice: (EpisodeEntry, WatchChoice) -> Unit,
) {
    val theme = LocalAnstelleTheme.current
    val isTv = LocalIsTv.current
    val typography = androidx.compose.material3.MaterialTheme.typography
    val list = show?.episodes
    val watch = show?.watch ?: WatchMarks()
    val seasons = remember(list) { list.orEmpty().groupBy { it.seasonLabel } }
    val playingSeason = remember(list, playingId) { list?.firstOrNull { it.id == playingId }?.seasonLabel }
    var season by remember(list) { mutableStateOf(playingSeason ?: seasons.keys.firstOrNull()) }
    var holding by remember { mutableStateOf<EpisodeEntry?>(null) }
    // The card last held keeps the requester after its dialog closes, so Back lands on it again.
    var held by remember { mutableStateOf<Long?>(null) }
    val returnFocus = remember { FocusRequester() }
    Column(
        Modifier.fillMaxWidth().padding(top = if (isTv) 2.dp else 0.dp),
        verticalArrangement = Arrangement.spacedBy(if (isTv) 6.dp else 4.dp),
    ) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(theme.line))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            androidx.compose.material3.Text("EPISODES", style = typography.labelMedium, color = theme.accent, modifier = Modifier.padding(end = 6.dp))
            when {
                // It reads from SQLite at once, so nothing stands in while it loads.
                list == null -> Unit
                list.isEmpty() -> androidx.compose.material3.Text("NONE TO WATCH", style = typography.labelMedium, color = theme.textMuted)
                else -> SeasonTabs(seasons.mapValues { it.value.size }, season, watch.doneSeasons(seasons), onSelect = { season = it })
            }
        }
        val shown = seasons[season].orEmpty()
        if (shown.isNotEmpty()) {
            val target = shown.firstOrNull { it.id == playingId } ?: shown.first()
            val listState = rememberLazyListState()
            val targetFocus = remember { FocusRequester() }
            LaunchedEffect(list) {
                listState.scrollToItem(shown.indexOf(target))
                withFrameNanos { }
                runCatching { targetFocus.requestFocus() }
            }
            LazyRow(state = listState, horizontalArrangement = Arrangement.spacedBy(episodeCardSpacing)) {
                items(shown, key = { it.id }) { episode ->
                    val resume = watch.resumeMs(episode.id, episode.durationMs)
                    EpisodeCard(
                        label = episode.label,
                        stillUrl = episode.stillUrl,
                        progress = MediaProgress(episode.durationMs, resume),
                        watched = watch.watched(episode.id, episode.durationMs),
                        current = episode.id == playingId,
                        onClick = { onPick(episode, resume) },
                        onHold = {
                            held = episode.id
                            holding = episode
                        },
                        touchWidth = 132.dp,
                        modifier = Modifier
                            .let { if (episode.id == target.id) it.focusRequester(targetFocus) else it }
                            .let { if (episode.id == held) it.focusRequester(returnFocus) else it },
                    )
                }
            }
        }
    }
    holding?.let { episode ->
        EpisodeActionsDialog(
            show = title,
            label = episode.label,
            facts = episode.facts,
            choices = WatchChoices.episodeActions(watch.resumeMs(episode.id, episode.durationMs), canAddToChannel),
            returnFocus = returnFocus,
            onDismiss = { holding = null },
            onAction = { choice -> onChoice(episode, choice) },
        )
    }
}

/**
 * The on-demand banner, straight on the picture over the edge shade: ON DEMAND and viewing
 * times, with the stream's technical line beside them, the title and detail, the time in with
 * the bar and length, and the [keys] under a hairline. The ladder takes taps and drags on
 * phones and tablets. The [skip] key sits under the
 * technical line, in the room the title leaves at the row's end.
 */
@Composable
private fun Overlay(
    state: OnDemandState,
    viewModel: OnDemandPlayerViewModel,
    modifier: Modifier,
    keys: @Composable RowScope.() -> Unit,
    /** The Skip key, while a segment the profile is asked to skip plays. */
    skip: (@Composable () -> Unit)? = null,
    /** The episode strip, in the keys' place while Episodes is open. */
    episodes: (@Composable () -> Unit)? = null,
) {
    val theme = LocalAnstelleTheme.current
    val isTv = LocalIsTv.current
    var position by remember(state.itemId) { mutableLongStateOf(viewModel.positionMs) }
    var duration by remember(state.itemId) { mutableLongStateOf(viewModel.durationMs) }
    var wallClock by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(state.itemId) {
        while (true) {
            position = viewModel.positionMs
            duration = viewModel.durationMs
            wallClock = System.currentTimeMillis()
            delay(POSITION_MS)
        }
    }
    val typography = androidx.compose.material3.MaterialTheme.typography
    Column(
        modifier
            .fillMaxWidth()
            .edgeShade(theme.surface, shadeFade),
    ) {
        // Hand-held sizes off TV: the touch keys close the banner, so their own height is the air under it.
        Column(
            Modifier.padding(start = osdSide, end = osdSide, bottom = if (isTv) osdEdge else 4.dp),
            verticalArrangement = Arrangement.spacedBy(if (isTv) 4.dp else 2.dp),
        ) {
            BannerHeader(state.stream) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    androidx.compose.material3.Text("ON DEMAND", style = typography.labelMedium, color = theme.accent)
                    // Start records viewing time; finish follows remaining media, including pauses and seeks.
                    if (duration > 0 && state.startedAtUtcMs > 0) {
                        androidx.compose.material3.Text(
                            WatchChoices.clockRange(state.startedAtUtcMs, wallClock + (duration - position).coerceAtLeast(0)),
                            style = typography.labelMedium,
                            color = theme.textMuted,
                        )
                    }
                }
            }
            // The Skip key centered on the title and its detail line.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(if (isTv) 4.dp else 2.dp)) {
                    TitleLogo(
                        logoUrl = state.logoUrl,
                        title = state.title.orEmpty(),
                    )
                    val detail = lineUnderLogo(state.detail, state.title.orEmpty(), state.isEpisode, state.logoUrl != null)
                    detail?.let { PlayerDetailLine(it) }
                }
                // Its label ends under the technical line's end, its fill running into the margin as the key row's does.
                skip?.let { Box(Modifier.padding(start = 16.dp).offset(x = KEY_PADDING)) { it() } }
            }
            ProgressRow(
                positionMs = position,
                lengthMs = duration,
                modifier = Modifier.padding(top = if (isTv) 4.dp else 0.dp),
                // Phones tap or drag the ladder; the remote seeks with left and right.
                onSeek = if (isTv) {
                    null
                } else {
                    { target: Long ->
                        viewModel.seekTo(target)
                        position = target
                    }
                },
            )
            if (episodes != null) episodes() else OsdKeys(Modifier.padding(top = if (isTv) 2.dp else 0.dp), keys)
        }
    }
}

@Composable
private fun Message(text: String) {
    val theme = LocalAnstelleTheme.current
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        androidx.compose.material3.Text(
            text,
            style = androidx.compose.material3.MaterialTheme.typography.titleLarge,
            color = theme.text,
            modifier = Modifier
                .softShade(theme.surface)
                .padding(horizontal = 24.dp, vertical = 14.dp),
        )
    }
}

private const val ALONE_SKIP_MS = 5_000L
private const val POSITION_MS = 500L
