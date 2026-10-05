package tv.anstelle.feature.settings.servers

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
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
import tv.anstelle.core.theme.LocalIsTv
import tv.anstelle.core.theme.components.FocusTile
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

/** One server: connection, sync status, item counts and which libraries are synced. */
@Composable
fun ServerDetailScreen(
    onSignIn: (Long) -> Unit,
    onRemoved: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ServerDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val server = state.server ?: return
    val firstFocus = remember { FocusRequester() }
    if (LocalIsTv.current) LaunchedEffect(Unit) { runCatching { firstFocus.requestFocus() } }
    // Removing everything synced from a server is destructive, so it takes a second press.
    var confirmRemove by remember { mutableStateOf(false) }

    SettingsPage(
        title = server.name,
        subtitle = listOfNotNull(
            server.serverVersion?.let { "Jellyfin $it" },
            server.userName?.let { "signed in as $it" },
            server.baseUrl,
        ).joinToString(" · "),
        modifier = modifier,
    ) {
        item(key = "status") {
            MessageText(server.statusLine(state.progress, state.nowUtcMs), isError = server.lastSyncError != null && state.progress == null)
            MessageText(countsLine(state.counts))
        }
        item(key = "sync") {
            FocusTile(
                title = if (server.isSignedIn) "Sync now" else "Sign in again",
                subtitle = if (server.isSignedIn) "Fetch changes and check for removed items." else "The server no longer accepts the saved sign-in.",
                icon = if (server.isSignedIn) Icons.Filled.Sync else Icons.AutoMirrored.Filled.Login,
                onClick = { if (server.isSignedIn) viewModel.syncNow(full = false) else onSignIn(server.id) },
                modifier = Modifier.fillMaxWidth().focusRequester(firstFocus),
            )
        }
        item(key = "libraries") { SectionLabel("Libraries") }
        items(state.libraries, key = { it.id }) { library ->
            val supported = viewModel.canAir(library)
            FocusTile(
                title = library.name,
                subtitle = if (supported) {
                    val use = if (library.isEnabled) "Used for channels" else "Not used"
                    "${collectionLabel(library.collectionType)} · $use. Select to change."
                } else {
                    "${collectionLabel(library.collectionType)} · not supported yet"
                },
                icon = if (supported && library.isEnabled) Icons.Filled.VideoLibrary else Icons.Filled.VideocamOff,
                onClick = { if (supported) viewModel.toggle(library) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item(key = "actions") { SectionLabel("Server") }
        item(key = "full") {
            FocusTile(
                title = "Fetch everything again",
                subtitle = "Re-reads every item instead of only the changes.",
                icon = Icons.Filled.Refresh,
                onClick = { viewModel.syncNow(full = true) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item(key = "signin") {
            FocusTile(
                title = "Sign in as someone else",
                subtitle = "Switch the Jellyfin user this server syncs for.",
                icon = Icons.AutoMirrored.Filled.Login,
                onClick = { onSignIn(server.id) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item(key = "remove") {
            FocusTile(
                title = if (confirmRemove) "Select again to remove ${server.name}" else "Remove server",
                subtitle = "Signs out and deletes everything synced from it.",
                icon = Icons.Filled.DeleteForever,
                onClick = { if (confirmRemove) viewModel.remove(onRemoved) else confirmRemove = true },
                modifier = Modifier.fillMaxWidth().onFocusChanged { if (!it.isFocused) confirmRemove = false },
            )
        }
    }
}
