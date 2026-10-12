package tv.anstelle.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.ClosedCaptionDisabled
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.InsertChart
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.SettingsBackupRestore
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
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
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import tv.anstelle.core.common.TimeSource
import tv.anstelle.core.data.ProfileRepository
import tv.anstelle.core.data.ServerRepository
import tv.anstelle.core.data.Settings
import tv.anstelle.core.data.SettingsRepository
import tv.anstelle.core.data.ThemeRepository
import tv.anstelle.core.data.WebServerController
import tv.anstelle.core.data.WebServerStatus
import tv.anstelle.core.data.backup.BackupRepository
import tv.anstelle.core.data.backup.BackupSummary
import tv.anstelle.core.data.channels.ChannelRepository
import tv.anstelle.core.data.channels.ChannelSummary
import tv.anstelle.core.data.playback.TrackChoices
import tv.anstelle.core.data.stats.DashboardHistory
import tv.anstelle.core.database.dao.PairedClientDao
import tv.anstelle.core.database.dao.TypeCount
import tv.anstelle.core.database.entity.PairedClientEntity
import tv.anstelle.core.model.MediaServer
import tv.anstelle.core.model.MediaType
import tv.anstelle.core.model.SubtitleMode
import tv.anstelle.core.model.ThemeSpec
import tv.anstelle.core.model.TrackPrefs
import tv.anstelle.core.model.WatchSync
import tv.anstelle.core.model.codeOf
import tv.anstelle.core.theme.DefaultTheme
import tv.anstelle.core.theme.LocalAnstelleTheme
import tv.anstelle.core.theme.components.LinkRow
import tv.anstelle.core.theme.components.SettingsPage
import tv.anstelle.core.theme.components.StatusLine
import tv.anstelle.core.theme.components.rememberInitialFocus
import tv.anstelle.core.theme.components.settingsGuided
import tv.anstelle.feature.settings.appearance.ThemePreview
import tv.anstelle.feature.settings.playback.SyncPlace
import tv.anstelle.feature.settings.playback.languageName
import tv.anstelle.feature.settings.playback.modeName
import tv.anstelle.feature.settings.playback.syncName
import tv.anstelle.feature.settings.servers.syncedAgo
import javax.inject.Inject

/** What the channel list holds, for the home's readout. */
@Immutable
data class ChannelStats(
    val total: Int = 0,
    val custom: Int = 0,
    val visible: Int = 0,
    val favorites: Int = 0,
    val hidden: Int = 0,
    val groups: Int = 0,
) {
    val library: Int get() = total - custom

    companion object {
        fun of(channels: List<ChannelSummary>) = ChannelStats(
            total = channels.size,
            custom = channels.count { !it.channel.fromLibrary },
            visible = channels.count { !it.channel.isHidden },
            favorites = channels.count { it.channel.isFavorite },
            hidden = channels.count { it.channel.isHidden },
            groups = channels.mapTo(HashSet()) { it.channel.groupId }.size,
        )
    }
}

