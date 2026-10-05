package tv.anstelle.feature.settings.servers

import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Sync
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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import tv.anstelle.core.common.TimeSource
import tv.anstelle.core.data.ServerRepository
import tv.anstelle.core.model.MediaServer
import tv.anstelle.core.theme.LocalIsTv
import tv.anstelle.core.theme.components.FocusTile
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

/** The connected Jellyfin servers. */
@Composable
fun ServersScreen(
    onAddServer: () -> Unit,
    onOpenServer: (Long) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ServersViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val firstFocus = remember { FocusRequester() }
    if (LocalIsTv.current && state.loaded) LaunchedEffect(Unit) { runCatching { firstFocus.requestFocus() } }

    SettingsPage(
        title = "Media servers",
        subtitle = "AnstelleTV builds its channels from the Jellyfin libraries you connect here.",
        modifier = modifier,
    ) {
        items(state.servers, key = { it.id }) { server ->
            val progress = state.progress[server.id]
            FocusTile(
                title = "${server.name} · ${server.userName.orEmpty()}",
                subtitle = server.statusLine(progress, state.nowUtcMs),
                icon = when {
                    progress != null -> Icons.Filled.Sync
                    !server.isSignedIn || server.lastSyncError != null -> Icons.Filled.CloudOff
                    else -> Icons.Filled.Dns
                },
                onClick = { onOpenServer(server.id) },
                modifier = Modifier.fillMaxWidth().let { if (server == state.servers.first()) it.focusRequester(firstFocus) else it },
            )
        }
        item(key = "add") {
            FocusTile(
                title = "Add a Jellyfin server",
                subtitle = "Sign in with Quick Connect or a password.",
                icon = Icons.Filled.Add,
                onClick = onAddServer,
                modifier = Modifier.fillMaxWidth().let { if (state.servers.isEmpty()) it.focusRequester(firstFocus) else it },
            )
        }
    }
}
