package tv.anstelle.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import tv.anstelle.core.data.ProfileRepository
import tv.anstelle.core.data.ServerRepository
import tv.anstelle.core.data.Settings
import tv.anstelle.core.data.SettingsRepository
import tv.anstelle.core.data.WebServerController
import tv.anstelle.core.data.WebServerStatus
import tv.anstelle.core.data.playback.TrackChoices
import tv.anstelle.core.database.dao.PairedClientDao
import tv.anstelle.core.database.entity.PairedClientEntity
import tv.anstelle.core.model.MediaServer
import tv.anstelle.core.model.TrackPrefs
import tv.anstelle.core.model.WatchSync
import tv.anstelle.core.model.codeOf
import tv.anstelle.core.theme.LocalAnstelleTheme
import tv.anstelle.core.theme.LocalIsTv
import tv.anstelle.core.theme.components.FocusTile
import tv.anstelle.core.theme.components.PageHeader
import tv.anstelle.core.theme.components.SectionLabel
import tv.anstelle.core.theme.components.TileTrailing
import tv.anstelle.core.theme.components.pagePadding
import tv.anstelle.feature.settings.playback.SyncPlace
import tv.anstelle.feature.settings.playback.languageName
import tv.anstelle.feature.settings.playback.syncDescription
import tv.anstelle.feature.settings.playback.syncName
import tv.anstelle.feature.settings.playback.watchHistorySummary

import tv.anstelle.feature.settings.channels.activeProfileId
import tv.anstelle.feature.settings.playback.summary as playbackSummary
import javax.inject.Inject

@Immutable
data class SettingsUiState(
    val webServerEnabled: Boolean = true,
    val webServer: WebServerStatus = WebServerStatus.Stopped,
    val pairedClients: List<PairedClientEntity> = emptyList(),
    val servers: List<MediaServer> = emptyList(),
    val liveTvSync: WatchSync = codeOf(Settings.LiveTvSync.default),
    val onDemandSync: WatchSync = codeOf(Settings.OnDemandSync.default),
    val tracks: TrackPrefs = TrackPrefs(),
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settings: SettingsRepository,
    private val webServer: WebServerController,
    pairedClients: PairedClientDao,
    servers: ServerRepository,
    profiles: ProfileRepository,
    tracks: TrackChoices,
) : ViewModel() {
    private val sync = combine(settings.observe(Settings.LiveTvSync), settings.observe(Settings.OnDemandSync)) { live, onDemand ->
        codeOf<WatchSync>(live) to codeOf<WatchSync>(onDemand)
    }

    val state: StateFlow<SettingsUiState> = combine(
        settings.observe(Settings.WebServerEnabled),
        webServer.status,
        pairedClients.observeAll(),
        servers.observeServers(),
        sync,
    ) { enabled, status, clients, serverList, (live, onDemand) ->
        SettingsUiState(enabled, status, clients, serverList, live, onDemand)
    }.combine(profiles.activeProfileId().flatMapLatest { tracks.observePrefs(it) }) { state, prefs ->
        state.copy(tracks = prefs)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    fun setWebServerEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settings.set(Settings.WebServerEnabled, enabled)
            if (enabled) webServer.startIfEnabled() else webServer.stop()
        }
    }
}

/** The rows of the settings menu, in order. */
private enum class SettingsItem { SERVERS, CHANNELS, LANGUAGES, HISTORY, WEB }

/**
 * On-device settings, laid out like a TV's own setup menu: each row names a setting and shows
 * its value on the right. Choices step with left and right on the D-pad (OK steps forward,
 * and so does a tap). On TV and tablets a panel beside the menu explains the focused row;
 * phones show the explanation under each row instead.
 */
