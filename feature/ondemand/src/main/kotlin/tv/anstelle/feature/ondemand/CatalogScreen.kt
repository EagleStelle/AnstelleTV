package tv.anstelle.feature.ondemand

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import tv.anstelle.core.data.ondemand.CatalogKind
import tv.anstelle.core.data.ondemand.CatalogWatch
import tv.anstelle.core.data.ondemand.SearchFilters
import tv.anstelle.core.data.ondemand.SearchResults
import tv.anstelle.core.data.ondemand.SearchSort
import tv.anstelle.core.data.ondemand.Title
import tv.anstelle.core.data.ondemand.WatchCard
import tv.anstelle.core.model.WatchChoice
import tv.anstelle.core.data.ondemand.WatchChoices
import tv.anstelle.core.model.MediaProgress
import tv.anstelle.core.theme.components.WatchProgress
import tv.anstelle.core.data.ondemand.WatchFilter
import tv.anstelle.core.model.WatchKind
import tv.anstelle.core.theme.LocalAnstelleTheme
import tv.anstelle.core.theme.LocalIsTv
import tv.anstelle.core.theme.components.ActionDialog
import tv.anstelle.core.theme.components.DialogAction
import tv.anstelle.core.theme.components.dialogActions
import tv.anstelle.core.theme.components.FocusSurface
import tv.anstelle.core.theme.components.LocalRailFocus
import tv.anstelle.core.theme.components.requestWhenReady
import tv.anstelle.core.theme.components.screenEdge
import tv.anstelle.core.theme.components.screenGap
import tv.anstelle.core.theme.components.screenSide

/** Where a catalog tab's picks lead: a title's page, the player, Add to channel. */
internal class CatalogActions(
    val onOpenTitle: (titleId: Long) -> Unit,
    val onOpenEpisode: (itemId: Long) -> Unit,
    val onPlay: (itemId: Long, positionMs: Long) -> Unit,
    val onPlaySeries: (titleId: Long, positionMs: Long) -> Unit,
    /** Null for a member: adding to a channel is the owner's and admins'. */
    val onAddToChannel: ((itemId: Long) -> Unit)?,
)

/** A poster's width in a row: a TV shows about six past the banner, a phone three. */
private val PosterWidth @Composable get() = if (LocalIsTv.current) 132.dp else 108.dp

/** Search's grid shares the width at about a row poster's size. */
private val PosterMin @Composable get() = if (LocalIsTv.current) 132.dp else 104.dp

/**
 * One kind's tab: random-title banner, then configured rows scrolling sideways. Movie cards use
 * poster tiles; episode cards use wide still tiles. On TV the channel and page keys jump rows.
 */
@Composable
internal fun CatalogTab(
    kind: CatalogKind,
    state: CatalogTabState?,
    watch: CatalogWatch,
    actions: CatalogActions,
    focus: TabFocus,
    modifier: Modifier = Modifier,
) {
    val isTv = LocalIsTv.current
    if (state == null || !state.loaded) return
    if (state.empty) {
        EmptyNote(
            "No ${kind.label.lowercase()} yet",
            "Once your library syncs, every ${kind.count(1).substringAfter(' ')} you may watch shows here, with its channels' rows.",
            modifier,
        )
        return
    }
    var holding by remember { mutableStateOf<WatchCard?>(null) }
    var holdingTitle by remember { mutableStateOf<Title?>(null) }
    val navigation = rememberTvBrowseNavigation(buildList {
        if (state.banner.isNotEmpty()) add(TvBrowseRow("banner", listOf("banner"), scrollable = false))
        state.shelves.forEach { content ->
            val ids = if (content.cards.isNotEmpty()) content.cards.map { it.itemId } else content.titles.map { it.id }
            add(TvBrowseRow(content.shelf.key, ids.map(Long::toString)))
        }
    }, focus)

    BrowseList(navigation, modifier) {
        if (state.banner.isNotEmpty()) {
            item(key = "banner") {
                RotatingBanner(
                    count = state.banner.size,
                    backdropUrl = { state.banner[it].artwork.backdropUrl },
                    onOpen = { i -> actions.onOpenTitle(state.banner[i].id) },
                    // Edge to edge, like a title page's hero.
                    modifier = Modifier.tvFocusItem(navigation.row("banner"), "banner", horizontalEdges = false),
                    footer = { i ->
                        val title = state.banner[i]
                        val progress = MediaProgress(title.durationMs, watch[title.id]?.resumeMs ?: 0L)
                        if (progress.hasProgress) WatchProgress(progress, modifier = Modifier.weight(1f))
                    },
                ) { i -> TitleSlide(state.banner[i], watch) }
            }
        }
        state.shelves.forEach { content ->
            val shelfKey = "${kind.key}/${content.shelf.key}"
            val row = navigation.row(content.shelf.key)
            item(key = shelfKey) {
                ShelfRow(row, heading = { ShelfHeader(content.shelf, it) }) {
                    if (content.cards.isNotEmpty()) {
                        items(content.cards, key = { it.itemId }) { card ->
                            if (card.kind == CatalogKind.MOVIES) {
                                PosterCard(
                                    card = card,
                                    onClick = {
                                        actions.onOpenTitle(card.titleId)
                                    },
                                    onHold = {
                                        holding = card
                                    },
                                    modifier = Modifier.width(PosterWidth).tvFocusItem(row, card.itemId.toString()),
                                )
                            } else {
                                WatchCardTile(
                                    card = card,
                                    onClick = {
                                        actions.onOpenEpisode(card.itemId)
                                    },
                                    onHold = {
                                        holding = card
                                    },
                                    modifier = Modifier.tvFocusItem(row, card.itemId.toString()),
                                )
                            }
                        }
                    } else {
                        items(content.titles, key = { it.id }) { title ->
                            PosterCard(
                                title = title,
                                watch = watch,
                                onClick = {
                                    actions.onOpenTitle(title.id)
                                },
                                onHold = {
                                    holdingTitle = title
                                },
                                modifier = Modifier.width(PosterWidth).tvFocusItem(row, title.id.toString()),
                            )
                        }
                    }
                }
            }
        }
    }

    holding?.let { card -> CardActions(card, actions, onDismiss = { holding = null; if (isTv) navigation.restore() }) }
    holdingTitle?.let { title ->
        TitleCardActions(title, watch, actions, onDismiss = { holdingTitle = null; if (isTv) navigation.restore() })
    }
}

