package tv.anstelle.feature.guide

import tv.anstelle.core.theme.components.obscured
import tv.anstelle.core.data.profiles.Restriction
import tv.anstelle.core.model.ProfileAccess
import tv.anstelle.core.theme.components.OffAirMark
import tv.anstelle.core.theme.components.RestrictedMark
import tv.anstelle.core.theme.components.StandInSize
import android.view.View
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
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
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
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
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

import kotlinx.coroutines.delay
import tv.anstelle.core.data.guide.ProgramDetails
import tv.anstelle.core.data.lineUnderLogo
import tv.anstelle.core.data.guide.ProgramStrip
import tv.anstelle.core.data.ondemand.AiringTense
import tv.anstelle.core.data.ondemand.WatchChoice
import tv.anstelle.core.data.ondemand.WatchChoices
import tv.anstelle.core.data.ondemand.WatchKind
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
import tv.anstelle.core.theme.components.DialogAction
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
    onPlayOnDemand: (itemId: Long, positionMs: Long) -> Unit,
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
        val rows = theme.spec.guide.density
        val rowHeight = if (isTv) rows.tvRowHeightDp else rows.rowHeightDp
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
            headerHeight = if (isTv) 26.dp else 32.dp,
            dpPerMinute = if (isTv) 5f else 4f,
            showDetailLine = rowHeight >= 50,
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
    LaunchedEffect(state.channels) {
        if (state.channels.none { it.id == grid.selectedChannel }) {
            val playing = viewModel.playbackState.value.channel?.id
            val index = state.channels.indexOfFirst { it.id == playing }.coerceAtLeast(0)
            state.channels.getOrNull(index)?.let { grid.reset(it.id, System.currentTimeMillis()) }
            listState.scrollToItem(index)
        }
    }
    // The preview opened on a channel (at launch, or back from the full-screen player): the selection follows it, its row at the top.
    LaunchedEffect(follow, state.channels) {
        val channelId = follow ?: return@LaunchedEffect
        val index = state.channels.indexOfFirst { it.id == channelId }
        if (index >= 0) {
            grid.selectNow(channelId, System.currentTimeMillis())
            listState.scrollToItem(index)
        }
        viewModel.followed()
    }
    LaunchedEffect(grid) {
        androidx.compose.runtime.snapshotFlow { grid.windowStart to grid.spanMs }.collect { (start, span) ->
            if (span > 0) viewModel.showing(start, start + span)
        }
    }
    if (isTv) LaunchedEffect(Unit) { runCatching { gridFocus.requestFocus() } }

    val selected by remember(state.channels, state.access) {
        derivedStateOf { selectedProgram(state.channels, strips.value, grid, state.access) }
    }
    LaunchedEffect(selected?.channel?.id) {
        selected?.let { viewModel.focus(it.channel.id) }
    }
    // Browsing that rests on a program airing now shows its channel in the preview.
    LaunchedEffect(selected?.channel?.id, selected?.startUtcMs, actions == null) {
        val program = selected ?: return@LaunchedEffect
        if (actions != null) return@LaunchedEffect
        delay(AUTO_PREVIEW_MS)
        if (program.isOn(System.currentTimeMillis()) && viewModel.playbackState.value.channel?.id != program.channel.id) {
            viewModel.preview(program.channel.id)
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
            // A movie's year is its whole detail line; the loaded details say for sure when they match.
            val isMovie = details?.takeIf { it.mediaItemId == program.mediaItemId }?.isEpisode?.not() ?: (program.detail?.toIntOrNull() != null)
            val choices = remember(program, isMovie) { programChoices(program, isMovie, nowMs, canAdd = onAddToChannel != null) }
            // Nothing to offer (a restricted program still to come, on a member's profile): no dialog.
            if (choices.isEmpty()) {
                LaunchedEffect(program) { actions = null }
                return@Box
            }
            ProgramActions(
                program = program,
                choices = choices,
                nowMs = nowMs,
                onWatch = { openPlayer(program.channel.number) },
                onStartOver = {
                    viewModel.openingPlayer()
                    onStartOver(program.channel.number)
                },
                onPlay = { position -> onPlayOnDemand(program.mediaItemId, position) },
                onAddToChannel = { onAddToChannel?.invoke(program.mediaItemId) },
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
    return SelectedProgram(channel, strip.items[i], strip.titles[i], strip.details[i], strip.starts[i], strip.ends[i], Restriction.titleFor(strip.items[i], access).takeIf { strip.isRestricted(i, access) })
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
                Modifier.align(Alignment.BottomStart).fillMaxWidth().background(theme.banner).onSizeChanged { strip.intValue = it.height },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                androidx.compose.material3.Text(
                    "${channel.number}",
                    color = theme.onAccent,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = theme.displayFont,
                    style = androidx.compose.ui.text.TextStyle(fontFeatureSettings = "tnum"),
                    maxLines = 1,
                    modifier = Modifier.background(theme.accent).padding(horizontal = 6.dp, vertical = 2.dp),
                )
                androidx.compose.material3.Text(
                    channel.name,
                    color = theme.bannerText,
                    fontSize = 12.sp,
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
                        fontSize = 11.sp,
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
            val titleHeight = if (isTv) 56.dp else 30.dp
            val channel = selected?.channel ?: playback.channel?.let { GuideChannel(it.id, it.number, it.name, it.logoUri, it.isFavorite) }
            // The title's slot, with the channel's ident in the top-end corner beside it, inside the slot's height.
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (offAir) {
                    // Off air has no title: the preview's mark takes its place, in the slot's height.
                    Box(Modifier.weight(1f).height(titleHeight), contentAlignment = Alignment.BottomStart) {
                        OffAirMark(size = StandInSize.PANEL, alignStart = true)
                    }
                } else {
                    TitleLogo(
                        logoUrl = artwork?.logoUrl,
                        title = title,
                        height = titleHeight,
                        maxLogoWidth = if (isTv) 320.dp else 200.dp,
                        style = androidx.compose.ui.text.TextStyle(
                            fontSize = if (isTv) 24.sp else 20.sp,
                            fontWeight = FontWeight.SemiBold,
                            fontFamily = theme.displayFont,
                        ),
                        // A restricted program's title and logo are blurred.
                        modifier = Modifier.weight(1f).obscured(restricted, theme.textMuted, radius = 10.dp),
                    )
                }
                channel?.let { DetailsIdent(it) }
            }
            // The episode (or a movie's title and year) in full ink, then its facts quieter on the same line.
            val lead = lineUnderLogo(detail, title, isEpisode = more?.isEpisode ?: true, hasLogo = artwork?.logoUrl != null)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                // The episode (blurred when restricted), then its facts, which stay readable.
                lead?.let {
                    androidx.compose.material3.Text(
                        it,
                        color = theme.text,
                        fontSize = 13.sp,
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
                        fontSize = 13.sp,
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
                    fontSize = 13.sp,
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
                Box(Modifier.padding(top = 3.dp).height(with(LocalDensity.current) { (16.sp * overviewLines).toDp() })) {
                    RestrictedMark(restriction.title, size = StandInSize.LINE, alignStart = true)
                }
                return@Column
            }
            androidx.compose.material3.Text(
                more?.overview.orEmpty(),
                color = theme.text,
                fontSize = 12.sp,
                lineHeight = 16.sp,
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
private fun DetailsIdent(channel: GuideChannel) {
    val theme = LocalAnstelleTheme.current
    val isTv = LocalIsTv.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        channel.logo?.let { ChannelLogo(it, height = if (isTv) 56.dp else 30.dp) }
        Column(horizontalAlignment = if (channel.logo != null) Alignment.Start else Alignment.End) {
            androidx.compose.material3.Text(
                "${channel.number}",
                color = theme.accent,
                fontSize = if (isTv) 18.sp else 13.sp,
                lineHeight = if (isTv) 21.sp else 15.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = theme.displayFont,
                style = androidx.compose.ui.text.TextStyle(fontFeatureSettings = "tnum"),
                maxLines = 1,
            )
            androidx.compose.material3.Text(
                channel.name,
                color = theme.text,
                fontSize = if (isTv) 12.sp else 11.sp,
                lineHeight = if (isTv) 15.sp else 13.sp,
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
    if (program.mediaItemId == 0L) {
        return if (tense == AiringTense.ON_NOW) listOf(WatchChoices.liveOffAir(program.endUtcMs)) else emptyList()
    }
    val add = WatchChoices.addToChannel(isMovie).takeIf { canAdd }
    // Something the profile may not watch: the channel stays dark, and nothing plays on demand.
    if (program.restricted) {
        return listOfNotNull(WatchChoices.liveRestricted(program.endUtcMs, program.restrictedAs ?: Restriction.AGE_TITLE).takeIf { tense == AiringTense.ON_NOW }, add)
    }
    val watch = if (tense == AiringTense.ON_NOW) {
        WatchChoices.onNow(channel.number, channel.name, program.startUtcMs, program.endUtcMs, nowMs, isEpisode = !isMovie)
    } else {
        listOf(WatchChoices.fromStart(tense, program.startUtcMs))
    }
    return watch + listOfNotNull(add)
}

/** What can be done with the selected program: [choices] under a header saying what and when. */
@Composable
private fun ProgramActions(
    program: SelectedProgram,
    choices: List<WatchChoice>,
    nowMs: Long,
    onWatch: () -> Unit,
    onStartOver: () -> Unit,
    onPlay: (positionMs: Long) -> Unit,
    onAddToChannel: () -> Unit,
    onDismiss: () -> Unit,
) {
    val theme = LocalAnstelleTheme.current
    val actions = choices.map { choice ->
        when (choice.kind) {
            WatchKind.LIVE -> DialogAction(choice.title, choice.detail, Icons.Filled.LiveTv, onWatch)
            WatchKind.RESTART -> DialogAction(choice.title, choice.detail, Icons.Filled.Replay, onStartOver)
            WatchKind.PLAY -> DialogAction(choice.title, choice.detail, Icons.Filled.PlayArrow) { onPlay(choice.positionMs) }
            WatchKind.ADD_TO_CHANNEL -> DialogAction(choice.title, choice.detail, Icons.AutoMirrored.Filled.PlaylistAdd, onAddToChannel)
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
            AiringTense.ON_NOW -> StatusLine(true, "On now", "$range  ·  ${WatchChoices.span(program.endUtcMs - nowMs, roundUp = true)} left")
            AiringTense.LATER -> StatusLine(false, "Later", range)
            AiringTense.AIRED -> StatusLine(null, "Aired", range)
        }
    }
}

private fun timeRange(startUtcMs: Long, endUtcMs: Long) = WatchChoices.clockRange(startUtcMs, endUtcMs)

private const val CLOCK_MS = 30_000L

/** How long browsing rests on a program airing now before its channel shows in the preview. */
private const val AUTO_PREVIEW_MS = 2_000L

/**
 * Lines kept for the selected program's overview. TV keeps fewer, paying for a logo large
 * enough to read from the couch without taking channels from the grid.
 */
private const val OVERVIEW_LINES = 5
private const val TV_OVERVIEW_LINES = 3

/** Narrower than this (a phone held upright), the preview sits over the details instead of beside them. */
private const val STACK_BELOW_DP = 600
