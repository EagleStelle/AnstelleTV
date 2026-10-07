package tv.anstelle.feature.guide

import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
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
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.tv.material3.ExperimentalTvMaterial3Api
import kotlinx.coroutines.delay
import tv.anstelle.core.data.guide.ProgramDetails
import tv.anstelle.core.data.guide.ProgramStrip
import tv.anstelle.core.data.playback.PlaybackState
import tv.anstelle.core.data.playback.TuneStatus
import tv.anstelle.core.player.SubtitleOverlays
import tv.anstelle.core.theme.LocalAnstelleTheme
import tv.anstelle.core.theme.LocalIsTv
import tv.anstelle.core.theme.components.FocusTile
import tv.anstelle.core.theme.components.PlaceholderAction
import tv.anstelle.core.theme.components.PlaceholderScreen
import java.text.DateFormat
import java.util.Date

/** The cell the selection is on. [mediaItemId] is 0 off air. */
@Immutable
internal data class SelectedProgram(
    val channel: GuideChannel,
    val mediaItemId: Long,
    val title: String,
    val detail: String?,
    val startUtcMs: Long,
    val endUtcMs: Long,
) {
    fun isOn(nowMs: Long) = nowMs in startUtcMs until endUtcMs
}

/**
 * The channel guide: a preview window playing the current channel, the selected program's
 * details, group tabs (all channels, favorites, each group) and the program grid.
 *
 * OK (or a tap) on a program airing now shows its channel in the preview window, and again
 * watches it in full screen. On any other program, or held down (or Menu), it offers to play
 * it on demand from the beginning or from here, or to add its show to a channel.
 */
