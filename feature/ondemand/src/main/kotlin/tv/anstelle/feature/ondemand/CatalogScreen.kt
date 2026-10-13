package tv.anstelle.feature.ondemand

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
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
import tv.anstelle.core.data.ondemand.WatchChoice
import tv.anstelle.core.data.ondemand.WatchChoices
import tv.anstelle.core.data.ondemand.WatchFilter
import tv.anstelle.core.data.ondemand.WatchKind
import tv.anstelle.core.theme.LocalAnstelleTheme
import tv.anstelle.core.theme.LocalIsTv
import tv.anstelle.core.theme.components.ActionDialog
import tv.anstelle.core.theme.components.DialogAction
import tv.anstelle.core.theme.components.FocusSurface
import tv.anstelle.core.theme.components.WatchIcons
import tv.anstelle.core.theme.components.screenEdge
import tv.anstelle.core.theme.components.screenGap
import tv.anstelle.core.theme.components.screenSide

/** Where a catalog tab's picks lead: a title's page, the player, Add to channel. */
internal class CatalogActions(
    val onOpenTitle: (titleId: Long) -> Unit,
    val onPlay: (itemId: Long, positionMs: Long) -> Unit,
    /** Null for a member: adding to a channel is the owner's and admins'. */
    val onAddToChannel: ((itemId: Long) -> Unit)?,
)

/**
 * The thing last opened on a tab, by its key, so coming back focuses it again; and whether the
 * tab may take focus for itself when it first shows (not when it was just picked in the tab row).
 */
internal class TabFocus(val opened: String?, val onOpened: (String) -> Unit, val returnTo: FocusRequester, val autoFocus: Boolean)

/** A poster's width in a row: a TV shows about six past the banner, a phone three. */
private val PosterWidth @Composable get() = if (LocalIsTv.current) 132.dp else 108.dp

/** Search's grid shares the width at about a row poster's size. */
private val PosterMin @Composable get() = if (LocalIsTv.current) 132.dp else 104.dp

