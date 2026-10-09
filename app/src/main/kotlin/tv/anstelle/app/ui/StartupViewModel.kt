package tv.anstelle.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import tv.anstelle.core.data.ProfileRepository
import tv.anstelle.core.data.ThemeRepository
import tv.anstelle.core.data.playback.ChannelPlayer
import tv.anstelle.core.model.ProfileRights
import tv.anstelle.core.theme.DefaultTheme
import tv.anstelle.core.theme.ResolvedTheme
import javax.inject.Inject

/**
 * Who is watching, as the app shell sees it. Screens are built anew for each [profileId], so
 * nothing of one profile's carries over to the next. [launchPicker] is true while the picker
 * the app opens with is still up: there is more than one profile, or the only one has a PIN.
 * [canManageApp] is true for the owner and admins: only they see the app-wide settings.
 */
data class AppSession(val profileId: Long, val launchPicker: Boolean, val canManageApp: Boolean)

@HiltViewModel
class StartupViewModel @Inject constructor(
    private val profiles: ProfileRepository,
    themes: ThemeRepository,
    channelPlayer: ChannelPlayer,
) : ViewModel() {
    private val _session = MutableStateFlow<AppSession?>(null)

    /** Null until the profile and its theme are known. */
    val session: StateFlow<AppSession?> = _session.asStateFlow()

    private val _needsServer = MutableStateFlow(false)

    /** True while no profile exists: the app opens on connecting a server, whose user becomes the owner. */
    val needsServer: StateFlow<Boolean> = _needsServer.asStateFlow()

    private val _theme = MutableStateFlow(DefaultTheme.resolved)
    val theme: StateFlow<ResolvedTheme> = _theme.asStateFlow()

    init {
        viewModelScope.launch {
            themes.seedBuiltIns()
            if (!profiles.hasProfiles()) {
                _needsServer.value = true
                profiles.observeProfiles().first { it.isNotEmpty() }
                _needsServer.value = false
            }
            val active = profiles.activeProfile()
            val picker = profiles.observeProfiles().first().size > 1 || active.hasPin
            // The first frame already wears the profile's theme.
            _theme.value = themes.observeActiveTheme().first()?.let(ResolvedTheme::from) ?: DefaultTheme.resolved
            launch { themes.observeActiveTheme().collect { spec -> _theme.value = spec?.let(ResolvedTheme::from) ?: DefaultTheme.resolved } }
            profiles.observeActiveProfile().map { it.id to ProfileRights.canManageApp(it) }.distinctUntilChanged().collect { (id, manages) ->
                val previous = _session.value
                if (previous != null && previous.profileId != id) channelPlayer.stop()
                _session.value = AppSession(id, launchPicker = previous?.launchPicker ?: picker, canManageApp = manages)
            }
        }
    }

    /** The launch picker chose the profile that was already active. */
    fun pickerDone() = _session.update { it?.copy(launchPicker = false) }
}
