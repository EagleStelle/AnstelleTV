package tv.anstelle.feature.player

import tv.anstelle.core.theme.components.obscured
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Audiotrack

import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind

import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Constraints
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
import tv.anstelle.core.data.lineUnderLogo
import tv.anstelle.core.player.SubtitleOverlays
import tv.anstelle.core.theme.ControlShape
import tv.anstelle.core.theme.LocalAnstelleTheme
import tv.anstelle.core.theme.LocalIsTv
import tv.anstelle.core.theme.PanelShape
import tv.anstelle.core.theme.components.AgeRestrictedMark
import tv.anstelle.core.theme.components.RestrictedSize
import tv.anstelle.core.theme.components.AirMarker
import tv.anstelle.core.theme.components.NumberBlock
import tv.anstelle.core.theme.components.SegmentLadder
import tv.anstelle.core.theme.components.TitleLogo

import java.text.DateFormat
import java.util.Date
import kotlin.math.abs

/** Where the banner's keys are: [CONTROLS] has focus on them, the others show a track list. */
private enum class Panel { CONTROLS, AUDIO, SUBTITLES }

/**
 * Full-screen channel playback, like a TV: up/down or the channel keys surf, digits enter a
 * number, left and right seek back 10 s and ahead 30 s (the channel's schedule moves with the
 * program). The banner always carries its keys (audio, subtitles and Start over); it snaps in on
 * every tune and seek with focus left on the picture, and OK, Info or Menu moves focus into the
 * keys. On phones, swipe up or down to surf, tap for the banner and drag its bar to seek.
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
    LandscapeFullScreen()

    var bannerVisible by remember { mutableStateOf(true) }
    var bannerRequest by remember { mutableLongStateOf(0L) }
    LaunchedEffect(state.tuneCount, bannerRequest) {
        bannerVisible = true
        delay(BANNER_MS)
        bannerVisible = false
    }
    var panel by remember { mutableStateOf<Panel?>(null) }
    BackHandler(enabled = panel != null) { panel = if (panel == Panel.CONTROLS) null else Panel.CONTROLS }
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
            Panel.CONTROLS -> {
                withFrameNanos { }
                runCatching { (returnKey ?: audioKey).requestFocus() }
            }
            else -> Unit
        }
    }

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
                        val menu = event.key == Key.Info || event.key == Key.Menu
                        if (entry.isNotEmpty() && !menu) viewModel.commitEntry() else panel = Panel.CONTROLS
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
                    Key.DirectionLeft, Key.MediaRewind -> {
                        viewModel.seekBy(-BACK_MS)
                        bannerRequest++
                    }
                    Key.DirectionRight, Key.MediaFastForward -> {
                        viewModel.seekBy(AHEAD_MS)
                        bannerRequest++
                    }
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
            TuneStatus.RESTRICTED -> Restricted(state)
            TuneStatus.NO_SCHEDULE, TuneStatus.ERROR -> Message(state.message ?: "Nothing to play.")
            else -> if (state.buffering || player == null) Message("Tuning…", subtle = true)
        }

        AnimatedVisibility(
            visible = bannerVisible || panel != null,
            enter = slideInVertically(tween(SNAP_MS)) { it / 2 } + fadeIn(tween(SNAP_MS)),
            exit = slideOutVertically(tween(SNAP_MS)) { it / 2 } + fadeOut(tween(SNAP_MS)),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            // Read again whenever the playback state moves: the keys stay on screen across tunes.
            val audio = remember(state, panel) { viewModel.audioOptions().firstOrNull { it.selected }?.short }
            val subtitles = remember(state, panel) { viewModel.subtitleOptions().firstOrNull { it.selected }?.short }
            InfoBanner(
                state = state,
                // Phones drag the bar; the remote seeks with left and right.
                onSeek = if (isTv) null else { position: Long -> viewModel.seekTo(position).also { bannerRequest++ } },
                keys = when (panel) {
                    null, Panel.CONTROLS -> {
                        {
                            OsdKey("Audio", Icons.Filled.Audiotrack, onClick = {
                                returnKey = audioKey
                                panel = Panel.AUDIO
                            }, Modifier.focusRequester(audioKey), value = audio)
                            OsdKey("Subtitles", Icons.Filled.Subtitles, onClick = {
                                returnKey = subtitlesKey
                                panel = Panel.SUBTITLES
                            }, Modifier.focusRequester(subtitlesKey), value = subtitles)
                            // Tracks on the left, the player's own action on the right.
                            Spacer(Modifier.weight(1f))
                            OsdKey("Start over", Icons.Filled.Replay, onClick = {
                                panel = null
                                viewModel.restart()
                                bannerRequest++
                            })
                        }
                    }
                    Panel.AUDIO -> {
                        {
                            TrackChoices("Audio", remember { viewModel.audioOptions() }) {
                                viewModel.selectAudio(it)
                                panel = null
                            }
                        }
                    }
                    Panel.SUBTITLES -> {
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
        if (entry.isNotEmpty()) {
            NumberEntry(entry, Modifier.align(Alignment.TopEnd).padding(12.dp))
        }
        notice?.let { Message(it, modifier = Modifier.align(Alignment.TopCenter).padding(top = 32.dp)) }

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
 * The OSD banner: the channel number on its accent block, NOW with the stream's technical line
 * beside it, the program on now with its progress (time in, segment ladder, length), the
 * [keys] under a hairline, and what's next behind a hollow marker. The number block and NEXT
 * are squares of one size, as tall as the banner, with the program's column between them. With
 * [onSeek] (phones), the ladder takes taps and drags.
 */