/**
 * A Continue watching or Next up card's other ways: resume or play, from the start, and Add to channel.
 */
@Composable
private fun CardActions(card: WatchCard, actions: CatalogActions, onDismiss: () -> Unit) {
    val theme = LocalAnstelleTheme.current
    val typography = androidx.compose.material3.MaterialTheme.typography
    val movie = !card.kind.episodic
    val choices = listOfNotNull(
        if (card.positionMs > 0) {
            if (card.kind.episodic) WatchChoices.resumeEpisode(card.number, card.positionMs) else WatchChoices.resume(card.positionMs)
        } else {
            if (card.kind.episodic) WatchChoices.playEpisode(card.number) else WatchChoice(WatchKind.PLAY, "Play", "On Demand from 0:00.")
        },
        if (card.kind.episodic) WatchChoices.playSeriesFromStart().takeIf { card.positionMs > 0 } else WatchChoices.playFromStart.takeIf { card.positionMs > 0 },
        WatchChoices.addToChannel(isMovie = movie).takeIf { actions.onAddToChannel != null },
    )
    ActionDialog(
        actions = choices.dialogActions { choice ->
            onDismiss()
            if (choice.kind == WatchKind.ADD_TO_CHANNEL) actions.onAddToChannel?.invoke(card.itemId) else actions.onPlay(card.itemId, choice.positionMs)
        },
        onDismiss = onDismiss,
    ) {
        androidx.compose.material3.Text(card.title, style = typography.headlineSmall, color = theme.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
        listOfNotNull(card.number, card.episodeTitle).joinToString("  ").ifEmpty { null }?.let {
            androidx.compose.material3.Text(it, style = typography.bodyMedium, color = theme.textMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** A movie or series poster's shared ways to play or add it to a channel. */
@Composable
private fun TitleCardActions(title: Title, watch: CatalogWatch, actions: CatalogActions, onDismiss: () -> Unit) {
    val theme = LocalAnstelleTheme.current
    val typography = androidx.compose.material3.MaterialTheme.typography
    val movie = !title.kind.episodic
    val resume = watch[title.id]?.resumeMs ?: 0L
    val episode = watch.seriesProgress[title.id]
    val itemId = if (movie) title.id else episode?.itemId ?: title.id
    val choices = listOfNotNull(
        if (movie) (if (resume > 0) WatchChoices.resume(resume) else WatchChoice(WatchKind.PLAY, "Play", "On Demand from 0:00.")) else null,
        if (!movie) (episode?.let {
            if (it.positionMs > 0) WatchChoices.resumeEpisode(it.number, it.positionMs) else WatchChoices.playSeries(it.number ?: "S1E1")
        } ?: WatchChoices.playSeries()) else null,
        WatchChoices.playFromStart.takeIf { movie && resume > 0 },
        WatchChoices.playSeriesFromStart().takeIf { !movie && (episode?.positionMs ?: 0) > 0 },
        WatchChoices.addToChannel(isMovie = movie).takeIf { actions.onAddToChannel != null },
    )
    ActionDialog(
        actions = choices.dialogActions { choice ->
            onDismiss()
            when {
                choice.kind == WatchKind.ADD_TO_CHANNEL -> actions.onAddToChannel?.invoke(title.id)
                !movie && episode == null -> actions.onPlaySeries(title.id, choice.positionMs)
                else -> actions.onPlay(itemId, choice.positionMs)
            }
        },
        onDismiss = onDismiss,
    ) {
        androidx.compose.material3.Text(title.title, style = typography.headlineSmall, color = theme.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (!movie && episode != null) {
            listOfNotNull(episode.number, episode.episodeTitle).joinToString("  ").ifEmpty { null }?.let {
                androidx.compose.material3.Text(it, style = typography.bodyMedium, color = theme.textMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** What a search key opens. */
private enum class FilterKey(val title: String) { SORT("Sort by"), TYPE("Type"), GENRE("Genre"), DECADE("Decade"), WATCHED("Watched") }

/**
 * Search's page under the tab row, whose search field fills it: with nothing typed, every
 * title newest-added first; once something is typed, what it finds, with keys to sort and
 * narrow it. Both are an endless grid of posters.
 */
@Composable
internal fun SearchTab(
    query: String,
    filters: SearchFilters,
    onFilters: (SearchFilters) -> Unit,
    results: SearchResults,
    watch: CatalogWatch,
    actions: CatalogActions,
    focus: TabFocus,
    modifier: Modifier = Modifier,
) {
    val theme = LocalAnstelleTheme.current
    val isTv = LocalIsTv.current
    val typography = androidx.compose.material3.MaterialTheme.typography
    var picking by remember { mutableStateOf<FilterKey?>(null) }
    var holdingTitle by remember { mutableStateOf<Title?>(null) }
    // Each key, and the one a closed sheet hands focus back to, so the D-pad stays where it was.
    val keys = remember { FilterKey.entries.associateWith { FocusRequester() } }
    var back by remember { mutableStateOf<FilterKey?>(null) }
    LaunchedEffect(back) {
        val key = back ?: return@LaunchedEffect
        keys.getValue(key).requestWhenReady()
        back = null
    }
    val searching = query.isNotBlank()
    val grid = rememberLazyGridState()
    val scope = rememberCoroutineScope()
    var area by rememberSaveable { mutableStateOf("results") }
    val posters = rememberTvRowFocus(results.titles.map { it.id.toString() }, onFocused = { area = "results" })
    val filterItems = buildList {
        add(FilterKey.SORT.name)
        if (filters.sort.orderLabel(filters.descending) != null) add("order")
        if (CatalogKind.entries.size > 1) add(FilterKey.TYPE.name)
        add(FilterKey.GENRE.name)
        add(FilterKey.DECADE.name)
        if (watch.known) add(FilterKey.WATCHED.name)
        if (filters.narrows) add("clear")
    }
    val filterFocus = rememberTvRowFocus(filterItems, onFocused = { area = "filters" })
    val rail = LocalRailFocus.current
    fun enterResults() {
        scope.launch {
            val index = results.titles.indexOfFirst { it.id.toString() == posters.selected }
            if (index >= 0) {
                grid.scrollToItem(index + if (searching) 2 else 1)
                posters.requester.requestWhenReady()
            }
        }
    }
    fun enterFilters() {
        scope.launch {
            grid.scrollToItem(0)
            filterFocus.restore()
        }
    }
    fun restore() {
        if (searching && (area == "filters" || results.titles.isEmpty())) enterFilters() else enterResults()
    }
    DisposableEffect(focus, searching, results.titles, filterFocus.selected, posters.selected) {
        if (isTv) focus.enter = ::restore
        onDispose { focus.enter = null }
    }
    if (isTv) LaunchedEffect(Unit) {
        if (focus.autoFocus && (posters.hasFocused || filterFocus.hasFocused)) restore()
    }
    LazyVerticalGrid(
        state = grid,
        columns = GridCells.Adaptive(PosterMin),
        // The gap under the tabs is the grid's own edge, not its padding, so it holds while scrolling.
        modifier = modifier.padding(top = screenGap).fillMaxSize().then(if (isTv) Modifier.focusGroup() else Modifier),
        contentPadding = PaddingValues(start = screenSide - TILE_FRAME, end = screenSide - TILE_FRAME, bottom = screenEdge),
        horizontalArrangement = Arrangement.spacedBy(screenGap - TILE_FRAME * 2),
        verticalArrangement = Arrangement.spacedBy(screenGap - TILE_FRAME * 2),
    ) {
        if (searching) {
            fullItem("keys") {
                // The keys run edge to edge; their first lines up with the posters' pictures.
                FocusRow(
                    filterFocus,
                    padding = screenSide,
                    spacing = screenGap,
                    modifier = Modifier.bleed(screenSide - TILE_FRAME)
                        .onPreviewKeyEvent { event ->
                            if (!isTv) return@onPreviewKeyEvent false
                            when (event.key) {
                                Key.DirectionUp -> {
                                    if (event.type == KeyEventType.KeyDown) focus.header.requestFocus()
                                    true
                                }
                                Key.DirectionDown -> {
                                    if (event.type == KeyEventType.KeyDown) enterResults()
                                    true
                                }
                                else -> false
                            }
                        },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    item(key = "sort") { SearchKey(FilterKey.SORT.title, filters.sort.label, modifier = Modifier.tvFocusItem(filterFocus, FilterKey.SORT.name).focusRequester(keys.getValue(FilterKey.SORT))) { picking = FilterKey.SORT } }
                    // The order turns round in place: two ways need no list.
                    filters.sort.orderLabel(filters.descending)?.let { order ->
                        item(key = "order") { SearchKey("Order", order, icon = Icons.Filled.SwapVert, modifier = Modifier.tvFocusItem(filterFocus, "order")) { onFilters(filters.reversed()) } }
                    }
                    if (CatalogKind.entries.size > 1) item(key = "type") { SearchKey(FilterKey.TYPE.title, filters.kind?.label ?: "Any", modifier = Modifier.tvFocusItem(filterFocus, FilterKey.TYPE.name).focusRequester(keys.getValue(FilterKey.TYPE))) { picking = FilterKey.TYPE } }
                    item(key = "genre") { SearchKey(FilterKey.GENRE.title, filters.genre ?: "Any", modifier = Modifier.tvFocusItem(filterFocus, FilterKey.GENRE.name).focusRequester(keys.getValue(FilterKey.GENRE))) { picking = FilterKey.GENRE } }
                    item(key = "decade") { SearchKey(FilterKey.DECADE.title, filters.decade?.let(::decadeName) ?: "Any", modifier = Modifier.tvFocusItem(filterFocus, FilterKey.DECADE.name).focusRequester(keys.getValue(FilterKey.DECADE))) { picking = FilterKey.DECADE } }
                    if (watch.known) item(key = "watched") { SearchKey(FilterKey.WATCHED.title, filters.watch.label, modifier = Modifier.tvFocusItem(filterFocus, FilterKey.WATCHED.name).focusRequester(keys.getValue(FilterKey.WATCHED))) { picking = FilterKey.WATCHED } }
                    if (filters.narrows) {
                        item(key = "clear") { SearchKey("Clear", null, icon = Icons.Filled.Close, modifier = Modifier.tvFocusItem(filterFocus, "clear")) { onFilters(SearchFilters(sort = filters.sort, descending = filters.descending)) } }
                    }
                }
            }
        }
        fullItem("heading") {
            val shown = results.titles.size
            ResultsHeading(
                title = if (searching) "Results" else "Recently added",
                count = when {
                    !searching -> null
                    filters.narrows -> "$shown of ${results.found}"
                    else -> "${results.found}"
                },
            )
        }
        if (searching && results.titles.isEmpty()) {
            fullItem("none") {
                androidx.compose.material3.Text(
                    if (filters.narrows && results.found > 0) "Nothing found matches these filters." else "No movie or series has that in its title.",
                    style = typography.bodyLarge,
                    color = theme.textMuted,
                    modifier = Modifier.padding(start = TILE_FRAME, top = 4.dp),
                )
            }
        }
        items(results.titles, key = { it.id }) { title ->
            PosterCard(
                title = title,
                watch = watch,
                onClick = { actions.onOpenTitle(title.id) },
                onHold = { holdingTitle = title },
                modifier = Modifier.tvFocusItem(posters, title.id.toString(), horizontalEdges = false)
                    .focusProperties {
                        if (isTv && grid.layoutInfo.visibleItemsInfo.firstOrNull { it.key == title.id }?.column == 0) {
                            left = rail?.rail ?: FocusRequester.Cancel
                        }
                    }
                    .onPreviewKeyEvent { event ->
                        val firstRow = grid.layoutInfo.visibleItemsInfo.firstOrNull { it.key == title.id }?.row == (if (searching) 2 else 1)
                        if (isTv && event.key == Key.DirectionUp && firstRow) {
                            if (event.type == KeyEventType.KeyDown) {
                                if (searching) enterFilters() else focus.header.requestFocus()
                            }
                            true
                        } else false
                    },
            )
        }
    }

    picking?.let { field ->
        val close = { picking = null; if (isTv) back = field }
        val choices: List<DialogAction> = when (field) {
            FilterKey.SORT -> SearchSort.entries.map { sort ->
                DialogAction.choice(sort.label, sort == filters.sort) { close(); if (sort != filters.sort) onFilters(filters.sortedBy(sort)) }
            }
            FilterKey.TYPE -> (listOf<CatalogKind?>(null) + CatalogKind.entries).map { kind ->
                DialogAction.choice(kind?.label ?: "Any", kind == filters.kind) { close(); onFilters(filters.copy(kind = kind)) }
            }
            FilterKey.GENRE -> (listOf<String?>(null) + (results.genres + listOfNotNull(filters.genre)).distinct()).map { genre ->
                DialogAction.choice(genre ?: "Any", genre == filters.genre) { close(); onFilters(filters.copy(genre = genre)) }
            }
            FilterKey.DECADE -> (listOf<Int?>(null) + (results.decades + listOfNotNull(filters.decade)).distinct()).map { decade ->
                DialogAction.choice(decade?.let(::decadeName) ?: "Any", decade == filters.decade) { close(); onFilters(filters.copy(decade = decade)) }
            }
            FilterKey.WATCHED -> WatchFilter.entries.map { option ->
                DialogAction.choice(option.label, option == filters.watch) { close(); onFilters(filters.copy(watch = option)) }
            }
        }
        ActionDialog(actions = choices, onDismiss = close) {
            androidx.compose.material3.Text(field.title, style = typography.headlineSmall, color = theme.text)
        }
    }
    holdingTitle?.let { title ->
        TitleCardActions(title, watch, actions, onDismiss = { holdingTitle = null; if (isTv) restore() })
    }
}

/** A grid item across every column: the keys, a heading, a line. */
private fun LazyGridScope.fullItem(key: String, content: @Composable () -> Unit) =
    item(key = key, span = { GridItemSpan(maxLineSpan) }) { content() }

/** The grid's heading in the rows' field voice, with how many it holds. */
@Composable
private fun ResultsHeading(title: String, count: String?) {
    val theme = LocalAnstelleTheme.current
    val isTv = LocalIsTv.current
    val style = androidx.compose.material3.MaterialTheme.typography.labelMedium.copy(fontSize = if (isTv) 15.sp else 13.sp, lineHeight = 18.sp)
    // Its pictures sit the rows' gap under it, as under a shelf's heading: the grid's spacing and their inset make up the rest.
    Row(Modifier.padding(start = TILE_FRAME, top = screenGap, bottom = TILE_FRAME), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.material3.Text(title.uppercase(), style = style, color = theme.text, maxLines = 1)
        count?.let { androidx.compose.material3.Text(it.uppercase(), style = style.copy(fontFeatureSettings = "tnum"), color = theme.textMuted, maxLines = 1) }
    }
}

/** "1990s". */
private fun decadeName(decade: Int) = "${decade}s"

/**
 * A search key: the field's name in the OSD's voice, then the value in use and the drop-down
 * mark, on a flat coal fill that focus turns rose.
 */
@Composable
private fun SearchKey(field: String, value: String?, modifier: Modifier = Modifier, icon: ImageVector = Icons.Filled.ArrowDropDown, onClick: () -> Unit) {
    val theme = LocalAnstelleTheme.current
    val isTv = LocalIsTv.current
    val label = androidx.compose.material3.MaterialTheme.typography.labelMedium
    FocusSurface(onClick = onClick, modifier = modifier.heightIn(min = if (isTv) 40.dp else 48.dp)) { focused ->
        Row(
            Modifier.align(Alignment.Center).padding(start = 14.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val ink = if (focused) theme.onAccent else theme.text
            androidx.compose.material3.Text(field.uppercase(), style = label, color = if (focused) ink.copy(alpha = 0.76f) else theme.textMuted, maxLines = 1)
            value?.let { androidx.compose.material3.Text(it.uppercase(), style = label, color = ink, maxLines = 1) }
            androidx.compose.material3.Icon(icon, contentDescription = null, tint = ink, modifier = Modifier.size(18.dp))
        }
    }
}