@Composable
fun SettingsScreen(
    onOpenServers: () -> Unit,
    onOpenChannels: () -> Unit,
    onOpenLanguages: () -> Unit,
    onOpenWatchHistory: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val theme = LocalAnstelleTheme.current
    val isTv = LocalIsTv.current
    val wide = isTv || LocalConfiguration.current.screenWidthDp >= WIDE_DP
    val firstFocus = remember { FocusRequester() }
    var current by remember { mutableStateOf(SettingsItem.SERVERS) }
    if (isTv) LaunchedEffect(Unit) { runCatching { firstFocus.requestFocus() } }

    fun Modifier.tracks(item: SettingsItem) = onFocusChanged { if (it.isFocused) current = item }
    fun about(item: SettingsItem) = if (wide) null else description(item, state)

    Row(modifier.fillMaxSize().background(theme.background)) {
        // The header stays put while the rows scroll under it, so it is never left half clipped.
        Column(Modifier.then(if (wide) Modifier.weight(0.56f) else Modifier.weight(1f)).fillMaxHeight()) {
        PageHeader(
            "Settings",
            modifier = Modifier.padding(start = pagePadding, end = if (wide) 24.dp else pagePadding, top = if (isTv) 32.dp else 16.dp),
        )
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(start = pagePadding, end = if (wide) 24.dp else pagePadding, top = 4.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            item(key = "library") { SectionLabel("Library") }
            item(key = "servers") {
                FocusTile(
                    title = "Media servers",
                    subtitle = about(SettingsItem.SERVERS),
                    icon = Icons.Filled.Dns,
                    value = serversValue(state.servers),
                    trailing = TileTrailing.Chevron,
                    onClick = onOpenServers,
                    modifier = Modifier.fillMaxWidth().focusRequester(firstFocus).tracks(SettingsItem.SERVERS),
                )
            }
            item(key = "channels") {
                FocusTile(
                    title = "Channels",
                    subtitle = about(SettingsItem.CHANNELS),
                    icon = Icons.Filled.LiveTv,
                    trailing = TileTrailing.Chevron,
                    onClick = onOpenChannels,
                    modifier = Modifier.fillMaxWidth().tracks(SettingsItem.CHANNELS),
                )
            }
            item(key = "playback") { SectionLabel("Playback") }
            item(key = "languages") {
                FocusTile(
                    title = "Audio and subtitles",
                    subtitle = about(SettingsItem.LANGUAGES),
                    icon = Icons.Filled.Subtitles,
                    value = state.tracks.audioLanguages.firstOrNull()?.let(::languageName),
                    trailing = TileTrailing.Chevron,
                    onClick = onOpenLanguages,
                    modifier = Modifier.fillMaxWidth().tracks(SettingsItem.LANGUAGES),
                )
            }
            item(key = "history") {
                FocusTile(
                    title = "Watch history",
                    subtitle = about(SettingsItem.HISTORY),
                    icon = Icons.Filled.History,
                    trailing = TileTrailing.Chevron,
                    onClick = onOpenWatchHistory,
                    modifier = Modifier.fillMaxWidth().tracks(SettingsItem.HISTORY),
                )
            }
            item(key = "network") { SectionLabel("Network") }
            item(key = "web") {
                val toggle = { viewModel.setWebServerEnabled(!state.webServerEnabled) }
                FocusTile(
                    title = "Web control",
                    subtitle = about(SettingsItem.WEB),
                    icon = if (state.webServerEnabled) Icons.Filled.Wifi else Icons.Filled.WifiOff,
                    value = if (state.webServerEnabled) "On" else "Off",
                    toggle = state.webServerEnabled,
                    onClick = toggle,
                    onPrevious = toggle,
                    onNext = toggle,
                    modifier = Modifier.fillMaxWidth().tracks(SettingsItem.WEB),
                )
            }
            if (!wide) {
                item(key = "paired") { SectionLabel("Paired browsers") }
                pairedBrowsers(state.pairedClients)
            }
        }
        }
        if (wide) {
            Box(Modifier.width(1.dp).fillMaxHeight().background(theme.line))
            InfoPanel(current, state, Modifier.weight(0.44f).fillMaxHeight())
        }
    }
}

