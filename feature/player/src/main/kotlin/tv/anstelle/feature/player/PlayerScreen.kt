package tv.anstelle.feature.player

import tv.anstelle.core.theme.components.obscured
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
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

import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind

import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import tv.anstelle.core.data.playback.PlaybackState
import tv.anstelle.core.data.playback.ProgramInfo
import tv.anstelle.core.data.playback.TuneStatus
import tv.anstelle.core.data.lineUnderLogo
import tv.anstelle.core.data.ondemand.WatchChoices
import tv.anstelle.core.data.profiles.Restriction
import tv.anstelle.core.model.Channel
import tv.anstelle.core.theme.LocalAnstelleTheme
import tv.anstelle.core.theme.LocalIsTv
import tv.anstelle.core.theme.components.ActionDialog
import tv.anstelle.core.theme.components.DialogAction
import tv.anstelle.core.theme.components.dialogActions
import tv.anstelle.core.theme.components.OffAirMark
import tv.anstelle.core.theme.components.RestrictedMark
import tv.anstelle.core.theme.components.StandInSize
import tv.anstelle.core.theme.components.AirMarker
import tv.anstelle.core.theme.components.ChannelLogo
import tv.anstelle.core.theme.components.SegmentLadder
import tv.anstelle.core.theme.components.WatchIcons

import java.text.DateFormat
import java.util.Date

/** A key that asks first, in a dialog over the picture. */
private enum class Confirm { START_OVER, ON_DEMAND }

/**
 * Full-screen channel playback, like a TV: up/down or the channel keys surf, digits enter a
 * number, left and right seek back 10 s and ahead 30 s (the channel's schedule moves with the
 * program). The banner always carries its keys (audio and subtitles when selectable, On demand and Start over); it
 * snaps in on every tune with a key highlighted on TV and goes by itself after a few seconds
 * with no key pressed. A seek brings it in with focus left on the picture, so left and right
 * keep seeking; OK, Info or Menu then moves focus into the keys, where left and right walk
 * them. On phones, swipe up or down to surf, tap for the banner and drag its bar to seek.
 */
