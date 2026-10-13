package tv.anstelle.feature.settings.servers

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import tv.anstelle.core.common.TimeSource
import tv.anstelle.core.data.ServerRepository
import tv.anstelle.core.database.dao.TypeCount
import tv.anstelle.core.database.entity.LibraryEntity
import tv.anstelle.core.model.MediaServer
import tv.anstelle.core.model.MediaType
import tv.anstelle.core.theme.LocalAnstelleTheme
import tv.anstelle.core.theme.LocalIsTv
import tv.anstelle.core.theme.PanelShape
import tv.anstelle.core.theme.components.DangerButton
import tv.anstelle.core.theme.components.InfoRow
import tv.anstelle.core.theme.components.LinkRow
import tv.anstelle.core.theme.components.OsdButton
import tv.anstelle.core.theme.components.SettingsButtons
import tv.anstelle.core.theme.components.SettingsPage
import tv.anstelle.core.theme.components.StatusLine
import tv.anstelle.core.theme.components.SwitchRow
import tv.anstelle.core.theme.components.foot
import tv.anstelle.core.theme.components.rememberInitialFocus
import tv.anstelle.core.theme.components.section
import javax.inject.Inject

@Immutable
data class ServerDetailUiState(
    val server: MediaServer? = null,
    val libraries: List<LibraryEntity> = emptyList(),
    val counts: List<TypeCount> = emptyList(),
    val progress: String? = null,
    val nowUtcMs: Long = 0,
)

@HiltViewModel
class ServerDetailViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val repository: ServerRepository,
    time: TimeSource,
) : ViewModel() {
    /** Matches the `serverId` argument of the navigation route. */
    val serverId: Long = checkNotNull(savedState.get<Long>("serverId"))

    val state: StateFlow<ServerDetailUiState> = combine(
        repository.observeServer(serverId),
        repository.observeLibraries(serverId),
        repository.observeItemCounts(serverId),
        repository.syncProgress,
    ) { server, libraries, counts, progress ->
        ServerDetailUiState(server, libraries, counts, progress[serverId]?.message, time.nowUtcMs())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ServerDetailUiState())

    fun canAir(library: LibraryEntity) = repository.canAir(library.collectionType)

    fun syncNow(full: Boolean) = repository.syncNow(serverId, fullRefresh = full, forceReconcile = true)

    fun toggle(library: LibraryEntity) {
        viewModelScope.launch { repository.setLibraryEnabled(library.id, !library.isEnabled) }
    }

    fun remove(onRemoved: () -> Unit) {
        viewModelScope.launch {
            repository.remove(serverId)
            onRemoved()
        }
    }
}

/**
 * One server: its readout (sync state, what the library holds, the connection), the sync
 * buttons, a switch per library, the Jellyfin user it syncs as, and removing it at the foot.
 */
@Composable
fun ServerDetailScreen(
    onSignIn: (Long) -> Unit,
    onRemoved: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ServerDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val server = state.server ?: return
    val firstFocus = rememberInitialFocus()

    SettingsPage(
        title = server.name,
        trail = listOf("Settings", "Servers"),
        about = "Full sync reads every item again.",
        aside = { ServerReadout(server, state.progress, state.counts, state.nowUtcMs) },
        modifier = modifier,
    ) {
        item(key = "sync") {
            SettingsButtons {
                if (server.isSignedIn) {
                    OsdButton("Sync now", onClick = { viewModel.syncNow(full = false) }, icon = Icons.Filled.Sync, primary = true, modifier = Modifier.focusRequester(firstFocus))
                    OsdButton("Full sync", onClick = { viewModel.syncNow(full = true) }, icon = Icons.Filled.Refresh)
                } else {
                    OsdButton("Sign in", onClick = { onSignIn(server.id) }, icon = Icons.AutoMirrored.Filled.Login, primary = true, modifier = Modifier.focusRequester(firstFocus))
                }
            }
        }
        section("Libraries")
        items(state.libraries, key = { it.id }) { library ->
            if (viewModel.canAir(library)) {
                SwitchRow(
                    title = library.name,
                    checked = library.isEnabled,
                    onCheckedChange = { viewModel.toggle(library) },
                    summary = collectionLabel(library.collectionType),
                    icon = Icons.Filled.VideoLibrary,
                )
            } else {
                InfoRow(library.name, summary = "${collectionLabel(library.collectionType)}, not supported yet", icon = Icons.Filled.VideocamOff)
            }
        }
        section("Account")
        item(key = "user") {
            LinkRow(
                title = "Jellyfin user",
                summary = server.userName ?: "Not signed in",
                icon = Icons.Filled.Person,
                onClick = { onSignIn(server.id) },
            )
        }
        foot {
            DangerButton(
                text = "Remove server",
                question = "Remove ${server.name}?",
                explanation = "Signs out and deletes everything synced from it.",
                confirm = "Remove",
                onConfirm = { viewModel.remove(onRemoved) },
            )
        }
    }
}

