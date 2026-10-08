package tv.anstelle.feature.profiles

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import tv.anstelle.core.common.TimeSource
import tv.anstelle.core.data.PinCheck
import tv.anstelle.core.data.ProfileRepository
import tv.anstelle.core.data.ServerRepository
import tv.anstelle.core.data.ThemeRepository
import tv.anstelle.core.data.profiles.PinHash
import tv.anstelle.core.model.MediaServer
import tv.anstelle.core.model.Profile
import tv.anstelle.core.model.ProfileRights
import tv.anstelle.core.model.ProfileRole
import tv.anstelle.core.model.RatingLevel
import tv.anstelle.core.model.RatingLimits
import tv.anstelle.core.theme.LocalIsTv
import tv.anstelle.core.theme.components.FocusTile
import tv.anstelle.core.theme.components.MenuPage
import tv.anstelle.core.theme.components.PinEntry
import tv.anstelle.core.theme.components.SectionLabel
import tv.anstelle.core.theme.components.TileTrailing
import tv.anstelle.core.theme.components.lockedOutMessage
import tv.anstelle.core.theme.components.rememberInitialFocus
import tv.anstelle.core.theme.components.wrongPinMessage
import javax.inject.Inject

/** Setting a new PIN: typed once, then again to confirm. */
@Immutable
data class NewPin(val first: String? = null, val message: String? = null, val attempt: Int = 0)

/** The profile's own PIN, asked before its owner edits the profile. */
@Immutable
data class OwnPin(val message: String? = null, val attempt: Int = 0, val busy: Boolean = false)

@Immutable
data class ProfileEditUiState(
    val profile: Profile? = null,
    /** The profile watching, whose role decides what may be changed here. */
    val viewer: Profile? = null,
    val loaded: Boolean = false,
    val themeName: String = "",
    val servers: List<MediaServer> = emptyList(),
    /** False when the profile's own Jellyfin sign-in lost its token. */
    val accountSignedIn: Boolean = true,
    /** Jellyfin's rating levels the library reaches, for the parental rating's choices. */
    val levels: List<RatingLevel> = emptyList(),
) {
    val canEdit: Boolean get() = profile != null && viewer != null && ProfileRights.canEdit(viewer, profile)
    val canSetParental: Boolean get() = profile != null && viewer != null && ProfileRights.canSetParental(viewer, profile)
    val canSetAdmin: Boolean get() = profile != null && viewer != null && ProfileRights.canSetAdmin(viewer, profile)
    val canDelete: Boolean get() = profile != null && viewer != null && ProfileRights.canDelete(viewer, profile)
}