@Composable
fun PlayerScreen(
    /** Plays the program on now on demand from [positionMs]; [channelId] and [atUtcMs] are its airing. */
    onPlayOnDemand: (itemId: Long, positionMs: Long, channelId: Long, atUtcMs: Long) -> Unit,
    /** Opens the current movie's or episode's page without starting on-demand playback. */
    onOpenOnDemand: (itemId: Long, isEpisode: Boolean) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PlayerViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val player by viewModel.player.collectAsStateWithLifecycle()
    val tracks = rememberPlayerTracks(player)
    val audioOptions = remember(state, player, tracks) { viewModel.audioOptions() }
    val subtitleOptions = remember(state, player, tracks) { viewModel.subtitleOptions() }
    val showAudio = audioOptions.size > 1
    val showSubtitles = subtitleOptions.any { it.groupIndex >= 0 }
    val entry by viewModel.entry.collectAsStateWithLifecycle()
    val notice by viewModel.notice.collectAsStateWithLifecycle()
    val isTv = LocalIsTv.current
    val theme = LocalAnstelleTheme.current
    val focus = remember { FocusRequester() }

    LifecycleStartEffect(viewModel) {
        viewModel.resume()
        onStopOrDispose { viewModel.pause() }
    }
    LandscapeFullScreen()

    var bannerVisible by remember { mutableStateOf(true) }
    var bannerRequest by remember { mutableLongStateOf(0L) }
    var confirm by remember { mutableStateOf<Confirm?>(null) }
    var panel by remember { mutableStateOf<PlayerPanel?>(null) }
    val browsingChoices = panel?.isPicker == true
    // Every tune brings the banner up with a key highlighted on TV; seeking leaves focus on the picture so left and right keep seeking.
    var tunedBanner by remember { mutableStateOf<Any?>(null) }
    // A finger is down in the banner: it stays up until the finger lifts, then waits again.
    var bannerTouched by remember { mutableStateOf(false) }
    LaunchedEffect(state.tuneCount, bannerRequest, confirm, bannerTouched, browsingChoices) {
        bannerVisible = true
        if (isTv && tunedBanner != state.tuneCount) {
            tunedBanner = state.tuneCount
            if (panel == null) panel = PlayerPanel.CONTROLS
        }
        if (confirm != null || bannerTouched || browsingChoices) return@LaunchedEffect
        // With no key pressed or touch for a while, the banner goes, highlighted key and all.
        delay(PLAYER_BANNER_MS)
        bannerVisible = false
        panel = null
    }
    // The banner's height, so the subtitles can step above it while it is up.
    var bannerHeight by remember { mutableIntStateOf(0) }
    fun closePanelOrBanner() {
        panel = panel.afterBack()
        bannerVisible = panel != null
    }
    // Android owns remote and system Back; a picker returns to its opener before the banner closes.
    BackHandler(enabled = bannerVisible && confirm == null) {
        closePanelOrBanner()
    }
    val audioKey = remember { FocusRequester() }
    val subtitlesKey = remember { FocusRequester() }
    val onDemandKey = remember { FocusRequester() }
    val startOverKey = remember { FocusRequester() }
    val onDemandProgram = state.now?.takeIf { it.mediaItemId != 0L && !it.restricted }
    val canPlayOnDemand = onDemandProgram != null
    // The key that opened a track list or a dialog gets focus back when it closes.
    var returnKey by remember { mutableStateOf<FocusRequester?>(null) }
    fun controlsFocus(): FocusRequester = returnKey?.takeUnless {
        it === audioKey && !showAudio || it === subtitlesKey && !showSubtitles
    } ?: onDemandKey
    fun keyModifier(key: FocusRequester): Modifier = Modifier
        .focusRequester(key)
        .onFocusChanged { if (it.isFocused) returnKey = key }
    LaunchedEffect(panel, confirm, showAudio, showSubtitles, canPlayOnDemand) {
        when (panel) {
            null -> {
                returnKey = null
                runCatching { focus.requestFocus() }
            }
            PlayerPanel.CONTROLS -> {
                // The banner may be hidden: bring it in, then take the key once it is composed.
                bannerRequest++
                if (confirm == null) controlsFocus().requestWhenShown()
            }
            PlayerPanel.AUDIO -> if (!showAudio) panel = PlayerPanel.CONTROLS
            PlayerPanel.SUBTITLES -> if (!showSubtitles) panel = PlayerPanel.CONTROLS
            PlayerPanel.EPISODES -> panel = PlayerPanel.CONTROLS // Episodes belongs to the on-demand player.
        }
    }

    // The picture itself holds focus, not one of the banner's keys.
    var pictureFocused by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focus)
            .onPreviewKeyEvent { event ->
                // Any key pressed with the banner's keys in use holds the banner up a while longer.
                if (panel != null && event.key != Key.Back && event.type == KeyEventType.KeyDown) bannerRequest++
                false
            }
            .onKeyEvent { event ->
                if (confirm != null || panel?.isPicker == true) return@onKeyEvent false
                val inKeys = panel == PlayerPanel.CONTROLS
                // OK opens things on key-up: on key-down, the newly focused control would get the key-up as a click.
                if (event.key in PlayerConfirmKeys) {
                    // With the keys up, OK is theirs; if none holds focus, it puts focus back on one.
                    if (inKeys && !pictureFocused) return@onKeyEvent false
                    if (inKeys) {
                        if (event.type == KeyEventType.KeyUp) runCatching { controlsFocus().requestFocus() }
                        return@onKeyEvent true
                    }
                    if (event.type == KeyEventType.KeyUp) {
                        val menu = event.key == Key.Info || event.key == Key.Menu
                        if (entry.isNotEmpty() && !menu) viewModel.commitEntry() else panel = PlayerPanel.CONTROLS
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
                    // Left and right walk the keys while they are up.
                    Key.DirectionLeft, Key.DirectionRight -> if (inKeys) {
                        return@onKeyEvent false
                    } else {
                        viewModel.seekBy(if (event.key == Key.DirectionLeft) -PLAYER_BACK_MS else PLAYER_AHEAD_MS)
                        bannerRequest++
                    }
                    Key.MediaRewind -> {
                        viewModel.seekBy(-PLAYER_BACK_MS)
                        bannerRequest++
                    }
                    Key.MediaFastForward -> {
                        viewModel.seekBy(PLAYER_AHEAD_MS)
                        bannerRequest++
                    }
                    else -> return@onKeyEvent false
                }
                true
            }
            .onFocusChanged { pictureFocused = it.isFocused }
            .focusable()
            // A tap hides the banner while it shows, and brings it back while it is hidden.
            .pointerInput(Unit) {
                detectTapGestures {
                    if (bannerVisible) {
                        panel = null
                        bannerVisible = false
                    } else {
                        bannerRequest++
                    }
                }
            }
            // Up and down change channel; left and right seek back and ahead, as the remote's arrows do.
            .onSwipe { swipe ->
                when (swipe) {
                    Swipe.UP -> viewModel.channelUp()
                    Swipe.DOWN -> viewModel.channelDown()
                    Swipe.LEFT, Swipe.RIGHT -> {
                        viewModel.seekBy(if (swipe == Swipe.LEFT) -PLAYER_BACK_MS else PLAYER_AHEAD_MS)
                        bannerRequest++
                    }
                }
            },
    ) {
        PlayerVideo(player, subtitleLift = if (bannerVisible) bannerHeight else 0, keepContentOnPlayerReset = true)

        when (state.status) {
            TuneStatus.OFF_AIR, TuneStatus.RESTRICTED -> StandIn(state)
            TuneStatus.NO_SCHEDULE, TuneStatus.ERROR -> Message(state.message ?: "Nothing to play.")
            else -> if (state.buffering || player == null) LoadingSpinner("Tuning")
        }

        // The banner and the ident come and go at once, as on demand's do: a fade would draw their shades through a layer, banded.
        if (bannerVisible) Box(
            Modifier
                .align(Alignment.BottomCenter)
                .onSizeChanged { bannerHeight = it.height }
                .onTouchHeld { bannerTouched = it },
        ) {
            // Read again whenever the playback state moves: the keys stay on screen across tunes.
            val audio = audioOptions.firstOrNull { it.selected }?.short
            val subtitles = subtitleOptions.firstOrNull { it.selected }?.short
            InfoBanner(
                state = state,
                // Phones drag the bar; the remote seeks with left and right.
                onSeek = if (isTv) null else { position: Long -> viewModel.seekTo(position).also { bannerRequest++ } },
                keys = when (panel) {
                    null, PlayerPanel.CONTROLS, PlayerPanel.EPISODES -> {
                        {
                            if (showAudio) OsdKey("Audio", Icons.Filled.Audiotrack, onClick = {
                                returnKey = audioKey
                                panel = PlayerPanel.AUDIO
                                bannerRequest++
                            }, keyModifier(audioKey), value = audio)
                            if (showSubtitles) OsdKey("Subtitles", Icons.Filled.Subtitles, onClick = {
                                returnKey = subtitlesKey
                                panel = PlayerPanel.SUBTITLES
                                bannerRequest++
                            }, keyModifier(subtitlesKey), value = subtitles)
                            // Tracks on the left, the player's own action on the right.
                            Spacer(Modifier.weight(1f))
                            OsdKey(
                                "On demand",
                                WatchIcons.OnDemand,
                                enabled = canPlayOnDemand,
                                onClick = {
                                    returnKey = onDemandKey
                                    confirm = Confirm.ON_DEMAND
                                },
                                modifier = keyModifier(onDemandKey),
                            )
                            OsdKey("Start over", WatchIcons.StartOver, onClick = {
                                returnKey = startOverKey
                                confirm = Confirm.START_OVER
                            }, keyModifier(startOverKey))
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
                                    bannerRequest++
                                },
                                onPick = if (pickingAudio) viewModel::selectAudio else viewModel::selectSubtitles,
                            )
                        }
                    }
                },
            )
        }
        // The channel's corner ident comes and goes with the banner; typed digits take its place while they are entered.
        if (entry.isNotEmpty() || (bannerVisible && state.channel != null)) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .cornerShade(theme.banner)
                    .padding(start = 32.dp, top = osdEdge, end = osdSide, bottom = 24.dp),
                contentAlignment = Alignment.TopEnd,
            ) {
                if (entry.isNotEmpty()) NumberEntry(entry) else state.channel?.let { ChannelIdent(it) }
            }
        }
        notice?.let { Message(it, modifier = Modifier.align(Alignment.TopCenter).padding(top = 32.dp)) }

        confirm?.let { asked ->
            val program = state.now
            val stay = DialogAction("Keep watching", "Stay on this channel.", Icons.Filled.Close) { confirm = null }
            when (asked) {
                Confirm.START_OVER -> ActionDialog(
                    actions = listOf(
                        DialogAction(WatchChoices.restart.title, WatchChoices.restart.detail, WatchIcons.StartOver) {
                            confirm = null
                            viewModel.restart()
                            bannerRequest++
                        },
                        stay,
                    ),
                    onDismiss = { confirm = null },
                ) { ConfirmHeader("Start over?", program?.title) }
                Confirm.ON_DEMAND -> {
                    // The program leaves the channel for the on-demand player, from its start or from where the channel is.
                    val elapsed = remember(program?.mediaItemId, program?.startUtcMs) { program?.let { System.currentTimeMillis() - it.startUtcMs } ?: 0L }
                    val choices = onDemandProgram?.let { WatchChoices.onDemandChoices(elapsed, it.isEpisode) }.orEmpty()
                    ActionDialog(
                        actions = choices.dialogActions(
                            isEpisode = onDemandProgram?.isEpisode == true,
                            onOpenDetails = onDemandProgram?.let {
                                {
                                    confirm = null
                                    onOpenOnDemand(it.mediaItemId, it.isEpisode)
                                }
                            },
                        ) { choice ->
                            confirm = null
                            val channel = state.channel
                            if (onDemandProgram != null && channel != null) onPlayOnDemand(onDemandProgram.mediaItemId, choice.positionMs, channel.id, onDemandProgram.startUtcMs)
                        } + stay,
                        onDismiss = { confirm = null },
                    ) { ConfirmHeader("Watch on Demand?", program?.title) }
                }
            }
        }
    }
}