@Immutable
data class SettingsUiState(
    val webServerEnabled: Boolean = true,
    val webServer: WebServerStatus = WebServerStatus.Stopped,
    val pairedClients: List<PairedClientEntity> = emptyList(),
    val servers: List<MediaServer> = emptyList(),
    /** What each server's library holds, by kind. */
    val serverCounts: Map<Long, List<TypeCount>> = emptyMap(),
    /** The sync running on a server, as its progress line. */
    val syncProgress: Map<Long, String> = emptyMap(),
    val nowUtcMs: Long = 0,
    val channels: ChannelStats = ChannelStats(),
    val liveTvSync: WatchSync = codeOf(Settings.liveTvSync(0).default),
    val onDemandSync: WatchSync = codeOf(Settings.onDemandSync(0).default),
    /** Whether the profile keeps its dashboard. */
    val dashboard: Boolean = Settings.dashboard(0).default,
    val tracks: TrackPrefs = TrackPrefs(),
    /** The theme the profile wears; null while the default is on. */
    val theme: ThemeSpec? = null,
    /** The owner and admins see the app-wide rows (servers, channels, web control, backup); members don't. */
    val canManageApp: Boolean = false,
    /** What a backup of this device holds. */
    val backup: BackupSummary = BackupSummary(),
    val loaded: Boolean = false,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class SettingsViewModel @Inject constructor(
    settings: SettingsRepository,
    webServer: WebServerController,
    pairedClients: PairedClientDao,
    servers: ServerRepository,
    channels: ChannelRepository,
    profiles: ProfileRepository,
    tracks: TrackChoices,
    themes: ThemeRepository,
    history: DashboardHistory,
    backups: BackupRepository,
    time: TimeSource,
) : ViewModel() {
    /** Live TV's and On Demand's watch history settings, and the dashboard, for the profile watching. */
    private val watching = profiles.activeProfileId().flatMapLatest { profileId ->
        combine(
            settings.observe(Settings.liveTvSync(profileId)),
            settings.observe(Settings.onDemandSync(profileId)),
            history.observeEnabled(profileId),
        ) { live, onDemand, dashboard -> Triple(codeOf<WatchSync>(live), codeOf<WatchSync>(onDemand), dashboard) }
    }

    private val serverCounts = servers.observeServers().flatMapLatest { list ->
        if (list.isEmpty()) {
            flowOf(emptyMap())
        } else {
            combine(list.map { server -> servers.observeItemCounts(server.id).map { server.id to it } }) { it.toMap() }
        }
    }

    val state: StateFlow<SettingsUiState> = combine(
        settings.observe(Settings.WebServerEnabled),
        webServer.status,
        pairedClients.observeAll(),
        servers.observeServers(),
        watching,
    ) { enabled, status, clients, serverList, (live, onDemand, dashboard) ->
        SettingsUiState(
            webServerEnabled = enabled,
            webServer = status,
            pairedClients = clients,
            servers = serverList,
            liveTvSync = live,
            onDemandSync = onDemand,
            dashboard = dashboard,
        )
    }.combine(channels.observeChannels()) { state, list ->
        state.copy(channels = ChannelStats.of(list))
    }.combine(serverCounts) { state, counts ->
        state.copy(serverCounts = counts)
    }.combine(servers.syncProgress) { state, progress ->
        state.copy(syncProgress = progress.mapValues { it.value.message }, nowUtcMs = time.nowUtcMs())
    }.combine(profiles.activeProfileId().flatMapLatest { tracks.observePrefs(it) }) { state, prefs ->
        state.copy(tracks = prefs)
    }.combine(themes.observeActiveTheme()) { state, theme ->
        state.copy(theme = theme)
    }.combine(backups.observeSummary()) { state, backup ->
        state.copy(backup = backup)
    }.combine(profiles.observeCanManageApp()) { state, manages ->
        state.copy(canManageApp = manages, loaded = true)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())
}

/** The areas of settings, in the order the home lists them. */
private enum class SettingsArea { SERVERS, CHANNELS, PLAYBACK, HISTORY, THEME, WEB, BACKUP }

/**
 * Settings' home: one row per area with its state under it, no headings, the way Android's and
 * Google TV's own settings open. On TV the guidance beside the list tells about the focused area.
 * Servers, channels and web control are app-wide: only the owner and admins see them.
 */
@Composable
fun SettingsScreen(
    onOpenServers: () -> Unit,
    onOpenChannels: () -> Unit,
    onOpenPlayback: () -> Unit,
    onOpenWatchHistory: () -> Unit,
    onOpenThemes: () -> Unit,
    onOpenWebControl: () -> Unit,
    onOpenBackup: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val theme = LocalAnstelleTheme.current
    if (!state.loaded) {
        Box(modifier.fillMaxSize().background(theme.background))
        return
    }
    val manages = state.canManageApp
    // Coming back from an area's page, focus returns to its row.
    val firstFocus = rememberInitialFocus()
    var current by remember { mutableStateOf(if (manages) SettingsArea.SERVERS else SettingsArea.PLAYBACK) }
    fun Modifier.tracks(area: SettingsArea) = onFocusChanged { if (it.isFocused) current = area }

    SettingsPage(
        title = "Settings",
        modifier = modifier,
        aside = if (settingsGuided) ({ AreaAside(current, state) }) else null,
    ) {
        if (manages) {
            item(key = "servers") {
                LinkRow(
                    title = "Servers",
                    summary = serversSummary(state.servers),
                    icon = Icons.Filled.Dns,
                    onClick = onOpenServers,
                    modifier = Modifier.focusRequester(firstFocus).tracks(SettingsArea.SERVERS),
                )
            }
            item(key = "channels") {
                LinkRow(
                    title = "Channels",
                    summary = when (state.channels.total) {
                        0 -> "None yet"
                        1 -> "1 channel"
                        else -> "%,d channels".format(state.channels.total)
                    },
                    icon = Icons.Filled.LiveTv,
                    onClick = onOpenChannels,
                    modifier = Modifier.tracks(SettingsArea.CHANNELS),
                )
            }
        }
        item(key = "playback") {
            LinkRow(
                title = "Playback",
                summary = state.tracks.audioLanguages.firstOrNull()?.let(::languageName) ?: "Default",
                icon = Icons.Filled.Subtitles,
                onClick = onOpenPlayback,
                modifier = Modifier.let { if (manages) it else it.focusRequester(firstFocus) }.tracks(SettingsArea.PLAYBACK),
            )
        }
        item(key = "history") {
            LinkRow(
                title = "Watch history",
                summary = if (state.liveTvSync == WatchSync.OFF && state.onDemandSync == WatchSync.OFF) "Off" else "On",
                icon = Icons.Filled.History,
                onClick = onOpenWatchHistory,
                modifier = Modifier.tracks(SettingsArea.HISTORY),
            )
        }
        item(key = "theme") {
            LinkRow(
                title = "Themes",
                summary = state.theme?.name ?: DefaultTheme.spec.name,
                icon = Icons.Filled.Palette,
                onClick = onOpenThemes,
                modifier = Modifier.tracks(SettingsArea.THEME),
            )
        }
        if (manages) {
            item(key = "web") {
                LinkRow(
                    title = "Web control",
                    summary = webSummary(state),
                    icon = if (state.webServerEnabled) Icons.Filled.Wifi else Icons.Filled.WifiOff,
                    onClick = onOpenWebControl,
                    modifier = Modifier.tracks(SettingsArea.WEB),
                )
            }
            item(key = "backup") {
                LinkRow(
                    title = "Backup",
                    summary = backupSummary(state.backup, state.nowUtcMs),
                    icon = Icons.Filled.SettingsBackupRestore,
                    onClick = onOpenBackup,
                    modifier = Modifier.tracks(SettingsArea.BACKUP),
                )
            }
        }
    }
}

/**
 * On TV, under the title: the focused area at a glance. Themes shows the theme it wears; the
 * other areas show a readout of what they hold, the way a set's information panel reads.
 */
@Composable
private fun AreaAside(area: SettingsArea, state: SettingsUiState) {
    val theme = LocalAnstelleTheme.current
    val typography = androidx.compose.material3.MaterialTheme.typography
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        androidx.compose.material3.Text(title(area), style = typography.headlineSmall, color = theme.text)
        when (area) {
            SettingsArea.SERVERS -> ServersReadout(state)
            SettingsArea.CHANNELS -> ChannelsReadout(state.channels)
            SettingsArea.PLAYBACK -> PlaybackReadout(state.tracks)
            SettingsArea.HISTORY -> HistoryReadout(state.liveTvSync, state.onDemandSync, state.dashboard)
            SettingsArea.THEME -> ThemePreview(state.theme ?: DefaultTheme.spec, Modifier.fillMaxWidth())
            SettingsArea.WEB -> WebReadout(state)
            SettingsArea.BACKUP -> BackupReadout(state.backup, state.nowUtcMs)
        }
    }
}