@Composable
fun GuideScreen(
    onWatch: (channelNumber: Int) -> Unit,
    onPlayOnDemand: (itemId: Long, positionMs: Long) -> Unit,
    onAddToChannel: (itemId: Long) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: GuideViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val strips = viewModel.strips.collectAsStateWithLifecycle()
    val details by viewModel.details.collectAsStateWithLifecycle()
    val playback by viewModel.playbackState.collectAsStateWithLifecycle()
    val player by viewModel.player.collectAsStateWithLifecycle()
    val isTv = LocalIsTv.current
    val theme = LocalAnstelleTheme.current

    LifecycleStartEffect(viewModel) {
        viewModel.startPreview()
        onStopOrDispose { viewModel.stopPreview() }
    }

    if (!state.loaded) return
    if (!state.hasChannels) {
        val actions = remember(onOpenSettings) {
            listOf(PlaceholderAction("Settings", "Connect a Jellyfin server, then add channels", Icons.Filled.Settings, onOpenSettings))
        }
        PlaceholderScreen(
            title = "Guide",
            description = "No channels yet. They are created from templates after your library syncs, or built in Settings, Channels.",
            actions = actions,
            modifier = modifier,
        )
        return
    }

    val now = rememberClock()
    val density = LocalDensity.current
    val metrics = remember(isTv, theme) {
        GridMetrics(
            rowHeight = theme.spec.guide.density.rowHeightDp.dp,
            channelWidth = if (isTv) 176.dp else 96.dp,
            headerHeight = 32.dp,
            dpPerMinute = if (isTv) 6f else 4f,
            showDetailLine = theme.spec.guide.density.rowHeightDp >= 56,
            touch = !isTv,
        )
    }
    val pxPerMs = with(density) { metrics.dpPerMinute.dp.toPx() } / 60_000f
    val grid = remember(pxPerMs) { viewModel.grid(pxPerMs) }
    val listState = rememberLazyListState()
    val gridFocus = remember { FocusRequester() }
    var actions by remember { mutableStateOf<SelectedProgram?>(null) }

    LaunchedEffect(grid) { grid.catchUp(now.value) }
    // A new tab (or the first load) selects the channel on now, when the tab has it, or the first one.
    LaunchedEffect(state.channels) {
        if (state.channels.none { it.id == grid.selectedChannel }) {
            val playing = viewModel.playbackState.value.channel?.id
            val index = state.channels.indexOfFirst { it.id == playing }.coerceAtLeast(0)
            state.channels.getOrNull(index)?.let { grid.reset(it.id) }
            listState.scrollToItem(index)
        }
    }
    LaunchedEffect(grid) {
        androidx.compose.runtime.snapshotFlow { grid.windowStart to grid.spanMs }.collect { (start, span) ->
            if (span > 0) viewModel.showing(start, start + span)
        }
    }
    if (isTv) LaunchedEffect(Unit) { runCatching { gridFocus.requestFocus() } }

    val selected by remember(state.channels) {
        derivedStateOf { selectedProgram(state.channels, strips.value, grid) }
    }
    LaunchedEffect(selected?.channel?.id, selected?.mediaItemId) {
        selected?.let { viewModel.focus(it.channel.id, it.mediaItemId) }
    }

    fun inPreview(channel: GuideChannel) = viewModel.playbackState.value.channel?.id == channel.id

    /** The first OK on a channel shows it in the preview window, the next one watches it full screen. */
    fun watch(channel: GuideChannel) {
        if (inPreview(channel)) onWatch(channel.number) else viewModel.preview(channel.id)
    }

    fun activate() {
        val program = selected ?: return
        if (program.isOn(System.currentTimeMillis())) watch(program.channel) else actions = program
    }

    /** A tap on a program airing now previews its channel; on the previewed channel, or a second tap, it acts like OK. */
    fun cellTapped(again: Boolean) {
        val program = selected ?: return
        val onNow = program.isOn(System.currentTimeMillis())
        when {
            onNow && !inPreview(program.channel) -> viewModel.preview(program.channel.id)
            again -> activate()
        }
    }

    Box(modifier.fillMaxSize().background(theme.background)) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = if (isTv) 32.dp else 12.dp, vertical = if (isTv) 20.dp else 8.dp),
            verticalArrangement = Arrangement.spacedBy(if (isTv) 10.dp else 6.dp),
        ) {
            Row(
                Modifier.fillMaxWidth().height(if (isTv) 150.dp else 104.dp),
                horizontalArrangement = Arrangement.spacedBy(if (isTv) 20.dp else 10.dp),
            ) {
                Preview(
                    player = player,
                    playback = playback,
                    onWatch = { playback.channel?.let { onWatch(it.number) } },
                    modifier = Modifier.fillMaxHeight(),
                )
                Details(selected, details, playback, Modifier.weight(1f).fillMaxHeight())
            }
            Tabs(state.tabs, state.tab, viewModel::selectTab)
            if (state.channels.isEmpty()) {
                androidx.compose.material3.Text(
                    if (state.tab == GuideViewModel.TAB_FAVORITES) {
                        "No favorite channels yet. Mark favorites in Settings, Channels."
                    } else {
                        "No channels here."
                    },
                    color = theme.textMuted,
                    fontFamily = theme.bodyFont,
                    modifier = Modifier.padding(16.dp),
                )
            } else {
                GuideGrid(
                    channels = state.channels,
                    strips = strips,
                    grid = grid,
                    listState = listState,
                    now = now,
                    metrics = metrics,
                    focusRequester = gridFocus,
                    onActivate = ::activate,
                    onActions = { actions = selected },
                    onCellTap = ::cellTapped,
                    onChannelTap = ::watch,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                )
            }
        }
        actions?.let { program ->
            ProgramActions(
                program = program,
                onWatch = { onWatch(program.channel.number) },
                onPlay = { position -> onPlayOnDemand(program.mediaItemId, position) },
                onAddToChannel = { onAddToChannel(program.mediaItemId) },
                onDismiss = {
                    actions = null
                    runCatching { gridFocus.requestFocus() }
                },
            )
        }
    }
}