/**
 * What the server is and how its sync stands, read like a set's system-information panel. It
 * is a hairline frame with no fill, the way the OSD draws facts (badges, empty slots), so it
 * never reads as something to press: everything that acts sits in the list beside or under it.
 * The sync state leads, set like the banner's NOW line; then what the library holds in tabular
 * figures; then the connection's facts, each behind the icon that names it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ServerReadout(server: MediaServer, progress: String?, counts: List<TypeCount>, nowUtcMs: Long) {
    val theme = LocalAnstelleTheme.current
    val isTv = LocalIsTv.current
    val typography = androidx.compose.material3.MaterialTheme.typography
    Column(
        Modifier
            .fillMaxWidth()
            .padding(bottom = if (isTv) 10.dp else 8.dp)
            .border(1.dp, theme.line, PanelShape)
            .padding(if (isTv) 24.dp else 18.dp),
        verticalArrangement = Arrangement.spacedBy(if (isTv) 20.dp else 16.dp),
    ) {
        SyncState(server, progress, nowUtcMs)

        val held = counts.filter { it.count > 0 }.associate { it.type to it.count }
        if (held.isEmpty()) {
            androidx.compose.material3.Text("No items yet", style = typography.bodyMedium, color = theme.textMuted)
        } else {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(if (isTv) 48.dp else 32.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // Movies, then series joined to their episodes, then anything else.
                held[MediaType.MOVIE]?.let { CountReadout(it, MediaType.MOVIE) }
                val series = held[MediaType.SERIES]
                val episodes = held[MediaType.EPISODE]
                when {
                    series != null && episodes != null -> SeriesReadout(series, episodes)
                    series != null -> CountReadout(series, MediaType.SERIES)
                    episodes != null -> CountReadout(episodes, MediaType.EPISODE)
                }
                for ((type, count) in held) {
                    if (type !in TopLevelKinds) CountReadout(count, type)
                }
            }
        }

        Box(Modifier.fillMaxWidth().height(1.dp).background(theme.line))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(if (isTv) 32.dp else 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            server.serverVersion?.let { ServerFact(Icons.Filled.Dns, "Server version", "Jellyfin $it") }
            server.userName?.let { ServerFact(Icons.Filled.Person, "Signed in as", it) }
            ServerFact(Icons.Filled.Public, "Address", server.baseUrl)
        }
    }
}

/** The kinds the readout places itself, in its own order; any other kind follows them. */
private val TopLevelKinds = setOf(MediaType.MOVIE, MediaType.SERIES, MediaType.EPISODE)

/** One count of what the library holds: the number in tabular figures over its kind. */
@Composable
private fun CountReadout(count: Int, type: MediaType, modifier: Modifier = Modifier, numberStyle: TextStyle = countStyle()) {
    val theme = LocalAnstelleTheme.current
    Column(modifier.semantics(mergeDescendants = true) {}) {
        androidx.compose.material3.Text("%,d".format(count), style = numberStyle, color = theme.text, maxLines = 1)
        androidx.compose.material3.Text(
            type.label(count).uppercase(),
            style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
            color = theme.textMuted,
            maxLines = 1,
        )
    }
}

/** A count's number: the readout's figure size. */
@Composable
private fun countStyle(): TextStyle {
    val typography = androidx.compose.material3.MaterialTheme.typography
    return if (LocalIsTv.current) typography.headlineMedium else typography.headlineSmall
}

