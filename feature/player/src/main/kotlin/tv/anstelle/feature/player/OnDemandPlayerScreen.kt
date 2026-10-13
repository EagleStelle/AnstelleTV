package tv.anstelle.feature.player

import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ViewList
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil3.compose.AsyncImage
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
import tv.anstelle.core.model.SegmentType
import tv.anstelle.core.player.SubtitleOverlays
import tv.anstelle.core.player.SubtitleStyle
import tv.anstelle.core.theme.ControlShape
import tv.anstelle.core.theme.LocalAnstelleTheme
import tv.anstelle.core.theme.LocalIsTv
import tv.anstelle.core.theme.components.AirMarker
import tv.anstelle.core.theme.components.FocusSurface
import tv.anstelle.core.theme.components.OsdTab
import tv.anstelle.core.theme.components.RestrictedMark
import tv.anstelle.core.theme.components.SegmentLadder
import tv.anstelle.core.theme.components.StandInSize
import tv.anstelle.core.theme.components.TitleLogo
import tv.anstelle.core.theme.components.PastShade
import tv.anstelle.core.theme.components.WatchStrip

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

    fun previous() = playback.skipToPrevious()

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

/** Where the banner's keys are: [CONTROLS] has focus on them, the others show a track list or the show's episodes in their place. */
private enum class DemandPanel { CONTROLS, AUDIO, SUBTITLES, EPISODES }

/**
 * Full-screen on-demand playback: OK on the picture pauses and brings the banner in with Play
 * highlighted, left and right jump back 10 s and ahead 30 s, the remote's previous and next keys
 * step through the show's episodes. The banner always carries its keys (audio and subtitles on
 * the left; then previous, 10 s back, pause, 30 s ahead and next, in the remote's own order,
 * pause in the middle); up, down or Menu moves focus into them. Coming and going with it, the
 * top corners hold the way back to the program's channel page ([onOpenOnDemand], top start)
 * and, for an episode, Episodes (top end), which swaps the keys for the show's seasons and a
 * strip of its episodes. Phones tap for the banner and drag its bar to seek.
 *
 * While a segment the profile is asked to skip plays (an intro, an outro…), a Skip key shows for
 * the whole segment: in the banner under the technical line while it is up, and alone at the
 * bottom end while it is hidden, preselected in rose on touch as on TV. On TV it takes focus as
 * it comes, so OK skips.
 */