/** The program under the selection, from the loaded cells. */
private fun selectedProgram(channels: List<GuideChannel>, strips: Map<Long, ProgramStrip>, grid: GuideGridState): SelectedProgram? {
    val channel = channels.firstOrNull { it.id == grid.selectedChannel } ?: return null
    val strip = strips[channel.id] ?: return null
    val i = strip.indexAt(grid.selectedTime)
    if (i < 0) return null
    return SelectedProgram(channel, strip.items[i], strip.titles[i], strip.details[i], strip.starts[i], strip.ends[i])
}

/** The wall clock, updated twice a minute; read it in drawing or layout to avoid recomposition. */
@Composable
private fun rememberClock(): State<Long> {
    val clock = remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            clock.longValue = System.currentTimeMillis()
            delay(CLOCK_MS)
        }
    }
    return clock
}

/** The channel on now in a small window. Selecting it watches in full screen. */
@OptIn(UnstableApi::class)
@Composable
private fun Preview(player: Player?, playback: PlaybackState, onWatch: () -> Unit, modifier: Modifier) {
    val theme = LocalAnstelleTheme.current
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier
            .aspectRatio(16f / 9f)
            .clip(shape)
            .background(Color.Black)
            .border(2.dp, if (focused) theme.focus else Color.Transparent, shape)
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onWatch),
    ) {
        AndroidView(
            factory = { context ->
                PlayerView(context).apply {
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    useController = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                    setKeepContentOnPlayerReset(true)
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
        val status = when (playback.status) {
            TuneStatus.OFF_AIR -> "Off air"
            TuneStatus.TUNING -> "Tuning…"
            TuneStatus.NO_SCHEDULE, TuneStatus.ERROR -> "Not playing"
            else -> null
        }
        playback.channel?.let { channel ->
            androidx.compose.material3.Text(
                listOfNotNull("${channel.number}  ${channel.name}", status).joinToString("  ·  "),
                color = Color.White,
                fontSize = 12.sp,
                fontFamily = theme.bodyFont,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .background(Color(0x99000000))
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }
    }
}

/** The selected program, or what the preview plays while nothing is selected. */
@Composable
private fun Details(selected: SelectedProgram?, details: ProgramDetails?, playback: PlaybackState, modifier: Modifier) {
    val theme = LocalAnstelleTheme.current
    val isTv = LocalIsTv.current
    val title: String
    val detail: String?
    val time: String
    if (selected != null) {
        title = selected.title
        detail = selected.detail
        time = "${timeRange(selected.startUtcMs, selected.endUtcMs)}  ·  ${selected.channel.number} ${selected.channel.name}"
    } else {
        val on = playback.now ?: return Box(modifier)
        title = on.title
        detail = on.detail
        time = timeRange(on.startUtcMs, on.endUtcMs)
    }
    val more = details?.takeIf { selected != null && it.mediaItemId == selected.mediaItemId }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        androidx.compose.material3.Text(
            title,
            color = theme.text,
            fontSize = if (isTv) 24.sp else 18.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = theme.displayFont,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        detail?.let {
            androidx.compose.material3.Text(it, color = theme.text, fontSize = if (isTv) 16.sp else 13.sp, fontFamily = theme.bodyFont, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.Text(
                time,
                color = theme.secondary,
                fontSize = if (isTv) 14.sp else 12.sp,
                fontFamily = theme.bodyFont,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            more?.badges?.forEach { Badge(it) }
        }
        if (more != null && more.facts.isNotEmpty()) {
            androidx.compose.material3.Text(more.facts, color = theme.textMuted, fontSize = if (isTv) 13.sp else 11.sp, fontFamily = theme.bodyFont, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        more?.overview?.let {
            androidx.compose.material3.Text(
                it,
                color = theme.textMuted,
                fontSize = if (isTv) 13.sp else 11.sp,
                fontFamily = theme.bodyFont,
                maxLines = if (isTv) 2 else 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun Badge(text: String) {
    val theme = LocalAnstelleTheme.current
    androidx.compose.material3.Text(
        text,
        color = theme.text,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        fontFamily = theme.bodyFont,
        modifier = Modifier
            .border(1.dp, theme.textMuted, RoundedCornerShape(4.dp))
            .padding(horizontal = 5.dp, vertical = 1.dp),
    )
}

@kotlin.OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun Tabs(tabs: List<GuideTab>, open: String, onSelect: (String) -> Unit) {
    val isTv = LocalIsTv.current
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(tabs, key = { it.key }) { tab ->
            val selected = tab.key == open
            if (isTv) {
                androidx.tv.material3.FilterChip(selected = selected, onClick = { onSelect(tab.key) }) {
                    androidx.tv.material3.Text(tab.label)
                }
            } else {
                androidx.compose.material3.FilterChip(
                    selected = selected,
                    onClick = { onSelect(tab.key) },
                    label = { androidx.compose.material3.Text(tab.label) },
                )
            }
        }
    }
}

/** What can be done with the selected program. */
@Composable
private fun ProgramActions(
    program: SelectedProgram,
    onWatch: () -> Unit,
    onPlay: (positionMs: Long) -> Unit,
    onAddToChannel: () -> Unit,
    onDismiss: () -> Unit,
) {
    val theme = LocalAnstelleTheme.current
    val first = remember { FocusRequester() }
    BackHandler(onBack = onDismiss)
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    val now = remember { System.currentTimeMillis() }
    val onNow = program.isOn(now)
    val elapsed = now - program.startUtcMs
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xB3000000))
            .pointerInput(Unit) { detectTapGestures { onDismiss() } },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .widthIn(max = 520.dp)
                .padding(24.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(theme.surface)
                // Taps on the panel itself don't close it.
                .pointerInput(Unit) { detectTapGestures { } }
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            androidx.compose.material3.Text(program.title, color = theme.text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, fontFamily = theme.displayFont)
            androidx.compose.material3.Text(
                listOfNotNull(program.detail, timeRange(program.startUtcMs, program.endUtcMs), "${program.channel.number} ${program.channel.name}").joinToString("  ·  "),
                color = theme.textMuted,
                fontSize = 14.sp,
                fontFamily = theme.bodyFont,
            )
            val tile = Modifier.fillMaxWidth()
            var focusUsed = false
            fun Modifier.firstFocus(): Modifier = if (focusUsed) this else this.focusRequester(first).also { focusUsed = true }
            if (onNow) {
                FocusTile("Watch channel ${program.channel.number}", onClick = onWatch, subtitle = "Join it live.", icon = Icons.Filled.LiveTv, modifier = tile.firstFocus())
            }
            if (program.mediaItemId != 0L) {
                FocusTile(
                    "Start from beginning",
                    onClick = { onPlay(0) },
                    subtitle = "Play it on demand, with pause and seeking.",
                    icon = Icons.Filled.Replay,
                    modifier = tile.firstFocus(),
                )
                if (onNow && elapsed >= MIN_RESUME_MS) {
                    FocusTile(
                        "Watch from here",
                        onClick = { onPlay(elapsed) },
                        subtitle = "On demand from ${minutes(elapsed)} in, with pause and seeking.",
                        icon = Icons.Filled.PlayArrow,
                        modifier = tile,
                    )
                }
                FocusTile(
                    "Add to channel",
                    onClick = onAddToChannel,
                    subtitle = "Always air it on a channel of your choice.",
                    icon = Icons.AutoMirrored.Filled.PlaylistAdd,
                    modifier = tile,
                )
            }
            FocusTile("Close", onClick = onDismiss, icon = Icons.Filled.Close, modifier = tile.firstFocus())
        }
    }
}

private fun time(utcMs: Long): String = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(utcMs))

private fun timeRange(startUtcMs: Long, endUtcMs: Long) = "${time(startUtcMs)} – ${time(endUtcMs)}"

private fun minutes(ms: Long): String {
    val minutes = ms / 60_000
    return if (minutes < 60) "$minutes min" else "${minutes / 60} h ${minutes % 60} min"
}

private const val CLOCK_MS = 30_000L
private const val MIN_RESUME_MS = 60_000L
