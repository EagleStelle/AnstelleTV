package tv.anstelle.feature.guide

import tv.anstelle.core.theme.components.obscured
import tv.anstelle.core.data.profiles.Restriction
import tv.anstelle.core.model.ProfileAccess
import tv.anstelle.core.theme.components.OffAirMark
import tv.anstelle.core.theme.components.RestrictedMark
import tv.anstelle.core.theme.components.StandInSize
import android.view.View
import tv.anstelle.core.model.MediaProgress
import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect

import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView

import kotlinx.coroutines.delay
import tv.anstelle.core.data.guide.ProgramDetails
import tv.anstelle.core.data.lineUnderLogo
import tv.anstelle.core.data.guide.ProgramStrip
import tv.anstelle.core.data.ondemand.AiringTense
import tv.anstelle.core.model.WatchChoice
import tv.anstelle.core.data.ondemand.WatchChoices
import tv.anstelle.core.model.WatchKind
import tv.anstelle.core.data.playback.PlaybackState
import tv.anstelle.core.data.playback.TuneStatus
import tv.anstelle.core.player.SubtitleOverlays
import tv.anstelle.core.player.SubtitleStyle
import tv.anstelle.core.theme.LocalAnstelleTheme
import tv.anstelle.core.theme.LocalIsTv
import tv.anstelle.core.theme.components.ActionDialog
import tv.anstelle.core.theme.components.AirMarker
import tv.anstelle.core.theme.components.Backdrop
import tv.anstelle.core.theme.components.ChannelLogo
import tv.anstelle.core.theme.components.LogoSize
import tv.anstelle.core.theme.components.dialogActions
import tv.anstelle.core.theme.components.NumberBlock
import tv.anstelle.core.theme.components.OsdBadge
import tv.anstelle.core.theme.components.OsdTab
import tv.anstelle.core.theme.components.StatusLine
import tv.anstelle.core.theme.components.screenEdge
import tv.anstelle.core.theme.components.screenGap
import tv.anstelle.core.theme.components.screenSide
import tv.anstelle.core.theme.components.TitleLogo
import tv.anstelle.core.theme.components.PlaceholderAction
import tv.anstelle.core.theme.components.PlaceholderScreen

