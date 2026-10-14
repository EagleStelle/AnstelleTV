package tv.anstelle.feature.ondemand

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import tv.anstelle.core.common.AnstelleDispatchers
import tv.anstelle.core.common.AppLog
import tv.anstelle.core.common.Dispatcher
import tv.anstelle.core.data.DatabaseChanges
import tv.anstelle.core.data.ProfileRepository
import tv.anstelle.core.data.Settings
import tv.anstelle.core.data.SettingsRepository
import tv.anstelle.core.data.ondemand.CatalogKind
import tv.anstelle.core.data.ondemand.CatalogRepository
import tv.anstelle.core.data.ondemand.CatalogSearch
import tv.anstelle.core.data.ondemand.CatalogWatch
import tv.anstelle.core.data.ondemand.SearchFilters
import tv.anstelle.core.data.ondemand.SearchResults
import tv.anstelle.core.data.ondemand.SeriesProgress
import tv.anstelle.core.data.ondemand.Shelf
import tv.anstelle.core.data.ondemand.ShelfOrder
import tv.anstelle.core.data.ondemand.ShelfType
import tv.anstelle.core.data.ondemand.Title
import tv.anstelle.core.data.ondemand.WatchCard
import tv.anstelle.core.data.playback.PlaybackReporter
import tv.anstelle.core.model.Channel
import tv.anstelle.core.model.ProfileAccess
import javax.inject.Inject
import java.time.LocalDate
import kotlin.random.Random

/** One row of a catalog tab with what it holds: cards for Continue watching and Next up, else titles. */
@Immutable
data class ShelfContent(
    val shelf: Shelf,
    val cards: List<WatchCard> = emptyList(),
    val titles: List<Title> = emptyList(),
)

@Immutable
data class CatalogTabState(
    val loaded: Boolean = false,
    /** Nothing of the kind the profile may watch: nothing synced yet, or all over its limit. */
    val empty: Boolean = false,
    val banner: List<Title> = emptyList(),
    val shelves: List<ShelfContent> = emptyList(),
)

@Immutable
data class CatalogUiState(
    val tabs: Map<CatalogKind, CatalogTabState> = emptyMap(),
    val watch: CatalogWatch = CatalogWatch(),
)

/**
 * On Demand's catalog tabs (one per [CatalogKind]) and Search. Nothing is read until one of
 * them first opens ([start]): the library from SQLite, then what the profile watched from the
 * server, which fills in Continue watching, Next up and the posters' marks once it answers. Each
 * visit deals its banners and shuffles every channel's row once, so they hold while it lasts.
 * Library changes and finished views are read again only while a tab or Search is on screen.
 */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@HiltViewModel
