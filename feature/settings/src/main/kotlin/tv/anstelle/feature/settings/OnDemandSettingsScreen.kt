package tv.anstelle.feature.settings

import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.NewReleases
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.ViewCarousel
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import tv.anstelle.core.data.DatabaseChanges
import tv.anstelle.core.data.Settings
import tv.anstelle.core.data.SettingsRepository
import tv.anstelle.core.data.ondemand.CatalogKind
import tv.anstelle.core.data.ondemand.CatalogRepository
import tv.anstelle.core.data.ondemand.Shelf
import tv.anstelle.core.data.ondemand.ShelfOrder
import tv.anstelle.core.data.ondemand.ShelfType
import tv.anstelle.core.theme.components.ChoiceRow
import tv.anstelle.core.theme.components.InfoRow
import tv.anstelle.core.theme.components.LinkRow
import tv.anstelle.core.theme.components.ReorderList
import tv.anstelle.core.theme.components.ReorderRow
import tv.anstelle.core.theme.components.SettingsNotice
import tv.anstelle.core.theme.components.SettingsPage
import tv.anstelle.core.theme.components.rememberInitialFocus
import tv.anstelle.core.theme.components.rememberReorderList
import tv.anstelle.core.theme.components.reorderItems
import tv.anstelle.core.theme.components.section
import tv.anstelle.feature.settings.channels.ChannelNumber
import javax.inject.Inject

@Immutable
data class OnDemandSettingsUiState(
    /** How many random channels the Channels banner cycles through. */
    val homeBanner: Int = Settings.OnDemandBanner.default,
    /** How many random titles each kind's banner cycles through. */
    val banners: Map<CatalogKind, Int> = emptyMap(),
    /** Each kind's rows in the order its tab shows them. */
    val shelves: Map<CatalogKind, List<Shelf>> = emptyMap(),
    val loaded: Boolean = false,
)

@OptIn(FlowPreview::class)
@HiltViewModel
class OnDemandSettingsViewModel @Inject constructor(
    private val settings: SettingsRepository,
    private val catalog: CatalogRepository,
    changes: DatabaseChanges,
) : ViewModel() {
    /** Each kind's rows in their default order: its fixed rows, then the library channels airing it. */
    private val available = MutableStateFlow<Map<CatalogKind, List<Shelf>>?>(null)

    private val banners: Flow<Map<CatalogKind, Int>> =
        combine(CatalogKind.entries.map { kind -> settings.observe(Settings.catalogBanner(kind)).map { kind to it } }) { it.toMap() }

    private val orders: Flow<Map<CatalogKind, String>> =
        combine(CatalogKind.entries.map { kind -> settings.observe(Settings.catalogShelves(kind)).map { kind to it } }) { it.toMap() }

    val state: StateFlow<OnDemandSettingsUiState> =
        combine(settings.observe(Settings.OnDemandBanner), banners, orders, available) { home, banners, orders, available ->
            OnDemandSettingsUiState(
                homeBanner = home,
                banners = banners,
                shelves = available.orEmpty().mapValues { (kind, shelves) -> ShelfOrder.arrange(shelves, ShelfOrder.decode(orders[kind].orEmpty())) },
                loaded = available != null,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), OnDemandSettingsUiState())

    init {
        viewModelScope.launch { load() }
        viewModelScope.launch {
            changes.tables
                .filter { tables -> tables.any { it in ChannelTables } }
                .debounce(RELOAD_DEBOUNCE_MS)
                .collect { load() }
        }
    }

    private suspend fun load() {
        available.value = CatalogKind.entries.associateWith { kind ->
            kind.fixedShelves.map { Shelf(it) } + catalog.channelTitles(kind).map { (channel, _) -> Shelf(ShelfType.CHANNEL, channel) }
        }
    }

    fun setHomeBanner(size: Int) {
        viewModelScope.launch { settings.set(Settings.OnDemandBanner, size) }
    }

    fun setBanner(kind: CatalogKind, size: Int) {
        viewModelScope.launch { settings.set(Settings.catalogBanner(kind), size) }
    }

    /** Stores [kind]'s rows with the one at [from] set down at [to]. */
    fun move(kind: CatalogKind, shown: List<Shelf>, from: Int, to: Int) {
        viewModelScope.launch { catalog.setShelfOrder(kind, ShelfOrder.moved(shown, from, to)) }
    }

    private companion object {
        const val RELOAD_DEBOUNCE_MS = 1_500L
        val ChannelTables = setOf("channel", "channel_rule", "channel_daypart", "media_item")
    }
}

