package tv.anstelle.feature.ondemand

import tv.anstelle.core.theme.components.obscured
import tv.anstelle.core.theme.components.AgeRestrictedMark
import tv.anstelle.core.theme.components.RestrictedSize
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import tv.anstelle.core.common.TimeSource
import tv.anstelle.core.data.DatabaseChanges
import tv.anstelle.core.data.ProfileRepository
import tv.anstelle.core.data.Settings
import tv.anstelle.core.data.SettingsRepository
import tv.anstelle.core.data.channels.ChannelRepository
import tv.anstelle.core.data.lineUnderLogo
import tv.anstelle.core.data.ondemand.ChannelNow
import tv.anstelle.core.data.ondemand.OnDemandRepository
import tv.anstelle.core.data.ondemand.WatchChoices
import tv.anstelle.core.model.Channel
import tv.anstelle.core.theme.ControlShape
import tv.anstelle.core.theme.LocalAnstelleTheme
import tv.anstelle.core.theme.LocalIsTv
import tv.anstelle.core.theme.components.AirMarker
import tv.anstelle.core.theme.components.Backdrop
import tv.anstelle.core.theme.components.FocusSurface
import tv.anstelle.core.theme.components.NumberBlock
import tv.anstelle.core.theme.components.PlaceholderAction
import tv.anstelle.core.theme.components.PlaceholderScreen
import tv.anstelle.core.theme.components.SegmentLadder
import tv.anstelle.core.theme.components.StatusLine
import tv.anstelle.core.theme.components.TitleLogo
import tv.anstelle.core.theme.components.screenEdge
import tv.anstelle.core.theme.components.screenGap
import tv.anstelle.core.theme.components.screenSide
import javax.inject.Inject
import kotlin.math.abs
import kotlin.random.Random

/** One channel in a row: what it airs now, or null before it has a schedule. */
@Immutable
data class ChannelTile(val channel: Channel, val now: ChannelNow?)

/** A guide group as a row of channels. */
@Immutable
data class ChannelRow(val key: String, val label: String, val numbers: String, val tiles: List<ChannelTile>)

@Immutable
data class OnDemandUiState(
    val loaded: Boolean = false,
    /** The banner's channels, each airing a program now. */
    val banner: List<ChannelNow> = emptyList(),
    val rows: List<ChannelRow> = emptyList(),
)

/**
 * On demand: what every channel airs now, as a banner of random channels over one row per
 * guide group, in the groups' own order. The airings follow the clock: the next one to end
 * reloads them all, as do schedule and library changes.
 */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@HiltViewModel
