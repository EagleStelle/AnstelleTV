package tv.anstelle.feature.settings.servers

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.SettingsBackupRestore
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import tv.anstelle.core.data.ProfileRepository
import tv.anstelle.core.data.QuickConnectState
import tv.anstelle.core.data.ServerCandidate
import tv.anstelle.core.data.ServerRepository
import tv.anstelle.core.data.SignInFor
import tv.anstelle.core.data.sync.userMessage
import tv.anstelle.core.model.ServerConnectException
import tv.anstelle.core.model.ServerProblem
import tv.anstelle.core.theme.LocalAnstelleTheme
import tv.anstelle.core.theme.LocalIsTv
import tv.anstelle.core.theme.components.AnstelleTextField
import tv.anstelle.core.theme.components.CodeBlocks
import tv.anstelle.core.theme.components.OsdButton
import tv.anstelle.core.theme.components.SettingsButtons
import tv.anstelle.core.theme.components.SettingsNotice
import tv.anstelle.core.theme.components.SettingsPage
import tv.anstelle.core.theme.components.rememberInitialFocus
import tv.anstelle.core.theme.components.section
import tv.anstelle.core.theme.components.settingsEdge
import tv.anstelle.core.theme.ControlShape
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width

import javax.inject.Inject

enum class AddServerStep { ADDRESS, SIGN_IN, QUICK_CONNECT }

@Immutable
data class AddServerUiState(
    val step: AddServerStep = AddServerStep.ADDRESS,
    val address: String = "",
    val username: String = "",
    val password: String = "",
    val candidate: ServerCandidate? = null,
    val quickConnectCode: String? = null,
    val busy: Boolean = false,
    val error: String? = null,
    /** Set once signed in; the screen then closes. */
    val signedIn: QuickConnectState.SignedIn? = null,
    /** Signing in as this profile's own user, so only its watch history goes there. */
    val linkProfileName: String? = null,
    /** Signing in makes a new profile from the user. */
    val newProfile: Boolean = false,
) {
    /** Signing in for a profile rather than for the server. */
    val forProfile: Boolean get() = linkProfileName != null || newProfile
}