@Composable
private fun ConfirmHeader(question: String, title: String?) {
    val theme = LocalAnstelleTheme.current
    androidx.compose.material3.Text(question, style = androidx.compose.material3.MaterialTheme.typography.headlineSmall, color = theme.text)
    if (title != null) {
        androidx.compose.material3.Text(
            title,
            style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
            color = theme.textMuted,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * The OSD banner, straight on the picture over the edge shade: NOW with the stream's technical
 * line beside it, the program on now with its progress (time in, segment ladder, length), the
 * [keys] under a hairline, and what's next in a column of its own behind a hairline. The
 * channel is named by its ident in the top-end corner. With [onSeek] (phones), the ladder
 * takes taps and drags.
 */
@Composable
private fun InfoBanner(
    state: PlaybackState,
    onSeek: ((positionMs: Long) -> Unit)?,
    keys: @Composable RowScope.() -> Unit,
) {
    val theme = LocalAnstelleTheme.current
    val isTv = LocalIsTv.current
    if (state.channel == null) return
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }
    val typography = androidx.compose.material3.MaterialTheme.typography
    // A small phone needs all of its width for the program's column.
    val withNext = isTv || with(LocalDensity.current) { LocalWindowInfo.current.containerSize.width.toDp() } >= NEXT_MIN_WIDTH
    Column(
        Modifier
            .fillMaxWidth()
            .edgeShade(theme.banner, shadeFade)
            // Hand-held sizes off TV: the touch keys close the banner, so their own height is the air under it.
            .padding(start = osdSide, end = osdSide, bottom = if (isTv) osdEdge else 4.dp),
        verticalArrangement = Arrangement.spacedBy(if (isTv) 6.dp else 2.dp),
    ) {
    BannerColumns(
        modifier = Modifier.fillMaxWidth(),
        endWidth = if (isTv) NEXT_WIDTH_TV else NEXT_WIDTH_TOUCH,
        middle = {
            Column(
                Modifier.padding(end = if (withNext) 24.dp else 0.dp),
                verticalArrangement = Arrangement.spacedBy(if (isTv) 4.dp else 2.dp),
            ) {
                val program = state.now
                val label = @Composable {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AirMarker(onAir = program != null)
                        androidx.compose.material3.Text("NOW", style = typography.labelMedium, color = theme.accent)
                        if (program != null) {
                            androidx.compose.material3.Text(timeRange(program), style = typography.labelMedium, color = theme.textMuted)
                        }
                    }
                }
                BannerHeader(state.stream, label)
                PlayerTitleLogo(
                    logoUrl = program?.logoUrl,
                    title = program?.title ?: "Tuning…",
                    // A restricted program's title and logo are blurred.
                    modifier = Modifier.obscured(program?.restricted == true, theme.textMuted, radius = 10.dp),
                )
                program?.let { lineUnderLogo(it.detail, it.title, it.isEpisode, it.logoUrl != null) }?.let {
                    PlayerDetailLine(
                        it,
                        modifier = Modifier.obscured(program.restricted, theme.textMuted, radius = 5.dp),
                    )
                }
                if (program != null) {
                    ProgressRow(
                        positionMs = now - program.startUtcMs,
                        lengthMs = program.endUtcMs - program.startUtcMs,
                        modifier = Modifier.padding(top = if (onSeek == null) 4.dp else 0.dp),
                        onSeek = onSeek,
                    )
                }
            }
        },
        end = if (!withNext) {
            null
        } else {
            {
                // A hairline divides NEXT off the program's column; its first line sits level with NOW.
                Box(
                    Modifier
                        .drawBehind {
                            val x = if (layoutDirection == LayoutDirection.Rtl) size.width - 1.dp.toPx() else 0f
                            drawRect(theme.line, topLeft = Offset(x, 0f), size = size.copy(width = 1.dp.toPx()))
                        }
                        .padding(start = if (isTv) 20.dp else 16.dp),
                    contentAlignment = Alignment.TopStart,
                ) {
                    state.next?.let { next ->
                        Column(verticalArrangement = Arrangement.spacedBy(if (isTv) 4.dp else 2.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                AirMarker(onAir = false)
                                androidx.compose.material3.Text("NEXT  ${time(next.startUtcMs)}", style = typography.labelMedium, color = theme.textMuted, maxLines = 1)
                            }
                            // The same logo and type as NOW, so the two read as one pair.
                            PlayerTitleLogo(
                                logoUrl = next.logoUrl,
                                title = next.title,
                                maxLines = 2,
                                modifier = Modifier.obscured(next.restricted, theme.textMuted, radius = 10.dp),
                            )
                            nextLine(next)?.let {
                                PlayerDetailLine(
                                    it,
                                    maxLines = 2,
                                    modifier = Modifier.obscured(next.restricted, theme.textMuted, radius = 5.dp),
                                )
                            }
                        }
                    }
                }
            }
        },
    )
    // The keys run the banner's whole width, under NEXT as well.
    OsdKeys(Modifier, keys)
    }
}

/** "S1E2 - Two Forces" for an episode; "Title (2025)" for a movie whose logo stands for its title. */
private fun nextLine(next: ProgramInfo): String? = lineUnderLogo(next.detail, next.title, next.isEpisode, next.logoUrl != null)

/**
 * Lays the banner out as [middle], the program's column, with [end] beside it [endWidth] wide
 * and as tall as [middle], so NEXT keeps one place however the program's column runs.
 */
@Composable
private fun BannerColumns(
    middle: @Composable () -> Unit,
    end: (@Composable () -> Unit)?,
    endWidth: Dp,
    modifier: Modifier = Modifier,
) {
    Layout(contents = listOfNotNull(middle, end), modifier = modifier) { measurables, constraints ->
        val center = measurables[0].first()
        val last = measurables.getOrNull(1)?.firstOrNull()
        val width = constraints.maxWidth
        val side = if (last == null) 0 else endWidth.roundToPx().coerceAtMost(width / 2)
        val centerPlaceable = center.measure(Constraints.fixedWidth(width - side))
        val height = centerPlaceable.height
        val lastPlaceable = last?.measure(Constraints.fixed(side, height))
        layout(width, height) {
            centerPlaceable.placeRelative(0, 0)
            lastPlaceable?.placeRelative(width - side, 0)
        }
    }
}

/**
 * The channel as a broadcaster's corner bug: its logo over its number over its name, set to
 * the screen's top-end corner on the corner shade. It comes and goes with the banner.
 */
@Composable
private fun ChannelIdent(channel: Channel) {
    val theme = LocalAnstelleTheme.current
    val isTv = LocalIsTv.current
    val typography = androidx.compose.material3.MaterialTheme.typography
    Column(horizontalAlignment = Alignment.End) {
        channel.logoUri?.let {
            ChannelLogo(
                it,
                height = if (isTv) 96.dp else 56.dp,
                alignment = Alignment.CenterEnd,
                modifier = Modifier.padding(bottom = if (isTv) 6.dp else 4.dp),
            )
        }
        androidx.compose.material3.Text(
            "${channel.number}",
            style = if (isTv) typography.displayMedium else typography.displaySmall,
            color = theme.accent,
            maxLines = 1,
        )
        androidx.compose.material3.Text(
            channel.name,
            style = typography.labelMedium,
            color = theme.bannerText,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.End,
            modifier = Modifier.widthIn(max = if (isTv) 240.dp else 180.dp),
        )
    }
}

/**
 * The digits typed so far, in the ident's corner and in its number's type, with an empty slot
 * marked for each digit still to come; the ladder under them drains for the two seconds before
 * the channel tunes, and the new channel's ident takes the corner.
 */
@Composable
private fun NumberEntry(entry: String) {
    val theme = LocalAnstelleTheme.current
    val isTv = LocalIsTv.current
    val typography = androidx.compose.material3.MaterialTheme.typography
    val left = remember { Animatable(1f) }
    LaunchedEffect(entry) {
        left.snapTo(1f)
        left.animateTo(0f, tween(ENTRY_MS.toInt(), easing = LinearEasing))
    }
    Column(Modifier.width(IntrinsicSize.Min), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            val style = if (isTv) typography.displayMedium else typography.displaySmall
            for (i in 0 until ENTRY_DIGITS) {
                val digit = entry.getOrNull(i)
                Box(Modifier.width(if (isTv) 30.dp else 24.dp), contentAlignment = Alignment.BottomCenter) {
                    // Every slot sets a digit's line, so the row holds its height as digits arrive.
                    androidx.compose.material3.Text(
                        digit?.toString() ?: "0",
                        style = style,
                        color = if (digit != null) theme.accent else Color.Transparent,
                        maxLines = 1,
                    )
                    if (digit == null) {
                        Box(Modifier.padding(bottom = if (isTv) 9.dp else 7.dp).fillMaxWidth().height(3.dp).background(theme.textMuted))
                    }
                }
            }
        }
        SegmentLadder(left.value)
    }
}