/**
 * Series and their episodes as one figure: the series count, then the episodes a size smaller,
 * joined by a rose crossbar that runs from one number into the other the way the mark's
 * crossbar runs on past the A. Episodes belong to series, so they never stand beside movies as
 * a count of their own. Labels share a baseline at the foot; the bar sits at the middle of the
 * episodes' digits, which lie inside the series' digits too.
 */
@Composable
private fun SeriesReadout(series: Int, episodes: Int) {
    val theme = LocalAnstelleTheme.current
    val isTv = LocalIsTv.current
    val typography = androidx.compose.material3.MaterialTheme.typography
    val episodeStyle = if (isTv) {
        typography.headlineSmall
    } else {
        typography.titleLarge.merge(TextStyle(fontFamily = theme.displayFont, fontFeatureSettings = "tnum"))
    }
    val density = LocalDensity.current
    val barLine = with(density) { episodeStyle.lineHeight.toDp() }
    val labelLine = with(density) { typography.labelSmall.lineHeight.toDp() }
    Row(verticalAlignment = Alignment.Bottom) {
        CountReadout(series, MediaType.SERIES)
        Column(Modifier.width(if (isTv) 40.dp else 28.dp).padding(horizontal = if (isTv) 8.dp else 6.dp)) {
            Box(Modifier.fillMaxWidth().height(barLine), contentAlignment = Alignment.Center) {
                Box(Modifier.fillMaxWidth().height(2.dp).background(theme.accent))
            }
            Spacer(Modifier.height(labelLine))
        }
        CountReadout(episodes, MediaType.EPISODE, numberStyle = episodeStyle)
    }
}

/**
 * The sync state as a status line: a solid square once synced, a hollow one before the first
 * sync and while one runs (with the ladder scanning under it), a warning mark when it failed or
 * the sign-in is gone, so each reads by its form as well as its color.
 */
@Composable
private fun SyncState(server: MediaServer, progress: String?, nowUtcMs: Long) {
    val theme = LocalAnstelleTheme.current
    val typography = androidx.compose.material3.MaterialTheme.typography
    val problem = when {
        progress != null -> null
        !server.isSignedIn -> "Signed out" to null
        server.lastSyncError != null -> "Sync failed" to server.lastSyncError
        else -> null
    }
    if (problem != null) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                androidx.compose.material3.Icon(Icons.Filled.ErrorOutline, contentDescription = "Problem", tint = theme.accent, modifier = Modifier.size(16.dp))
                androidx.compose.material3.Text(problem.first.uppercase(), style = typography.labelMedium, color = theme.accent, maxLines = 1)
            }
            problem.second?.let { androidx.compose.material3.Text(it, style = typography.bodyMedium, color = theme.text) }
        }
        return
    }
    if (progress != null) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            StatusLine(onAir = false, label = "Syncing", text = progress)
            ScanLadder()
        }
        return
    }
    val ago = server.syncedAgo(nowUtcMs)
    StatusLine(onAir = ago != null, label = if (ago != null) "Synced" else "Not synced yet", text = ago.orEmpty())
}

/** A sync running: a lit run of segments stepping along the ladder, the way a set scans for channels. */
@Composable
private fun ScanLadder(modifier: Modifier = Modifier, segments: Int = 24, run: Int = 4) {
    val theme = LocalAnstelleTheme.current
    val sweep by rememberInfiniteTransition(label = "scan").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 1_800, easing = LinearEasing)),
        label = "sweep",
    )
    Canvas(modifier.fillMaxWidth().height(6.dp)) {
        val gap = 2.dp.toPx()
        val width = (size.width - gap * (segments - 1)) / segments
        // Whole segments only, so the run snaps from one to the next instead of gliding.
        val head = (sweep * (segments + run)).toInt()
        for (i in 0 until segments) {
            drawRect(
                if (i in head - run until head) theme.accent else theme.line,
                topLeft = Offset(i * (width + gap), 0f),
                size = Size(width, size.height),
            )
        }
    }
}

/** One fact about the connection: the icon names it, so the value stands without a label. */
@Composable
private fun ServerFact(icon: ImageVector, name: String, value: String) {
    val theme = LocalAnstelleTheme.current
    Row(
        Modifier.semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        androidx.compose.material3.Icon(icon, contentDescription = name, tint = theme.textMuted, modifier = Modifier.size(18.dp))
        androidx.compose.material3.Text(
            value,
            style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
            color = theme.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
