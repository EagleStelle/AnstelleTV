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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Forward30
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import tv.anstelle.core.theme.components.FocusTile
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
 * Full-screen on-demand playback. Unlike a channel it can be paused and sought: OK plays or
 * pauses, left and right jump back 10 s and ahead 30 s, up or Menu opens the controls
 * (audio, subtitles, next episode). Phones tap for the controls.
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
    LaunchedEffect(overlay, state.playing, panel) {
        overlayVisible = true
        if (state.playing && panel == null) {
            delay(OVERLAY_MS)
            overlayVisible = false
        }
    }
    BackHandler(enabled = panel != null) { panel = if (panel == DemandPanel.CONTROLS) null else DemandPanel.CONTROLS }
    LaunchedEffect(panel) { if (panel == null) runCatching { focus.requestFocus() } }

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
            Overlay(state, viewModel, Modifier.align(Alignment.BottomCenter))
        }
        when (panel) {
            DemandPanel.CONTROLS -> DemandControls(
                state = state,
                isTv = isTv,
                onPlay = viewModel::togglePlay,
                onBack = { viewModel.seekBy(-BACK_MS) },
                onAhead = { viewModel.seekBy(AHEAD_MS) },
                onNext = {
                    panel = null
                    viewModel.next()
                },
                onAudio = { panel = DemandPanel.AUDIO },
                onSubtitles = { panel = DemandPanel.SUBTITLES },
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 32.dp),
            )
            DemandPanel.AUDIO -> Tracks("Audio", viewModel.audioOptions(), Modifier.align(Alignment.CenterEnd)) {
                viewModel.selectAudio(it)
                panel = null
            }
            DemandPanel.SUBTITLES -> Tracks("Subtitles", viewModel.subtitleOptions(), Modifier.align(Alignment.CenterEnd)) {
                viewModel.selectSubtitles(it)
                panel = null
            }
            null -> Unit
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

/** Title, position and a seek bar. On phones the bar can be dragged. */
@Composable
private fun Overlay(state: OnDemandState, viewModel: OnDemandPlayerViewModel, modifier: Modifier) {
    val theme = LocalAnstelleTheme.current
    val isTv = LocalIsTv.current
    var position by remember { mutableLongStateOf(viewModel.positionMs) }
    var duration by remember { mutableLongStateOf(viewModel.durationMs) }
    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        while (true) {
            if (!dragging) position = viewModel.positionMs
            duration = viewModel.durationMs
            delay(POSITION_MS)
        }
    }
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 24.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(theme.banner.copy(alpha = 0.92f))
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(state.title ?: "", size = 24, weight = FontWeight.SemiBold)
        state.detail?.let { Text(it, size = 16, color = theme.textMuted) }
        val fraction = if (dragging) dragFraction else if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
        if (isTv) {
            Box(Modifier.padding(top = 6.dp).fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(theme.surfaceVariant)) {
                Box(Modifier.fillMaxWidth(fraction).height(6.dp).background(theme.primary))
            }
        } else {
            androidx.compose.material3.Slider(
                value = fraction,
                onValueChange = {
                    dragging = true
                    dragFraction = it
                },
                onValueChangeFinished = {
                    viewModel.seekTo((dragFraction * duration).toLong())
                    position = (dragFraction * duration).toLong()
                    dragging = false
                },
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            val shown = if (dragging) (dragFraction * duration).toLong() else position
            Text(
                "${clock(shown)} / ${clock(duration)}" + if (state.transcoded) "  ·  converted by the server" else "",
                size = 14,
                color = theme.textMuted,
            )
            state.next?.let { Text("Next: $it", size = 14, color = theme.secondary) }
        }
    }
}

@Composable
private fun DemandControls(
    state: OnDemandState,
    isTv: Boolean,
    onPlay: () -> Unit,
    onBack: () -> Unit,
    onAhead: () -> Unit,
    onNext: () -> Unit,
    onAudio: () -> Unit,
    onSubtitles: () -> Unit,
    modifier: Modifier,
) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        val tile = Modifier.width(if (isTv) 150.dp else 96.dp)
        FocusTile(if (state.playing) "Pause" else "Play", onClick = onPlay, icon = if (state.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, modifier = tile.focusRequester(first))
        FocusTile("Back 10 s", onClick = onBack, icon = Icons.Filled.Replay10, modifier = tile)
        FocusTile("Ahead 30 s", onClick = onAhead, icon = Icons.Filled.Forward30, modifier = tile)
        if (state.next != null) FocusTile("Next episode", onClick = onNext, icon = Icons.Filled.SkipNext, modifier = tile)
        FocusTile("Audio", onClick = onAudio, icon = Icons.Filled.Audiotrack, modifier = tile)
        FocusTile("Subtitles", onClick = onSubtitles, icon = Icons.Filled.Subtitles, modifier = tile)
    }
}

@Composable
private fun Tracks(title: String, options: List<TrackOption>, modifier: Modifier, onPick: (TrackOption) -> Unit) {
    val theme = LocalAnstelleTheme.current
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    Column(
        modifier
            .padding(32.dp)
            .width(420.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(theme.banner.copy(alpha = 0.95f))
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(title, size = 24, weight = FontWeight.SemiBold)
        if (options.isEmpty()) Text("This one has none.", size = 18, color = theme.textMuted)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            itemsIndexed(options) { i, option ->
                FocusTile(
                    title = option.label,
                    icon = if (option.selected) Icons.Filled.Check else null,
                    onClick = { onPick(option) },
                    modifier = Modifier.fillMaxWidth().let { if (i == 0) it.focusRequester(first) else it },
                )
            }
        }
    }
}

@Composable
private fun Message(text: String) {
    val theme = LocalAnstelleTheme.current
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text,
            size = 20,
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(theme.banner.copy(alpha = 0.85f))
                .padding(horizontal = 24.dp, vertical = 14.dp),
        )
    }
}

@Composable
private fun Text(
    text: String,
    size: Int,
    modifier: Modifier = Modifier,
    weight: FontWeight = FontWeight.Normal,
    color: Color = LocalAnstelleTheme.current.text,
) {
    androidx.compose.material3.Text(
        text,
        modifier = modifier,
        color = color,
        fontSize = size.sp,
        fontWeight = weight,
        fontFamily = LocalAnstelleTheme.current.bodyFont,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
}

/** "12:34" or "1:02:03". */
private fun clock(ms: Long): String {
    val seconds = ms.coerceAtLeast(0) / 1000
    val h = seconds / 3600
    val m = seconds % 3600 / 60
    val s = seconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

private const val BACK_MS = 10_000L
private const val AHEAD_MS = 30_000L
private const val OVERLAY_MS = 4_000L
private const val POSITION_MS = 500L

private val ConfirmKeys = setOf(Key.DirectionCenter, Key.Enter, Key.NumPadEnter, Key.Menu, Key.Info)