/** The cell the selection is on. [mediaItemId] is 0 off air. */
@Immutable
internal data class SelectedProgram(
    val channel: GuideChannel,
    val mediaItemId: Long,
    val title: String,
    val detail: String?,
    val startUtcMs: Long,
    val endUtcMs: Long,
    /** Why the profile may not watch it ("Age restricted", "No access"), or null: then it is blacked out, and never played on demand. */
    val restrictedAs: String? = null,
    val isEpisode: Boolean = false,
) {
    val restricted: Boolean get() = restrictedAs != null

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
    onStartOver: (channelNumber: Int) -> Unit,
    /** Plays [itemId] on demand; [channelId] and [atUtcMs] are the airing it was picked from. */
    onPlayOnDemand: (itemId: Long, positionMs: Long, channelId: Long, atUtcMs: Long) -> Unit,
    /** Opens the selected movie's or episode's page in On Demand. */
    onOpenOnDemand: (itemId: Long, isEpisode: Boolean) -> Unit,
    /** Null for a member: adding to a channel is the owner's and admins'. */
    onAddToChannel: ((itemId: Long) -> Unit)?,
    /** Null for a member, who has no channels or servers to set up. */
    onOpenSettings: (() -> Unit)?,
    modifier: Modifier = Modifier,
    viewModel: GuideViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val strips = viewModel.strips.collectAsStateWithLifecycle()
    val details by viewModel.details.collectAsStateWithLifecycle()
    val playback by viewModel.playbackState.collectAsStateWithLifecycle()
    val player by viewModel.player.collectAsStateWithLifecycle()
    val follow by viewModel.follow.collectAsStateWithLifecycle()
    val isTv = LocalIsTv.current
    val theme = LocalAnstelleTheme.current
    val lifecycleOwner = LocalLifecycleOwner.current

    LifecycleStartEffect(viewModel) {
        viewModel.startPreview()
        onStopOrDispose { viewModel.stopPreview() }
    }

    if (!state.loaded) return
    if (!state.hasChannels) {
        val actions = remember(onOpenSettings) {
            listOfNotNull(onOpenSettings?.let { PlaceholderAction("Settings", "Connect a Jellyfin server, then add channels", Icons.Filled.Settings, it) })
        }
        PlaceholderScreen(
            title = "Live TV",
            description = if (onOpenSettings != null) {
                "No channels yet. They are made from your library after it syncs, or built in Settings, Channels."
            } else {
                "No channels yet. They appear once the owner or an admin connects a Jellyfin server."
            },
            actions = actions,
            modifier = modifier,
        )
        return
    }

    val now = rememberClock()
    val density = LocalDensity.current
    val metrics = remember(isTv, theme, state.hasLogos) {
        val rowHeight = theme.spec.guide.density.rowHeightDp
        GridMetrics(
            rowHeight = rowHeight.dp,
            // Logos take the column's start, so it widens to keep the names their room.
            channelWidth = when {
                isTv -> if (state.hasLogos) 172.dp else 148.dp
                else -> if (state.hasLogos) 132.dp else 96.dp
            },
            // The logo's box fills the row less its air, capped so the name keeps its width.
            channelLogoHeight = (rowHeight - if (isTv) 16 else 20).coerceIn(22, if (isTv) 36 else 28).dp,
            // A phone truncates a long name on one line; TV may break it over two.
            channelNameLines = if (isTv && rowHeight >= 50) 2 else 1,
            headerHeight = 26.dp,
            dpPerMinute = if (isTv) 5f else 4f,
            touch = !isTv,
        )
    }
    val pxPerMs = with(density) { metrics.dpPerMinute.dp.toPx() } / 60_000f
    val grid = remember(pxPerMs) { viewModel.grid(pxPerMs) }
    val listState = rememberLazyListState()
    val gridFocus = remember { FocusRequester() }
    var actions by remember { mutableStateOf<SelectedProgram?>(null) }

    LaunchedEffect(grid) { grid.catchUp(now.value) }
    // A new tab (or the first load) selects what airs now on the channel in the preview, when the tab has it, or on the first one.
    LaunchedEffect(state.channels, follow) {
        if (follow != null) return@LaunchedEffect
        if (state.channels.none { it.id == grid.selectedChannel }) {
            val playing = viewModel.playbackState.value.channel?.id
            val index = state.channels.indexOfFirst { it.id == playing }.coerceAtLeast(0)
            state.channels.getOrNull(index)?.let { grid.reset(it.id, System.currentTimeMillis()) }
            listState.scrollToItem(index)
        }
    }
    // The preview opened on a channel (at launch, or back from the full-screen player): the selection follows it, its row at the top.
    LaunchedEffect(lifecycleOwner, follow, state.channels, state.tab) {
        val channelId = follow ?: return@LaunchedEffect
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            val index = state.channels.indexOfFirst { it.id == channelId }
            if (index < 0) {
                if (state.tab != GuideViewModel.TAB_ALL) viewModel.selectTab(GuideViewModel.TAB_ALL)
                return@repeatOnLifecycle
            }
            val nowMs = System.currentTimeMillis()
            grid.catchUp(nowMs)
            grid.selectNow(channelId, nowMs)
            listState.scrollToItem(index)
            viewModel.followed()
        }
    }
    LaunchedEffect(grid) {
        androidx.compose.runtime.snapshotFlow { grid.windowStart to grid.spanMs }.collect { (start, span) ->
            if (span > 0) viewModel.showing(start, start + span)
        }
    }
    // Every time the guide opens, what airs now on the selected channel is selected, and on TV highlighted with focus.
    val hasChannels = state.channels.isNotEmpty()
    LaunchedEffect(lifecycleOwner, hasChannels) {
        if (!hasChannels) return@LaunchedEffect
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            if (isTv) {
                // The grid composes after the channels arrive; take focus once it is there.
                repeat(20) {
                    withFrameNanos { }
                    if (runCatching { gridFocus.requestFocus() }.getOrDefault(false)) return@repeatOnLifecycle
                }
            }
        }
    }

    val selected by remember(state.channels, state.access) {
        derivedStateOf { selectedProgram(state.channels, strips.value, grid, state.access) }
    }
    // Browsing that rests on a program airing now shows its channel in the preview.
    LaunchedEffect(lifecycleOwner, selected?.channel?.id, selected?.startUtcMs, actions == null, follow) {
        val program = selected ?: return@LaunchedEffect
        if (actions != null || follow != null) return@LaunchedEffect
        lifecycleOwner.lifecycle.autoPreview {
            if (actions != null || !lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return@autoPreview
            val latest = selected ?: return@autoPreview
            if (latest.channel.id == program.channel.id && latest.startUtcMs == program.startUtcMs &&
                latest.isOn(System.currentTimeMillis()) && viewModel.playbackState.value.channel?.id != latest.channel.id) {
                viewModel.preview(latest.channel.id)
            }
        }
    }
    // A selection on the program airing now moves on with it when it ends, so OK keeps watching the channel live.
    LaunchedEffect(selected?.channel?.id, selected?.startUtcMs) {
        val program = selected ?: return@LaunchedEffect
        if (!program.isOn(System.currentTimeMillis())) return@LaunchedEffect
        delay(program.endUtcMs - System.currentTimeMillis())
        if (grid.selectedChannel == program.channel.id && grid.selectedTime < program.endUtcMs) {
            grid.selectNow(program.channel.id, System.currentTimeMillis())
        }
    }
    // The preview can't show a program that isn't on now, so the details stay on what it plays.
    val described by remember(state.channels, state.access) {
        derivedStateOf { selected?.takeIf { it.isOn(now.value) } ?: playingProgram(playback) }
    }
    LaunchedEffect(described?.mediaItemId) {
        described?.let { viewModel.describe(it.mediaItemId) }
    }

    fun inPreview(channel: GuideChannel) = viewModel.playbackState.value.channel?.id == channel.id

    fun openPlayer(number: Int) {
        viewModel.openingPlayer()
        onWatch(number)
    }

    /** The first OK on a channel shows it in the preview window, the next one watches it full screen. */
    fun watch(channel: GuideChannel) {
        if (inPreview(channel)) openPlayer(channel.number) else viewModel.preview(channel.id)
    }

    fun activate() {
        val program = selected ?: return
        when {
            program.isOn(System.currentTimeMillis()) -> watch(program.channel)
            // An off-air stretch to come offers nothing to watch.
            program.mediaItemId != 0L -> actions = program
        }
    }

    fun hold() {
        val program = selected ?: return
        if (program.mediaItemId != 0L || program.isOn(System.currentTimeMillis())) actions = program
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

    // A phone held upright stacks the preview over the details, both edge to edge.
    val stacked = !isTv && with(LocalDensity.current) { LocalWindowInfo.current.containerSize.width.toDp() } < STACK_BELOW_DP.dp
    val side = screenSide
    Box(modifier.fillMaxSize().background(theme.background)) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = if (stacked) 0.dp else side, vertical = if (stacked) 0.dp else screenEdge),
            verticalArrangement = Arrangement.spacedBy(screenGap),
        ) {
            if (stacked) {
                // The details hang straight off the preview: one band of picture down the top.
                Column(Modifier.fillMaxWidth()) {
                    Preview(
                        player = player,
                        playback = playback,
                        onWatch = { playback.channel?.let { openPlayer(it.number) } },
                        edgeToEdge = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Details(described, details, playback, state, edgeToEdge = true, modifier = Modifier.fillMaxWidth())
                }
            } else {
                // The preview and the details meet with no gap: one band of picture across the top.
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                    Preview(
                        player = player,
                        playback = playback,
                        onWatch = { playback.channel?.let { openPlayer(it.number) } },
                        modifier = Modifier.fillMaxHeight(),
                    )
                    Details(described, details, playback, state, Modifier.weight(1f))
                }
            }
            Tabs(state.tabs, state.tab, viewModel::selectTab, Modifier.padding(horizontal = if (stacked) side else 0.dp))
            if (state.channels.isEmpty()) {
                androidx.compose.material3.Text(
                    if (state.tab == GuideViewModel.TAB_FAVORITES) {
                        if (onOpenSettings != null) "No favorite channels yet. Mark favorites in Settings, Channels." else "No favorite channels yet."
                    } else {
                        "No channels here."
                    },
                    color = theme.textMuted,
                    fontFamily = theme.bodyFont,
                    modifier = Modifier.padding(horizontal = if (stacked) side + 16.dp else 16.dp, vertical = 16.dp),
                )
            } else {
                GuideGrid(
                    channels = state.channels,
                    strips = strips,
                    access = state.access,
                    grid = grid,
                    listState = listState,
                    now = now,
                    metrics = metrics,
                    focusRequester = gridFocus,
                    onActivate = ::activate,
                    onActions = ::hold,
                    onCellTap = ::cellTapped,
                    onChannelTap = ::watch,
                    modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = if (stacked) side else 0.dp),
                )
            }
        }
        actions?.let { program ->
            val nowMs = remember(program) { System.currentTimeMillis() }
            // Use media identity even before details load; movies may have no year.
            val isMovie = details?.takeIf { it.mediaItemId == program.mediaItemId }?.isEpisode?.not() ?: !program.isEpisode
            val choices = remember(program, isMovie) { programChoices(program, isMovie, nowMs, canAdd = onAddToChannel != null) }
            // Nothing to offer (a restricted program still to come, on a member's profile): no dialog.
            if (choices.isEmpty()) {
                LaunchedEffect(program) { actions = null }
                return@Box
            }
            ProgramActions(
                program = program,
                choices = choices,
                isEpisode = !isMovie,
                nowMs = nowMs,
                onWatch = { openPlayer(program.channel.number) },
                onStartOver = {
                    viewModel.openingPlayer()
                    onStartOver(program.channel.number)
                },
                onPlay = { position -> onPlayOnDemand(program.mediaItemId, position, program.channel.id, program.startUtcMs) },
                onAddToChannel = { onAddToChannel?.invoke(program.mediaItemId) },
                onOpenDetails = {
                    actions = null
                    onOpenOnDemand(program.mediaItemId, !isMovie)
                },
                onDismiss = {
                    actions = null
                    runCatching { gridFocus.requestFocus() }
                },
            )
        }
    }
}