class OnDemandViewModel @Inject constructor(
    private val profiles: ProfileRepository,
    private val channels: ChannelRepository,
    private val repository: OnDemandRepository,
    private val settings: SettingsRepository,
    private val time: TimeSource,
    changes: DatabaseChanges,
) : ViewModel() {
    private val _state = MutableStateFlow(OnDemandUiState())
    val state: StateFlow<OnDemandUiState> = _state.asStateFlow()

    /** Bumped when airings may have moved on: a program ended, or the schedule changed. */
    private val tick = MutableStateFlow(0)

    /** The banner's deal, fixed for this visit so a refresh never reshuffles it. */
    private val seed = Random.nextLong()

    init {
        viewModelScope.launch {
            val profile = profiles.ensureActiveProfile()
            combine(
                channels.observeChannels(),
                channels.observeGroups(),
                settings.observe(Settings.OnDemandBanner),
                tick,
            ) { list, groups, bannerSize, _ -> Triple(list, groups, bannerSize) }
                .collect { (list, groups, bannerSize) ->
                    val tunable = list.filter { !it.channel.isHidden && it.inSeason }.map { it.channel }
                    val now = repository.nowOn(tunable, time.nowUtcMs(), profile.maxRatingScore)
                    val tiles = tunable.map { ChannelTile(it, now[it.id]) }
                    val rows = buildList {
                        tiles.filter { it.channel.isFavorite }.takeIf { it.isNotEmpty() }?.let { add(row("favorites", "Favorites", it)) }
                        for (group in groups) {
                            tiles.filter { it.channel.groupId == group.id }.takeIf { it.isNotEmpty() }?.let { add(row("group:${group.id}", group.name, it)) }
                        }
                        val grouped = groups.mapTo(HashSet()) { it.id }
                        tiles.filter { it.channel.groupId !in grouped }.takeIf { it.isNotEmpty() }?.let { add(row("other", "Other channels", it)) }
                    }
                    // The banner deals programs the profile may watch.
                    val onAir = tiles.mapNotNull { tile -> tile.now?.takeIf { !it.offAir && it.restriction == null } }
                    val banner = onAir.shuffled(Random(seed)).take(bannerSize).sortedBy { it.channel.number }
                    _state.value = OnDemandUiState(loaded = true, banner = banner, rows = rows)
                    scheduleRefresh(now.values)
                }
        }
        viewModelScope.launch {
            changes.tables
                .filter { tables -> tables.any { it in OnDemandTables } }
                .debounce(RELOAD_DEBOUNCE_MS)
                .collect { tick.value++ }
        }
    }

    private var refreshJob: kotlinx.coroutines.Job? = null

    /** Reloads once the first of [airings] ends, so tiles move on with the channels. */
    private fun scheduleRefresh(airings: Collection<ChannelNow>) {
        refreshJob?.cancel()
        val now = time.nowUtcMs()
        val next = airings.minOfOrNull { it.endUtcMs } ?: return
        refreshJob = viewModelScope.launch {
            delay((next - now).coerceIn(MIN_REFRESH_MS, MAX_REFRESH_MS))
            tick.value++
        }
    }

    private fun row(key: String, label: String, tiles: List<ChannelTile>): ChannelRow {
        val first = tiles.minOf { it.channel.number }
        val last = tiles.maxOf { it.channel.number }
        return ChannelRow(key, label, if (first == last) "$first" else "$first–$last", tiles)
    }

    private companion object {
        const val RELOAD_DEBOUNCE_MS = 1_500L
        const val MIN_REFRESH_MS = 5_000L
        const val MAX_REFRESH_MS = 10 * 60_000L
        val OnDemandTables = setOf("schedule_slot", "media_item")
    }
}

/**
 * On demand, by channel: a banner cycling through random channels' programs, then each guide
 * group as a row of channel tiles, numbered and named, showing what airs on them now. A
 * channel opens on its program, where it can be watched live or on demand.
 */
