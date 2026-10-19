package tv.anstelle.feature.ondemand

import tv.anstelle.core.theme.components.obscured
import tv.anstelle.core.theme.components.OffAirMark
import tv.anstelle.core.theme.components.RestrictedMark
import tv.anstelle.core.theme.components.WatchProgress
import tv.anstelle.core.theme.components.StandInSize
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleStartEffect
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
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import tv.anstelle.core.common.TimeSource
import tv.anstelle.core.data.DatabaseChanges
import tv.anstelle.core.data.ProfileRepository
import tv.anstelle.core.data.Settings
import tv.anstelle.core.data.SettingsRepository
import tv.anstelle.core.data.channels.ChannelRepository
import tv.anstelle.core.data.channels.ChannelSummary
import tv.anstelle.core.data.ondemand.CatalogKind
import tv.anstelle.core.data.ondemand.ChannelNow
import tv.anstelle.core.data.ondemand.OnDemandRepository
import tv.anstelle.core.data.ondemand.WatchChoices
import tv.anstelle.core.model.Channel
import tv.anstelle.core.model.ChannelGroup
import tv.anstelle.core.model.MediaProgress
import tv.anstelle.core.model.ProfileAccess
import tv.anstelle.core.theme.ControlShape
import tv.anstelle.core.theme.LocalAnstelleTheme
import tv.anstelle.core.theme.LocalIsTv
import tv.anstelle.core.theme.components.Backdrop
import tv.anstelle.core.theme.components.FocusSurface
import tv.anstelle.core.theme.components.NumberBlock
import tv.anstelle.core.theme.components.OsdTab
import tv.anstelle.core.theme.components.OsdTabBar
import tv.anstelle.core.theme.components.osdTabHeight
import tv.anstelle.core.theme.components.ChannelLogo
import tv.anstelle.core.theme.components.PlaceholderAction
import tv.anstelle.core.theme.components.PlaceholderScreen
import tv.anstelle.core.theme.components.WatchStrip
import tv.anstelle.core.theme.components.StatusLine
import tv.anstelle.core.theme.components.TitleLogo
import tv.anstelle.core.theme.components.screenEdge
import tv.anstelle.core.theme.components.screenSide
import tv.anstelle.core.theme.components.requestWhenReady
import javax.inject.Inject
import kotlin.random.Random

/** One channel in a row: what it airs now, or null before it has a schedule. */
@Immutable
data class ChannelTile(val channel: Channel, val now: ChannelNow?)

