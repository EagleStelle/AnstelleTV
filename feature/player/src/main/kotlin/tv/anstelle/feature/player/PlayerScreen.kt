package tv.anstelle.feature.player

import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import tv.anstelle.core.data.playback.PlaybackState
import tv.anstelle.core.data.playback.ProgramInfo
import tv.anstelle.core.data.playback.TrackOption
import tv.anstelle.core.data.playback.TuneStatus
import tv.anstelle.core.theme.LocalAnstelleTheme
import tv.anstelle.core.theme.LocalIsTv
import tv.anstelle.core.theme.components.FocusTile
import java.text.DateFormat
import java.util.Date
import kotlin.math.abs

/** What the controls panel shows. */
private enum class Panel { CONTROLS, AUDIO, SUBTITLES }

/**
 * Full-screen channel playback, like a TV: up/down or the channel keys surf, digits enter a
 * number, left (or the last-channel key) jumps back, OK shows the info banner and then the
 * audio and subtitle choices. On phones, swipe up or down to surf and tap for the controls.
 */
@Composable
fun PlayerScreen(
    modifier: Modifier = Modifier,
    viewModel: PlayerViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val player by viewModel.player.collectAsStateWithLifecycle()
    val entry by viewModel.entry.collectAsStateWithLifecycle()
    val notice by viewModel.notice.collectAsStateWithLifecycle()
    val isTv = LocalIsTv.current
    val focus = remember { FocusRequester() }

    LifecycleStartEffect(viewModel) {
        viewModel.resume()
        onStopOrDispose { viewModel.pause() }
    }

    var bannerVisible by remember { mutableStateOf(true) }
    var bannerRequest by remember { mutableLongStateOf(0L) }
    LaunchedEffect(state.tuneCount, bannerRequest) {
        bannerVisible = true
        delay(BANNER_MS)
        bannerVisible = false
    }
    var panel by remember { mutableStateOf<Panel?>(null) }
    BackHandler(enabled = panel != null) { panel = if (panel == Panel.CONTROLS) null else Panel.CONTROLS }
    LaunchedEffect(panel) { if (panel == null) runCatching { focus.requestFocus() } }

    val swipeThreshold = with(LocalDensity.current) { 96.dp.toPx() }
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focus)
            .onKeyEvent { event ->
                if (panel != null) return@onKeyEvent false
                // OK opens things on key-up: on key-down, the newly focused control would get the key-up as a click.
                if (event.key in ConfirmKeys) {
                    if (event.type == KeyEventType.KeyUp) {
                        when {
                            event.key == Key.Info || event.key == Key.Menu -> panel = Panel.CONTROLS
                            entry.isNotEmpty() -> viewModel.commitEntry()
                            bannerVisible -> panel = Panel.CONTROLS
                            else -> bannerRequest++
                        }
                    }
                    return@onKeyEvent true
                }
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                digitOf(event.key)?.let {
                    viewModel.digit(it)
                    return@onKeyEvent true
                }
                when (event.key) {
                    Key.DirectionUp, Key.ChannelUp -> viewModel.channelUp()
                    Key.DirectionDown, Key.ChannelDown -> viewModel.channelDown()
                    Key.DirectionLeft, Key.LastChannel -> viewModel.lastChannel()
                    else -> return@onKeyEvent false
                }
                true
            }
            .focusable()
            .pointerInput(Unit) { detectTapGestures { panel = if (panel == null) Panel.CONTROLS else null } }
            .pointerInput(Unit) {
                var dragged = 0f
                detectVerticalDragGestures(
                    onDragStart = { dragged = 0f },
                    onDragEnd = {
                        if (abs(dragged) > swipeThreshold) if (dragged < 0) viewModel.channelUp() else viewModel.channelDown()
                    },
                    onVerticalDrag = { _, amount -> dragged += amount },
                )
            },
    ) {
        Video(player)

        when (state.status) {
            TuneStatus.OFF_AIR -> OffAir(state)
            TuneStatus.NO_SCHEDULE, TuneStatus.ERROR -> Message(state.message ?: "Nothing to play.")
            else -> if (state.buffering || player == null) Message("Tuning…", subtle = true)
        }

        if (bannerVisible || panel != null) {
            InfoBanner(state, Modifier.align(Alignment.BottomCenter))
        }
        if (entry.isNotEmpty()) {
            Text(
                entry.padEnd(1, '-'),
                size = if (isTv) 64 else 44,
                weight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.TopEnd).padding(32.dp),
            )
        }
        notice?.let { Message(it, modifier = Modifier.align(Alignment.TopCenter).padding(top = 32.dp)) }

        when (panel) {
            Panel.CONTROLS -> Controls(
                isTv = isTv,
                onAudio = { panel = Panel.AUDIO },
                onSubtitles = { panel = Panel.SUBTITLES },
                onLast = {
                    panel = null
                    viewModel.lastChannel()
                },
                onUp = viewModel::channelUp,
                onDown = viewModel::channelDown,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 32.dp),
            )
            Panel.AUDIO -> TrackList("Audio", viewModel.audioOptions(), Modifier.align(Alignment.CenterEnd)) {
                viewModel.selectAudio(it)
                panel = null
            }
            Panel.SUBTITLES -> TrackList("Subtitles", viewModel.subtitleOptions(), Modifier.align(Alignment.CenterEnd)) {
                viewModel.selectSubtitles(it)
                panel = null
            }
            null -> Unit
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun Video(player: androidx.media3.common.Player?) {
    AndroidView(
        factory = { context ->
            PlayerView(context).apply {
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                useController = false
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                setShutterBackgroundColor(android.graphics.Color.BLACK)
                // Surfing swaps players; keep the last frame instead of flashing black.
                setKeepContentOnPlayerReset(true)
                keepScreenOn = true
                isFocusable = false
            }
        },
        update = { view -> view.player = player },
        onRelease = { view -> view.player = null },
        modifier = Modifier.fillMaxSize(),
    )
}