@HiltViewModel
class AddServerViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val repository: ServerRepository,
    private val profiles: ProfileRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(AddServerUiState())
    val state: StateFlow<AddServerUiState> = _state.asStateFlow()
    private var quickConnectJob: Job? = null

    /** Who signing in is for: the server, a profile's own user, or a new profile. */
    private val target: SignInFor = when {
        (savedState.get<Long>("linkProfileId") ?: 0L) > 0 -> SignInFor.Profile(savedState.get<Long>("linkProfileId")!!)
        savedState.get<Boolean>("newProfile") == true -> SignInFor.NewProfile
        else -> SignInFor.Server
    }

    init {
        // Signing in again to a known server starts with its saved address.
        val serverId = savedState.get<Long>("serverId") ?: 0L
        if (target != SignInFor.Server) {
            viewModelScope.launch {
                val server = repository.observeServer(serverId).first() ?: return@launch
                val profile = (target as? SignInFor.Profile)?.let { profiles.observeProfile(it.profileId).first() }
                // Signing a profile in again starts with its user on this server.
                val account = profile?.let { p -> profiles.observeAccounts(p.id).first().firstOrNull { it.serverId == server.id } }
                _state.update {
                    it.copy(
                        address = server.baseUrl,
                        username = account?.userName.orEmpty(),
                        linkProfileName = profile?.name,
                        newProfile = target == SignInFor.NewProfile,
                    )
                }
                connect()
            }
        } else if (serverId > 0) {
            viewModelScope.launch {
                val server = repository.observeServer(serverId).first() ?: return@launch
                _state.update { it.copy(address = server.baseUrl, username = server.userName.orEmpty()) }
            }
        }
    }

    fun setAddress(value: String) = _state.update { it.copy(address = value, error = null) }
    fun setUsername(value: String) = _state.update { it.copy(username = value, error = null) }
    fun setPassword(value: String) = _state.update { it.copy(password = value, error = null) }

    fun connect() = launchAction { current ->
        val candidate = repository.findServer(current.address)
        _state.update { it.copy(step = AddServerStep.SIGN_IN, candidate = candidate) }
    }

    fun signInWithPassword() = launchAction { current ->
        val candidate = current.candidate ?: return@launchAction
        val signedIn = repository.signInWithPassword(candidate, current.username, current.password, target)
        _state.update { it.copy(password = "", signedIn = signedIn) }
    }


    fun startQuickConnect() {
        val candidate = _state.value.candidate ?: return
        quickConnectJob?.cancel()
        _state.update { it.copy(step = AddServerStep.QUICK_CONNECT, quickConnectCode = null, error = null, busy = true) }
        quickConnectJob = viewModelScope.launch {
            try {
                repository.quickConnect(candidate, target).collect { update ->
                    when (update) {
                        is QuickConnectState.WaitingForApproval ->
                            _state.update { it.copy(quickConnectCode = update.code, busy = false) }
                        is QuickConnectState.SignedIn ->
                            _state.update { it.copy(signedIn = update) }
                    }
                }
            } catch (e: ServerConnectException) {
                val message = if (e.problem == ServerProblem.SERVER_ERROR) "The code expired. Try again." else e.userMessage()
                _state.update { it.copy(step = AddServerStep.SIGN_IN, busy = false, error = message) }
            }
        }
    }

    /** Back from a later step returns to the previous one; false when there is none. */
    fun back(): Boolean {
        val current = _state.value
        quickConnectJob?.cancel()
        return when (current.step) {
            AddServerStep.ADDRESS -> false
            // A profile's sign-in has no address to go back to.
            AddServerStep.SIGN_IN -> if (target != SignInFor.Server) false else {
                _state.update { it.copy(step = AddServerStep.ADDRESS, error = null, busy = false) }
                true
            }
            AddServerStep.QUICK_CONNECT -> {
                _state.update { it.copy(step = AddServerStep.SIGN_IN, error = null, busy = false, quickConnectCode = null) }
                true
            }
        }
    }

    private fun launchAction(action: suspend (AddServerUiState) -> Unit) {
        val current = _state.value
        if (current.busy) return
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                action(current)
            } catch (e: CancellationException) {
                throw e
            } catch (e: ServerConnectException) {
                _state.update { it.copy(error = e.userMessage()) }
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }
}

/**
 * Connect a Jellyfin server: its address, then Quick Connect or a password. For a profile it
 * skips to signing in at the server's address, and [onProfile] gets the profile. [trail] names
 * the pages above it; the first server, before any profile, has none.
 */
@Composable
fun AddServerScreen(
    onSignedIn: (Long) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    trail: List<String> = listOf("Settings", "Servers"),
    onProfile: (profileId: Long) -> Unit = {},
    /** Before any profile exists: copying a backup in instead of adding a server. */
    onRestore: (() -> Unit)? = null,
    viewModel: AddServerViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.signedIn) {
        val signedIn = state.signedIn ?: return@LaunchedEffect
        val profileId = signedIn.profileId
        if (profileId != null) onProfile(profileId) else onSignedIn(signedIn.serverId)
    }
    androidx.activity.compose.BackHandler { if (!viewModel.back()) onBack() }
    val pages = if (state.forProfile) listOf("Profiles") else trail

    when (state.step) {
        AddServerStep.ADDRESS -> AddressStep(state, viewModel, pages, onRestore, modifier)
        AddServerStep.SIGN_IN -> SignInStep(state, viewModel, pages, modifier)
        AddServerStep.QUICK_CONNECT -> QuickConnectStep(state, viewModel, pages, modifier)
    }
}

