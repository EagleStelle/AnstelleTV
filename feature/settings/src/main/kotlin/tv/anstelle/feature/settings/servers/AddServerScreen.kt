package tv.anstelle.feature.settings.servers

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import tv.anstelle.core.data.QuickConnectState
import tv.anstelle.core.data.ServerCandidate
import tv.anstelle.core.data.ServerRepository
import tv.anstelle.core.data.sync.userMessage
import tv.anstelle.core.model.ServerConnectException
import tv.anstelle.core.model.ServerProblem
import tv.anstelle.core.theme.LocalAnstelleTheme
import tv.anstelle.core.theme.LocalIsTv
import tv.anstelle.core.theme.components.AnstelleTextField
import tv.anstelle.core.theme.components.FocusTile
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
    val signedInServerId: Long? = null,
)

@HiltViewModel
class AddServerViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val repository: ServerRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(AddServerUiState())
    val state: StateFlow<AddServerUiState> = _state.asStateFlow()
    private var quickConnectJob: Job? = null

    init {
        // Signing in again to a known server starts with its saved address.
        val serverId = savedState.get<Long>("serverId") ?: 0L
        if (serverId > 0) {
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
        val serverId = repository.signInWithPassword(candidate, current.username, current.password)
        _state.update { it.copy(password = "", signedInServerId = serverId) }
    }

    fun startQuickConnect() {
        val candidate = _state.value.candidate ?: return
        quickConnectJob?.cancel()
        _state.update { it.copy(step = AddServerStep.QUICK_CONNECT, quickConnectCode = null, error = null, busy = true) }
        quickConnectJob = viewModelScope.launch {
            try {
                repository.quickConnect(candidate).collect { update ->
                    when (update) {
                        is QuickConnectState.WaitingForApproval ->
                            _state.update { it.copy(quickConnectCode = update.code, busy = false) }
                        is QuickConnectState.SignedIn ->
                            _state.update { it.copy(signedInServerId = update.serverId) }
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
            AddServerStep.SIGN_IN -> {
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

/** Connect a Jellyfin server: addresses, then Quick Connect or a password. */
@Composable
fun AddServerScreen(
    onSignedIn: (Long) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AddServerViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.signedInServerId) { state.signedInServerId?.let(onSignedIn) }
    androidx.activity.compose.BackHandler { if (!viewModel.back()) onBack() }

    when (state.step) {
        AddServerStep.ADDRESS -> AddressStep(state, viewModel, modifier)
        AddServerStep.SIGN_IN -> SignInStep(state, viewModel, modifier)
        AddServerStep.QUICK_CONNECT -> QuickConnectStep(state, viewModel, modifier)
    }
}

@Composable
private fun AddressStep(state: AddServerUiState, viewModel: AddServerViewModel, modifier: Modifier) {
    val firstFocus = rememberInitialFocus()
    SettingsPage(
        title = "Add a Jellyfin server",
        subtitle = "Enter the server's address: an IP address or a web address.",
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
                modifier = Modifier.fillMaxWidth().focusRequester(firstFocus),
            )
        }
        item(key = "error") { state.error?.let { MessageText(it, isError = true) } }
        item(key = "connect") {
            FocusTile(
                title = if (state.busy) "Connecting…" else "Connect",
                onClick = viewModel::connect,
                icon = Icons.Filled.Link,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun SignInStep(state: AddServerUiState, viewModel: AddServerViewModel, modifier: Modifier) {
    val candidate = state.candidate ?: return
    val firstFocus = rememberInitialFocus()
    SettingsPage(
        title = "Sign in to ${candidate.info.name}",
        subtitle = "Jellyfin ${candidate.info.version} at ${candidate.info.url}",
        modifier = modifier,
    ) {
        if (candidate.quickConnectEnabled) {
            item(key = "quick") {
                FocusTile(
                    title = "Use Quick Connect",
                    subtitle = "Get a code and approve it from Jellyfin on your phone or computer. No typing.",
                    icon = Icons.Filled.QrCode2,
                    onClick = viewModel::startQuickConnect,
                    modifier = Modifier.fillMaxWidth().focusRequester(firstFocus),
                )
            }
            item(key = "or") { MessageText("Or sign in with a password:") }
        }
        item(key = "user") {
            AnstelleTextField(
                value = state.username,
                onValueChange = viewModel::setUsername,
                label = "Username",
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth().let { if (candidate.quickConnectEnabled) it else it.focusRequester(firstFocus) },
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
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item(key = "error") { state.error?.let { MessageText(it, isError = true) } }
        item(key = "signin") {
            FocusTile(
                title = if (state.busy) "Signing in…" else "Sign in",
                onClick = viewModel::signInWithPassword,
                icon = Icons.AutoMirrored.Filled.Login,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun QuickConnectStep(state: AddServerUiState, viewModel: AddServerViewModel, modifier: Modifier) {
    val theme = LocalAnstelleTheme.current
    val isTv = LocalIsTv.current
    val firstFocus = rememberInitialFocus()
    SettingsPage(
        title = "Quick Connect",
        subtitle = "In Jellyfin on another device, open your profile, choose Quick Connect and enter this code.",
        modifier = modifier,
    ) {
        item(key = "code") {
            Box(Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
                androidx.compose.material3.Text(
                    state.quickConnectCode ?: "······",
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = if (isTv) 72.sp else 48.sp,
                    letterSpacing = 8.sp,
                    color = theme.primary,
                )
            }
        }
        item(key = "waiting") { MessageText(if (state.quickConnectCode == null) "Getting a code…" else "Waiting for approval…") }
        item(key = "cancel") {
            FocusTile(
                title = "Back",
                onClick = { viewModel.back() },
                icon = Icons.AutoMirrored.Filled.ArrowBack,
                modifier = Modifier.fillMaxWidth().focusRequester(firstFocus),
            )
        }
    }
}