class CatalogViewModel @Inject constructor(
    profiles: ProfileRepository,
    private val repository: CatalogRepository,
    settings: SettingsRepository,
    reporter: PlaybackReporter,
    changes: DatabaseChanges,
    @Dispatcher(AnstelleDispatchers.Default) default: CoroutineDispatcher,
) : ViewModel() {
    /** What the titles and channels hold, from SQLite. */
    private class Library(val titles: Map<CatalogKind, List<Title>>, val channels: Map<CatalogKind, List<Pair<Channel, Set<Long>>>>) {
        val all: List<Title> = titles.values.flatten()
    }

    /** What the server says the profile watched. */
    private data class Watching(
        val watch: CatalogWatch = CatalogWatch(),
        val resume: Map<CatalogKind, List<WatchCard>> = emptyMap(),
        val nextUp: Map<CatalogKind, List<WatchCard>> = emptyMap(),
    )

    /** This visit's deal: banners and channel rows shuffle by it. */
    private val seed = Random.nextLong()

    private val started = MutableStateFlow(false)
    private val libraryTick = MutableStateFlow(0)
    private val watchTick = MutableStateFlow(0)

    /** True while a catalog tab or Search is on screen; out of view nothing is read again. */
    private val visible = MutableStateFlow(false)

    /** Only Search reads both kinds. */
    private val selected = MutableStateFlow<List<CatalogKind>>(listOf(CatalogKind.MOVIES))
    private val libraries = HashMap<Pair<ProfileAccess, List<CatalogKind>>, Pair<Int, Library>>()
    private var libraryDay = LocalDate.now()

    private val access: Flow<ProfileAccess> = started.filter { it }.take(1)
        .flatMapLatest { profiles.observeAccess(profiles.activeProfile().id) }
        .shareIn(viewModelScope, SharingStarted.Eagerly, replay = 1)

    private val library: StateFlow<Library?> = visible.flatMapLatest { shown ->
        if (!shown) return@flatMapLatest emptyFlow()
        combine(access, libraryTick, selected) { access, tick, kinds -> Triple(access, tick, kinds) }
            .flatMapLatest { (access, tick, kinds) -> flow {
                val startedAt = System.nanoTime()
                val today = LocalDate.now()
                if (today != libraryDay) { libraries.clear(); libraryDay = today }
                val key = access to kinds
                libraries[key]?.takeIf { it.first == tick }?.let {
                    emit(it.second)
                    AppLog.i("Catalog", "${kinds.joinToString { it.key }} cached")
                    return@flow
                }
                // Publish the open tab's local titles first. Network and channel matching cannot
                // delay the banner or Recently added; the other tab stays unloaded until opened.
                val titles = kinds.associateWith { repository.titles(it, access) }
                emit(Library(titles, emptyMap()))
                AppLog.i("Catalog", "${kinds.joinToString { it.key }} local titles in ${(System.nanoTime() - startedAt) / 1_000_000} ms")
                val complete = Library(titles, kinds.associateWith { repository.channelTitles(it) })
                // Bound this cache to the current access rules, with Movies, Series and Search.
                libraries.keys.removeAll { it.first != access }
                libraries[key] = tick to complete
                emit(complete)
            } }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val watchCache = HashMap<Pair<ProfileAccess, List<CatalogKind>>, Pair<Long, Watching>>()
    private var watchRevision = 0
    private val watching: StateFlow<Watching> = visible.flatMapLatest { shown ->
        if (!shown) return@flatMapLatest emptyFlow()
        combine(access, watchTick, selected) { access, tick, kinds -> Triple(access, tick, kinds) }
            .mapLatest { (access, tick, kinds) ->
                if (watchRevision != tick) { watchCache.clear(); watchRevision = tick }
                watchCache.keys.removeAll { it.first != access }
                val key = access to kinds
                val now = System.nanoTime()
                watchCache[key]?.takeIf { now - it.first < WATCH_CACHE_NS }?.let { return@mapLatest it.second }
                coroutineScope {
                    val id = access.profileId
                    val watch = async { repository.watch(id, kinds.singleOrNull()) }
                    val resume = kinds.associateWith { kind -> async { repository.continueWatching(kind, id, access) } }
                    val nextUp = kinds.filter { ShelfType.NEXT_UP in it.fixedShelves }
                        .associateWith { async { repository.nextUp(id, access) } }
                    val resumeCards = resume.mapValues { it.value.await() }
                    val nextUpCards = nextUp.mapValues { it.value.await() }
                    val seriesProgress = (nextUpCards.values.flatten() + resumeCards.values.flatten())
                        .filter { it.kind.episodic }
                        .associate { card ->
                            card.titleId to SeriesProgress(card.itemId, card.number, card.episodeTitle, card.positionMs)
                        }
                    Watching(watch.await().copy(seriesProgress = seriesProgress), resumeCards, nextUpCards)
                        .also {
                            // A rejected/unavailable sign-in is not a successful empty history.
                            // Retry on the next visit instead of caching missing rows and badges.
                            if (it.watch.known) watchCache[key] = System.nanoTime() to it
                        }
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, Watching())

    private val orders: Flow<Map<CatalogKind, String>> =
        combine(CatalogKind.entries.map { kind -> settings.observe(Settings.catalogShelves(kind)).map { kind to it } }) { it.toMap() }

    private val bannerSizes: Flow<Map<CatalogKind, Int>> =
        combine(CatalogKind.entries.map { kind -> settings.observe(Settings.catalogBanner(kind)).map { kind to it } }) { it.toMap() }

    val state: StateFlow<CatalogUiState> = combine(library, watching, orders, bannerSizes) { library, watching, orders, sizes ->
        if (library == null) {
            CatalogUiState(watch = watching.watch)
        } else {
            CatalogUiState(
                tabs = library.titles.keys.associateWith { tab(it, library, watching, orders[it].orEmpty(), sizes[it] ?: 0) },
                watch = watching.watch,
            )
        }
    }.flowOn(default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CatalogUiState())

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _filters = MutableStateFlow(SearchFilters())
    val filters: StateFlow<SearchFilters> = _filters.asStateFlow()

    /**
     * What the words typed find, narrowed and ordered by the filters; typing settles for a moment
     * first. With nothing typed, every title, newest in the library first.
     */
    val results: StateFlow<SearchResults> = combine(
        library,
        _query.debounce { if (it.isBlank()) 0L else SEARCH_DEBOUNCE_MS }.distinctUntilChanged(),
        _filters,
        watching.map { it.watch }.distinctUntilChanged(),
    ) { library, query, filters, watch ->
        val all = library?.all.orEmpty()
        if (query.isBlank()) {
            SearchResults(titles = all.sortedWith(compareByDescending<Title> { it.addedUtcMs }.thenBy { it.sortKey }), found = all.size)
        } else {
            CatalogSearch.search(all, query, filters, watch)
        }
    }.flowOn(default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SearchResults())

    init {
        viewModelScope.launch {
            started.first { it }
            changes.tables
                .filter { tables -> tables.any { it in LibraryTables } }
                .debounce(RELOAD_DEBOUNCE_MS)
                .collect { libraryTick.value++ }
        }
        // Coming back from the player can beat its last report to Jellyfin: read again once that lands.
        viewModelScope.launch {
            merge(reporter.reported, repository.watchChanges).debounce(RELOAD_DEBOUNCE_MS).collect { watchTick.value++ }
        }
        // Reconnecting a profile can leave its visible access rules unchanged. Its account
        // table still emits, so discard history collected under the previous credentials.
        viewModelScope.launch {
            access.flatMapLatest { profiles.observeAccounts(it.profileId) }
                .drop(1)
                .collect { watchTick.value++ }
        }
    }

    /** Starts reading, the first time a catalog tab or Search opens. */
    fun start() {
        started.value = true
    }

    suspend fun firstEpisodeId(titleId: Long): Long? = repository.firstEpisodeId(titleId, access.first())

    fun setVisible(visible: Boolean) {
        this.visible.value = visible
    }

    /** Null selects Search, the only screen that needs both kinds together. */
    fun select(kind: CatalogKind?) {
        selected.value = kind?.let(::listOf) ?: CatalogKind.entries
    }

    fun setQuery(query: String) {
        _query.value = query
    }

    fun setFilters(filters: SearchFilters) {
        _filters.value = filters
    }

    /** [kind]'s tab: its banner's deal, then its rows in the stored order, leaving out the empty ones. */
    private fun tab(kind: CatalogKind, library: Library, watching: Watching, order: String, bannerSize: Int): CatalogTabState {
        val titles = library.titles[kind].orEmpty()
        if (titles.isEmpty()) return CatalogTabState(loaded = true, empty = true)
        val byId = titles.associateBy { it.id }
        val dealt = Random(seed xor kind.ordinal.toLong())
        // The banner deals only what is still to watch: never a movie or series watched through.
        val unwatched = titles.filter { !watching.watch.watched(it) }
        val banner = unwatched.filter { it.artwork.backdropUrl != null }.ifEmpty { unwatched }
            .sortedBy { it.id }
            .shuffled(dealt)
            .take(bannerSize)
        val channels = library.channels[kind].orEmpty()
        val channelIds = channels.associate { (channel, ids) -> channel.id to ids }
        val available = kind.fixedShelves.map { Shelf(it) } + channels.map { (channel, _) -> Shelf(ShelfType.CHANNEL, channel) }
        val shelves = ShelfOrder.arrange(available, ShelfOrder.decode(order)).mapNotNull { shelf ->
            when (shelf.type) {
                ShelfType.CONTINUE -> watching.resume[kind].orEmpty().takeIf { it.isNotEmpty() }?.let { ShelfContent(shelf, cards = it) }
                ShelfType.NEXT_UP -> watching.nextUp[kind].orEmpty().takeIf { it.isNotEmpty() }?.let { ShelfContent(shelf, cards = it) }
                ShelfType.RECENT -> titles.filter { it.addedUtcMs > 0 }
                    .sortedByDescending { it.addedUtcMs }
                    .take(RECENT_COUNT)
                    .takeIf { it.isNotEmpty() }
                    ?.let { ShelfContent(shelf, titles = it) }
                ShelfType.CHANNEL -> {
                    val channel = shelf.channel ?: return@mapNotNull null
                    // Everything the channel airs that the profile may watch, shuffled anew each visit.
                    channelIds[channel.id].orEmpty()
                        .mapNotNull(byId::get)
                        .sortedBy { it.id }
                        .shuffled(Random(seed xor channel.id))
                        .takeIf { it.isNotEmpty() }
                        ?.let { ShelfContent(shelf, titles = it) }
                }
            }
        }
        return CatalogTabState(loaded = true, banner = banner, shelves = shelves)
    }

    private companion object {
        const val RELOAD_DEBOUNCE_MS = 1_500L
        const val SEARCH_DEBOUNCE_MS = 150L

        const val WATCH_CACHE_NS = 60_000_000_000L

        /** How many titles Recently added holds; Search has all of them, newest first. */
        const val RECENT_COUNT = 24

        /** Tables whose changes read the library again. */
        val LibraryTables = setOf("media_item", "media_server", "channel", "channel_group", "channel_rule", "channel_daypart", "channel_season")
    }
}