/**
 * All the servers as one readout, the same size however many there are: how their syncing
 * stands, what their libraries hold together, and how many servers there are and how many need
 * attention. Each server's own readout is on its page.
 */
@Composable
private fun ServersReadout(state: SettingsUiState) {
    val servers = state.servers
    if (servers.isEmpty()) {
        ReadoutFrame { ReadoutFact(Icons.Filled.Dns, "No server yet", quiet = true) }
        return
    }
    val trouble = servers.count { !it.isSignedIn || it.lastSyncError != null }
    val totals = state.serverCounts.values.flatten().groupBy({ it.type }, { it.count }).mapValues { it.value.sum() }
    ReadoutFrame {
        when {
            state.syncProgress.isNotEmpty() ->
                StatusLine(onAir = false, label = "Syncing", text = if (servers.size > 1) "${state.syncProgress.size} of ${servers.size}" else "")
            trouble > 0 -> Attention(if (servers.size > 1) "$trouble of ${servers.size}" else "")
            else -> {
                // The newest sync speaks for all of them.
                val latest = servers.filter { it.lastSyncUtcMs != null }.maxByOrNull { it.lastSyncUtcMs!! }
                val ago = latest?.syncedAgo(state.nowUtcMs)
                StatusLine(onAir = ago != null, label = if (ago != null) "Synced" else "Not synced yet", text = ago.orEmpty())
            }
        }
        ReadoutFigures {
            ReadoutFigure(totals[MediaType.MOVIE] ?: 0, if (totals[MediaType.MOVIE] == 1) "movie" else "movies")
            ReadoutFigure(totals[MediaType.SERIES] ?: 0, "series")
            ReadoutFigure(totals[MediaType.EPISODE] ?: 0, if (totals[MediaType.EPISODE] == 1) "episode" else "episodes")
        }
        ReadoutRule()
        ReadoutFacts {
            ReadoutFact(Icons.Filled.Dns, "${servers.size}", if (servers.size == 1) "server" else "servers")
            if (trouble > 0) ReadoutFact(Icons.Filled.ErrorOutline, "$trouble", if (trouble == 1) "needs attention" else "need attention")
        }
    }
}

