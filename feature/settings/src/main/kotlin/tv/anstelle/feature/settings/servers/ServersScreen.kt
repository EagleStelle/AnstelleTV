package tv.anstelle.feature.settings.servers

import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Sync
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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import tv.anstelle.core.common.TimeSource
import tv.anstelle.core.data.ServerRepository
import tv.anstelle.core.model.MediaServer
import tv.anstelle.core.theme.components.LinkRow
import tv.anstelle.core.theme.components.OsdButton
import tv.anstelle.core.theme.components.SettingsButtons
import tv.anstelle.core.theme.components.SettingsPage
import tv.anstelle.core.theme.components.rememberInitialFocus
import javax.inject.Inject

@Immutable
data class ServersUiState(
    val servers: List<MediaServer> = emptyList(),
    val progress: Map<Long, String> = emptyMap(),
    val nowUtcMs: Long = 0,
    val loaded: Boolean = false,
)

@HiltViewModel
class ServersViewModel @Inject constructor(
    repository: ServerRepository,
    time: TimeSource,
) : ViewModel() {
    val state: StateFlow<ServersUiState> = combine(repository.observeServers(), repository.syncProgress) { servers, progress ->
        ServersUiState(servers, progress.mapValues { it.value.message }, time.nowUtcMs(), loaded = true)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ServersUiState())
}

/** The connected Jellyfin servers, each opening its own page, and the button that adds one. */
@Composable
fun ServersScreen(
    onAddServer: () -> Unit,
    onOpenServer: (Long) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ServersViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val firstFocus = rememberInitialFocus(ready = state.loaded)

    SettingsPage(
        title = "Servers",
        trail = listOf("Settings"),
        about = "AnstelleTV builds its channels from the Jellyfin libraries of the servers you connect here. " +
            "Add one with its address, then sign in with Quick Connect or a password.",
        modifier = modifier,
    ) {
        item(key = "add") {
            SettingsButtons {
                OsdButton(
                    "Add server",
                    onClick = onAddServer,
                    icon = Icons.Filled.Add,
                    primary = true,
                    modifier = if (state.servers.isEmpty()) Modifier.focusRequester(firstFocus) else Modifier,
                )
            }
        }
        items(state.servers, key = { it.id }) { server ->
            val progress = state.progress[server.id]
            val trouble = !server.isSignedIn || server.lastSyncError != null
            val status = server.statusLine(progress, state.nowUtcMs)
            LinkRow(
                title = server.name,
                // "alice, synced 5 minutes ago"; a problem or a running sync speaks alone.
                summary = server.userName?.takeIf { !trouble && progress == null }?.let { "$it, ${status.replaceFirstChar(Char::lowercase)}" } ?: status,
                icon = when {
                    progress != null -> Icons.Filled.Sync
                    trouble -> Icons.Filled.CloudOff
                    else -> Icons.Filled.Dns
                },
                onClick = { onOpenServer(server.id) },
                modifier = if (server == state.servers.first()) Modifier.focusRequester(firstFocus) else Modifier,
            )
        }
    }
}