@Composable
private fun AddressStep(state: AddServerUiState, viewModel: AddServerViewModel, trail: List<String>, onRestore: (() -> Unit)?, modifier: Modifier) {
    val firstFocus = rememberInitialFocus()
    SettingsPage(
        title = when {
            state.newProfile -> "Add profile"
            state.linkProfileName != null -> "Sign in"
            else -> "Add server"
        },
        trail = trail,
        about = if (state.forProfile) "Reaching the server…" else "Your Jellyfin server's address.",
        modifier = modifier,
    ) {
        item(key = "address") {
            AnstelleTextField(
                value = state.address,
                onValueChange = viewModel::setAddress,
                label = "Server address",
                placeholder = "192.168.1.20:8096 or jellyfin.example.com",
                keyboardType = KeyboardType.Uri,
                enabled = !state.busy,
                isLast = true,
                onDone = viewModel::connect,
                modifier = Modifier.fillMaxWidth().padding(horizontal = settingsEdge, vertical = 8.dp).focusRequester(firstFocus),
            )
        }
        item(key = "error") { state.error?.let { SettingsNotice(it, isError = true) } }
        item(key = "connect") {
            SettingsButtons {
                OsdButton(text = if (state.busy) "Connecting…" else "Connect", onClick = viewModel::connect, icon = Icons.Filled.Link, primary = true)
                if (onRestore != null) OsdButton(text = "Restore backup", onClick = onRestore, icon = Icons.Filled.SettingsBackupRestore)
            }
        }
    }
}

@Composable
private fun SignInStep(state: AddServerUiState, viewModel: AddServerViewModel, trail: List<String>, modifier: Modifier) {
    val candidate = state.candidate ?: return
    val firstFocus = rememberInitialFocus()
    val quick = candidate.quickConnectEnabled
    SettingsPage(
        title = if (state.newProfile) "Add profile" else "Sign in",
        trail = trail,
        about = when {
            state.newProfile ->
                "Sign in as a user on ${candidate.info.name}. The profile takes their name, picture and watch progress."
            state.linkProfileName != null ->
                "Sign in as ${state.linkProfileName}'s user on ${candidate.info.name}."
            else -> "${candidate.info.name}: Jellyfin ${candidate.info.version} at ${candidate.info.url}"
        },
        modifier = modifier,
    ) {
        if (quick) {
            section("Quick Connect")
            item(key = "quick") {
                SettingsButtons {
                    OsdButton("Use Quick Connect", onClick = viewModel::startQuickConnect, icon = Icons.Filled.QrCode2, primary = true, modifier = Modifier.focusRequester(firstFocus))
                }
            }
            section("Password")
        }
        item(key = "user") {
            AnstelleTextField(
                value = state.username,
                onValueChange = viewModel::setUsername,
                label = "Username",
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth().padding(horizontal = settingsEdge, vertical = 4.dp).let { if (quick) it else it.focusRequester(firstFocus) },
            )
        }
        item(key = "password") {
            AnstelleTextField(
                value = state.password,
                onValueChange = viewModel::setPassword,
                label = "Password",
                isPassword = true,
                isLast = true,
                enabled = !state.busy,
                onDone = viewModel::signInWithPassword,
                modifier = Modifier.fillMaxWidth().padding(horizontal = settingsEdge, vertical = 4.dp),
            )
        }
        item(key = "error") { state.error?.let { SettingsNotice(it, isError = true) } }
        item(key = "signin") {
            SettingsButtons {
                OsdButton(
                    text = if (state.busy) "Signing in…" else "Sign in",
                    onClick = viewModel::signInWithPassword,
                    icon = Icons.AutoMirrored.Filled.Login,
                    primary = !quick,
                )
            }
        }
    }
}

@Composable
private fun QuickConnectStep(state: AddServerUiState, viewModel: AddServerViewModel, trail: List<String>, modifier: Modifier) {
    val firstFocus = rememberInitialFocus()
    SettingsPage(
        title = "Quick Connect",
        trail = trail,
        about = "In Jellyfin, open Quick Connect and enter this code.",
        aside = {
            CodeBlocks(state.quickConnectCode)
        },
        modifier = modifier,
    ) {
        item(key = "waiting") { SettingsNotice(if (state.quickConnectCode == null) "Getting a code…" else "Waiting for approval…") }
        item(key = "cancel") {
            SettingsButtons {
                OsdButton("Cancel", onClick = { viewModel.back() }, icon = Icons.Filled.Close, modifier = Modifier.focusRequester(firstFocus))
            }
        }
    }
}
