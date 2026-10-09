package tv.anstelle.feature.settings.channels

import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.ViewCarousel
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import tv.anstelle.core.data.Settings
import tv.anstelle.core.data.SettingsRepository
import tv.anstelle.core.data.channels.ChannelRepository
import tv.anstelle.core.data.channels.ChannelSummary
import tv.anstelle.core.theme.LocalAnstelleTheme
import tv.anstelle.core.theme.components.ChoiceRow
import tv.anstelle.core.theme.components.LinkRow
import tv.anstelle.core.theme.components.OsdButton
import tv.anstelle.core.theme.components.SettingsButtons
import tv.anstelle.core.theme.components.SettingsNotice
import tv.anstelle.core.theme.components.SettingsPage
import tv.anstelle.core.theme.components.SettingsSearch
import tv.anstelle.core.theme.components.rememberInitialFocus
import tv.anstelle.core.theme.components.section
import javax.inject.Inject

@Immutable
data class ChannelsUiState(
    val channels: List<ChannelSummary> = emptyList(),
    /** Library channels that were deleted. */
    val removed: Int = 0,
    /** How many random channels the On Demand banner cycles through. */
    val bannerSize: Int = 10,
    val loaded: Boolean = false,
)

@HiltViewModel
class ChannelsViewModel @Inject constructor(
    channels: ChannelRepository,
    private val settings: SettingsRepository,
) : ViewModel() {
    val state: StateFlow<ChannelsUiState> = combine(
        channels.observeChannels(),
        channels.observeRemovedCount(),
        settings.observe(Settings.OnDemandBanner),
    ) { list, removed, banner ->
        ChannelsUiState(list, removed, banner, loaded = true)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ChannelsUiState())

    fun setBannerSize(size: Int) {
        viewModelScope.launch { settings.set(Settings.OnDemandBanner, size) }
    }
}

/**
 * Every channel, the same for every profile: a search over them, the button that builds one, how
 * Live TV groups them and segments number them, how many On Demand deals into its banner, then
 * the channels someone built and the ones made from the library, each opening its own page. While
 * a search is typed, the page lists only the channels it finds.
 */
@Composable
fun ChannelsScreen(
    onOpenChannel: (Long) -> Unit,
    onNewChannel: () -> Unit,
    onOpenRemoved: () -> Unit,
    onOpenNumbers: () -> Unit,
    onOpenGroups: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ChannelsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // Coming back from a channel, focus returns to it: the list is still scrolled there.
    val firstFocus = rememberInitialFocus(ready = state.loaded)
    var query by rememberSaveable { mutableStateOf("") }
    SettingsPage(
        title = "Channels",
        trail = listOf("Settings"),
        about = "Shared by every profile. Groups are the tabs in Live TV; segments set the channel numbers. " +
            "Library channels update on their own until you change them.",
        modifier = modifier,
    ) {
        item(key = "search") { SettingsSearch(query, onQueryChange = { query = it }, label = "Find a channel") }
        val needle = query.trim()
        if (needle.isNotEmpty()) {
            val found = state.channels.filter { it.channel.name.contains(needle, ignoreCase = true) || it.channel.number.toString().startsWith(needle) }
            section(counted("Found", found.size), key = "section-found")
            if (found.isEmpty()) item(key = "found-none") { SettingsNotice("No channel has that name or number.") }
            channelRows(found, onOpenChannel)
            return@SettingsPage
        }
        item(key = "new") {
            SettingsButtons {
                OsdButton("New channel", onClick = onNewChannel, icon = Icons.Filled.Add, primary = true, modifier = Modifier.focusRequester(firstFocus))
            }
        }
        section("Live TV")
        item(key = "groups") {
            LinkRow(title = "Groups", icon = Icons.Filled.Folder, onClick = onOpenGroups)
        }
        item(key = "numbers") {
            LinkRow(title = "Segments", icon = Icons.Filled.FormatListNumbered, onClick = onOpenNumbers)
        }
        section("On Demand")
        item(key = "banner") {
            ChoiceRow(
                title = "Banner",
                value = state.bannerSize,
                choices = Settings.OnDemandBannerSizes,
                label = { "$it channels" },
                about = "How many random channels show at the top of On Demand.",
                onSelect = viewModel::setBannerSize,
                icon = Icons.Filled.ViewCarousel,
            )
        }
        // The channels someone built first, then the ones made from the library, each a group of its own.
        val (library, custom) = state.channels.partition { it.channel.fromLibrary }
        section(counted("Custom channels", custom.size), key = "section-custom")
        if (state.loaded && custom.isEmpty()) {
            item(key = "custom-empty") { SettingsNotice("No custom channels yet.", isInfo = true) }
        }
        channelRows(custom, onOpenChannel)
        section(counted("Library channels", library.size), key = "section-library")
        if (state.removed > 0) {
            item(key = "removed") {
                LinkRow(
                    title = "Removed channels",
                    summary = if (state.removed == 1) "1 made from your library" else "${state.removed} made from your library",
                    icon = Icons.Filled.Restore,
                    onClick = onOpenRemoved,
                )
            }
        }
        if (state.loaded && library.isEmpty()) {
            item(key = "library-empty") { SettingsNotice("No library channels yet.", isInfo = true) }
        }
        channelRows(library, onOpenChannel)
    }
}

/** "Custom channels  3": a section's name with how many it holds, once it holds any. */
private fun counted(name: String, count: Int) = if (count == 0) name else "$name  $count"

/** Channels by number, each opening its page; a library channel someone changed says so. */
private fun LazyListScope.channelRows(channels: List<ChannelSummary>, onOpenChannel: (Long) -> Unit) {
    items(channels, key = { it.channel.id }) { summary ->
        val channel = summary.channel
        LinkRow(
            title = channel.name,
            summary = listOfNotNull(
                summary.groupName,
                playbackLabel(channel.playbackOrder, channel.blockSize),
                "edited".takeIf { channel.fromLibrary && !channel.isManaged },
                "favorite".takeIf { channel.isFavorite },
                "hidden".takeIf { channel.isHidden },
                summary.seasonLabel?.let { if (summary.inSeason) "on air $it" else "off season, on air $it" },
            ).joinToString(", "),
            leading = { ChannelNumber(channel.number) },
            onClick = { onOpenChannel(channel.id) },
        )
    }
}

/** A channel's number in the icon's place: tabular, so the column of numbers lines up. */
@Composable
internal fun ChannelNumber(number: Int) {
    val theme = LocalAnstelleTheme.current
    androidx.compose.material3.Text(
        "$number",
        style = androidx.compose.material3.MaterialTheme.typography.titleMedium.merge(TextStyle(fontFamily = theme.displayFont, fontFeatureSettings = "tnum")),
        color = androidx.compose.material3.LocalContentColor.current,
        maxLines = 1,
        modifier = Modifier.widthIn(min = 40.dp),
    )
}
