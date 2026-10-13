package tv.anstelle.feature.settings.channels

import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.VideoLibrary
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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import tv.anstelle.core.data.channels.ChannelRepository
import tv.anstelle.core.data.channels.ChannelSummary
import tv.anstelle.core.theme.LocalAnstelleTheme
import tv.anstelle.core.theme.LocalIsTv
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
    val loaded: Boolean = false,
)

@HiltViewModel
class ChannelsViewModel @Inject constructor(
    channels: ChannelRepository,
) : ViewModel() {
    val state: StateFlow<ChannelsUiState> = channels.observeChannels()
        .map { ChannelsUiState(it, loaded = true) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ChannelsUiState())
}

/**
 * Live TV's settings, the same for every profile: the button that builds a channel, the groups
 * that make Live TV's tabs and number the channels, then a way into the channels someone built and
 * into the ones made from the library, so a long custom list is never above the library's.
 */
@Composable
fun ChannelsScreen(
    onOpenChannelList: (library: Boolean) -> Unit,
    onNewChannel: () -> Unit,
    onOpenGroups: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ChannelsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val firstFocus = rememberInitialFocus(ready = state.loaded)
    val (library, custom) = state.channels.partition { it.channel.fromLibrary }
    SettingsPage(
        title = "Live TV",
        trail = listOf("Settings"),
        about = "Shared by every profile. Groups are the tabs in Live TV and number their channels.",
        modifier = modifier,
    ) {
        item(key = "new") {
            SettingsButtons {
                OsdButton("New channel", onClick = onNewChannel, icon = Icons.Filled.Add, primary = true, modifier = Modifier.focusRequester(firstFocus))
            }
        }
        section("Configuration")
        item(key = "groups") {
            LinkRow(title = "Groups", icon = Icons.Filled.Folder, onClick = onOpenGroups)
        }
        section("Channels")
        item(key = "custom") {
            LinkRow(title = "Custom", summary = channelCount(custom.size), icon = Icons.Filled.Edit, onClick = { onOpenChannelList(false) })
        }
        item(key = "library") {
            LinkRow(title = "Library", summary = channelCount(library.size), icon = Icons.Filled.VideoLibrary, onClick = { onOpenChannelList(true) })
        }
    }
}

/**
 * One kind of channel, by number: the ones someone built, or the ones made from the library, each
 * opening its page, with a search over just these at the top. While it is typed, only what it
 * finds is listed.
 */
@Composable
fun ChannelListScreen(
    library: Boolean,
    onOpenChannel: (Long) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ChannelsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    val kind = state.channels.filter { it.channel.fromLibrary == library }
    SettingsPage(
        title = if (library) "Library" else "Custom",
        trail = listOf("Settings", "Live TV"),
        about = if (library) "Made from the library. Each can be a favorite or hidden." else "Channels someone built.",
        modifier = modifier,
    ) {
        item(key = "search") { SettingsSearch(query, onQueryChange = { query = it }, label = "Find a channel") }
        val needle = query.trim()
        val shown = if (needle.isEmpty()) kind else kind.filter { it.channel.name.contains(needle, ignoreCase = true) || it.channel.number.toString().startsWith(needle) }
        if (state.loaded && shown.isEmpty()) {
            item(key = "none") {
                SettingsNotice(
                    when {
                        needle.isNotEmpty() -> "No channel has that name or number."
                        library -> "No library channels yet."
                        else -> "No custom channels yet."
                    },
                )
            }
        }
        channelRows(shown, onOpenChannel)
    }
}

private fun channelCount(n: Int) = when (n) {
    0 -> "None yet"
    1 -> "1 channel"
    else -> "%,d channels".format(n)
}

/** Channels by number, each opening its page. */
private fun LazyListScope.channelRows(channels: List<ChannelSummary>, onOpenChannel: (Long) -> Unit) {
    items(channels, key = { it.channel.id }) { summary ->
        val channel = summary.channel
        LinkRow(
            title = channel.name,
            summary = listOfNotNull(
                summary.groupName,
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