/** What On demand deals its rows and banner from. */
private data class Deal(val channels: List<ChannelSummary>, val groups: List<ChannelGroup>, val bannerSize: Int, val access: ProfileAccess)

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
 * On demand's Home: what every channel airs now, as a banner of random channels over one row
 * per guide group, in the groups' own order. The airings follow the clock: the next one to end
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

    /**
     * True while the Channels tab is on screen. Only then do the tiles follow the clock and the
     * schedule; out of view nothing is read, and the tiles are read afresh when it shows again.
     */
    private val visible = MutableStateFlow(false)

    fun setVisible(visible: Boolean) {
        this.visible.value = visible
        if (!visible) refreshJob?.cancel()
    }

    init {
        viewModelScope.launch {
            val profile = profiles.activeProfile()
            visible.flatMapLatest { shown ->
                if (!shown) return@flatMapLatest emptyFlow()
                combine(
                    channels.observeChannels(),
                    channels.observeGroups(),
                    settings.observe(Settings.OnDemandBanner),
                    profiles.observeAccess(profile.id),
                    tick,
                ) { list, groups, bannerSize, access, _ -> Deal(list, groups, bannerSize, access) }
            }
                .collect { (list, groups, bannerSize, access) ->
                    val tunable = profiles.tunable(list, access)
                    val now = repository.nowOn(tunable, time.nowUtcMs(), access)
                    val tiles = tunable.map { ChannelTile(it, now[it.id]) }
                    val rows = buildList {
                        tiles.filter { it.channel.isFavorite }.takeIf { it.isNotEmpty() }?.let { add(row("favorites", "Favorites", it)) }
                        for (group in groups) {
                            tiles.filter { it.channel.groupId == group.id }.takeIf { it.isNotEmpty() }?.let { add(row("group:${group.id}", group.name, it)) }
                        }
                    }
                    // The banner deals programs the profile may watch.
                    val onAir = tiles.mapNotNull { tile -> tile.now?.takeIf { !it.offAir && it.restriction == null } }
                    val banner = onAir.shuffled(Random(seed)).take(bannerSize).sortedBy { it.channel.number }
                    _state.value = OnDemandUiState(loaded = true, banner = banner, rows = rows)
                    scheduleRefresh(now.values)
                }
        }
        viewModelScope.launch {
            visible.flatMapLatest { shown -> if (shown) changes.tables else emptyFlow() }
                .filter { tables -> tables.any { it in OnDemandTables } }
                .debounce(RELOAD_DEBOUNCE_MS)
                .collect { tick.value++ }
        }
    }

    /** The channel airing title [titleId] now, whose page a title opens in place of its own; null when none does. */
    suspend fun airingChannel(titleId: Long): Long? {
        val profile = profiles.activeProfile()
        return repository.airingChannel(titleId, time.nowUtcMs(), profiles.access(profile.id))
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

/** One of On Demand's tabs: Channels, then one per [CatalogKind]. Search is no tab: it is the field beside them. */
@Immutable
private data class OnDemandTab(val key: String, val label: String, val kind: CatalogKind? = null)

/** Search's page, which the field opens; no tab is open meanwhile. */
private const val SEARCH = "search"
private const val HOME = "home"

private val Tabs = listOf(OnDemandTab(HOME, "Channels")) + CatalogKind.entries.map { OnDemandTab(it.key, it.label, kind = it) }

/**
 * On demand, with a tab row along its top: Channels (a banner of random channels over each guide
 * group's tiles), then a tab per kind of title (Movies, Series), each a banner of random titles
 * over rows of what the library holds; and apart at the row's end, the search field, which opens
 * Search's page. A channel opens on its program; a title on its page; a Continue watching card
 * opens its movie or episode.
 */
@Composable
fun OnDemandScreen(
    onOpenChannel: (channelId: Long) -> Unit,
    onOpenTitle: (titleId: Long) -> Unit,
    onOpenEpisode: (itemId: Long) -> Unit,
    onPlay: (itemId: Long, positionMs: Long) -> Unit,
    /** Null for a member, who has no channels or servers to set up. */
    onOpenSettings: (() -> Unit)?,
    /** Null for a member: adding to a channel is the owner's and admins'. */
    onAddToChannel: ((itemId: Long) -> Unit)?,
    modifier: Modifier = Modifier,
    catalog: CatalogViewModel = hiltViewModel(),
) {
    val theme = LocalAnstelleTheme.current
    val isTv = LocalIsTv.current
    // Back from a detail page restores this visit's tab; a new visit starts on Channels.
    var open by rememberSaveable { mutableStateOf(HOME) }
    // The tab Search was opened from, which leaving Search goes back to.
    var browsing by rememberSaveable { mutableStateOf(HOME) }
    // Opening Search puts the caret in its field at once.
    var typeNow by remember { mutableStateOf(false) }
    // A tab picked in the row keeps focus on the row; coming back to the screen, the tab focuses its own content.
    var picked by remember { mutableStateOf(false) }
    val headerFocus = remember { FocusRequester() }
    val focus = remember(picked) { TabFocus(headerFocus, autoFocus = !picked) }
    TvRailEntry(headerFocus)
    // A movie or series airing on a channel right now opens that channel's page, not its own.
    val home: OnDemandViewModel = hiltViewModel()
    val scope = rememberCoroutineScope()
    val openTitle: (Long) -> Unit = remember(onOpenTitle, onOpenChannel, home) {
        { id ->
            scope.launch {
                val channel = home.airingChannel(id)
                if (channel != null) onOpenChannel(channel) else onOpenTitle(id)
            }
        }
    }
    val actions = remember(openTitle, onOpenEpisode, onPlay, onAddToChannel, catalog) {
        CatalogActions(
            onOpenTitle = openTitle,
            onOpenEpisode = onOpenEpisode,
            onPlay = onPlay,
            onPlaySeries = { titleId, positionMs ->
                scope.launch {
                    catalog.firstEpisodeId(titleId)?.let { onPlay(it, positionMs) }
                }
            },
            onAddToChannel = onAddToChannel,
        )
    }
    val searching = open == SEARCH
    val tab = Tabs.firstOrNull { it.key == open } ?: Tabs[0]
    if (tab.kind != null || searching) LaunchedEffect(Unit) { catalog.start() }
    // Only the part of the page on screen keeps itself up to date; the rest catches up when it shows.
    val homeShown = !searching && tab.key == HOME
    LifecycleStartEffect(home, catalog, open) {
        catalog.select(if (searching) null else tab.kind)
        home.setVisible(homeShown)
        catalog.setVisible(!homeShown)
        onStopOrDispose {
            home.setVisible(false)
            catalog.setVisible(false)
        }
    }
    // Each tab keeps its own scroll while another is open.
    val saved = rememberSaveableStateHolder()
    val query by catalog.query.collectAsStateWithLifecycle()
    // Leaving Search goes back to the tab it was opened from.
    fun leaveSearch() {
        picked = true
        open = browsing
        if (isTv) scope.launch { headerFocus.requestWhenReady() }
    }
    BackHandler(enabled = searching, onBack = ::leaveSearch)

    Column(modifier.fillMaxSize().background(theme.background)) {
        TabRow(
            tabs = Tabs,
            open = open,
            focus = focus,
            onSelect = {
                picked = true
                open = it
            },
            search = SearchState(
                query = query,
                onQuery = catalog::setQuery,
                typeNow = typeNow,
                onStarted = { typeNow = false },
                onOpen = {
                    picked = true
                    if (open != SEARCH) browsing = open
                    typeNow = true
                    open = SEARCH
                },
                onLeave = ::leaveSearch,
            ),
        )
        Box(Modifier.weight(1f).fillMaxWidth()) {
            saved.SaveableStateProvider(if (searching) SEARCH else tab.key) {
                when {
                    searching -> {
                        val filters by catalog.filters.collectAsStateWithLifecycle()
                        val results by catalog.results.collectAsStateWithLifecycle()
                        val state by catalog.state.collectAsStateWithLifecycle()
                        SearchTab(query, filters, catalog::setFilters, results, state.watch, actions, focus)
                    }
                    tab.key == HOME -> HomeTab(onOpenChannel, onOpenSettings, focus)
                    else -> {
                        val state by catalog.state.collectAsStateWithLifecycle()
                        val kind = tab.kind ?: return@SaveableStateProvider
                        CatalogTab(kind, state.tabs[kind], state.watch, actions, focus)
                    }
                }
            }
        }
    }
}

/** The search field's words and what it does with them, for the tab row. */
private class SearchState(
    val query: String,
    val onQuery: (String) -> Unit,
    /** Put the caret in the field as it opens. */
    val typeNow: Boolean,
    val onStarted: () -> Unit,
    /** Shows Search's page. */
    val onOpen: () -> Unit,
    /** Back to the tab Search was opened from. */
    val onLeave: () -> Unit,
)

/**
 * The tabs along On Demand's top: flat labels with no hairline under the row (user decision), the
 * open one underlined. Search stands apart at the row's end as a field, not a tab (user decision: it is
 * not one of the tabs), its edge on the page's side margin, its words on the labels' line.
 * Opened, it widens toward the tabs and no tab is underlined; on a phone it takes the whole row,
 * with a back key to the tabs. Coming into the row from below lands on the open tab, or the
 * field while Search is open.
 */
@Composable
private fun TabRow(tabs: List<OnDemandTab>, open: String, focus: TabFocus, onSelect: (String) -> Unit, search: SearchState) {
    val isTv = LocalIsTv.current
    val requesters = remember(tabs) { (tabs.map { it.key } + SEARCH).associateWith { FocusRequester() } }
    val rail = tv.anstelle.core.theme.components.LocalRailFocus.current
    val searching = open == SEARCH
    // On TV the row runs a tab's padding past the side margin, so labels start on it and the field ends on it.
    // A phone keeps the tabs and the field inside the margin, so the first tab starts as far in as the field ends.
    val tabPad = if (isTv) 14.dp else 12.dp
    val endPad = if (isTv) tabPad else 0.dp
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .padding(top = if (isTv) screenEdge else 4.dp)
            .padding(horizontal = if (isTv) screenSide - tabPad else screenSide),
    ) {
        // Too narrow to open beside the tabs: the field takes the row while Search is open.
        if (searching && maxWidth < WIDE_ROW) {
            SearchField(
                query = search.query,
                onQuery = search.onQuery,
                requester = focus.header,
                open = true,
                onOpen = search.onOpen,
                startTyping = search.typeNow,
                onStarted = search.onStarted,
                onBack = search.onLeave,
                // A tab's height, so the row keeps its height.
                modifier = Modifier.fillMaxWidth().padding(end = endPad),
            )
            return@BoxWithConstraints
        }
        val room = maxWidth - tabPad - TABS_ROOM
        val targetWidth = if (searching) minOf(SEARCH_OPEN, room) else osdTabHeight(compact = isTv)
        // TV changes width in one layout pass, so the input never squeezes the search icon mid-transition.
        val width = if (isTv) targetWidth else animateDpAsState(
            targetValue = targetWidth,
            animationSpec = tween(160, easing = FastOutSlowInEasing),
            label = "search width",
        ).value
        OsdTabBar(
            modifier = Modifier
                .fillMaxWidth()
                .focusProperties { onEnter = { focus.header.requestFocus() } }
                .onPreviewKeyEvent { event ->
                    if (!isTv) return@onPreviewKeyEvent false
                    when (event.key) {
                        Key.DirectionDown -> {
                            val enter = focus.enter
                            if (event.type == KeyEventType.KeyDown) enter?.invoke()
                            enter != null
                        }
                        Key.DirectionUp -> true
                        else -> false
                    }
                }
                .focusGroup(),
        ) {
            Row(
                modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                for (tab in tabs) {
                    OsdTab(
                        tab.label,
                        selected = tab.key == open,
                        onClick = { onSelect(tab.key) },
                        compact = isTv,
                        modifier = Modifier.focusRequester(if (tab.key == open) focus.header else requesters.getValue(tab.key))
                            .focusProperties {
                                if (isTv && tab == tabs.first()) left = rail?.rail ?: FocusRequester.Cancel
                            },
                    )
                }
            }
            SearchField(
                query = search.query,
                onQuery = search.onQuery,
                requester = if (searching) focus.header else requesters.getValue(SEARCH),
                open = searching,
                onOpen = search.onOpen,
                startTyping = search.typeNow,
                onStarted = search.onStarted,
                modifier = Modifier.padding(start = 16.dp, end = endPad).width(width),
            )
        }
    }
}