/** What the focused row does, at reading size, with what belongs to it. */
@Composable
private fun InfoPanel(item: SettingsItem, state: SettingsUiState, modifier: Modifier) {
    val theme = LocalAnstelleTheme.current
    val isTv = LocalIsTv.current
    LazyColumn(
        modifier.background(theme.surface),
        contentPadding = PaddingValues(horizontal = if (isTv) 40.dp else 24.dp, vertical = if (isTv) 40.dp else 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(key = "title") {
            androidx.compose.material3.Text(
                title(item),
                style = androidx.compose.material3.MaterialTheme.typography.headlineSmall,
                color = theme.text,
            )
        }
        item(key = "about") {
            androidx.compose.material3.Text(
                if (item == SettingsItem.HISTORY) HISTORY_INTRO else description(item, state),
                style = androidx.compose.material3.MaterialTheme.typography.bodyLarge,
                color = theme.textMuted,
            )
        }
        when (item) {
            SettingsItem.SERVERS -> {
                if (state.servers.isNotEmpty()) item(key = "list") { SectionLabel("Connected") }
                items(state.servers, key = { "server-${it.id}" }) { server ->
                    val trouble = !server.isSignedIn || server.lastSyncError != null
                    Fact(server.name, listOfNotNull(server.userName, if (trouble) "needs attention" else null).joinToString(" · "), trouble)
                }
            }
            SettingsItem.WEB -> {
                val urls = state.webServer.urls.takeIf { state.webServerEnabled && state.webServer.running }.orEmpty()
                if (urls.isNotEmpty()) {
                    item(key = "urls") { SectionLabel("Open in a browser") }
                    items(urls, key = { it }) { url -> Fact(url, null, false) }
                }
                item(key = "paired") { SectionLabel("Paired browsers · ${state.pairedClients.size}") }
                if (state.pairedClients.isEmpty()) {
                    item(key = "none") { Fact("None yet", "A browser pairs with the PIN this screen shows.", false) }
                }
                items(state.pairedClients, key = { "client-${it.id}" }) { client ->
                    Fact(client.name, listOfNotNull(client.lastAddress, client.userAgent?.take(60)).joinToString(" · "), false)
                }
            }
            SettingsItem.HISTORY -> {
                item(key = "in-use") { SectionLabel("In use") }
                item(key = "live") { Fact("Live TV · ${syncName(state.liveTvSync)}", syncDescription(SyncPlace.LIVE_TV, state.liveTvSync), false) }
                item(key = "ondemand") { Fact("On demand · ${syncName(state.onDemandSync)}", syncDescription(SyncPlace.ON_DEMAND, state.onDemandSync), false) }
            }
            else -> Unit
        }
    }
}

/** One line of the info panel: a name and what is known about it, marked when something is wrong. */
@Composable
private fun Fact(name: String, detail: String?, warn: Boolean) {
    val theme = LocalAnstelleTheme.current
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (warn) {
            androidx.compose.material3.Icon(Icons.Filled.ErrorOutline, contentDescription = "Needs attention", tint = theme.accent, modifier = Modifier.size(18.dp))
        }
        Column {
            androidx.compose.material3.Text(name, style = androidx.compose.material3.MaterialTheme.typography.titleMedium, color = theme.text)
            if (!detail.isNullOrEmpty()) {
                androidx.compose.material3.Text(detail, style = androidx.compose.material3.MaterialTheme.typography.bodyMedium, color = theme.textMuted)
            }
        }
    }
}

private fun LazyListScope.pairedBrowsers(clients: List<PairedClientEntity>) {
    if (clients.isEmpty()) {
        item(key = "paired-none") { SettingsNote("No browsers paired yet. Turn on web control and open its address in a browser.") }
    }
    items(clients, key = { it.id }) { client ->
        FocusTile(
            title = client.name,
            subtitle = listOfNotNull(client.lastAddress, client.userAgent?.take(60)).joinToString(" · "),
            icon = Icons.Filled.Computer,
            onClick = {},
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun SettingsNote(text: String) {
    androidx.compose.material3.Text(
        text,
        style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
        color = LocalAnstelleTheme.current.textMuted,
        modifier = Modifier.padding(vertical = 4.dp),
    )
}

private fun title(item: SettingsItem) = when (item) {
    SettingsItem.SERVERS -> "Media servers"
    SettingsItem.CHANNELS -> "Channels"
    SettingsItem.LANGUAGES -> "Audio and subtitles"
    SettingsItem.HISTORY -> "Watch history"
    SettingsItem.WEB -> "Web control"
}

private fun description(item: SettingsItem, state: SettingsUiState): String = when (item) {
    SettingsItem.SERVERS -> when {
        state.servers.isEmpty() -> "Connect a Jellyfin server to start building channels."
        else -> "AnstelleTV builds its channels from the Jellyfin libraries you connect here."
    }
    SettingsItem.CHANNELS -> "Lineups, numbers, groups, logos and favorites. Channels are made from your library; any of them can be edited."
    SettingsItem.LANGUAGES -> playbackSummary(state.tracks)
    SettingsItem.HISTORY -> watchHistorySummary(state.liveTvSync, state.onDemandSync)
    SettingsItem.WEB -> {
        val server = state.webServer
        when {
            !state.webServerEnabled -> "Off. Turn it on to let browsers on your network manage AnstelleTV."
            server.running && server.urls.isNotEmpty() -> "Browsers on your network can manage AnstelleTV and watch channels. Open ${server.urls.first()}."
            server.error != null -> "Not running: ${server.error}"
            else -> "Starting…"
        }
    }
}

private fun serversValue(servers: List<MediaServer>): String = when {
    servers.isEmpty() -> "None"
    servers.any { !it.isSignedIn || it.lastSyncError != null } -> "Needs attention"
    servers.size == 1 -> servers.first().name
    else -> "${servers.size} connected"
}

private const val HISTORY_INTRO = "What Live TV and On demand save to your server."

private const val WIDE_DP = 600