@Composable
fun OnDemandPlayerScreen(
    /** Null when the item wasn't picked from a channel's airing: then there is no page to go back to. */
    onOpenOnDemand: (() -> Unit)?,
    modifier: Modifier = Modifier,
    viewModel: OnDemandPlayerViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val episodes by viewModel.episodes.collectAsStateWithLifecycle()
    val theme = LocalAnstelleTheme.current
    val isTv = LocalIsTv.current
    val focus = remember { FocusRequester() }
    var panel by remember { mutableStateOf<DemandPanel?>(null) }
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
    LaunchedEffect(Unit) { if (isTv) panel = DemandPanel.CONTROLS }
    // Picking an episode takes reading: the strip stays up until one is picked or Back closes it.
    val browsingEpisodes = panel == DemandPanel.EPISODES
    LaunchedEffect(overlay, state.playing, browsingEpisodes) {
        overlayVisible = true
        if (state.playing && !browsingEpisodes) {
            // With no key pressed for a while, the banner goes, highlighted key and all.
            delay(OVERLAY_MS)
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
    // Back hides the banner, whatever has focus in it, and only then leaves the player.
    BackHandler(enabled = panel != null || !hiddenByUser && (overlayVisible || !state.playing)) {
        panel = null
        hiddenByUser = true
    }
    val playKey = remember { FocusRequester() }
    val audioKey = remember { FocusRequester() }
    val subtitlesKey = remember { FocusRequester() }
    val episodesKey = remember { FocusRequester() }
    val skipKey = remember { FocusRequester() }
    // The key that opened a track list gets focus back when the list closes.
    var returnKey by remember { mutableStateOf<FocusRequester?>(null) }
    LaunchedEffect(panel) {
        when (panel) {
            null -> {
                returnKey = null
                runCatching { focus.requestFocus() }
            }
            DemandPanel.CONTROLS -> {
                // The banner may be hidden: bring it in, then take the key once it is composed.
                overlay++
                (returnKey ?: playKey).requestWhenShown()
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
    LaunchedEffect(skipShown) { if (isTv && skipShown && overlayShown) skipKey.requestWhenShown() }
    LaunchedEffect(overlayShown) { if (isTv && skipShown && overlayShown && panel == null) skipKey.requestWhenShown() }
    // A focused key that goes (the Skip key at its segment's end) takes focus with it: put it back.
    LaunchedEffect(playerFocused, skipShown, overlayShown) {
        if (playerFocused) return@LaunchedEffect
        withFrameNanos { }
        if (playerFocused) return@LaunchedEffect
        when {
            panel != null -> (returnKey ?: playKey).requestWhenShown()
            isTv && skipShown && overlayShown -> skipKey.requestWhenShown()
            else -> runCatching { focus.requestFocus() }
        }
    }
    // A Back press the banner took on key-down; its key-up hides the banner.
    var backHeld by remember { mutableStateOf(false) }
    // An OK press that skips, decided on key-down and done on key-up.
    var skipHeld by remember { mutableStateOf(false) }
    // An OK press whose key-down reached the picture; only its key-up acts there.
    var okHeld by remember { mutableStateOf(false) }

    Box(
        modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focus)
            // Back hides the banner in one press: left to the focus system, it would first only move focus off the key.
            .onPreviewKeyEvent { event ->
                // Any key pressed with the banner's keys in use holds the banner up a while longer.
                if (panel != null && event.key != Key.Back && event.type == KeyEventType.KeyDown) overlay++
                if (event.key in ConfirmKeys && event.type == KeyEventType.KeyDown) okHeld = false
                // OK skips while the Skip key stands alone or has focus, whatever else holds focus or
                // the banner does before the key comes up: one decision, from what plays right now.
                if (event.key in OkKeys) {
                    if (event.type == KeyEventType.KeyDown && event.nativeKeyEvent.repeatCount == 0) {
                        skipHeld = viewModel.state.value.skip != null && (!bannerUp() || skipFocused)
                    }
                    if (!skipHeld) return@onPreviewKeyEvent false
                    if (event.type == KeyEventType.KeyUp) {
                        skipHeld = false
                        viewModel.skip()
                    }
                    return@onPreviewKeyEvent true
                }
                if (event.key != Key.Back) return@onPreviewKeyEvent false
                val shown = panel != null || !hiddenByUser && (overlayVisible || !state.playing)
                if (event.type == KeyEventType.KeyDown && shown) backHeld = true
                if (!backHeld) return@onPreviewKeyEvent false
                if (event.type == KeyEventType.KeyUp) {
                    backHeld = false
                    panel = null
                    hiddenByUser = true
                }
                true
            }
            .onKeyEvent { event ->
                // OK on the picture pauses and puts focus on Play, bringing the banner in; Menu or Info only bring its keys.
                val onPicture = panel == null || panel == DemandPanel.CONTROLS && pictureFocused
                if (onPicture && event.key in ConfirmKeys) {
                    // On key-up, so a key focused by this press doesn't take its release as a click,
                    // and only for a press that began here: a release whose press went to a key does nothing.
                    if (event.type == KeyEventType.KeyDown) okHeld = true
                    if (event.type == KeyEventType.KeyUp && okHeld) {
                        okHeld = false
                        if (event.key != Key.Menu && event.key != Key.Info) {
                            viewModel.pause()
                            returnKey = null
                        }
                        if (panel == null) panel = DemandPanel.CONTROLS else runCatching { (returnKey ?: playKey).requestFocus() }
                        overlay++
                    }
                    return@onKeyEvent true
                }
                if (panel != null) return@onKeyEvent false
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (event.key) {
                    Key.DirectionLeft, Key.MediaRewind -> viewModel.seekBy(-BACK_MS)
                    Key.DirectionRight, Key.MediaFastForward -> viewModel.seekBy(AHEAD_MS)
                    Key.MediaPlayPause, Key.MediaPlay, Key.MediaPause -> viewModel.togglePlay()
                    Key.MediaNext -> viewModel.next()
                    Key.MediaPrevious -> viewModel.previous()
                    Key.DirectionUp, Key.DirectionDown -> panel = DemandPanel.CONTROLS
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
            },
    ) {
        Video(viewModel.player, subtitleLift = if (overlayShown) overlayHeight else 0)
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
            val audio = remember(state, panel) { viewModel.audioOptions().firstOrNull { it.selected }?.short }
            val subtitles = remember(state, panel) { viewModel.subtitleOptions().firstOrNull { it.selected }?.short }
            Overlay(
                state = state,
                viewModel = viewModel,
                modifier = Modifier.align(Alignment.BottomCenter).onSizeChanged { overlayHeight = it.height },
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
                episodes = if (panel != DemandPanel.EPISODES) {
                    null
                } else {
                    {
                        EpisodeStrip(episodes, playingId = state.itemId) { episode, resumeMs ->
                            if (episode.id != state.itemId) viewModel.playEpisode(episode.id, resumeMs)
                            panel = null
                        }
                    }
                },
                keys = when (panel) {
                    null, DemandPanel.CONTROLS, DemandPanel.EPISODES -> {
                        {
                            OsdKey("Audio", Icons.Filled.Audiotrack, onClick = {
                                returnKey = audioKey
                                panel = DemandPanel.AUDIO
                            }, Modifier.focusRequester(audioKey), value = audio)
                            OsdKey("Subtitles", Icons.Filled.Subtitles, onClick = {
                                returnKey = subtitlesKey
                                panel = DemandPanel.SUBTITLES
                            }, Modifier.focusRequester(subtitlesKey), value = subtitles)
                            // Tracks on the left; the transport on the right in the remote's order, pause in the middle.
                            Spacer(Modifier.weight(1f))
                            // An episode keeps both episode keys in place, dimmed at either end of its show; a movie has neither.
                            if (state.isEpisode) {
                                OsdKey("Previous", Icons.Filled.SkipPrevious, enabled = state.previous != null, onClick = {
                                    panel = null
                                    viewModel.previous()
                                })
                            }
                            OsdKey("10 s", Icons.Filled.Replay10, onClick = {
                                viewModel.seekBy(-BACK_MS)
                                overlay++
                            })
                            OsdKey(
                                if (state.playing) "Pause" else "Play",
                                if (state.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                onClick = viewModel::togglePlay,
                                modifier = Modifier.focusRequester(playKey),
                            )
                            OsdKey("30 s", Icons.Filled.Forward30, onClick = {
                                viewModel.seekBy(AHEAD_MS)
                                overlay++
                            })
                            if (state.isEpisode) {
                                OsdKey("Next", Icons.Filled.SkipNext, enabled = state.next != null, onClick = {
                                    panel = null
                                    viewModel.next()
                                })
                            }
                        }
                    }
                    DemandPanel.AUDIO -> {
                        {
                            TrackChoices("Audio", remember { viewModel.audioOptions() }) {
                                viewModel.selectAudio(it)
                                panel = null
                            }
                        }
                    }
                    DemandPanel.SUBTITLES -> {
                        {
                            TrackChoices("Subtitles", remember { viewModel.subtitleOptions() }) {
                                viewModel.selectSubtitles(it)
                                panel = null
                            }
                        }
                    }
                },
            )
            // The top corners, which the live player gives its ident: the way back to the program's page, and the show's episodes.
            onOpenOnDemand?.let { open ->
                Box(
                    Modifier
                        .align(Alignment.TopStart)
                        .cornerShade(theme.banner, atStart = true)
                        .padding(start = osdSide - KEY_PADDING, top = osdEdge, end = 32.dp, bottom = 24.dp),
                ) {
                    OsdKey("On Demand", Icons.AutoMirrored.Filled.ArrowBack, onClick = open)
                }
            }
            if (state.isEpisode) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .cornerShade(theme.banner)
                        .padding(start = 32.dp, top = osdEdge, end = osdSide - KEY_PADDING, bottom = 24.dp),
                ) {
                    OsdKey(
                        "Episodes",
                        Icons.AutoMirrored.Filled.ViewList,
                        onClick = {
                            if (panel == DemandPanel.EPISODES) {
                                // Again, it gives the banner its keys back, focus staying here.
                                returnKey = episodesKey
                                panel = DemandPanel.CONTROLS
                            } else {
                                viewModel.loadEpisodes()
                                panel = DemandPanel.EPISODES
                            }
                        },
                        modifier = Modifier.focusRequester(episodesKey),
                    )
                }
            }
        }
        // With the banner hidden, the Skip key stands alone at the bottom end, its label on the side
        // margin, preselected. Focus stays on the picture, whose OK skips while it shows; a tap skips too.
        if (skip != null && !overlayShown) {
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
 * rose with the show's season tabs, then a strip of its episodes, opened on the one playing
 * (focused on TV). OK or a tap plays one from where it was left; the one playing just closes it.
 */
@Composable
private fun EpisodeStrip(show: ShowEpisodes?, playingId: Long, onPick: (episode: EpisodeEntry, resumeMs: Long) -> Unit) {
    val theme = LocalAnstelleTheme.current
    val isTv = LocalIsTv.current
    val typography = androidx.compose.material3.MaterialTheme.typography
    val list = show?.episodes
    val seasons = remember(list) { list.orEmpty().groupBy { it.seasonLabel } }
    val playingSeason = remember(list, playingId) { list?.firstOrNull { it.id == playingId }?.seasonLabel }
    var season by remember(list) { mutableStateOf(playingSeason ?: seasons.keys.firstOrNull()) }
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
                seasons.size > 1 -> LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    val watch = show?.watch
                    items(seasons.keys.toList(), key = { it }) { key ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (key == playingSeason) AirMarker(onAir = true, modifier = Modifier.padding(start = 6.dp))
                            val episodes = seasons.getValue(key)
                            OsdTab(
                                key,
                                selected = key == season,
                                onClick = { season = key },
                                count = episodes.size,
                                compact = true,
                                done = watch != null && episodes.all { watch.watched(it.id, it.durationMs) },
                            )
                        }
                    }
                }
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
            LazyRow(state = listState, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(shown, key = { it.id }) { episode ->
                    val resume = show?.watch?.resumeMs(episode.id, episode.durationMs) ?: 0
                    EpisodeCard(
                        episode = episode,
                        playing = episode.id == playingId,
                        resumeMs = resume,
                        watched = show?.watch?.watched(episode.id, episode.durationMs) == true,
                        onClick = { onPick(episode, resume) },
                        modifier = if (episode.id == target.id) Modifier.focusRequester(targetFocus) else Modifier,
                    )
                }
            }
        }
    }
}

/**
 * One episode in the strip, a channel tile's way: its still in a small inset, what was watched
 * at its foot ([WatchStrip]), its number and title, then PLAYING behind the on-air square or
 * its length (the time left once started). One watched through reads as aired, as on a
 * channel's page: the still under the past shade and the title quiet, except the one playing.
 * Focus fills the whole card rose, so the still sits in a rose frame.
 */
@Composable
private fun EpisodeCard(episode: EpisodeEntry, playing: Boolean, resumeMs: Long, watched: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val theme = LocalAnstelleTheme.current
    val isTv = LocalIsTv.current
    val typography = androidx.compose.material3.MaterialTheme.typography
    FocusSurface(onClick = onClick, color = Color.Transparent, modifier = modifier.width(if (isTv) 174.dp else 132.dp)) { focused ->
        val ink = if (focused) theme.onAccent else theme.accent
        Column(Modifier.padding(3.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(ControlShape).background(theme.surfaceHigh)) {
                episode.stillUrl?.let {
                    AsyncImage(model = it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                }
                val aired = watched && !playing
                if (aired) Box(Modifier.matchParentSize().background(PastShade))
                val fraction = if (episode.durationMs > 0) resumeMs.toFloat() / episode.durationMs else 0f
                if (fraction > 0f || watched) WatchStrip(fraction, watched, Modifier.align(Alignment.BottomStart))
            }
            Column(Modifier.padding(start = 3.dp, end = 3.dp, bottom = 3.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                androidx.compose.material3.Text(
                    episode.label,
                    style = typography.titleSmall,
                    color = if (watched && !playing && !focused) theme.textMuted else Color.Unspecified,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (playing) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        AirMarker(onAir = true, color = ink)
                        androidx.compose.material3.Text("PLAYING", style = typography.labelMedium, color = ink)
                    }
                } else {
                    (WatchChoices.timeLeft(episode.durationMs, resumeMs) ?: episode.facts)?.let {
                        androidx.compose.material3.Text(
                            it,
                            style = typography.bodySmall,
                            color = if (focused) theme.onAccent.copy(alpha = 0.76f) else theme.textMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun Video(player: androidx.media3.common.Player, subtitleLift: Int) {
    AndroidView(
        factory = { context ->
            PlayerView(context).apply {
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                useController = false
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                setShutterBackgroundColor(android.graphics.Color.BLACK)
                keepScreenOn = true
                isFocusable = false
                subtitleView?.let(SubtitleStyle::applyTo)
            }
        },
        update = { view ->
            view.player = player
            // libass draws ASS subtitles in a layer of its own over the video.
            view.subtitleView?.let { SubtitleOverlays.attach(it, player) }
            view.liftSubtitles(subtitleLift)
        },
        onRelease = { view ->
            view.subtitleView?.let(SubtitleOverlays::detach)
            view.player = null
        },
        modifier = Modifier.fillMaxSize(),
    )
}

/**
 * The on-demand banner, straight on the picture over the edge shade: ON DEMAND, playing or
 * paused, with the stream's technical line beside it, the title, the time in with the bar and
 * the length, what plays next and the [keys] under a hairline. Phones and tablets set what plays next
 * beside the detail line, and their ladder takes taps and drags. The [skip] key sits under the
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
    var position by remember { mutableLongStateOf(viewModel.positionMs) }
    var duration by remember { mutableLongStateOf(viewModel.durationMs) }
    var wallClock by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
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
            .edgeShade(theme.banner, shadeFade),
    ) {
        // Hand-held sizes off TV: the touch keys close the banner, so their own height is the air under it.
        Column(
            Modifier.padding(start = osdSide, end = osdSide, bottom = if (isTv) osdEdge else 4.dp),
            verticalArrangement = Arrangement.spacedBy(if (isTv) 4.dp else 2.dp),
        ) {
            BannerHeader(state.stream) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    androidx.compose.material3.Text("ON DEMAND", style = typography.labelMedium, color = theme.accent)
                    // When it starts and ends on the clock, as the channel's NOW line gives its airing; a pause moves both.
                    if (duration > 0) {
                        val startMs = wallClock - position
                        androidx.compose.material3.Text(
                            WatchChoices.clockRange(startMs, startMs + duration),
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
                        height = if (isTv) 56.dp else 28.dp,
                        maxLogoWidth = if (isTv) 360.dp else 220.dp,
                        style = if (isTv) typography.headlineMedium else typography.headlineSmall,
                        modifier = Modifier.padding(top = if (isTv) 0.dp else 2.dp),
                    )
                    val detail = lineUnderLogo(state.detail, state.title.orEmpty(), state.isEpisode, state.logoUrl != null)
                    detail?.let { DetailLine(it) }
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

/** The line under the title: S1E2 - its title for an episode, the year for a movie. */
@Composable
private fun DetailLine(text: String, modifier: Modifier = Modifier) {
    androidx.compose.material3.Text(
        text,
        style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
        color = LocalAnstelleTheme.current.textMuted,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
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
                .softShade(theme.banner)
                .padding(horizontal = 24.dp, vertical = 14.dp),
        )
    }
}


private const val BACK_MS = 10_000L
private const val AHEAD_MS = 30_000L
private const val OVERLAY_MS = 3_000L
private const val POSITION_MS = 500L

private val OkKeys = setOf(Key.DirectionCenter, Key.Enter, Key.NumPadEnter)
private val ConfirmKeys = OkKeys + setOf(Key.Menu, Key.Info)