@Composable
fun OnDemandScreen(
    onOpenChannel: (channelId: Long) -> Unit,
    /** Null for a member, who has no channels or servers to set up. */
    onOpenSettings: (() -> Unit)?,
    modifier: Modifier = Modifier,
    viewModel: OnDemandViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val theme = LocalAnstelleTheme.current
    val isTv = LocalIsTv.current
    if (!state.loaded) return
    if (state.rows.isEmpty()) {
        val actions = remember(onOpenSettings) {
            listOfNotNull(onOpenSettings?.let { PlaceholderAction("Settings", "Connect a Jellyfin server, then add channels", Icons.Filled.Settings, it) })
        }
        PlaceholderScreen(
            title = "On demand",
            description = "No channels yet. Once your library syncs, every channel's program can be watched here whenever you like.",
            actions = actions,
            modifier = modifier,
        )
        return
    }
    val clock = rememberClock()
    val listState = rememberLazyListState()
    val bannerFocus = remember { FocusRequester() }
    val returnFocus = remember { FocusRequester() }
    // The tile a channel was opened from, so Back lands on it again.
    var opened by rememberSaveable { mutableStateOf<String?>(null) }
    if (isTv) {
        LaunchedEffect(Unit) {
            val returned = opened != null && runCatching { returnFocus.requestFocus() }.isSuccess
            if (!returned) runCatching { bannerFocus.requestFocus() }
        }
    }
    fun open(key: String, channelId: Long) {
        opened = key
        onOpenChannel(channelId)
    }

    // The guide's margins and gaps: on TV the banner sits where the guide's preview band does;
    // a phone runs it edge to edge from the top, like the guide's stacked preview.
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize().background(theme.background),
        contentPadding = PaddingValues(top = if (isTv) screenEdge else 0.dp, bottom = screenEdge),
        verticalArrangement = Arrangement.spacedBy(screenGap),
    ) {
        if (state.banner.isNotEmpty()) {
            item(key = "banner") {
                Banner(
                    slides = state.banner,
                    nowMs = clock.value,
                    onOpen = { open("banner", it.channel.id) },
                    // Its picture lines up with the tiles' pictures under it, inside the same focus frame.
                    modifier = Modifier
                        .padding(horizontal = if (isTv) screenSide - TILE_FRAME else 0.dp)
                        .let { if (opened == "banner") it.focusRequester(returnFocus) else it }
                        .focusRequester(bannerFocus),
                )
            }
        }
        for (row in state.rows) {
            item(key = row.key) {
                Column(verticalArrangement = Arrangement.spacedBy(screenGap - TILE_FRAME)) {
                    // A header takes a gap more above it than under it, so it reads with its own row.
                    RowHeader(row, Modifier.padding(start = screenSide, end = screenSide, top = screenGap))
                    // Pictures sit the guide's gap apart: the spacing plus each tile's focus inset.
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = screenSide - TILE_FRAME),
                        horizontalArrangement = Arrangement.spacedBy(screenGap - TILE_FRAME * 2),
                    ) {
                        items(row.tiles, key = { it.channel.id }) { tile ->
                            val key = "${row.key}/${tile.channel.id}"
                            ChannelTileCard(
                                tile = tile,
                                nowMs = clock.value,
                                onClick = { open(key, tile.channel.id) },
                                modifier = if (key == opened) Modifier.focusRequester(returnFocus) else Modifier,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** A row's name in the OSD's field voice, then the channel numbers it spans. */
@Composable
private fun RowHeader(row: ChannelRow, modifier: Modifier = Modifier) {
    val theme = LocalAnstelleTheme.current
    val isTv = LocalIsTv.current
    val style = androidx.compose.material3.MaterialTheme.typography.labelMedium.copy(fontSize = if (isTv) 15.sp else 13.sp, lineHeight = 18.sp)
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        androidx.compose.material3.Text(row.label.uppercase(), style = style, color = theme.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        androidx.compose.material3.Text(row.numbers, style = style, color = theme.textMuted, maxLines = 1)
    }
}

/**
 * The banner: one channel's program at a time, its backdrop under the wash with the channel's
 * number and name, the show's logo, what airs and how far it has got. It cuts to the next
 * channel every few seconds while focus is elsewhere and holds while focused; left and right
 * (or a swipe) step through it, OK opens the channel. Squares under it count the channels.
 */
@Composable
private fun Banner(slides: List<ChannelNow>, nowMs: Long, onOpen: (ChannelNow) -> Unit, modifier: Modifier = Modifier) {
    val theme = LocalAnstelleTheme.current
    val isTv = LocalIsTv.current
    val typography = androidx.compose.material3.MaterialTheme.typography
    var index by rememberSaveable { mutableIntStateOf(0) }
    var focused by remember { mutableStateOf(false) }
    val count = slides.size
    val shown = index.coerceIn(0, count - 1)
    val slide = slides[shown]
    LaunchedEffect(shown, focused, count) {
        if (!focused && count > 1) {
            delay(ROTATE_MS)
            index = (shown + 1) % count
        }
    }
    val swipe = with(LocalDensity.current) { SWIPE.toPx() }
    FocusSurface(
        onClick = { onOpen(slide) },
        color = Color.Transparent,
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged { focused = it.isFocused }
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    // At the first channel, left goes on to the navigation rail.
                    Key.DirectionLeft -> (shown > 0).also { if (it) index = shown - 1 }
                    Key.DirectionRight -> true.also { index = (shown + 1) % count }
                    else -> false
                }
            }
            .pointerInput(count) {
                var dragged = 0f
                detectHorizontalDragGestures(
                    onDragStart = { dragged = 0f },
                    onHorizontalDrag = { change, amount ->
                        change.consume()
                        dragged += amount
                    },
                    onDragEnd = {
                        if (abs(dragged) > swipe) index = (index.coerceIn(0, count - 1) + if (dragged < 0) 1 else count - 1) % count
                    },
                )
            },
    ) { isFocused ->
        // On TV focus shows as an accent frame round the picture; phones run it edge to edge.
        Backdrop(
            url = slide.artwork.backdropUrl,
            modifier = Modifier
                .padding(if (isTv) TILE_FRAME else 0.dp)
                .fillMaxWidth()
                .heightIn(min = if (isTv) 252.dp else 236.dp)
                .clip(if (isTv) ControlShape else androidx.compose.ui.graphics.RectangleShape),
            alignment = Alignment.Center,
        ) {
            Column(
                Modifier
                    .align(Alignment.BottomStart)
                    // On a phone the text lines up with the row headers under it.
                    .padding(horizontal = if (isTv) 28.dp else screenSide, vertical = if (isTv) 22.dp else 16.dp),
                verticalArrangement = Arrangement.spacedBy(if (isTv) 6.dp else 4.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    NumberBlock(
                        "${slide.channel.number}",
                        style = typography.titleLarge.merge(TextStyle(fontFamily = theme.displayFont, fontWeight = FontWeight.Bold, fontFeatureSettings = "tnum")),
                    )
                    androidx.compose.material3.Text(
                        slide.channel.name,
                        style = typography.titleMedium,
                        color = theme.text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                TitleLogo(
                    logoUrl = slide.artwork.logoUrl,
                    title = slide.title,
                    height = if (isTv) 72.dp else 48.dp,
                    maxLogoWidth = if (isTv) 420.dp else 260.dp,
                    style = if (isTv) typography.headlineLarge else typography.headlineMedium,
                    maxLines = 2,
                    modifier = Modifier.padding(top = 4.dp),
                )
                lineUnderLogo(slide.detail, slide.title, slide.isEpisode, slide.artwork.logoUrl != null)?.let {
                    androidx.compose.material3.Text(it, style = typography.bodyLarge, color = theme.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                StatusLine(
                    onAir = true,
                    label = "On now",
                    text = "${WatchChoices.clockRange(slide.startUtcMs, slide.endUtcMs)}  ·  ${WatchChoices.span(slide.endUtcMs - nowMs, roundUp = true)} left",
                )
                slide.overview?.let {
                    androidx.compose.material3.Text(
                        it,
                        style = typography.bodyMedium,
                        color = theme.text,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 620.dp),
                    )
                }
                Row(
                    Modifier.fillMaxWidth().padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    SegmentLadder(progress(slide.startUtcMs, slide.endUtcMs, nowMs), Modifier.width(if (isTv) 320.dp else 180.dp))
                    Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) { Pager(count, shown, isFocused) }
                }
            }
        }
    }
}

/** One square per banner channel: the one showing solid in the accent, the rest hollow. */
@Composable
private fun Pager(count: Int, shown: Int, focused: Boolean) {
    if (count < 2) return
    val theme = LocalAnstelleTheme.current
    Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
        for (i in 0 until count) {
            AirMarker(onAir = i == shown, color = if (i == shown) (if (focused) theme.text else theme.accent) else theme.textMuted)
        }
    }
}

/**
 * A channel as a tile: its program's backdrop under the wash with the show's logo and how far
 * the airing has got, then the channel's number and name and what airs. Focus fills the tile,
 * so the picture sits in an accent frame over an accent caption.
 */
@Composable
private fun ChannelTileCard(tile: ChannelTile, nowMs: Long, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val theme = LocalAnstelleTheme.current
    val isTv = LocalIsTv.current
    val typography = androidx.compose.material3.MaterialTheme.typography
    val now = tile.now
    FocusSurface(onClick = onClick, modifier = modifier.width(if (isTv) 214.dp else 172.dp), color = Color.Transparent) { focused ->
        Column(Modifier.padding(TILE_FRAME)) {
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(ControlShape).background(theme.surfaceHigh)) {
                val restriction = now?.restriction
                if (now == null || now.offAir) {
                    OffAir(now, Modifier.align(Alignment.Center))
                } else if (restriction != null) {
                    // Over the profile's maturity rating: the picture is blacked out, the logo or title
                    // blurred, and the ladder runs over the whole restricted stretch.
                    Box(Modifier.fillMaxSize().background(theme.background)) {
                        AgeRestrictedMark(
                            detail = restriction.rating?.let { "Rated $it" } ?: "Not rated",
                            size = RestrictedSize.TILE,
                            modifier = Modifier.align(Alignment.TopStart).padding(start = 10.dp, top = 8.dp),
                            alignStart = true,
                        )
                        TitleLogo(
                            logoUrl = now.artwork.logoUrl,
                            title = now.title,
                            height = if (isTv) 40.dp else 32.dp,
                            maxLogoWidth = if (isTv) 170.dp else 136.dp,
                            style = typography.titleMedium.merge(TextStyle(fontFamily = theme.displayFont)),
                            maxLines = 2,
                            modifier = Modifier.align(Alignment.BottomStart).padding(start = 10.dp, end = 10.dp, bottom = 16.dp).obscured(true, theme.textMuted),
                        )
                        SegmentLadder(
                            progress(now.startUtcMs, now.endUtcMs, nowMs),
                            Modifier.align(Alignment.BottomStart).padding(horizontal = 10.dp, vertical = 7.dp),
                            segments = 16,
                        )
                    }
                } else {
                    Backdrop(now.artwork.backdropUrl, Modifier.fillMaxSize(), alignment = Alignment.Center) {
                        TitleLogo(
                            logoUrl = now.artwork.logoUrl,
                            title = now.title,
                            height = if (isTv) 40.dp else 32.dp,
                            maxLogoWidth = if (isTv) 170.dp else 136.dp,
                            style = typography.titleMedium.merge(TextStyle(fontFamily = theme.displayFont)),
                            maxLines = 2,
                            modifier = Modifier.align(Alignment.BottomStart).padding(start = 10.dp, end = 10.dp, bottom = 16.dp),
                        )
                        SegmentLadder(
                            progress(now.startUtcMs, now.endUtcMs, nowMs),
                            Modifier.align(Alignment.BottomStart).padding(horizontal = 10.dp, vertical = 7.dp),
                            segments = 16,
                        )
                    }
                }
            }
            Column(Modifier.padding(start = 4.dp, end = 4.dp, top = 7.dp, bottom = 5.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    ChannelNumber(tile.channel.number, focused, typography.labelLarge)
                    androidx.compose.material3.Text(
                        tile.channel.name,
                        style = typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                val line = when {
                    now == null -> "Nothing scheduled"
                    now.offAir -> "Back at ${WatchChoices.clockTime(now.endUtcMs)}"
                    now.restriction != null -> "Age restricted until ${WatchChoices.clockTime(now.endUtcMs)}"
                    // Time left first: a long episode title is what gets cut.
                    else -> listOfNotNull("${WatchChoices.span(now.endUtcMs - nowMs, roundUp = true)} left", now.detail.takeIf { now.isEpisode }).joinToString(" · ")
                }
                androidx.compose.material3.Text(
                    line,
                    style = typography.bodySmall,
                    color = if (focused) theme.onAccent.copy(alpha = 0.76f) else theme.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun OffAir(now: ChannelNow?, modifier: Modifier) {
    val theme = LocalAnstelleTheme.current
    androidx.compose.material3.Text(
        if (now == null) "NO SCHEDULE" else "OFF AIR",
        style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
        color = theme.textMuted,
        modifier = modifier,
    )
}

/** The inset that focus fills round a tile's picture. */
private val TILE_FRAME = 3.dp

/** How far a swipe has to travel to step the banner. */
private val SWIPE = 48.dp

/** How long the banner holds a channel while focus is elsewhere. */
private const val ROTATE_MS = 8_000L