/**
 * On Demand, the same for every profile: how many random picks each tab's banner deals, then a
 * way into each kind's rows, so Series is never under every row of Movies.
 */
@Composable
fun OnDemandSettingsScreen(
    /** Opens a kind's rows, by its [CatalogKind.key]. */
    onOpenRows: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: OnDemandSettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val firstFocus = rememberInitialFocus(ready = state.loaded)

    SettingsPage(
        title = "On Demand",
        trail = listOf("Settings"),
        about = "Banners show random picks. Rows keep the order you set.",
        modifier = modifier,
    ) {
        section("Banners")
        item(key = "banner-home") {
            ChoiceRow(
                title = "Channels",
                value = state.homeBanner,
                choices = Settings.OnDemandBannerSizes,
                label = { "$it channels" },
                onSelect = viewModel::setHomeBanner,
                icon = Icons.Filled.ViewCarousel,
                modifier = Modifier.focusRequester(firstFocus),
            )
        }
        for (kind in CatalogKind.entries) {
            item(key = "banner-${kind.key}") {
                ChoiceRow(
                    title = kind.label,
                    value = state.banners[kind] ?: Settings.catalogBanner(kind).default,
                    choices = Settings.OnDemandBannerSizes,
                    label = { kind.count(it) },
                    onSelect = { viewModel.setBanner(kind, it) },
                    icon = kind.icon,
                )
            }
        }
        section("Rows")
        for (kind in CatalogKind.entries) {
            item(key = "rows-${kind.key}") {
                LinkRow(
                    title = kind.label,
                    summary = state.shelves[kind]?.let { if (it.isEmpty()) "None yet" else rowCount(it.size) },
                    icon = kind.icon,
                    onClick = { onOpenRows(kind.key) },
                )
            }
        }
    }
}

/**
 * One kind's rows in the order its tab shows them, each moved by its handle: its fixed rows
 * (Continue watching, Next up, Recently added) and every library channel that airs that kind.
 */
@Composable
fun OnDemandRowsScreen(
    /** The kind's [CatalogKind.key]. */
    kindKey: String,
    modifier: Modifier = Modifier,
    viewModel: OnDemandSettingsViewModel = hiltViewModel(),
) {
    val kind = CatalogKind.entries.firstOrNull { it.key == kindKey } ?: return
    val state by viewModel.state.collectAsStateWithLifecycle()
    val firstFocus = rememberInitialFocus(ready = state.loaded)
    val listState = rememberLazyListState()
    val shelves = state.shelves[kind].orEmpty()
    val order = rememberReorderList(shelves, listState, key = { it.key }) { _, from, to -> viewModel.move(kind, shelves, from, to) }

    SettingsPage(
        title = kind.label,
        trail = listOf("Settings", "On Demand"),
        about = "Drag a row by its handle to move it.",
        listState = listState,
        modifier = modifier,
    ) {
        if (state.loaded && shelves.isEmpty()) item(key = "rows-empty") { SettingsNotice("No rows yet.") }
        shelfRows(order, firstFocus)
    }
}

private fun rowCount(n: Int) = if (n == 1) "1 row" else "$n rows"

/** The rows of one kind's tab in order, each a fact with its handle: a channel's number, or the fixed row's icon. */
private fun androidx.compose.foundation.lazy.LazyListScope.shelfRows(order: ReorderList<Shelf>, firstFocus: FocusRequester) {
    reorderItems(order) { index, shelf ->
        ReorderRow(
            list = order,
            item = shelf,
            title = shelf.label,
            modifier = if (index == 0) Modifier.focusRequester(firstFocus) else Modifier,
        ) { rowModifier ->
            val channel = shelf.channel
            if (channel != null) {
                InfoRow(title = channel.name, leading = { ChannelNumber(channel.number) }, modifier = rowModifier)
            } else {
                InfoRow(title = shelf.label, icon = shelf.type.icon, modifier = rowModifier)
            }
        }
    }
}

/** A kind's mark in Settings. */
internal val CatalogKind.icon: ImageVector
    get() = when (this) {
        CatalogKind.MOVIES -> Icons.Filled.Movie
        CatalogKind.SERIES -> Icons.Filled.Tv
    }

/** A fixed row's mark. */
internal val ShelfType.icon: ImageVector
    get() = when (this) {
        ShelfType.CONTINUE -> Icons.Filled.History
        ShelfType.NEXT_UP -> Icons.Filled.SkipNext
        ShelfType.RECENT -> Icons.Filled.NewReleases
        ShelfType.CHANNEL -> Icons.Filled.Tv
    }