/** A problem as a status line: the warning mark and the field label in the accent, so it reads without color too. */
@Composable
private fun Attention(text: String) {
    val theme = LocalAnstelleTheme.current
    val typography = androidx.compose.material3.MaterialTheme.typography
    androidx.compose.foundation.layout.Row(
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        androidx.compose.material3.Icon(
            Icons.Filled.ErrorOutline,
            contentDescription = null,
            tint = theme.accent,
            modifier = Modifier.size(16.dp),
        )
        androidx.compose.material3.Text("NEEDS ATTENTION", style = typography.labelMedium, color = theme.accent, maxLines = 1)
        if (text.isNotEmpty()) androidx.compose.material3.Text(text.uppercase(), style = typography.labelMedium, color = theme.textMuted, maxLines = 1)
    }
}

/** How many channels air, who made them, and how they're kept. */
@Composable
private fun ChannelsReadout(stats: ChannelStats) {
    ReadoutFrame {
        StatusLine(onAir = stats.visible > 0, label = "On air", text = "${stats.visible} of ${stats.total}")
        ReadoutFigures {
            ReadoutFigure(stats.total, if (stats.total == 1) "channel" else "channels", lead = true)
            ReadoutFigure(stats.custom, "custom")
            ReadoutFigure(stats.library, "from your library")
        }
        ReadoutRule()
        ReadoutFacts {
            ReadoutFact(Icons.Filled.Star, "${stats.favorites}", if (stats.favorites == 1) "favorite" else "favorites")
            ReadoutFact(Icons.Filled.Folder, "${stats.groups}", if (stats.groups == 1) "group" else "groups")
            ReadoutFact(Icons.Filled.VisibilityOff, "${stats.hidden}", "hidden")
        }
    }
}

/** The audio languages in order, then the subtitle mode and its languages. */
@Composable
private fun PlaybackReadout(prefs: TrackPrefs) {
    ReadoutFrame {
        ReadoutHeading(Icons.Filled.Audiotrack, "Audio", prefs.audioLanguages.size.takeIf { it > 1 }?.let { "$it in order" })
        if (prefs.audioLanguages.isEmpty()) {
            ReadoutFact(Icons.Filled.Audiotrack, "Each program's default track", quiet = true)
        } else {
            Languages(prefs.audioLanguages)
        }
        ReadoutRule()
        ReadoutHeading(Icons.Filled.ClosedCaption, "Subtitles", modeName(prefs.subtitleMode))
        when {
            prefs.subtitleMode == SubtitleMode.OFF -> ReadoutFact(Icons.Filled.ClosedCaptionDisabled, "Turned on while watching", quiet = true)
            prefs.subtitleLanguages.isEmpty() -> ReadoutFact(Icons.Filled.ErrorOutline, "No language chosen")
            else -> Languages(prefs.subtitleLanguages)
        }
    }
}

