package tv.anstelle.feature.profiles

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import tv.anstelle.core.common.TimeSource
import tv.anstelle.core.data.PinCheck
import tv.anstelle.core.data.ProfileRepository
import tv.anstelle.core.data.ServerRepository
import tv.anstelle.core.model.MediaServer
import tv.anstelle.core.model.Profile
import tv.anstelle.core.model.ProfileRights
import tv.anstelle.core.theme.LocalAnstelleTheme
import tv.anstelle.core.theme.LocalIsTv
import tv.anstelle.core.theme.components.ActionDialog
import tv.anstelle.core.theme.components.AnstelleMark
import tv.anstelle.core.theme.components.DialogAction
import tv.anstelle.core.theme.components.Notice
import tv.anstelle.core.theme.components.OsdButton
import tv.anstelle.core.theme.components.PinEntry
import tv.anstelle.core.theme.components.lockedOutMessage
import tv.anstelle.core.theme.components.wrongPinMessage
import tv.anstelle.core.theme.components.pagePadding
import tv.anstelle.core.theme.components.requestWhenReady
import javax.inject.Inject

/** Switching to [profile] waits for its PIN. */
@Immutable
data class PinPrompt(
    val profile: Profile,
    val message: String? = null,
    val isError: Boolean = false,
    val attempt: Int = 0,
    val busy: Boolean = false,
)

@Immutable
data class ProfilesUiState(
    val profiles: List<Profile> = emptyList(),
    /** The profile watching now; its role decides what the picker offers. */
    val active: Profile? = null,
    val loaded: Boolean = false,
    /** Selecting a profile opens its settings instead of switching to it. */
    val managing: Boolean = false,
    val pin: PinPrompt? = null,
    /** Why a profile can't be added yet. */
    val notice: String? = null,
    /** Adding a profile with more than one server signed in: the servers to sign in on. */
    val serverChoice: List<MediaServer>? = null,
) {
    val canAdd: Boolean get() = active?.let(ProfileRights::canAdd) == true

    fun canEdit(profile: Profile): Boolean = active?.let { ProfileRights.canEdit(it, profile) } == true
}

sealed interface ProfilesEvent {
    data object Watching : ProfilesEvent
    data class Edit(val profileId: Long) : ProfilesEvent

    /** Sign in as someone on [serverId]; the profile takes their name and picture. */
    data class AddFromJellyfin(val serverId: Long) : ProfilesEvent
}

@HiltViewModel
class ProfilePickerViewModel @Inject constructor(
    private val repository: ProfileRepository,
    private val servers: ServerRepository,
    private val time: TimeSource,
) : ViewModel() {
    private val _state = MutableStateFlow(ProfilesUiState())
    val state: StateFlow<ProfilesUiState> = _state.asStateFlow()

    private val _events = Channel<ProfilesEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    init {
        viewModelScope.launch {
            repository.ensureActiveProfile()
            combine(repository.observeProfiles(), repository.observeActiveProfile()) { all, active -> all to active }
                .collect { (all, active) -> _state.update { it.copy(profiles = all, active = active, loaded = true) } }
        }
    }

    /**
     * Switches to [profile], or with [ProfilesUiState.managing] opens it when the profile
     * watching may change it (the page asks for the profile's own PIN). At [atLaunch] even
     * the active profile asks for its PIN.
     */
    fun select(profile: Profile, atLaunch: Boolean) {
        val state = _state.value
        when {
            state.managing -> if (state.canEdit(profile)) _events.trySend(ProfilesEvent.Edit(profile.id))
            profile.hasPin && (atLaunch || profile.id != state.active?.id) -> _state.update { it.copy(pin = PinPrompt(profile)) }
            else -> switchTo(profile)
        }
    }

    /**
     * Profiles are Jellyfin users, so a new one signs in to a server: the only one signed in,
     * or the one picked when there are more. Only the owner and admins add them.
     */
    fun add() {
        if (!_state.value.canAdd) return
        viewModelScope.launch {
            val signedIn = servers.observeServers().first().filter { it.isSignedIn }
            when (signedIn.size) {
                0 -> _state.update { it.copy(notice = "Profiles are Jellyfin users. Connect a Jellyfin server in Settings first, then add one.") }
                1 -> _events.send(ProfilesEvent.AddFromJellyfin(signedIn.single().id))
                else -> _state.update { it.copy(serverChoice = signedIn, notice = null) }
            }
        }
    }

    fun chooseServer(serverId: Long) {
        _state.update { it.copy(serverChoice = null) }
        _events.trySend(ProfilesEvent.AddFromJellyfin(serverId))
    }

    fun cancelServerChoice() = _state.update { it.copy(serverChoice = null) }

    fun toggleManaging() = _state.update { it.copy(managing = !it.managing, notice = null) }

    fun cancelPin() = _state.update { it.copy(pin = null) }

    fun submitPin(pin: String) {
        val prompt = _state.value.pin ?: return
        _state.update { it.copy(pin = prompt.copy(busy = true, message = null)) }
        viewModelScope.launch {
            when (val result = repository.checkPin(prompt.profile.id, pin)) {
                PinCheck.Right -> {
                    _state.update { it.copy(pin = null) }
                    switchTo(prompt.profile)
                }
                is PinCheck.Wrong -> _state.update {
                    it.copy(pin = prompt.copy(message = wrongPinMessage(result.triesLeft), isError = true, attempt = prompt.attempt + 1))
                }
                is PinCheck.LockedOut -> {
                    val wait = result.untilUtcMs - time.nowUtcMs()
                    _state.update { it.copy(pin = prompt.copy(message = lockedOutMessage(wait), isError = true, attempt = prompt.attempt + 1, busy = true)) }
                    kotlinx.coroutines.delay(wait.coerceAtLeast(0))
                    _state.update { s -> s.copy(pin = s.pin?.copy(busy = false, message = null)) }
                }
            }
        }
    }

    private fun switchTo(profile: Profile) {
        viewModelScope.launch {
            repository.setActive(profile.id)
            _events.send(ProfilesEvent.Watching)
        }
    }
}

