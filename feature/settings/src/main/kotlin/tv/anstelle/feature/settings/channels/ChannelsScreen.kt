package tv.anstelle.feature.settings.channels

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.filled.ViewCarousel
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import tv.anstelle.core.data.Settings
import tv.anstelle.core.data.SettingsRepository
import tv.anstelle.core.theme.LocalAnstelleTheme
import tv.anstelle.core.theme.components.TileTrailing
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import tv.anstelle.core.data.channels.ChannelRepository
import tv.anstelle.core.data.channels.ChannelSummary
import tv.anstelle.core.theme.LocalIsTv
import tv.anstelle.core.theme.components.FocusTile
import tv.anstelle.feature.settings.servers.MessageText
import tv.anstelle.feature.settings.servers.SectionLabel
import tv.anstelle.feature.settings.servers.SettingsPage
import javax.inject.Inject

@Immutable
data class ChannelsUiState(
    val channels: List<ChannelSummary> = emptyList(),
    /** Library channels that were deleted. */
    val removed: Int = 0,
    /** How many random channels the On demand banner cycles through. */
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

    /** Steps the banner's size through its choices, wrapping at either end. */
    fun stepBannerSize(by: Int) {
        val sizes = Settings.OnDemandBannerSizes
        val current = sizes.indexOf(state.value.bannerSize).takeIf { it >= 0 } ?: sizes.indexOf(Settings.OnDemandBanner.default)
        setBannerSize(sizes[(current + by).mod(sizes.size)])
    }
}

/** Every channel, the same for every profile, with the ways to add more. */
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
    val isTv = LocalIsTv.current
    val firstFocus = remember { FocusRequester() }
    // Coming back from a channel, focus returns to it: the list is still scrolled there.
    val returnFocus = remember { FocusRequester() }
    var lastOpened by rememberSaveable { mutableStateOf<Long?>(null) }
    var pickingBanner by remember { mutableStateOf(false) }
    if (pickingBanner) {
        BannerSizePicker(
            current = state.bannerSize,
            onPick = {
                viewModel.setBannerSize(it)
                pickingBanner = false
            },
            onDismiss = { pickingBanner = false },
        )
    }
    if (isTv) {
        LaunchedEffect(state.loaded) {
            if (!state.loaded) return@LaunchedEffect
            val returned = lastOpened != null && runCatching { returnFocus.requestFocus() }.isSuccess
            if (!returned) runCatching { firstFocus.requestFocus() }
        }
    }
    SettingsPage(
        title = "Channels",
        subtitle = if (state.channels.isEmpty()) null else "${state.channels.size} channels. Select one to change it.",
        modifier = modifier,
    ) {
        item(key = "new") {
            FocusTile(
                title = "New channel",
                subtitle = "Build a channel from genres, networks, years, shows and more.",
                icon = Icons.Filled.Add,
                onClick = onNewChannel,
                modifier = Modifier.fillMaxWidth().focusRequester(firstFocus),
            )
        }
        item(key = "groups") {
            FocusTile(
                title = "Groups",
                subtitle = "Sort channels into guide groups.",
                icon = Icons.Filled.Folder,
                onClick = onOpenGroups,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item(key = "numbers") {
            FocusTile(
                title = "Channel numbers",
                subtitle = "Where each group of channels is numbered from.",
                icon = Icons.Filled.FormatListNumbered,
                onClick = onOpenNumbers,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item(key = "banner") {
            // The D-pad steps the size in place; touch picks it from a list.
            FocusTile(
                title = "On demand banner",
                subtitle = "Channels picked at random on each visit for the top of On demand.",
                icon = Icons.Filled.ViewCarousel,
                value = "${state.bannerSize} channels",
                onClick = if (isTv) ({ viewModel.stepBannerSize(1) }) else ({ pickingBanner = true }),
                onPrevious = if (isTv) ({ viewModel.stepBannerSize(-1) }) else null,
                onNext = if (isTv) ({ viewModel.stepBannerSize(1) }) else null,
                trailing = if (isTv) TileTrailing.None else TileTrailing.Chevron,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (state.removed > 0) {
            item(key = "removed") {
                FocusTile(
                    title = "Removed channels",
                    subtitle = "Bring back channels made from your library that were deleted.",
                    icon = Icons.Filled.Restore,
                    onClick = onOpenRemoved,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        if (state.loaded && state.channels.isEmpty()) {
            item(key = "empty") {
                MessageText("No channels yet. They are made from your library after it syncs, or add your own.")
            }
        }
        if (state.channels.isNotEmpty()) item(key = "label") { SectionLabel("Your channels") }
        items(state.channels, key = { it.channel.id }) { summary ->
            val channel = summary.channel
            FocusTile(
                title = "${channel.number}  ${channel.name}",
                subtitle = listOfNotNull(
                    summary.groupName,
                    playbackLabel(channel.playbackOrder, channel.blockSize),
                    "favorite".takeIf { channel.isFavorite },
                    "hidden".takeIf { channel.isHidden },
                    summary.seasonLabel?.let { if (summary.inSeason) "on air $it" else "off season, on air $it" },
                ).joinToString(" · "),
                icon = when {
                    channel.isHidden -> Icons.Filled.VisibilityOff
                    channel.isFavorite -> Icons.Filled.Star
                    else -> Icons.Filled.LiveTv
                },
                onClick = {
                    lastOpened = channel.id
                    onOpenChannel(channel.id)
                },
                modifier = Modifier.fillMaxWidth().let { if (channel.id == lastOpened) it.focusRequester(returnFocus) else it },
            )
        }
    }
}

/** The banner's sizes as a list, for touch. */
@Composable
private fun BannerSizePicker(current: Int, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    val theme = LocalAnstelleTheme.current
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = theme.surface,
        title = { androidx.compose.material3.Text("On demand banner", style = androidx.compose.material3.MaterialTheme.typography.headlineSmall, color = theme.text) },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(Settings.OnDemandBannerSizes) { size ->
                    FocusTile(
                        title = "$size channels",
                        selected = size == current,
                        onClick = { onPick(size) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                androidx.compose.material3.Text("Cancel", color = theme.accent)
            }
        },
    )
}