/** The program under the selection, from the loaded cells. */
private fun selectedProgram(channels: List<GuideChannel>, strips: Map<Long, ProgramStrip>, grid: GuideGridState, access: ProfileAccess): SelectedProgram? {
    val channel = channels.firstOrNull { it.id == grid.selectedChannel } ?: return null
    val strip = strips[channel.id] ?: return null
    val i = strip.indexAt(grid.selectedTime)
    if (i < 0) return null
    return SelectedProgram(
        channel, strip.items[i], strip.titles[i], strip.details[i], strip.starts[i], strip.ends[i],
        Restriction.titleFor(strip.items[i], access).takeIf { strip.isRestricted(i, access) },
        isEpisode = strip.shows[i] != strip.items[i],
    )
}

/** What the preview plays, as a program in the guide; null before it has tuned. */
private fun playingProgram(playback: PlaybackState): SelectedProgram? {
    val channel = playback.channel ?: return null
    val on = playback.now ?: return null
    return SelectedProgram(
        GuideChannel(channel.id, channel.number, channel.name, channel.logoUri, channel.isFavorite),
        on.mediaItemId,
        on.title,
        on.detail,
        on.startUtcMs,
        on.endUtcMs,
        playback.restriction?.title,
        isEpisode = on.isEpisode,
    )
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

/**
 * The channel on now in a window. Selecting it watches in full screen. [edgeToEdge] spans the
 * screen's width with no frame, as on a phone held upright.
 */
@OptIn(UnstableApi::class)
@Composable
private fun Preview(player: Player?, playback: PlaybackState, onWatch: () -> Unit, modifier: Modifier, edgeToEdge: Boolean = false) {
    val theme = LocalAnstelleTheme.current
    var focused by remember { mutableStateOf(false) }
    // Beside the details it only rounds its outer corners, so the two meet square.
    val shape = if (edgeToEdge) RectangleShape else RoundedCornerShape(topStart = 2.dp, bottomStart = 2.dp)
    // The channel strip's height, in pixels: the subtitles keep clear of it.
    val strip = remember { mutableIntStateOf(0) }
    Box(
        modifier
            .aspectRatio(16f / 9f)
            .clip(shape)
            .background(Color.Black)
            .border(if (focused) 3.dp else if (edgeToEdge) 0.dp else 1.dp, if (focused) theme.accent else theme.line, shape)
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
                    subtitleView?.let { subtitles ->
                        SubtitleStyle.applyTo(subtitles)
                        // The picture's frame takes the video's shape once it is known, after any update.
                        subtitles.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> keepSubtitlesClear(strip.intValue) }
                    }
                }
            },
            update = { view ->
                view.player = player
                // libass draws ASS subtitles in a layer of its own over the video.
                view.subtitleView?.let { SubtitleOverlays.attach(it, player) }
                view.keepSubtitlesClear(strip.intValue)
            },
            onRelease = { view ->
                view.subtitleView?.let(SubtitleOverlays::detach)
                view.player = null
            },
            modifier = Modifier.fillMaxSize(),
        )
        // Off air or restricted: the ground stands in for the picture, with the player's own mark.
        if (playback.status == TuneStatus.OFF_AIR || playback.status == TuneStatus.RESTRICTED) {
            Box(Modifier.fillMaxSize().background(theme.background), contentAlignment = Alignment.Center) {
                val until = playback.now?.let { WatchChoices.clockTime(it.endUtcMs) }
                val inset = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 20.dp)
                if (playback.status == TuneStatus.OFF_AIR) {
                    OffAirMark(inset, StandInSize.PANEL, until)
                } else {
                    RestrictedMark(playback.restriction?.title ?: Restriction.AGE_TITLE, inset, StandInSize.PANEL, until)
                }
            }
        }
        val status = when (playback.status) {
            TuneStatus.OFF_AIR -> "Off air"
            TuneStatus.RESTRICTED -> playback.restriction?.title ?: Restriction.AGE_TITLE
            TuneStatus.TUNING -> "Tuning…"
            TuneStatus.NO_SCHEDULE, TuneStatus.ERROR -> "Not playing"
            else -> null
        }
        playback.channel?.let { channel ->
            Row(
                Modifier.align(Alignment.BottomStart).fillMaxWidth().background(theme.surface).onSizeChanged { strip.intValue = it.height },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                androidx.compose.material3.Text(
                    "${channel.number}",
                    color = theme.onAccent,
                    fontSize = theme.sp(12f),
                    fontWeight = FontWeight.Bold,
                    fontFamily = theme.displayFont,
                    style = androidx.compose.ui.text.TextStyle(fontFeatureSettings = "tnum"),
                    maxLines = 1,
                    modifier = Modifier.background(theme.accent).padding(horizontal = 6.dp, vertical = 2.dp),
                )
                androidx.compose.material3.Text(
                    channel.name,
                    color = theme.text,
                    fontSize = theme.sp(12f),
                    fontFamily = theme.bodyFont,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false).padding(horizontal = 6.dp),
                )
                // The status keeps its own slot, so a long channel name can never push it out of view.
                status?.let {
                    androidx.compose.material3.Text(
                        it.uppercase(),
                        color = theme.accent,
                        fontSize = theme.sp(11f),
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = theme.displayFont,
                        maxLines = 1,
                        modifier = Modifier.padding(end = 6.dp),
                    )
                }
            }
        }
    }
}

