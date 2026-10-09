package tv.anstelle.feature.settings.channels

import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.RestorePage
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
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
import tv.anstelle.core.data.channels.ChannelRepository
import tv.anstelle.core.data.channels.GeneratedChannel
import tv.anstelle.core.theme.components.MenuAction
import tv.anstelle.core.theme.components.MenuRow
import tv.anstelle.core.theme.components.OsdButton
import tv.anstelle.core.theme.components.SettingsButtons
import tv.anstelle.core.theme.components.SettingsNotice
import tv.anstelle.core.theme.components.SettingsPage
import tv.anstelle.core.theme.components.rememberInitialFocus
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
    private val repository: ChannelRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(RemovedUiState())
    val state: StateFlow<RemovedUiState> = _state.asStateFlow()

    private var removed: List<GeneratedChannel> = emptyList()

    init {
        viewModelScope.launch {
            removed = repository.removedChannels()
            publish()
        }
    }

    fun bringBack(key: String) = bringBack(listOf(key))

    fun bringBackAll() = bringBack(removed.map { it.key })

    private fun bringBack(keys: List<String>) {
        if (keys.isEmpty() || _state.value.busy) return
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            repository.bringBack(keys)
            removed = removed.filter { it.key !in keys }
            publish("Brought back ${keys.size} ${if (keys.size == 1) "channel" else "channels"}.")
        }
    }

    private fun publish(message: String? = null) {
        _state.value = RemovedUiState(
            channels = removed.map { RemovedRow(it.key, it.name, "${it.segment.groupName}, ${matchSummary(it.match)}") },
            loading = false,
            message = message,
        )
    }
}

/** Channels made from the library that were deleted and still fit the library: each one's menu brings it back. */
@Composable
fun RemovedChannelsScreen(
    modifier: Modifier = Modifier,
    viewModel: RemovedChannelsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // The list arrives after matching, so focus waits for it.
    val firstFocus = rememberInitialFocus(ready = !state.loading)
    SettingsPage(
        title = "Removed channels",
        trail = listOf("Settings", "Channels"),
        about = "Library channels you deleted. They stay gone until you bring them back.",
        modifier = modifier,
    ) {
        item(key = "status") {
            SettingsNotice(
                when {
                    state.loading -> "Matching with your library…"
                    state.message != null -> state.message!!
                    state.channels.isEmpty() -> "None of them fits your library now."
                    else -> null
                } ?: return@item,
            )
        }
        if (state.channels.isNotEmpty()) {
            item(key = "all") {
                SettingsButtons {
                    OsdButton(
                        if (state.busy) "Bringing back…" else "Bring back all",
                        onClick = viewModel::bringBackAll,
                        icon = Icons.Filled.RestorePage,
                        primary = true,
                        modifier = Modifier.focusRequester(firstFocus),
                    )
                }
            }
        }
        items(state.channels, key = { it.key }) { channel ->
            MenuRow(
                title = channel.name,
                summary = channel.details,
                icon = Icons.Filled.Restore,
                actions = listOf(MenuAction("Bring back", Icons.Filled.Restore) { viewModel.bringBack(channel.key) }),
            )
        }
    }
}