/**
 * Off air, or a program the profile may not watch: the picture stays dark until the stretch
 * ends, one mark saying why and until when. The corner ident, in on every tune, names the channel.
 */
@Composable
private fun StandIn(state: PlaybackState) {
    val theme = LocalAnstelleTheme.current
    val until = state.now?.let { time(it.endUtcMs) }
    Box(Modifier.fillMaxSize().background(theme.background), contentAlignment = Alignment.Center) {
        if (state.status == TuneStatus.OFF_AIR) {
            OffAirMark(size = StandInSize.SCREEN, until = until)
        } else {
            RestrictedMark(state.restriction?.title ?: Restriction.AGE_TITLE, size = StandInSize.SCREEN, until = until)
        }
    }
}

@Composable
private fun Message(text: String, modifier: Modifier = Modifier) {
    val theme = LocalAnstelleTheme.current
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        androidx.compose.material3.Text(
            text,
            style = androidx.compose.material3.MaterialTheme.typography.titleLarge,
            color = theme.text,
            modifier = modifier
                .softShade(theme.banner)
                .padding(horizontal = 24.dp, vertical = 14.dp),
        )
    }
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

/** Narrower touch screens leave NEXT out of the banner. */
private val NEXT_MIN_WIDTH = 720.dp

/** How wide the banner's NEXT column is. */
private val NEXT_WIDTH_TV = 240.dp
private val NEXT_WIDTH_TOUCH = 200.dp

/** Mirrors `PlayerViewModel`: four digits at most, tuned 2 s after the last one. */
private const val ENTRY_DIGITS = 4
private const val ENTRY_MS = 2_000L