/**
 * Keeps the picture's subtitles (text cues and the libass layer inside the subtitle view) clear
 * of the channel strip, [stripPx] tall, across the preview's foot: the layer shrinks evenly
 * toward the picture's top edge until its foot meets the strip, so dialogue sits just above it,
 * signs at the top stay put and an ASS layer keeps its proportions. A picture letterboxed clear
 * of the strip keeps its subtitles at full size.
 */
@OptIn(UnstableApi::class)
private fun PlayerView.keepSubtitlesClear(stripPx: Int) {
    val subtitles = subtitleView ?: return
    // The picture's frame: the subtitle view fills it.
    val frame = subtitles.parent as? View ?: return
    // How far the strip reaches up into the picture; a letterbox bar under it takes some or all.
    val overlap = stripPx - (height - frame.bottom)
    val scale = if (overlap > 0 && subtitles.height > overlap) 1f - overlap.toFloat() / subtitles.height else 1f
    subtitles.pivotX = subtitles.width / 2f
    subtitles.pivotY = 0f
    subtitles.scaleX = scale
    subtitles.scaleY = scale
}

/**
 * The selected program while it is on now, otherwise what the preview plays, over its show's
 * backdrop, its channel's ident in the top-end corner. The show's logo stands in for its title
 * when the server has one. Five lines are
 * kept for the overview whether or not it fills them, so the grid never jumps as the
 * selection moves. A program the profile may not watch shows no artwork or
 * overview: the restriction takes the overview's place. Off air, the off-air mark takes the
 * title's.
 */
