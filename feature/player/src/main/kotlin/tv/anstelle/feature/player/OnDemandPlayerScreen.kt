package tv.anstelle.feature.player

import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Audiotrack

import androidx.compose.material.icons.filled.Forward30
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
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
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import tv.anstelle.core.data.ProfileRepository
import tv.anstelle.core.data.lineUnderLogo
import tv.anstelle.core.data.playback.ChannelPlayer
import tv.anstelle.core.data.playback.OnDemandPlayer
import tv.anstelle.core.data.playback.OnDemandState
import tv.anstelle.core.data.playback.OnDemandStatus
import tv.anstelle.core.data.playback.TRACK_TYPE_AUDIO
import tv.anstelle.core.data.playback.TRACK_TYPE_TEXT
import tv.anstelle.core.data.playback.TrackOption
import tv.anstelle.core.player.SubtitleOverlays
import tv.anstelle.core.theme.LocalAnstelleTheme
import tv.anstelle.core.theme.LocalIsTv
import tv.anstelle.core.theme.PanelShape
import tv.anstelle.core.theme.components.AirMarker
import tv.anstelle.core.theme.components.TitleLogo

import javax.inject.Inject

@HiltViewModel
class OnDemandPlayerViewModel @Inject constructor(
    savedState: SavedStateHandle,
    profiles: ProfileRepository,
    channels: ChannelPlayer,
    private val playback: OnDemandPlayer,
) : ViewModel() {
    /** Match the `itemId` and `positionMs` arguments of the navigation route. */
    private val itemId: Long = checkNotNull(savedState.get<Long>("itemId"))
    private var startMs: Long = savedState.get<Long>("positionMs") ?: 0L

    val state = playback.state
    val player get() = playback.player

    init {
        // Many TV devices decode one video at a time: the channel the guide was previewing stops.
        channels.stop()
        viewModelScope.launch {
            val profile = profiles.ensureActiveProfile()
            playback.play(itemId, startMs, profile.id, profile.maxRatingScore)
        }
    }

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

    fun audioOptions(): List<TrackOption> = playback.trackOptions(TRACK_TYPE_AUDIO)

    fun subtitleOptions(): List<TrackOption> = playback.trackOptions(TRACK_TYPE_TEXT)

    fun selectAudio(option: TrackOption) = playback.selectTrack(TRACK_TYPE_AUDIO, option)

    fun selectSubtitles(option: TrackOption) = playback.selectTrack(TRACK_TYPE_TEXT, option)

    override fun onCleared() {
        playback.release()
    }
}

private enum class DemandPanel { CONTROLS, AUDIO, SUBTITLES }

/**
 * Full-screen on-demand playback: OK plays or pauses, left and right jump back 10 s and ahead
 * 30 s, the remote's previous and next keys step through the show's episodes. The banner always
 * carries its keys (audio and subtitles on the left; then previous, 10 s back, pause, 30 s ahead
 * and next, in the remote's own order, pause in the middle); up, down or Menu moves focus into
 * them. Phones tap for the banner and drag its bar to seek.
 */
