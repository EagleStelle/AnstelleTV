package tv.anstelle.feature.settings.channels

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.items
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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import tv.anstelle.core.data.ProfileRepository
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
    val loaded: Boolean = false,
)

/** The active profile's id, read once. */
internal fun ProfileRepository.activeProfileId(): Flow<Long> = flow { emit(ensureActiveProfile().id) }

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ChannelsViewModel @Inject constructor(
    profiles: ProfileRepository,
    channels: ChannelRepository,
) : ViewModel() {
    val state: StateFlow<ChannelsUiState> = profiles.activeProfileId()
        .flatMapLatest { profileId ->
            combine(channels.observeChannels(profileId), channels.observeRemovedCount(profileId)) { list, removed ->
                ChannelsUiState(list, removed, loaded = true)
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ChannelsUiState())
}

/** Every channel of the active profile, with the ways to add more. */
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
    val firstFocus = remember { FocusRequester() }
    // Coming back from a channel, focus returns to it: the list is still scrolled there.
    val returnFocus = remember { FocusRequester() }
    var lastOpened by rememberSaveable { mutableStateOf<Long?>(null) }
    if (LocalIsTv.current) {
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
