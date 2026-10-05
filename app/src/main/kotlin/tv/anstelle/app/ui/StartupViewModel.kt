package tv.anstelle.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import tv.anstelle.core.data.ProfileRepository
import javax.inject.Inject

sealed interface StartupState {
    data object Loading : StartupState

    /** [showProfilePicker] is false when there is only one profile, so launch goes straight to the guide. */
    data class Ready(val showProfilePicker: Boolean) : StartupState
}

@HiltViewModel
class StartupViewModel @Inject constructor(
    profiles: ProfileRepository,
) : ViewModel() {
    private val _state = MutableStateFlow<StartupState>(StartupState.Loading)
    val state: StateFlow<StartupState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            profiles.ensureActiveProfile()
            val count = profiles.observeProfiles().first().size
            _state.value = StartupState.Ready(showProfilePicker = count > 1)
        }
    }
}