/** Channel, the program on now with its progress, and what's next. */
@Composable
private fun InfoBanner(state: PlaybackState, modifier: Modifier) {
    val theme = LocalAnstelleTheme.current
    val channel = state.channel ?: return
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 24.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(theme.banner.copy(alpha = 0.92f))
            .padding(horizontal = 28.dp, vertical = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(28.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.widthIn(min = 96.dp)) {
            Text("${channel.number}", size = 48, weight = FontWeight.Bold, color = theme.primary)
            Text(channel.name, size = 16, color = theme.bannerText, maxLines = 1)
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val program = state.now
            Text(program?.title ?: "Tuning…", size = 28, weight = FontWeight.SemiBold, color = theme.bannerText, maxLines = 1)
            program?.detail?.let { Text(it, size = 18, color = theme.textMuted, maxLines = 1) }
            if (program != null) {
                Text(timeRange(program) + if (state.transcoded) "  ·  converted by the server" else "", size = 16, color = theme.textMuted)
                Progress(program, now)
            }
        }
        state.next?.let { next ->
            Column(modifier = Modifier.widthIn(max = 320.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Next at ${time(next.startUtcMs)}", size = 16, color = theme.secondary)
                Text(next.title, size = 20, color = theme.bannerText, maxLines = 2)
            }
        }
    }
}

@Composable
private fun Progress(program: ProgramInfo, nowUtcMs: Long) {
    val theme = LocalAnstelleTheme.current
    val length = (program.endUtcMs - program.startUtcMs).coerceAtLeast(1)
    val fraction = ((nowUtcMs - program.startUtcMs).toFloat() / length).coerceIn(0f, 1f)
    Box(
        Modifier
            .padding(top = 6.dp)
            .fillMaxWidth()
            .height(6.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(theme.surfaceVariant),
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction)
                .height(6.dp)
                .background(theme.primary),
        )
    }
}

