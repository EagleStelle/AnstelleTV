package tv.anstelle.feature.settings

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
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
import kotlinx.coroutines.launch
import tv.anstelle.core.data.ProfileRepository
import tv.anstelle.core.data.Settings
import tv.anstelle.core.data.SettingsRepository
import tv.anstelle.core.data.WebServerController
import tv.anstelle.core.data.WebServerStatus
import tv.anstelle.core.database.dao.PairedClientDao
import tv.anstelle.core.database.entity.PairedClientEntity
import tv.anstelle.core.theme.components.InfoRow
import tv.anstelle.core.theme.components.RemoveKey
import tv.anstelle.core.theme.components.SettingsPage
import tv.anstelle.core.theme.components.SwitchRow
import tv.anstelle.core.theme.components.rememberInitialFocus
import tv.anstelle.core.theme.components.section
import javax.inject.Inject

@Immutable
data class WebControlUiState(
    val enabled: Boolean = true,
    val status: WebServerStatus = WebServerStatus.Stopped,
    val pairedClients: List<PairedClientEntity> = emptyList(),
    /** Who paired each browser, by profile id. */
    val profileNames: Map<Long, String> = emptyMap(),
    val loaded: Boolean = false,
)

@HiltViewModel
class WebControlViewModel @Inject constructor(
    private val settings: SettingsRepository,
    private val webServer: WebServerController,
    pairedClients: PairedClientDao,
    profiles: ProfileRepository,
) : ViewModel() {
    val state: StateFlow<WebControlUiState> = combine(
        settings.observe(Settings.WebServerEnabled),
        webServer.status,
        pairedClients.observeAll(),
        profiles.observeProfiles(),
    ) { enabled, status, clients, profileList ->
        WebControlUiState(enabled, status, clients, profileList.associate { it.id to it.name }, loaded = true)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WebControlUiState())

    /** Unpairs the browser at once; it has to pair again to come back. */
    fun unpair(clientId: Long) {
        viewModelScope.launch { webServer.unpair(clientId) }
    }

    fun setEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settings.set(Settings.WebServerEnabled, enabled)
            if (enabled) webServer.startIfEnabled() else webServer.stop()
        }
    }
}

/** The web UI this device serves on the network: on or off, where to open it, and who paired. */
@Composable
fun WebControlScreen(
    modifier: Modifier = Modifier,
    viewModel: WebControlViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val firstFocus = rememberInitialFocus(ready = state.loaded)
    val status = state.status
    SettingsPage(
        title = "Web control",
        trail = listOf("Settings"),
        about = "Open an address below in a browser on your network and enter the PIN shown here.",
        modifier = modifier,
    ) {
        item(key = "switch") {
            SwitchRow(
                title = "Web control",
                checked = state.enabled,
                onCheckedChange = viewModel::setEnabled,
                summary = when {
                    !state.enabled -> "Off"
                    status.running -> "On"
                    status.error != null -> "Not running: ${status.error}"
                    else -> "Starting…"
                },
                icon = Icons.Filled.Wifi,
                modifier = Modifier.focusRequester(firstFocus),
            )
        }
        val urls = status.urls.takeIf { state.enabled && status.running }.orEmpty()
        if (urls.isNotEmpty()) {
            section("Address")
            items(urls, key = { "url-$it" }) { url -> InfoRow(url, icon = Icons.Filled.Link) }
        }
        section("Paired browsers")
        if (state.pairedClients.isEmpty()) {
            item(key = "none") { InfoRow("None yet", icon = Icons.Filled.Computer) }
        }
        items(state.pairedClients, key = { "client-${it.id}" }) { client ->
            // The browser is a fact; its red bin unpairs it.
            Row(verticalAlignment = Alignment.CenterVertically) {
                InfoRow(
                    title = client.name,
                    summary = listOfNotNull(state.profileNames[client.profileId], client.lastAddress, client.userAgent?.take(60)).joinToString("  "),
                    icon = Icons.Filled.Computer,
                    modifier = Modifier.weight(1f),
                )
                RemoveKey("Unpair ${client.name}", onClick = { viewModel.unpair(client.id) })
            }
        }
    }
}