/**
 * One kind's tab: the banner of random titles, then its rows in the order Settings, On Demand
 * keeps, each one row scrolling sideways, the way Home's channel rows do: Continue watching
 * and Next up as cards, Recently added and every library channel's titles as tall posters. On
 * TV the channel and page keys jump row to row.
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
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val bannerFocus = remember { FocusRequester() }
    val firsts = remember(state.shelves) { state.shelves.map { FocusRequester() } }
    // The row focus is in, for the channel keys.
    var current by remember { mutableIntStateOf(-1) }
    var holding by remember { mutableStateOf<WatchCard?>(null) }
    val lead = if (state.banner.isNotEmpty()) 1 else 0
    if (isTv) {
        LaunchedEffect(Unit) {
            withFrameNanos { }
            val returned = focus.opened?.startsWith("${kind.key}/") == true && runCatching { focus.returnTo.requestFocus() }.isSuccess
            if (!returned && focus.autoFocus) runCatching { bannerFocus.requestFocus() }
        }
    }
    fun jump(delta: Int): Boolean {
        val target = (current + delta).coerceIn(0, state.shelves.lastIndex)
        if (state.shelves.isEmpty() || target == current) return false
        scope.launch {
            list.scrollToItem(lead + target)
            withFrameNanos { }
            runCatching { firsts[target].requestFocus() }
        }
        return true
    }

    LazyColumn(
        state = list,
        // The gap under the tabs is the list's own edge, not its padding, so it holds while scrolling.
        modifier = modifier
            .padding(top = screenGap)
            .fillMaxSize()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.ChannelDown, Key.PageDown -> jump(1)
                    Key.ChannelUp, Key.PageUp -> jump(-1)
                    else -> false
                }
            },
        contentPadding = PaddingValues(bottom = screenEdge),
        verticalArrangement = Arrangement.spacedBy(screenGap),
    ) {
        if (state.banner.isNotEmpty()) {
            item(key = "banner") {
                RotatingBanner(
                    count = state.banner.size,
                    backdropUrl = { state.banner[it].artwork.backdropUrl },
                    onOpen = { i ->
                        focus.onOpened("${kind.key}/banner")
                        actions.onOpenTitle(state.banner[i].id)
                    },
                    // Edge to edge, like a title page's hero.
                    modifier = Modifier
                        .let { if (focus.opened == "${kind.key}/banner") it.focusRequester(focus.returnTo) else it }
                        .focusRequester(bannerFocus)
                        .onFocusChanged { if (it.isFocused) current = -1 },
                ) { i -> TitleSlide(state.banner[i], watch) }
            }
        }
        state.shelves.forEachIndexed { row, content ->
            val shelfKey = "${kind.key}/${content.shelf.key}"
            item(key = shelfKey) {
                Column(verticalArrangement = Arrangement.spacedBy(screenGap - TILE_FRAME)) {
                    // A heading takes a gap more above it than under it, so it reads with its own row.
                    ShelfHeader(content.shelf, content.count, Modifier.padding(start = screenSide, end = screenSide, top = screenGap))
                    // Pictures sit the guide's gap apart: the spacing plus each card's focus inset.
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = screenSide - TILE_FRAME),
                        horizontalArrangement = Arrangement.spacedBy(screenGap - TILE_FRAME * 2),
                    ) {
                        fun Modifier.place(i: Int, key: String) = this
                            .let { if (i == 0) it.focusRequester(firsts[row]) else it }
                            .let { if (key == focus.opened) it.focusRequester(focus.returnTo) else it }
                            .onFocusChanged { if (it.isFocused) current = row }
                        if (content.cards.isNotEmpty()) {
                            itemsIndexed(content.cards, key = { _, card -> card.itemId }) { i, card ->
                                val key = "$shelfKey/${card.itemId}"
                                WatchCardTile(
                                    card = card,
                                    onClick = {
                                        focus.onOpened(key)
                                        actions.onPlay(card.itemId, card.positionMs)
                                    },
                                    onHold = {
                                        focus.onOpened(key)
                                        holding = card
                                    },
                                    modifier = Modifier.place(i, key),
                                )
                            }
                        } else {
                            itemsIndexed(content.titles, key = { _, title -> title.id }) { i, title ->
                                val key = "$shelfKey/${title.id}"
                                PosterCard(
                                    title = title,
                                    watch = watch,
                                    onClick = {
                                        focus.onOpened(key)
                                        actions.onOpenTitle(title.id)
                                    },
                                    modifier = Modifier.width(PosterWidth).place(i, key),
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    holding?.let { card -> CardActions(card, actions, onDismiss = { holding = null; runCatching { focus.returnTo.requestFocus() } }) }
}

/**
 * A Continue watching or Next up card's other ways: resume or play, from the start, its movie's
 * or series' page, and Add to channel.
 */