/** A language list in order: the first four, then how many more. */
@Composable
private fun Languages(codes: List<String>) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        codes.take(SHOWN_LANGUAGES).forEachIndexed { i, code -> ReadoutRank(i + 1, languageName(code)) }
        if (codes.size > SHOWN_LANGUAGES) ReadoutFact(Icons.Filled.MoreHoriz, "${codes.size - SHOWN_LANGUAGES} more", quiet = true)
    }
}

/** What Live TV and On Demand send to Jellyfin, each as checks, then whether the dashboard keeps anything. */
@Composable
private fun HistoryReadout(liveTv: WatchSync, onDemand: WatchSync, dashboard: Boolean) {
    ReadoutFrame {
        for ((place, sync) in listOf(SyncPlace.LIVE_TV to liveTv, SyncPlace.ON_DEMAND to onDemand)) {
            ReadoutHeading(place.icon, place.title, syncName(sync))
            ReadoutFacts {
                ReadoutCheck(sync == WatchSync.STATUS || sync == WatchSync.BOTH, "Status")
                ReadoutCheck(sync == WatchSync.PROGRESS || sync == WatchSync.BOTH, "Progress")
            }
            ReadoutRule()
        }
        ReadoutHeading(Icons.Filled.InsertChart, "Dashboard", if (dashboard) "On" else "Off")
        ReadoutCheck(dashboard, "Shows what you watch most")
    }
}

/** Whether the web UI runs, where to open it, and the browsers paired with it. */
@Composable
private fun WebReadout(state: SettingsUiState) {
    val theme = LocalAnstelleTheme.current
    val status = state.webServer
    val running = state.webServerEnabled && status.running
    ReadoutFrame {
        StatusLine(
            onAir = running,
            label = when {
                !state.webServerEnabled -> "Off"
                running -> "On"
                status.error != null -> "Not running"
                else -> "Starting"
            },
            text = "",
        )
        status.urls.firstOrNull()?.takeIf { running }?.let { url ->
            androidx.compose.material3.Text(
                url.removePrefix("http://"),
                style = androidx.compose.material3.MaterialTheme.typography.headlineSmall,
                color = theme.text,
                maxLines = 1,
            )
        }
        ReadoutFigures { ReadoutFigure(state.pairedClients.size, if (state.pairedClients.size == 1) "paired browser" else "paired browsers") }
        if (state.pairedClients.isNotEmpty()) {
            ReadoutRule()
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (client in state.pairedClients.take(SHOWN_BROWSERS)) {
                    ReadoutFact(Icons.Filled.Computer, client.name, client.lastAddress)
                }
            }
        }
    }
}

private fun title(area: SettingsArea) = when (area) {
    SettingsArea.SERVERS -> "Servers"
    SettingsArea.CHANNELS -> "Channels"
    SettingsArea.PLAYBACK -> "Playback"
    SettingsArea.HISTORY -> "Watch history"
    SettingsArea.THEME -> "Themes"
    SettingsArea.WEB -> "Web control"
    SettingsArea.BACKUP -> "Backup"
}

private const val SHOWN_LANGUAGES = 4
private const val SHOWN_BROWSERS = 4

private fun serversSummary(servers: List<MediaServer>): String = when {
    servers.isEmpty() -> "None connected"
    servers.any { !it.isSignedIn || it.lastSyncError != null } -> "Needs attention"
    servers.size == 1 -> servers.first().name
    else -> "${servers.size} servers"
}

private fun webSummary(state: SettingsUiState): String {
    val server = state.webServer
    return when {
        !state.webServerEnabled -> "Off"
        server.running && server.urls.isNotEmpty() -> "On at ${server.urls.first()}"
        server.error != null -> "Not running"
        else -> "Starting"
    }
}
