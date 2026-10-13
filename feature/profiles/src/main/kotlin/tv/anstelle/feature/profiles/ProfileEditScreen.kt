package tv.anstelle.feature.profiles

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Password
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
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
import tv.anstelle.core.model.ProfileAccount
import tv.anstelle.core.model.ProfileRights
import tv.anstelle.core.model.ProfileRole
import tv.anstelle.core.model.RatingLevel
import tv.anstelle.core.model.RatingLimits
import tv.anstelle.core.theme.components.ChoiceRow
import tv.anstelle.core.theme.components.ConfirmSheet
import tv.anstelle.core.theme.components.DangerButton
import tv.anstelle.core.theme.components.LinkRow
import tv.anstelle.core.theme.components.LocalSettingsSheets
import tv.anstelle.core.theme.components.PinEntry
import tv.anstelle.core.theme.components.SettingsButtons
import tv.anstelle.core.theme.components.SettingsPage
import tv.anstelle.core.theme.components.SwitchRow
import tv.anstelle.core.theme.components.foot
import tv.anstelle.core.theme.components.lockedOutMessage
import tv.anstelle.core.theme.components.rememberInitialFocus
import tv.anstelle.core.theme.components.section
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
    /** The profile's own Jellyfin users, one per server it signed in to. */
    val accounts: List<ProfileAccount> = emptyList(),
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
        combine(profiles.observeProfile(profileId), profiles.observeActiveProfile(), ::Pair),
        themes.observeThemes(),
        servers.observeServers(),
        profiles.observeAccounts(profileId),
        levels,
    ) { (profile, viewer), themeList, serverList, accountList, levelList ->
        val theme = themeList.firstOrNull { it.id == profile?.themeId } ?: themeList.firstOrNull { it.spec.key == DEFAULT_THEME_KEY }
        ProfileEditUiState(
            profile = profile,
            viewer = viewer,
            loaded = true,
            themeName = theme?.spec?.name.orEmpty(),
            servers = serverList,
            accounts = accountList,
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

    /** Sets the highest rating the profile may watch; null is no limit. */
    fun setLimit(score: Int?) {
        if (!state.value.canSetParental) return
        act { profiles.setRatingLimit(profileId, score) }
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
    SettingsPage(
        title = profile.name,
        trail = listOf("Profiles"),
        about = listOfNotNull(
            "A PIN is asked when switching to this profile.",
            "Admins manage servers, channels and members.".takeIf { state.canSetAdmin },
            "Programs over the rating air blacked out.".takeIf { state.canSetParental },
        ).joinToString(" "),
        modifier = modifier,
    ) {
        // Profile: what the profile itself sets, the same rows for everyone who may edit it.
        section("Profile")
        item(key = "theme") {
            LinkRow(
                title = "Theme",
                summary = state.themeName,
                icon = Icons.Filled.Palette,
                onClick = { onOpenThemes(profile.id) },
                modifier = Modifier.focusRequester(firstFocus),
            )
        }
        // One sign-in per server: the Jellyfin user the profile plays as there. With one server the
        // row is plain Authentication; with more, each row is named after its server.
        for (server in state.servers) {
            val account = state.accounts.firstOrNull { it.serverId == server.id }
            item(key = "user-${server.id}") {
                LinkRow(
                    title = if (state.servers.size == 1) "Authentication" else server.name,
                    summary = when {
                        account != null && !account.signedIn -> "Signed out"
                        account != null -> account.userName
                        else -> "Not signed in"
                    },
                    icon = Icons.Filled.Key,
                    onClick = { onLinkAccount(profile.id, server.id) },
                )
            }
        }
        item(key = "pin") {
            val sheets = LocalSettingsSheets.current
            SwitchRow(
                title = "PIN",
                checked = profile.hasPin,
                onCheckedChange = { on ->
                    if (on) {
                        viewModel.startNewPin()
                    } else {
                        sheets.show(ConfirmSheet("Remove the PIN?", "Switching to this profile no longer asks for one.", "Remove", Icons.Filled.LockOpen, viewModel::removePin))
                    }
                },
                icon = Icons.Filled.Lock,
            )
        }
        if (profile.hasPin) {
            item(key = "pin-change") {
                LinkRow(title = "Change PIN", icon = Icons.Filled.Password, onClick = viewModel::startNewPin)
            }
        }
        // Permissions: what the owner or an admin sets for the profile. Only they see the group,
        // and each row only when the viewer may change it.
        if (state.canSetAdmin || state.canSetParental) section("Permissions")
        if (state.canSetAdmin) {
            item(key = "admin") {
                SwitchRow(
                    title = "Admin",
                    checked = profile.role == ProfileRole.ADMIN,
                    onCheckedChange = { viewModel.toggleAdmin() },
                    icon = Icons.Filled.AdminPanelSettings,
                )
            }
        }
        // Parental controls belong to members; an admin has none.
        if (state.canSetParental) {
            item(key = "limit") {
                ChoiceRow(
                    title = "Parental Rating",
                    value = profile.maxRatingScore,
                    choices = RatingLimits.choices(state.levels),
                    label = { RatingLimits.label(it, state.levels) },
                    onSelect = viewModel::setLimit,
                    icon = Icons.Filled.Shield,
                )
            }
        }
        if (state.canSetAdmin) {
            item(key = "make-owner") {
                SettingsButtons {
                    DangerButton(
                        text = "Transfer ownership",
                        question = "Make ${profile.name} the owner?",
                        explanation = "You stay an admin. The owner edits every profile and is the only one who makes admins.",
                        confirm = "Transfer",
                        icon = Icons.Filled.SwapHoriz,
                        onConfirm = viewModel::makeOwner,
                    )
                }
            }
        }
        // Deleting stands apart at the foot of the page, under no heading.
        if (state.canDelete) {
            foot {
                DangerButton(
                    text = "Delete profile",
                    question = "Delete ${profile.name}?",
                    explanation = "Its Jellyfin sign-ins are signed out and its settings removed.",
                    confirm = "Delete",
                    onConfirm = viewModel::delete,
                )
            }
        }
    }
}