@HiltViewModel
class ProfileEditViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val profiles: ProfileRepository,
    private val time: TimeSource,
    themes: ThemeRepository,
    servers: ServerRepository,
) : ViewModel() {
    /** Matches the `profileId` argument of the navigation route. */
    val profileId: Long = checkNotNull(savedState.get<Long>("profileId"))

    private val levels = MutableStateFlow<List<RatingLevel>>(emptyList())

    val state: StateFlow<ProfileEditUiState> = combine(
        profiles.observeProfile(profileId),
        profiles.observeActiveProfile(),
        themes.observeThemes(),
        servers.observeServers(),
        levels,
    ) { profile, viewer, themeList, serverList, levelList ->
        val theme = themeList.firstOrNull { it.id == profile?.themeId } ?: themeList.firstOrNull { it.spec.key == DEFAULT_THEME_KEY }
        ProfileEditUiState(
            profile = profile,
            viewer = viewer,
            loaded = true,
            themeName = theme?.spec?.name.orEmpty(),
            servers = serverList,
            accountSignedIn = profile?.jellyfinUserId == null || profiles.isAccountSignedIn(profile),
            levels = levelList,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ProfileEditUiState())

    private val _newPin = MutableStateFlow<NewPin?>(null)
    val newPin: StateFlow<NewPin?> = _newPin

    /** Null once the profile's own PIN was entered, or when none is needed. */
    private val _ownPin = MutableStateFlow<OwnPin?>(OwnPin())
    val ownPin: StateFlow<OwnPin?> = _ownPin
    private var unlocked = false

    init {
        viewModelScope.launch { levels.value = profiles.ratingLevels() }
    }

    /** Editing one's own profile asks for its PIN once; the owner and admins edit others without one. */
    fun needsOwnPin(state: ProfileEditUiState): Boolean {
        val profile = state.profile ?: return false
        val viewer = state.viewer ?: return false
        return !unlocked && ProfileRights.needsPin(viewer, profile)
    }

    fun enterOwnPin(pin: String) {
        val entry = _ownPin.value ?: OwnPin()
        _ownPin.value = entry.copy(busy = true, message = null)
        viewModelScope.launch {
            when (val result = profiles.checkPin(profileId, pin)) {
                PinCheck.Right -> {
                    unlocked = true
                    _ownPin.value = null
                }
                is PinCheck.Wrong -> _ownPin.value = entry.copy(message = wrongPinMessage(result.triesLeft), attempt = entry.attempt + 1)
                is PinCheck.LockedOut -> {
                    val wait = result.untilUtcMs - time.nowUtcMs()
                    _ownPin.value = entry.copy(message = lockedOutMessage(wait), attempt = entry.attempt + 1, busy = true)
                    delay(wait.coerceAtLeast(0))
                    _ownPin.update { it?.copy(busy = false, message = null) }
                }
            }
        }
    }

    /** Steps through the library's ratings, from no limit down to the lowest. */
    fun stepLimit(by: Int) {
        val state = state.value
        val profile = state.profile ?: return
        if (!state.canSetParental) return
        val choices = RatingLimits.choices(state.levels)
        val index = choices.indexOf(profile.maxRatingScore).takeIf { it >= 0 } ?: 0
        act { profiles.setRatingLimit(profileId, choices[(index + by).mod(choices.size)]) }
    }

    fun toggleAdmin() {
        val state = state.value
        val profile = state.profile ?: return
        if (!state.canSetAdmin) return
        act { profiles.setAdmin(profileId, profile.role == ProfileRole.MEMBER) }
    }

    fun makeOwner() {
        if (!state.value.canSetAdmin) return
        act { profiles.transferOwnership(profileId) }
    }

    fun startNewPin() {
        _newPin.value = NewPin()
    }

    fun cancelNewPin() {
        _newPin.value = null
    }

    fun enterNewPin(pin: String) {
        val step = _newPin.value ?: return
        when {
            step.first == null -> _newPin.value = NewPin(first = pin, attempt = step.attempt + 1)
            step.first == pin -> {
                _newPin.value = null
                act { profiles.setPin(profileId, pin) }
            }
            else -> _newPin.value = NewPin(message = "The PINs didn't match. Start again.", attempt = step.attempt + 1)
        }
    }

    fun removePin() = act { profiles.setPin(profileId, null) }

    /** The page closes once the profile is gone. */
    fun delete() {
        if (state.value.canDelete) act { profiles.delete(profileId) }
    }

    private fun act(action: suspend () -> Unit) {
        viewModelScope.launch { action() }
    }

    private companion object {
        const val DEFAULT_THEME_KEY = "rose"
    }
}

/**
 * One profile's settings, as a setup menu. A profile is a Jellyfin user, whose name and
 * picture it shows. Who may change what follows the roles: the owner edits every profile, an
 * admin members' profiles and the admin's own, a member only the member's own. Editing one's
 * own profile asks for its PIN first, when one is set. Two groups: Profile (the theme,
 * authentication with Jellyfin and the PIN) and Permissions (admin, the parental rating for
 * members, transferring ownership), then deleting the profile on its own at the foot.
 */
@Composable
fun ProfileEditScreen(
    onOpenThemes: (profileId: Long) -> Unit,
    onLinkAccount: (profileId: Long, serverId: Long) -> Unit,
    onGone: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ProfileEditViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val newPin by viewModel.newPin.collectAsStateWithLifecycle()
    val ownPin by viewModel.ownPin.collectAsStateWithLifecycle()
    val isTv = LocalIsTv.current
    val profile = state.profile
    // Gone, or no longer the viewer's to change (handing the owner role on, say).
    LaunchedEffect(state.loaded, profile, state.canEdit) { if (state.loaded && (profile == null || !state.canEdit)) onGone() }
    if (profile == null || !state.canEdit) return

    if (ownPin != null && viewModel.needsOwnPin(state)) {
        val entry = ownPin ?: OwnPin()
        PinEntry(
            title = "Edit ${profile.name}",
            message = entry.message,
            isError = entry.message != null,
            enabled = !entry.busy,
            attempt = entry.attempt,
            onPin = viewModel::enterOwnPin,
            onCancel = onGone,
            modifier = modifier,
        )
        return
    }

    newPin?.let { step ->
        PinEntry(
            title = if (step.first == null) "New PIN for ${profile.name}" else "Enter the PIN again",
            message = step.message,
            isError = step.message != null,
            attempt = step.attempt,
            onPin = viewModel::enterNewPin,
            onCancel = viewModel::cancelNewPin,
            modifier = modifier,
        )
        return
    }

    val firstFocus = rememberInitialFocus()
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmOwner by remember { mutableStateOf(false) }
    // A profile signs in as a Jellyfin user of its own, or else uses the server's own sign-in (the first profile).
    val server = state.servers.firstOrNull { it.id == profile.serverId } ?: state.servers.firstOrNull()
    MenuPage(title = profile.name, modifier = modifier) {
        // Profile: what the profile itself sets, the same rows for everyone who may edit it.
        item(key = "profile") { SectionLabel("Profile") }
        item(key = "theme") {
            FocusTile(
                title = "Theme",
                icon = Icons.Filled.Palette,
                value = state.themeName,
                trailing = TileTrailing.Chevron,
                onClick = { onOpenThemes(profile.id) },
                modifier = Modifier.fillMaxWidth().focusRequester(firstFocus),
            )
        }
        if (server != null) {
            val own = profile.jellyfinUserId != null && profile.serverId == server.id
            item(key = "user") {
                FocusTile(
                    title = "Authentication",
                    icon = Icons.Filled.Key,
                    // The Jellyfin user the profile signs in as, or that the sign-in is gone.
                    value = when {
                        own && !state.accountSignedIn -> "Signed out"
                        own -> profile.jellyfinUserName
                        else -> server.userName
                    },
                    trailing = TileTrailing.Chevron,
                    onClick = { onLinkAccount(profile.id, server.id) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        item(key = "pin") {
            FocusTile(
                title = if (profile.hasPin) "Change PIN" else "Set PIN",
                icon = Icons.Filled.Lock,
                trailing = TileTrailing.Chevron,
                onClick = viewModel::startNewPin,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (profile.hasPin) {
            item(key = "pin-remove") {
                FocusTile(title = "Remove PIN", icon = Icons.Filled.LockOpen, onClick = viewModel::removePin, modifier = Modifier.fillMaxWidth())
            }
        }
        // Permissions: what the owner or an admin sets for the profile. Only they see the group,
        // and each row only when the viewer may change it.
        if (state.canSetAdmin || state.canSetParental) {
            item(key = "permissions") { SectionLabel("Permissions") }
        }
        if (state.canSetAdmin) {
            item(key = "admin") {
                FocusTile(
                    title = "Set Admin",
                    icon = Icons.Filled.AdminPanelSettings,
                    value = if (profile.role == ProfileRole.ADMIN) "On" else "Off",
                    toggle = profile.role == ProfileRole.ADMIN,
                    onClick = viewModel::toggleAdmin,
                    onPrevious = viewModel::toggleAdmin,
                    onNext = viewModel::toggleAdmin,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        // Parental controls belong to members; an admin has none.
        if (state.canSetParental) {
            item(key = "limit") {
                FocusTile(
                    title = "Parental Rating",
                    icon = Icons.Filled.Shield,
                    value = RatingLimits.label(profile.maxRatingScore, state.levels),
                    onClick = { viewModel.stepLimit(1) },
                    onPrevious = if (isTv) ({ viewModel.stepLimit(-1) }) else null,
                    onNext = if (isTv) ({ viewModel.stepLimit(1) }) else null,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        if (state.canSetAdmin) {
            item(key = "make-owner") {
                FocusTile(
                    title = if (confirmOwner) "Select again to transfer" else "Transfer ownership",
                    icon = Icons.Filled.SwapHoriz,
                    onClick = { if (confirmOwner) viewModel.makeOwner() else confirmOwner = true },
                    modifier = Modifier.fillMaxWidth().onFocusChanged { if (!it.isFocused) confirmOwner = false },
                )
            }
        }
        // Deleting stands apart at the foot of the page, under no heading, so it never reads as part of a group.
        if (state.canDelete) {
            item(key = "delete") {
                FocusTile(
                    title = if (confirmDelete) "Select again to delete" else "Delete profile",
                    icon = Icons.Filled.Delete,
                    onClick = { if (confirmDelete) viewModel.delete() else confirmDelete = true },
                    modifier = Modifier.padding(top = 24.dp).fillMaxWidth().onFocusChanged { if (!it.isFocused) confirmDelete = false },
                )
            }
        }
    }
}