@Composable
private fun OffAir(state: PlaybackState) {
    val theme = LocalAnstelleTheme.current
    Column(
        modifier = Modifier.fillMaxSize().background(theme.background),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("OFF AIR", size = 72, weight = FontWeight.Bold, color = theme.primary)
        Spacer(Modifier.height(12.dp))
        state.now?.let { Text("Back on the air at ${time(it.endUtcMs)}", size = 24, color = theme.text) }
        state.channel?.let { Text("${it.number}  ${it.name}", size = 18, color = theme.textMuted) }
    }
}

@Composable
private fun Message(text: String, modifier: Modifier = Modifier, subtle: Boolean = false) {
    val theme = LocalAnstelleTheme.current
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text,
            size = if (subtle) 18 else 22,
            color = if (subtle) theme.textMuted else theme.text,
            modifier = modifier
                .clip(RoundedCornerShape(12.dp))
                .background(theme.banner.copy(alpha = 0.85f))
                .padding(horizontal = 24.dp, vertical = 14.dp),
        )
    }
}

@Composable
private fun Controls(
    isTv: Boolean,
    onAudio: () -> Unit,
    onSubtitles: () -> Unit,
    onLast: () -> Unit,
    onUp: () -> Unit,
    onDown: () -> Unit,
    modifier: Modifier,
) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        val tile = Modifier.width(if (isTv) 200.dp else 120.dp)
        FocusTile("Audio", onClick = onAudio, icon = Icons.Filled.Audiotrack, modifier = tile.focusRequester(first))
        FocusTile("Subtitles", onClick = onSubtitles, icon = Icons.Filled.Subtitles, modifier = tile)
        FocusTile("Last channel", onClick = onLast, icon = Icons.AutoMirrored.Filled.Undo, modifier = tile)
        if (!isTv) {
            FocusTile("Up", onClick = onUp, icon = Icons.Filled.KeyboardArrowUp, modifier = tile)
            FocusTile("Down", onClick = onDown, icon = Icons.Filled.KeyboardArrowDown, modifier = tile)
        }
    }
}

@Composable
private fun TrackList(title: String, options: List<TrackOption>, modifier: Modifier, onPick: (TrackOption) -> Unit) {
    val theme = LocalAnstelleTheme.current
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    Column(
        modifier = modifier
            .padding(32.dp)
            .width(420.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(theme.banner.copy(alpha = 0.95f))
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(title, size = 24, weight = FontWeight.SemiBold, color = theme.bannerText)
        if (options.isEmpty()) Text("This program has none.", size = 18, color = theme.textMuted)
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
private fun Text(
    text: String,
    size: Int,
    modifier: Modifier = Modifier,
    weight: FontWeight = FontWeight.Normal,
    color: Color = LocalAnstelleTheme.current.text,
    maxLines: Int = Int.MAX_VALUE,
) {
    androidx.compose.material3.Text(
        text,
        modifier = modifier,
        color = color,
        fontSize = size.sp,
        fontWeight = weight,
        fontFamily = LocalAnstelleTheme.current.bodyFont,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}

private fun digitOf(key: Key): Int? = when (key) {
    Key.Zero, Key.NumPad0 -> 0
    Key.One, Key.NumPad1 -> 1
    Key.Two, Key.NumPad2 -> 2
    Key.Three, Key.NumPad3 -> 3
    Key.Four, Key.NumPad4 -> 4
    Key.Five, Key.NumPad5 -> 5
    Key.Six, Key.NumPad6 -> 6
    Key.Seven, Key.NumPad7 -> 7
    Key.Eight, Key.NumPad8 -> 8
    Key.Nine, Key.NumPad9 -> 9
    else -> null
}

private fun time(utcMs: Long): String = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(utcMs))

private fun timeRange(program: ProgramInfo) = "${time(program.startUtcMs)} – ${time(program.endUtcMs)}"

private const val BANNER_MS = 5_000L

private val ConfirmKeys = setOf(Key.DirectionCenter, Key.Enter, Key.NumPadEnter, Key.Info, Key.Menu)
