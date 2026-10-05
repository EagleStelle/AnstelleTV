package tv.anstelle.feature.profiles

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChildCare
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import tv.anstelle.core.data.ProfileRepository
import tv.anstelle.core.model.Profile
import tv.anstelle.core.theme.components.PlaceholderAction
import tv.anstelle.core.theme.components.PlaceholderScreen
import javax.inject.Inject

@HiltViewModel
class ProfilePickerViewModel @Inject constructor(
    private val repository: ProfileRepository,
) : ViewModel() {
    val profiles: StateFlow<List<Profile>> = repository.observeProfiles()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun select(profile: Profile, onSelected: () -> Unit) {
        viewModelScope.launch {
            repository.setActive(profile.id)
            onSelected()
        }
    }
}

/** Who's watching? PIN entry and kid restrictions arrive in Phase 8. */
@Composable
fun ProfilePickerScreen(
    onProfileSelected: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ProfilePickerViewModel = hiltViewModel(),
) {
    val profiles by viewModel.profiles.collectAsStateWithLifecycle()
    val actions = remember(profiles) {
        profiles.map { profile ->
            PlaceholderAction(
                title = profile.name,
                subtitle = when {
                    profile.isKid -> "Kids"
                    profile.hasPin -> "PIN protected"
                    else -> null
                },
                icon = when {
                    profile.isKid -> Icons.Filled.ChildCare
                    profile.hasPin -> Icons.Filled.Lock
                    else -> Icons.Filled.Person
                },
            ) { viewModel.select(profile, onProfileSelected) }
        }
    }
    PlaceholderScreen(
        title = "Who's watching?",
        description = "Each profile has its own channels, favorites and theme.",
        actions = actions,
        modifier = modifier,
    )
}