@Composable
private fun Details(
    selected: SelectedProgram?,
    details: ProgramDetails?,
    playback: PlaybackState,
    state: GuideUiState,
    modifier: Modifier,
    edgeToEdge: Boolean = false,
) {
    val theme = LocalAnstelleTheme.current
    val isTv = LocalIsTv.current
    val title: String
    val detail: String?
    val time: String
    if (selected != null) {
        title = selected.title
        detail = selected.detail
        // The channel is named by the ident in the top-end corner.
        time = timeRange(selected.startUtcMs, selected.endUtcMs)
    } else {
        val on = playback.now
        title = on?.title.orEmpty()
        detail = on?.detail
        time = on?.let { timeRange(it.startUtcMs, it.endUtcMs) }.orEmpty()
    }
    val more = details?.takeIf { selected != null && it.mediaItemId == selected.mediaItemId }
    val restriction = when {
        selected == null -> playback.restriction
        more != null -> Restriction.of(more.mediaItemId, more.ratingScore, state.access)
        selected.restricted -> if (selected.restrictedAs == Restriction.NO_ACCESS_TITLE) Restriction.NoAccess else Restriction.Age
        else -> null
    }
    // Artwork belongs to the show, so it stays put while the selection moves through one show's episodes.
    val artwork = details?.takeIf { selected != null && it.title == selected.title }?.artwork
    val restricted = restriction != null
    val offAir = if (selected != null) selected.mediaItemId == 0L else playback.status == TuneStatus.OFF_AIR
    Backdrop(
        // A restricted program's picture is blacked out.
        url = artwork?.backdropUrl?.takeUnless { restricted },
        modifier = modifier.clip(if (edgeToEdge) RectangleShape else RoundedCornerShape(topEnd = 2.dp, bottomEnd = 2.dp)),
        alignment = Alignment.Center,
    ) {
        Column(
            Modifier.padding(horizontal = if (isTv) 16.dp else 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            val logoHeight = if (isTv) LogoSize.Standard else LogoSize.Compact
            val channel = selected?.channel ?: playback.channel?.let { GuideChannel(it.id, it.number, it.name, it.logoUri, it.isFavorite) }
            // The title's slot, with the channel's ident in the top-end corner beside it, inside the slot's height.
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (offAir) {
                    // Off air has no title: the preview's mark takes its place, in the slot's height.
                    Box(Modifier.weight(1f).height(logoHeight), contentAlignment = Alignment.BottomStart) {
                        OffAirMark(size = StandInSize.PANEL, alignStart = true)
                    }
                } else {
                    TitleLogo(
                        logoUrl = artwork?.logoUrl,
                        title = title,
                        height = logoHeight,
                        style = androidx.compose.ui.text.TextStyle(
                            fontSize = if (isTv) theme.sp(24f) else theme.sp(20f),
                            fontWeight = FontWeight.SemiBold,
                            fontFamily = theme.displayFont,
                        ),
                        // A restricted program's title and logo are blurred.
                        modifier = Modifier.weight(1f).obscured(restricted, theme.textMuted, radius = 10.dp),
                    )
                }
                channel?.let { DetailsIdent(it, logoHeight) }
            }
            // The episode (or a movie's title and year) in full ink, then its facts quieter on the same line.
            val lead = lineUnderLogo(detail, title, isEpisode = more?.isEpisode ?: true, hasLogo = artwork?.logoUrl != null)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                // The episode (blurred when restricted), then its facts, which stay readable.
                lead?.let {
                    androidx.compose.material3.Text(
                        it,
                        color = theme.text,
                        fontSize = theme.sp(13f),
                        fontFamily = theme.bodyFont,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false).obscured(restricted, theme.textMuted, radius = 5.dp),
                    )
                }
                if (more != null && more.facts.isNotEmpty()) {
                    androidx.compose.material3.Text(
                        if (lead != null) "·  ${more.facts}" else more.facts,
                        color = theme.textMuted,
                        fontSize = theme.sp(13f),
                        fontFamily = theme.bodyFont,
                        maxLines = 1,
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                if (selected != null) AirMarker(onAir = selected.isOn(System.currentTimeMillis()))
                androidx.compose.material3.Text(
                    time,
                    color = theme.accent,
                    fontSize = theme.sp(13f),
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = theme.displayFont,
                    style = androidx.compose.ui.text.TextStyle(fontFeatureSettings = "tnum"),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                more?.badges?.forEach { Badge(it.uppercase()) }
            }
            val overviewLines = if (isTv) TV_OVERVIEW_LINES else OVERVIEW_LINES
            if (restriction != null) {
                // The overview's lines stay reserved, so the grid holds still.
                Box(Modifier.padding(top = 3.dp).height(with(LocalDensity.current) { (theme.sp(16f) * overviewLines).toDp() })) {
                    RestrictedMark(restriction.title, size = StandInSize.LINE, alignStart = true)
                }
                return@Column
            }
            androidx.compose.material3.Text(
                more?.overview.orEmpty(),
                color = theme.text,
                fontSize = theme.sp(12f),
                lineHeight = theme.sp(16f),
                fontFamily = theme.bodyFont,
                minLines = overviewLines,
                maxLines = overviewLines,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 3.dp),
            )
        }
    }
}