@Composable
private fun InfoBanner(
    state: PlaybackState,
    onSeek: ((positionMs: Long) -> Unit)?,
    keys: @Composable RowScope.() -> Unit,
) {
    val theme = LocalAnstelleTheme.current
    val isTv = LocalIsTv.current
    val channel = state.channel ?: return
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
    SquareEnds(
        modifier = Modifier
            .fillMaxWidth()
            .padding(12.dp)
            .background(theme.banner, PanelShape)
            .border(1.dp, theme.line, PanelShape),
        start = {
            Column(
                Modifier.background(theme.accent, PanelShape).padding(horizontal = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                androidx.compose.material3.Text(
                    "${channel.number}",
                    style = if (isTv) typography.displayMedium else typography.displaySmall,
                    color = theme.onAccent,
                    maxLines = 1,
                )
                androidx.compose.material3.Text(
                    channel.name,
                    style = typography.labelMedium,
                    color = theme.onAccent,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
            }
        },
        middle = {
            // Hand-held sizes off TV: the touch keys close the column, so it needs no bottom padding.
            Column(
                Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = if (isTv) 12.dp else 0.dp),
                verticalArrangement = Arrangement.spacedBy(if (isTv) 4.dp else 2.dp, Alignment.CenterVertically),
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
                TitleLogo(
                    logoUrl = program?.logoUrl,
                    title = program?.title ?: "Tuning…",
                    height = if (isTv) 56.dp else 28.dp,
                    maxLogoWidth = if (isTv) 360.dp else 220.dp,
                    style = if (isTv) typography.headlineMedium else typography.headlineSmall,
                    // An age-restricted program's title and logo are blurred.
                    modifier = Modifier.padding(top = if (isTv) 0.dp else 2.dp).obscured(program?.restricted == true, theme.textMuted, radius = 10.dp),
                )
                program?.let { lineUnderLogo(it.detail, it.title, it.isEpisode, it.logoUrl != null) }?.let {
                    androidx.compose.material3.Text(
                        it,
                        style = typography.bodyMedium,
                        color = theme.textMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
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
                OsdKeys(Modifier.padding(top = if (isTv) 2.dp else 0.dp), keys)
            }
        },
        end = if (!withNext) {
            null
        } else {
            {
                // A hairline divides NEXT off the program's column.
                Box(
                    Modifier.drawBehind { drawRect(theme.line, size = size.copy(width = 1.dp.toPx())) }.padding(horizontal = if (isTv) 16.dp else 14.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    state.next?.let { next ->
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                AirMarker(onAir = false)
                                androidx.compose.material3.Text("NEXT  ${time(next.startUtcMs)}", style = typography.labelMedium, color = theme.textMuted, maxLines = 1)
                            }
                            TitleLogo(
                                logoUrl = next.logoUrl,
                                title = next.title,
                                height = if (isTv) 44.dp else 24.dp,
                                maxLogoWidth = 260.dp,
                                style = typography.titleMedium,
                                maxLines = 2,
                                modifier = Modifier.obscured(next.restricted, theme.textMuted),
                            )
                            nextLine(next)?.let {
                                androidx.compose.material3.Text(
                                    it,
                                    style = typography.bodySmall,
                                    color = theme.textMuted,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.obscured(next.restricted, theme.textMuted, radius = 5.dp),
                                )
                            }
                        }
                    }
                }
            }
        },
    )
}

/** "S1E2 - Two Forces" for an episode; "Title (2025)" for a movie whose logo stands for its title. */
private fun nextLine(next: ProgramInfo): String? = lineUnderLogo(next.detail, next.title, next.isEpisode, next.logoUrl != null)

/**
 * Lays out a banner as two squares of one size with [middle] between them: the squares are as
 * tall as the banner, and the banner is as tall as [middle] needs at the width the squares leave
 * it, so the side is settled in a few rounds of asking [middle] its height. The squares are
 * never narrower than [start]'s content.
 */
@Composable
private fun SquareEnds(
    start: @Composable () -> Unit,
    middle: @Composable () -> Unit,
    end: (@Composable () -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Layout(contents = listOfNotNull(start, middle, end), modifier = modifier) { measurables, constraints ->
        val first = measurables[0].first()
        val center = measurables[1].first()
        val last = measurables.getOrNull(2)?.firstOrNull()
        val width = constraints.maxWidth
        val ends = if (last != null) 2 else 1
        var side = first.maxIntrinsicWidth(Constraints.Infinity)
        repeat(SQUARE_ROUNDS) { side = maxOf(side, center.maxIntrinsicHeight((width - ends * side).coerceAtLeast(0))) }
        val middleWidth = (width - ends * side).coerceAtLeast(0)
        val centerPlaceable = center.measure(Constraints(minWidth = middleWidth, maxWidth = middleWidth, minHeight = side))
        val height = maxOf(side, centerPlaceable.height)
        val firstPlaceable = first.measure(Constraints.fixed(side, height))
        val lastPlaceable = last?.measure(Constraints.fixed(side, height))
        layout(width, height) {
            firstPlaceable.place(0, 0)
            centerPlaceable.place(side, 0)
            lastPlaceable?.place(side + middleWidth, 0)
        }
    }
}

/**
 * The digits typed so far, each dropping onto its own accent block, with hollow slots for
 * the rest; the ladder under them drains for the two seconds before the channel tunes.
 */
@Composable
private fun NumberEntry(entry: String, modifier: Modifier) {
    val theme = LocalAnstelleTheme.current
    val isTv = LocalIsTv.current
    val typography = androidx.compose.material3.MaterialTheme.typography
    val left = remember { Animatable(1f) }
    LaunchedEffect(entry) {
        left.snapTo(1f)
        left.animateTo(0f, tween(ENTRY_MS.toInt(), easing = LinearEasing))
    }
    Column(
        modifier
            .background(theme.banner, PanelShape)
            .border(1.dp, theme.line, PanelShape)
            .padding(12.dp)
            .width(IntrinsicSize.Min),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val slot = Modifier.width(if (isTv) 48.dp else 40.dp).height(if (isTv) 64.dp else 56.dp)
            for (i in 0 until ENTRY_DIGITS) {
                val digit = entry.getOrNull(i)
                if (digit != null) {
                    NumberBlock(digit.toString(), style = if (isTv) typography.displayMedium else typography.displaySmall, modifier = slot)
                } else {
                    Box(slot.border(1.dp, theme.line, ControlShape))
                }
            }
        }
        SegmentLadder(left.value, segments = 16)
    }
}

@Composable
private fun OffAir(state: PlaybackState) {
    val theme = LocalAnstelleTheme.current
    val typography = androidx.compose.material3.MaterialTheme.typography
    Column(
        modifier = Modifier.fillMaxSize().background(theme.background),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        state.channel?.let { NumberBlock("${it.number}", style = typography.displayMedium, modifier = Modifier.height(64.dp)) }
        androidx.compose.material3.Text("OFF AIR", style = typography.displayLarge, color = theme.text)
        state.now?.let {
            androidx.compose.material3.Text("Back on the air at ${time(it.endUtcMs)}", style = typography.titleLarge, color = theme.textMuted)
        }
        state.channel?.let { androidx.compose.material3.Text(it.name, style = typography.labelMedium, color = theme.textMuted) }
    }
}

/** A program over the profile's maturity rating: the picture stays dark until the next one. */
@Composable
private fun Restricted(state: PlaybackState) {
    val theme = LocalAnstelleTheme.current
    Box(Modifier.fillMaxSize().background(theme.background), contentAlignment = Alignment.Center) {
        AgeRestrictedMark(
            detail = state.restriction?.detail,
            size = RestrictedSize.SCREEN,
            extra = state.now?.let { "UNTIL ${time(it.endUtcMs)}" },
        )
    }
}

@Composable
private fun Message(text: String, modifier: Modifier = Modifier, subtle: Boolean = false) {
    val theme = LocalAnstelleTheme.current
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        androidx.compose.material3.Text(
            text,
            style = if (subtle) {
                androidx.compose.material3.MaterialTheme.typography.titleMedium
            } else {
                androidx.compose.material3.MaterialTheme.typography.titleLarge
            },
            color = if (subtle) theme.textMuted else theme.text,
            modifier = modifier
                .background(theme.banner, PanelShape)
                .border(1.dp, theme.line, PanelShape)
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


private const val BANNER_MS = 5_000L

/** Left and right seek this far back and ahead. */
private const val BACK_MS = 10_000L
private const val AHEAD_MS = 30_000L

/** The banner and panels snap in: short, no bounce. */
private const val SNAP_MS = 140

/** Narrower touch screens leave NEXT out of the banner. */
private val NEXT_MIN_WIDTH = 720.dp

/** Rounds of asking the banner's middle its height before the squares' side settles. */
private const val SQUARE_ROUNDS = 4

/** Mirrors `PlayerViewModel`: four digits at most, tuned 2 s after the last one. */
private const val ENTRY_DIGITS = 4
private const val ENTRY_MS = 2_000L

private val ConfirmKeys = setOf(Key.DirectionCenter, Key.Enter, Key.NumPadEnter, Key.Info, Key.Menu)
