package tv.anstelle.feature.settings.channels

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.RestorePage
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import tv.anstelle.core.data.ProfileRepository
import tv.anstelle.core.data.channels.ChannelRepository
import tv.anstelle.core.data.channels.GeneratedChannel
import tv.anstelle.core.theme.LocalIsTv
import tv.anstelle.core.theme.components.FocusTile
import tv.anstelle.feature.settings.servers.MessageText
import tv.anstelle.feature.settings.servers.SettingsPage
import javax.inject.Inject

@Immutable
data class RemovedRow(val key: String, val name: String, val details: String)

@Immutable
data class RemovedUiState(
    val channels: List<RemovedRow> = emptyList(),
    val loading: Boolean = true,
    val busy: Boolean = false,
    val message: String? = null,
)

@HiltViewModel
class RemovedChannelsViewModel @Inject constructor(
    private val profiles: ProfileRepository,
    private val repository: ChannelRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(RemovedUiState())
    val state: StateFlow<RemovedUiState> = _state.asStateFlow()

    private var removed: List<GeneratedChannel> = emptyList()

    init {
        viewModelScope.launch {
            removed = repository.removedChannels(profiles.ensureActiveProfile().id)
            publish()
        }
    }

    fun bringBack(key: String) = bringBack(listOf(key))

    fun bringBackAll() = bringBack(removed.map { it.key })

    private fun bringBack(keys: List<String>) {
        if (keys.isEmpty() || _state.value.busy) return
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            repository.bringBack(profiles.ensureActiveProfile().id, keys)
            removed = removed.filter { it.key !in keys }
            publish("Brought back ${keys.size} ${if (keys.size == 1) "channel" else "channels"}.")
        }
    }

    private fun publish(message: String? = null) {
        _state.value = RemovedUiState(
            channels = removed.map { RemovedRow(it.key, it.name, "${it.segment.groupName} · ${matchSummary(it.match)}") },
            loading = false,
            message = message,
        )
    }
}

/** Channels made from the library that were deleted, and still fit the library. */
@Composable
fun RemovedChannelsScreen(
    modifier: Modifier = Modifier,
    viewModel: RemovedChannelsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val firstFocus = remember { FocusRequester() }
    // The list arrives after matching, so focus waits for it.
    if (LocalIsTv.current) LaunchedEffect(state.loading) { if (!state.loading) runCatching { firstFocus.requestFocus() } }
    SettingsPage(
        title = "Removed channels",
        subtitle = "Channels made from your library that were deleted. They don't come back on their own.",
        modifier = modifier,
    ) {
        item(key = "status") {
            MessageText(
                when {
                    state.loading -> "Matching with your library…"
                    state.message != null -> state.message!!
                    state.channels.isEmpty() -> "Your library has no content for the removed channels now."
                    state.channels.size == 1 -> "1 removed channel still fits your library."
                    else -> "${state.channels.size} removed channels still fit your library."
                },
            )
        }
        if (state.channels.isNotEmpty()) {
            item(key = "all") {
                FocusTile(
                    title = if (state.busy) "Bringing back…" else "Bring back all ${state.channels.size}",
                    icon = Icons.Filled.RestorePage,
                    onClick = viewModel::bringBackAll,
                    modifier = Modifier.fillMaxWidth().focusRequester(firstFocus),
                )
            }
        }
        items(state.channels, key = { it.key }) { channel ->
            FocusTile(
                title = channel.name,
                subtitle = channel.details,
                icon = Icons.Filled.Restore,
                onClick = { viewModel.bringBack(channel.key) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