/**
 * The channel in the details' top-end corner, on the backdrop like the player's corner bug:
 * its logo in the standard box with its number over its name beside it, or the number over
 * the name alone, set to the corner. It never takes more than the title slot's height.
 */
@Composable
private fun DetailsIdent(channel: GuideChannel, logoHeight: Dp) {
    val theme = LocalAnstelleTheme.current
    val isTv = LocalIsTv.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        channel.logo?.let { ChannelLogo(it, height = logoHeight) }
        Column(horizontalAlignment = if (channel.logo != null) Alignment.Start else Alignment.End) {
            androidx.compose.material3.Text(
                "${channel.number}",
                color = theme.accent,
                fontSize = if (isTv) theme.sp(18f) else theme.sp(13f),
                lineHeight = if (isTv) theme.sp(21f) else theme.sp(15f),
                fontWeight = FontWeight.Bold,
                fontFamily = theme.displayFont,
                style = androidx.compose.ui.text.TextStyle(fontFeatureSettings = "tnum"),
                maxLines = 1,
            )
            androidx.compose.material3.Text(
                channel.name,
                color = theme.text,
                fontSize = if (isTv) theme.sp(12f) else theme.sp(11f),
                lineHeight = if (isTv) theme.sp(15f) else theme.sp(13f),
                fontFamily = theme.bodyFont,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = if (isTv) 160.dp else 104.dp),
            )
        }
    }
}