@Composable
private fun CardActions(card: WatchCard, actions: CatalogActions, onDismiss: () -> Unit) {
    val theme = LocalAnstelleTheme.current
    val typography = androidx.compose.material3.MaterialTheme.typography
    val movie = !card.kind.episodic
    val choices = listOfNotNull(
        if (card.positionMs > 0) WatchChoices.resume(card.positionMs) else WatchChoice(WatchKind.PLAY, "Play", "On Demand from 0:00."),
        WatchChoices.playFromStart.takeIf { card.positionMs > 0 },
        WatchChoices.addToChannel(isMovie = movie).takeIf { actions.onAddToChannel != null },
    )
    ActionDialog(
        actions = choices.map { choice ->
            DialogAction(choice.title, choice.detail, if (choice.kind == WatchKind.ADD_TO_CHANNEL) WatchIcons.AddToChannel else WatchIcons.play(choice.positionMs, false)) {
                onDismiss()
                if (choice.kind == WatchKind.ADD_TO_CHANNEL) actions.onAddToChannel?.invoke(card.itemId) else actions.onPlay(card.itemId, choice.positionMs)
            }
        }.let { list ->
            // Its page sits after the ways to play, before adding it anywhere.
            val page = DialogAction(if (movie) "Go to movie" else "Go to series", if (movie) "What it is about, and every way to watch it." else "Every season and episode.", Icons.Filled.Info) {
                onDismiss()
                actions.onOpenTitle(card.titleId)
            }
            val at = list.size - if (actions.onAddToChannel != null) 1 else 0
            list.take(at) + page + list.drop(at)
        },
        onDismiss = onDismiss,
    ) {
        androidx.compose.material3.Text(card.title, style = typography.headlineSmall, color = theme.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
        listOfNotNull(card.number, card.episodeTitle).joinToString("  ").ifEmpty { null }?.let {
            androidx.compose.material3.Text(it, style = typography.bodyMedium, color = theme.textMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
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
    // Each key, and the one a closed sheet hands focus back to, so the D-pad stays where it was.
    val keys = remember { FilterKey.entries.associateWith { FocusRequester() } }
    var back by remember { mutableStateOf<FilterKey?>(null) }
    LaunchedEffect(back) {
        val key = back ?: return@LaunchedEffect
        withFrameNanos { }
        runCatching { keys.getValue(key).requestFocus() }
        back = null
    }
    val searching = query.isNotBlank()
    if (isTv) {
        LaunchedEffect(Unit) {
            withFrameNanos { }
            if (focus.opened?.startsWith("search/") == true) runCatching { focus.returnTo.requestFocus() }
        }
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(PosterMin),
        // The gap under the tabs is the grid's own edge, not its padding, so it holds while scrolling.
        modifier = modifier.padding(top = screenGap).fillMaxSize(),
        contentPadding = PaddingValues(start = screenSide - TILE_FRAME, end = screenSide - TILE_FRAME, bottom = screenEdge),
        horizontalArrangement = Arrangement.spacedBy(screenGap - TILE_FRAME * 2),
        verticalArrangement = Arrangement.spacedBy(screenGap - TILE_FRAME * 2),
    ) {
        if (searching) {
            fullItem("keys") {
                // The keys run edge to edge; their first lines up with the posters' pictures.
                LazyRow(
                    modifier = Modifier.bleed(screenSide - TILE_FRAME),
                    contentPadding = PaddingValues(horizontal = screenSide),
                    horizontalArrangement = Arrangement.spacedBy(screenGap),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    item(key = "sort") { SearchKey(FilterKey.SORT.title, filters.sort.label, modifier = Modifier.focusRequester(keys.getValue(FilterKey.SORT))) { picking = FilterKey.SORT } }
                    // The order turns round in place: two ways need no list.
                    filters.sort.orderLabel(filters.descending)?.let { order ->
                        item(key = "order") { SearchKey("Order", order, icon = Icons.Filled.SwapVert) { onFilters(filters.reversed()) } }
                    }
                    if (CatalogKind.entries.size > 1) item(key = "type") { SearchKey(FilterKey.TYPE.title, filters.kind?.label ?: "Any", modifier = Modifier.focusRequester(keys.getValue(FilterKey.TYPE))) { picking = FilterKey.TYPE } }
                    item(key = "genre") { SearchKey(FilterKey.GENRE.title, filters.genre ?: "Any", modifier = Modifier.focusRequester(keys.getValue(FilterKey.GENRE))) { picking = FilterKey.GENRE } }
                    item(key = "decade") { SearchKey(FilterKey.DECADE.title, filters.decade?.let(::decadeName) ?: "Any", modifier = Modifier.focusRequester(keys.getValue(FilterKey.DECADE))) { picking = FilterKey.DECADE } }
                    if (watch.known) item(key = "watched") { SearchKey(FilterKey.WATCHED.title, filters.watch.label, modifier = Modifier.focusRequester(keys.getValue(FilterKey.WATCHED))) { picking = FilterKey.WATCHED } }
                    if (filters.narrows) {
                        item(key = "clear") { SearchKey("Clear", null, icon = Icons.Filled.Close) { onFilters(SearchFilters(sort = filters.sort, descending = filters.descending)) } }
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
        posters(results.titles, watch, actions, focus)
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
}

/** The grid's posters, each opening its page. */
private fun LazyGridScope.posters(titles: List<Title>, watch: CatalogWatch, actions: CatalogActions, focus: TabFocus) {
    items(titles, key = { it.id }) { title ->
        val key = "search/${title.id}"
        PosterCard(
            title = title,
            watch = watch,
            onClick = {
                focus.onOpened(key)
                actions.onOpenTitle(title.id)
            },
            modifier = if (key == focus.opened) Modifier.focusRequester(focus.returnTo) else Modifier,
        )
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
private fun SearchKey(field: String, value: String?, icon: ImageVector = Icons.Filled.ArrowDropDown, modifier: Modifier = Modifier, onClick: () -> Unit) {
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