@Composable
fun OnDemandPlayerScreen(
    modifier: Modifier = Modifier,
    viewModel: OnDemandPlayerViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val isTv = LocalIsTv.current
    val focus = remember { FocusRequester() }
    var panel by remember { mutableStateOf<DemandPanel?>(null) }
    var overlay by remember { mutableLongStateOf(0L) }
    var overlayVisible by remember { mutableStateOf(true) }

    LifecycleStartEffect(viewModel) {
        onStopOrDispose { viewModel.pause() }
    }
    LandscapeFullScreen()
    LaunchedEffect(overlay, state.playing, panel) {
        overlayVisible = true
        if (state.playing && panel == null) {
            delay(OVERLAY_MS)
            overlayVisible = false
        }
    }
    BackHandler(enabled = panel != null) { panel = if (panel == DemandPanel.CONTROLS) null else DemandPanel.CONTROLS }
    val playKey = remember { FocusRequester() }
    val audioKey = remember { FocusRequester() }
    val subtitlesKey = remember { FocusRequester() }
    // The key that opened a track list gets focus back when the list closes.
    var returnKey by remember { mutableStateOf<FocusRequester?>(null) }
    LaunchedEffect(panel) {
        when (panel) {
            null -> {
                returnKey = null
                runCatching { focus.requestFocus() }
            }
            DemandPanel.CONTROLS -> {
                withFrameNanos { }
                runCatching { (returnKey ?: playKey).requestFocus() }
            }
            else -> Unit
        }
    }

    Box(
        modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focus)
            .onKeyEvent { event ->
                if (panel != null) return@onKeyEvent false
                if (event.key in ConfirmKeys) {
                    // On key-up, so a control focused by this press doesn't take its release as a click.
                    if (event.type == KeyEventType.KeyUp) {
                        if (event.key == Key.Menu || event.key == Key.Info) panel = DemandPanel.CONTROLS else viewModel.togglePlay()
                        overlay++
                    }
                    return@onKeyEvent true
                }
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
            .focusable()
            .pointerInput(Unit) {
                detectTapGestures {
                    panel = if (panel == null) DemandPanel.CONTROLS else null
                    overlay++
                }
            },
    ) {
        Video(viewModel.player)
        when (state.status) {
            OnDemandStatus.ERROR -> Message(state.message ?: "This can't be played.")
            OnDemandStatus.LOADING -> Message("Loading…")
            else -> if (state.buffering) Message("Loading…")
        }
        if (overlayVisible || panel != null || !state.playing) {
            // Read again whenever the playback state moves: the keys stay on screen across episodes.
            val audio = remember(state, panel) { viewModel.audioOptions().firstOrNull { it.selected }?.short }
            val subtitles = remember(state, panel) { viewModel.subtitleOptions().firstOrNull { it.selected }?.short }
            Overlay(
                state = state,
                viewModel = viewModel,
                modifier = Modifier.align(Alignment.BottomCenter),
                keys = when (panel) {
                    null, DemandPanel.CONTROLS -> {
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
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun Video(player: androidx.media3.common.Player) {
    AndroidView(
        factory = { context ->
            PlayerView(context).apply {
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                useController = false
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                setShutterBackgroundColor(android.graphics.Color.BLACK)
                keepScreenOn = true
                isFocusable = false
            }
        },
        update = { view ->
            view.player = player
            // libass draws ASS subtitles in a layer of its own over the video.
            view.subtitleView?.let { SubtitleOverlays.attach(it, player) }
        },
        onRelease = { view ->
            view.subtitleView?.let(SubtitleOverlays::detach)
            view.player = null
        },
        modifier = Modifier.fillMaxSize(),
    )
}

/**
 * The on-demand banner: ON DEMAND or PAUSED with the stream's technical line beside it, the
 * title, the time in with the bar and the length, what plays next and the [keys] under a
 * hairline. Phones and tablets set what plays next beside the detail line, and their ladder
 * takes taps and drags.
 */
@Composable
private fun Overlay(
    state: OnDemandState,
    viewModel: OnDemandPlayerViewModel,
    modifier: Modifier,
    keys: @Composable RowScope.() -> Unit,
) {
    val theme = LocalAnstelleTheme.current
    val isTv = LocalIsTv.current
    var position by remember { mutableLongStateOf(viewModel.positionMs) }
    var duration by remember { mutableLongStateOf(viewModel.durationMs) }
    LaunchedEffect(Unit) {
        while (true) {
            position = viewModel.positionMs
            duration = viewModel.durationMs
            delay(POSITION_MS)
        }
    }
    val typography = androidx.compose.material3.MaterialTheme.typography
    Column(
        modifier
            .fillMaxWidth()
            .padding(12.dp)
            .background(theme.banner, PanelShape)
            .border(1.dp, theme.line, PanelShape),
    ) {
        // Hand-held sizes off TV: the touch keys close the column, so it needs no bottom padding.
        Column(
            Modifier.padding(start = 16.dp, end = 16.dp, top = if (isTv) 14.dp else 12.dp, bottom = if (isTv) 14.dp else 0.dp),
            verticalArrangement = Arrangement.spacedBy(if (isTv) 4.dp else 2.dp),
        ) {
            BannerHeader(state.stream) {
                androidx.compose.material3.Text(
                    if (state.playing) "ON DEMAND" else "PAUSED",
                    style = typography.labelMedium,
                    color = theme.accent,
                )
            }
            TitleLogo(
                logoUrl = state.logoUrl,
                title = state.title.orEmpty(),
                height = if (isTv) 56.dp else 28.dp,
                maxLogoWidth = if (isTv) 360.dp else 220.dp,
                style = if (isTv) typography.headlineMedium else typography.headlineSmall,
                modifier = Modifier.padding(top = if (isTv) 0.dp else 2.dp),
            )
            val detail = lineUnderLogo(state.detail, state.title.orEmpty(), state.isEpisode, state.logoUrl != null)
            if (isTv) {
                detail?.let { DetailLine(it) }
            } else if (detail != null || state.next != null) {
                // This and next on one line, as an OSD pairs them.
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    DetailLine(detail.orEmpty(), Modifier.weight(1f))
                    state.next?.let { NextLine(it, Modifier.widthIn(max = NEXT_MAX_WIDTH)) }
                }
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
            if (isTv) state.next?.let { NextLine(it) }
            OsdKeys(Modifier.padding(top = if (isTv) 2.dp else 0.dp), keys)
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

/** What plays next, behind a hollow marker. */
@Composable
private fun NextLine(next: String, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        AirMarker(onAir = false)
        androidx.compose.material3.Text(
            "NEXT  $next",
            style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
            color = LocalAnstelleTheme.current.textMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
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
                .background(theme.banner, PanelShape)
                .border(1.dp, theme.line, PanelShape)
                .padding(horizontal = 24.dp, vertical = 14.dp),
        )
    }
}


private const val BACK_MS = 10_000L
private const val AHEAD_MS = 30_000L
private const val OVERLAY_MS = 4_000L
private const val POSITION_MS = 500L

/** How much of the detail line NEXT may take on phones and tablets. */
private val NEXT_MAX_WIDTH = 360.dp

private val ConfirmKeys = setOf(Key.DirectionCenter, Key.Enter, Key.NumPadEnter, Key.Menu, Key.Info)