@Composable
private fun Badge(text: String) = OsdBadge(text)

@Composable
private fun Tabs(tabs: List<GuideTab>, open: String, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    val theme = LocalAnstelleTheme.current
    Box(modifier) {
        Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(1.dp).background(theme.line))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            items(tabs, key = { it.key }) { tab ->
                OsdTab(tab.label, selected = tab.key == open, onClick = { onSelect(tab.key) }, compact = LocalIsTv.current)
            }
        }
    }
}

/**
 * The ways to watch the selected program, in the words the channel's page in On demand uses
 * too. On now: the channel live, the channel from the program's start, or the program on
 * demand from its start or from where the channel is. Otherwise it plays on demand from the
 * start. Empty for an off-air stretch that isn't on now.
 */
private fun programChoices(program: SelectedProgram, isMovie: Boolean, nowMs: Long, canAdd: Boolean): List<WatchChoice> {
    val tense = AiringTense.of(program.startUtcMs, program.endUtcMs, nowMs)
    val channel = program.channel
    val add = WatchChoices.addToChannel(isMovie).takeIf { canAdd && program.mediaItemId != 0L }
    // Something the profile may not watch: the channel stays dark, and nothing plays on demand.
    val watch = WatchChoices.airing(
        tense, channel.number, channel.name, program.startUtcMs, program.endUtcMs, nowMs,
        isEpisode = !isMovie,
        offAir = program.mediaItemId == 0L,
        restrictionTitle = program.restrictedAs,
    )
    return watch + listOfNotNull(add)
}