/** From this wide, Search opens beside the tabs; narrower, it takes the row. */
private val WIDE_ROW = 640.dp

/** The search field's width while Search is open beside the tabs. */
private val SEARCH_OPEN = 560.dp

/** What the tabs need beside the search field. */
private val TABS_ROOM = 240.dp
/**
 * Home: a banner cycling through random channels' programs, then each guide group as a row of
 * channel tiles, numbered and named, showing what airs on them now. A channel opens on its
 * program, where it can be watched live or on demand.
 */
@Composable
private fun HomeTab(
    onOpenChannel: (channelId: Long) -> Unit,
    onOpenSettings: (() -> Unit)?,
    focus: TabFocus,
    viewModel: OnDemandViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val isTv = LocalIsTv.current
    if (!state.loaded) return
    if (state.rows.isEmpty()) {
        val actions = remember(onOpenSettings) {
            listOfNotNull(onOpenSettings?.let { PlaceholderAction("Settings", "Connect a Jellyfin server, then add channels", Icons.Filled.Settings, it) })
        }
        PlaceholderScreen(
            title = "On Demand",
            description = "No channels yet. Once your library syncs, every channel's program can be watched here whenever you like.",
            actions = actions,
        )
        return
    }
    val clock = rememberClock()
    val navigation = rememberTvBrowseNavigation(buildList {
        if (state.banner.isNotEmpty()) add(TvBrowseRow("banner", listOf("banner"), scrollable = false))
        state.rows.forEach { row -> add(TvBrowseRow(row.key, row.tiles.map { it.channel.id.toString() })) }
    }, focus)

    // The guide's margins and gaps: on TV the banner sits where the guide's preview band does;
    // a phone runs it edge to edge under the tabs, like the guide's stacked preview.
    BrowseList(navigation) {
        if (state.banner.isNotEmpty()) {
            item(key = "banner") {
                val slides = state.banner
                RotatingBanner(
                    count = slides.size,
                    backdropUrl = { slides[it].artwork.backdropUrl },
                    onOpen = { onOpenChannel(slides[it].channel.id) },
                    // Edge to edge, like a title page's hero.
                    modifier = Modifier.tvFocusItem(navigation.row("banner"), "banner", horizontalEdges = false),
                    footer = { i ->
                        val slide = slides[i]
                        val progress = MediaProgress(slide.endUtcMs - slide.startUtcMs, clock.value - slide.startUtcMs)
                        WatchProgress(
                            progress,
                            remaining = if (isTv) progress.remainingLabel else MediaProgress.remainingTime(progress.remainingMs),
                            modifier = Modifier.weight(1f),
                        )
                    },
                ) { i -> ChannelSlide(slides[i]) }
            }
        }
        for (row in state.rows) {
            val rowFocus = navigation.row(row.key)
            item(key = row.key) {
                ShelfRow(rowFocus, heading = { RowHeader(row, it) }) {
                    items(row.tiles, key = { it.channel.id }) { tile ->
                        ChannelTileCard(
                            tile = tile,
                            nowMs = clock.value,
                            onClick = { onOpenChannel(tile.channel.id) },
                            modifier = Modifier.tvFocusItem(rowFocus, tile.channel.id.toString()),
                        )
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
 * A channel's banner slide: its number and name, the show's logo, what airs and when, and two
 * lines of overview; the banner's foot carries how far the airing has got.
 */
@Composable
private fun ChannelSlide(slide: ChannelNow) {
    val theme = LocalAnstelleTheme.current
    val isTv = LocalIsTv.current
    val typography = androidx.compose.material3.MaterialTheme.typography
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        slide.channel.logoUri?.let { ChannelLogo(it, height = if (isTv) 30.dp else 24.dp, alignment = Alignment.CenterStart) }
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
    // An episode's line keeps the episode page's hierarchy, its number quiet; a movie's year and runtime stand under its logo.
    val line = if (slide.isEpisode) {
        slide.detail?.let { splitEpisodeLabel(it) }?.let { (number, title) -> numbered(number, title, theme.textMuted) }
    } else null
    line?.let { androidx.compose.material3.Text(it, style = typography.bodyLarge, color = theme.text, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    if (!slide.isEpisode && (slide.detail != null || slide.durationMs > 0)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            slide.detail?.let {
                androidx.compose.material3.Text(it, style = typography.bodyMedium.merge(TextStyle(fontFeatureSettings = "tnum")), color = theme.textMuted, maxLines = 1)
            }
            if (slide.durationMs > 0) {
                androidx.compose.material3.Text(MediaProgress.span(slide.durationMs).uppercase(), style = typography.bodyMedium.merge(TextStyle(fontFeatureSettings = "tnum")), color = theme.textMuted, maxLines = 1)
            }
        }
    }
    StatusLine(
        onAir = true,
        label = "On now",
        text = WatchChoices.clockRange(slide.startUtcMs, slide.endUtcMs),
    )
    slide.overview?.let {
        androidx.compose.material3.Text(
            it,
            style = typography.bodyMedium,
            color = theme.text,
            minLines = 2,
            maxLines = if (isTv) 5 else 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 620.dp),
        )
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
                val markInset = Modifier.padding(start = 10.dp, top = 8.dp)
                if (now == null || now.offAir) {
                    // Off air, or nothing scheduled: the ground with its mark, like a restricted tile, and
                    // the ladder over the off-air stretch.
                    Box(Modifier.fillMaxSize().background(theme.background)) {
                        if (now == null) {
                            OffAirMark(markInset.align(Alignment.TopStart), StandInSize.LINE, alignStart = true, title = "No schedule")
                        } else {
                            OffAirMark(markInset.align(Alignment.TopStart), StandInSize.LINE, alignStart = true)
                        }
                    }
                } else if (restriction != null) {
                    // Something the profile may not watch: the picture is blacked out, the logo or title
                    // blurred, and the ladder runs over the whole restricted stretch.
                    Box(Modifier.fillMaxSize().background(theme.background)) {
                        RestrictedMark(restriction.title, markInset.align(Alignment.TopStart), StandInSize.LINE, alignStart = true)
                        TitleLogo(
                            logoUrl = now.artwork.logoUrl,
                            title = now.title,
                            height = if (isTv) 40.dp else 32.dp,
                            maxLogoWidth = if (isTv) 170.dp else 136.dp,
                            style = typography.titleMedium.merge(TextStyle(fontFamily = theme.displayFont)),
                            maxLines = 2,
                            modifier = Modifier.align(Alignment.BottomStart).padding(start = 10.dp, end = 10.dp, bottom = 24.dp).obscured(true, theme.textMuted),
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
                            modifier = Modifier.align(Alignment.BottomStart).padding(start = 10.dp, end = 10.dp, bottom = 24.dp),
                        )
                    }
                }
                if (now != null) WatchStrip(
                    MediaProgress(now.endUtcMs - now.startUtcMs, nowMs - now.startUtcMs),
                    watched = false,
                    modifier = Modifier.align(Alignment.BottomStart),
                )
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
                if (now == null || now.offAir || now.restriction != null) {
                    // Off air, restricted or unscheduled, the picture already says why; the caption says when, in the mark's own voice.
                    androidx.compose.material3.Text(
                        if (now == null) "Nothing scheduled".uppercase() else "Until ${WatchChoices.clockTime(now.endUtcMs)}".uppercase(),
                        // Label-medium keeps body-small's line height, so a dark tile stands as tall as the rest.
                        style = typography.labelMedium,
                        color = if (focused) theme.onAccent.copy(alpha = 0.76f) else theme.textMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                } else if (now.isEpisode) {
                    // The ladder on the picture says how far the airing has got, so the line is only what airs.
                    val (number, title) = splitEpisodeLabel(now.detail.orEmpty())
                    CaptionLine(number, title, focused)
                } else {
                    // A movie's year, then its title when the picture shows its logo, the way a line under a logo names it.
                    CaptionLine(now.detail, now.title.takeIf { now.artwork.logoUrl != null }, focused)
                }
            }
        }
    }
}