/**
 * Who's watching: one tile per profile. Selecting one switches to it (after the profile's
 * PIN, when one is set) and goes to Live TV. *Manage profiles* shows only the profiles the
 * one watching may edit, as doors to their settings: the owner every profile, an admin
 * members' and the admin's own, a member only the member's own. Only the owner and admins see
 * *Add a profile*, which asks which server to sign in on when more than one is signed in.
 * [atLaunch] is the full-screen picker the app opens with, where every PIN is asked.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProfilePickerScreen(
    onProfileSelected: () -> Unit,
    onEditProfile: (Long) -> Unit,
    onAddFromJellyfin: (serverId: Long) -> Unit,
    modifier: Modifier = Modifier,
    atLaunch: Boolean = false,
    viewModel: ProfilePickerViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val theme = LocalAnstelleTheme.current
    val isTv = LocalIsTv.current
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                ProfilesEvent.Watching -> onProfileSelected()
                is ProfilesEvent.Edit -> onEditProfile(event.profileId)
                is ProfilesEvent.AddFromJellyfin -> onAddFromJellyfin(event.serverId)
            }
        }
    }

    state.pin?.let { prompt ->
        PinEntry(
            title = prompt.profile.name,
            message = prompt.message,
            isError = prompt.isError,
            enabled = !prompt.busy,
            attempt = prompt.attempt,
            onPin = viewModel::submitPin,
            onCancel = viewModel::cancelPin,
            modifier = modifier,
        )
        return
    }

    val activeFocus = remember { FocusRequester() }
    val addFocus = remember { FocusRequester() }
    // At a cold start the window takes a while to get focus; keep asking for a few seconds.
    if (isTv) LaunchedEffect(state.loaded) { if (state.loaded) activeFocus.requestWhenReady(frames = LAUNCH_FOCUS_FRAMES) }

    Box(modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .background(theme.background)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = pagePadding, vertical = if (isTv) 48.dp else 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(if (isTv) 28.dp else 20.dp),
        ) {
            if (atLaunch) AnstelleMark(Modifier.size(if (isTv) 48.dp else 40.dp))
            androidx.compose.material3.Text(
                if (state.managing) "Manage profiles" else "Who's watching?",
                style = if (isTv) androidx.compose.material3.MaterialTheme.typography.displaySmall else androidx.compose.material3.MaterialTheme.typography.headlineMedium,
                color = theme.text,
                textAlign = TextAlign.Center,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(if (isTv) 20.dp else 12.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(if (isTv) 20.dp else 12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                // Managing shows only what the profile watching may edit.
                for (profile in state.profiles.filter { !state.managing || state.canEdit(it) }) {
                    val isActive = profile.id == state.active?.id
                    ProfileTile(
                        name = profile.name,
                        picture = profile.pictureUrl,
                        detail = if (isActive && !atLaunch) "Watching now" else null,
                        locked = profile.hasPin,
                        role = profile.role,
                        editing = state.managing,
                        onClick = { viewModel.select(profile, atLaunch) },
                        modifier = if (isActive) Modifier.focusRequester(activeFocus) else Modifier,
                    )
                }
            }
            state.notice?.let { Notice(it, isError = true) }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (!state.managing && state.canAdd) OsdButton("Add a profile", onClick = viewModel::add, modifier = Modifier.focusRequester(addFocus), icon = Icons.Filled.PersonAdd)
                OsdButton(
                    if (state.managing) "Done" else "Manage profiles",
                    onClick = viewModel::toggleManaging,
                    icon = if (state.managing) Icons.Filled.Check else Icons.Filled.Edit,
                    primary = state.managing,
                )
            }
        }
        // With more than one server signed in, a new profile first picks the server to sign in on.
        state.serverChoice?.let { choice ->
            ActionDialog(
                actions = choice.map { server ->
                    DialogAction(server.name, server.baseUrl.substringAfter("://"), Icons.Filled.Dns) { viewModel.chooseServer(server.id) }
                },
                onDismiss = {
                    viewModel.cancelServerChoice()
                    runCatching { addFocus.requestFocus() }
                },
            ) {
                androidx.compose.material3.Text(
                    "Add a profile",
                    style = androidx.compose.material3.MaterialTheme.typography.headlineSmall,
                    color = theme.text,
                )
            }
        }
    }
}

/** About three seconds of frames. */
private const val LAUNCH_FOCUS_FRAMES = 180
