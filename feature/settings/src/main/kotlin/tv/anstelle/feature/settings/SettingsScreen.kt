package tv.anstelle.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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
import tv.anstelle.core.data.ServerRepository
import tv.anstelle.core.data.Settings
import tv.anstelle.core.data.SettingsRepository
import tv.anstelle.core.data.WebServerController
import tv.anstelle.core.data.WebServerStatus
import tv.anstelle.core.database.dao.PairedClientDao
import tv.anstelle.core.database.entity.PairedClientEntity
import tv.anstelle.core.model.MediaServer
import tv.anstelle.core.theme.LocalAnstelleTheme
import tv.anstelle.core.theme.LocalIsTv
import tv.anstelle.core.theme.components.FocusTile
import javax.inject.Inject

@Immutable
data class SettingsUiState(
    val webServerEnabled: Boolean = true,
    val webServer: WebServerStatus = WebServerStatus.Stopped,
    val pairedClients: List<PairedClientEntity> = emptyList(),
    val servers: List<MediaServer> = emptyList(),
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settings: SettingsRepository,
    private val webServer: WebServerController,
    pairedClients: PairedClientDao,
    servers: ServerRepository,
) : ViewModel() {
    val state: StateFlow<SettingsUiState> = combine(
        settings.observe(Settings.WebServerEnabled),
        webServer.status,
        pairedClients.observeAll(),
        servers.observeServers(),
    ) { enabled, status, clients, serverList -> SettingsUiState(enabled, status, clients, serverList) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    fun setWebServerEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settings.set(Settings.WebServerEnabled, enabled)
            if (enabled) webServer.startIfEnabled() else webServer.stop()
        }
    }
}

/** On-device settings. Live TV sources and themes fill in over later phases. */
@Composable
fun SettingsScreen(
    onOpenServers: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val theme = LocalAnstelleTheme.current
    val isTv = LocalIsTv.current
    val firstFocus = remember { FocusRequester() }
    if (isTv) LaunchedEffect(Unit) { runCatching { firstFocus.requestFocus() } }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(theme.background)
            .padding(horizontal = if (isTv) 48.dp else 16.dp, vertical = if (isTv) 32.dp else 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                androidx.compose.material3.Text(
                    "Settings",
                    style = androidx.compose.material3.MaterialTheme.typography.headlineSmall,
                    color = theme.text,
                )
            }
        }
        item {
            val server = state.webServer
            FocusTile(
                title = if (state.webServerEnabled) "Web control: on" else "Web control: off",
                subtitle = when {
                    !state.webServerEnabled -> "Select to let browsers on your network manage AnstelleTV."
                    server.running && server.urls.isNotEmpty() -> "Open ${server.urls.joinToString()} in a browser. Select to turn off."
                    server.error != null -> "Not running: ${server.error}"
                    else -> "Starting…"
                },
                icon = if (state.webServerEnabled) Icons.Filled.Wifi else Icons.Filled.WifiOff,
                onClick = { viewModel.setWebServerEnabled(!state.webServerEnabled) },
                modifier = Modifier.fillMaxWidth().focusRequester(firstFocus),
            )
        }
        item {
            val servers = state.servers
            FocusTile(
                title = "Media servers",
                subtitle = when {
                    servers.isEmpty() -> "Connect a Jellyfin server to start building channels."
                    servers.any { !it.isSignedIn || it.lastSyncError != null } -> "${servers.size} connected · needs attention"
                    else -> servers.joinToString { it.name }
                },
                icon = Icons.Filled.Dns,
                onClick = onOpenServers,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            androidx.compose.material3.Text(
                "Paired browsers (${state.pairedClients.size})",
                style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
                color = theme.text,
            )
        }
        items(state.pairedClients, key = { it.id }) { client ->
            FocusTile(
                title = client.name,
                subtitle = listOfNotNull(client.lastAddress, client.userAgent?.take(60)).joinToString(" · "),
                icon = Icons.Filled.Computer,
                onClick = {},
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