/** What can be done with the selected program: [choices] under a header saying what and when. */
@Composable
private fun ProgramActions(
    program: SelectedProgram,
    choices: List<WatchChoice>,
    isEpisode: Boolean,
    nowMs: Long,
    onWatch: () -> Unit,
    onStartOver: () -> Unit,
    onPlay: (positionMs: Long) -> Unit,
    onAddToChannel: () -> Unit,
    onOpenDetails: () -> Unit,
    onDismiss: () -> Unit,
) {
    val theme = LocalAnstelleTheme.current
    val actions = choices.dialogActions(isEpisode, onOpenDetails) { choice ->
        when (choice.kind) {
            WatchKind.LIVE -> onWatch()
            WatchKind.RESTART -> onStartOver()
            WatchKind.PLAY -> onPlay(choice.positionMs)
            WatchKind.ADD_TO_CHANNEL -> onAddToChannel()
        }
    }
    ActionDialog(actions, onDismiss) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            NumberBlock(
                "${program.channel.number}",
                style = androidx.compose.material3.MaterialTheme.typography.headlineSmall,
                modifier = Modifier.height(36.dp),
            )
            androidx.compose.material3.Text(
                program.title,
                style = androidx.compose.material3.MaterialTheme.typography.headlineSmall,
                color = theme.text,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.obscured(program.restricted, theme.textMuted),
            )
        }
        androidx.compose.material3.Text(
            // A restricted program's episode is left out, like its blurred title.
            listOfNotNull(program.detail.takeUnless { program.restricted }, program.channel.name).joinToString("  ·  "),
            style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
            color = theme.textMuted,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        val range = WatchChoices.clockRange(program.startUtcMs, program.endUtcMs)
        when (AiringTense.of(program.startUtcMs, program.endUtcMs, nowMs)) {
            AiringTense.ON_NOW -> StatusLine(true, "On now", "$range  ·  ${MediaProgress.span(program.endUtcMs - nowMs, roundUp = true)} left")
            AiringTense.LATER -> StatusLine(false, "Later", range)
            AiringTense.AIRED -> StatusLine(null, "Aired", range)
        }
    }
}

private fun timeRange(startUtcMs: Long, endUtcMs: Long) = WatchChoices.clockRange(startUtcMs, endUtcMs)

private const val CLOCK_MS = 30_000L


/**
 * Lines kept for the selected program's overview. TV keeps fewer, paying for a logo large
 * enough to read from the couch without taking channels from the grid.
 */
private const val OVERVIEW_LINES = 4
private const val TV_OVERVIEW_LINES = 3

/** Narrower than this (a phone held upright), the preview sits over the details instead of beside them. */
private const val STACK_BELOW_DP = 600
