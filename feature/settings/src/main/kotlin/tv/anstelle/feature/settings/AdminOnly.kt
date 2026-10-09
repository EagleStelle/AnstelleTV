package tv.anstelle.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import tv.anstelle.core.data.ProfileRepository
import tv.anstelle.core.theme.LocalAnstelleTheme
import javax.inject.Inject

@HiltViewModel
class AdminOnlyViewModel @Inject constructor(profiles: ProfileRepository) : ViewModel() {
    /** Null until known, so nothing behind the guard shows for a moment. */
    val canManage: StateFlow<Boolean?> = profiles.observeCanManageApp().stateIn(viewModelScope, SharingStarted.Eagerly, null)
}

/**
 * Shows [content], an app-wide setting (servers, channels, web control),
 * only to the owner and admins. Members never see a way in; should one land here anyway (a
 * restored back stack, say), [onLeave] takes them back.
 */
@Composable
fun AdminOnly(onLeave: () -> Unit, content: @Composable () -> Unit) {
    val viewModel: AdminOnlyViewModel = hiltViewModel()
    val canManage by viewModel.canManage.collectAsStateWithLifecycle()
    when (canManage) {
        true -> content()
        else -> {
            if (canManage == false) LaunchedEffect(Unit) { onLeave() }
            Box(Modifier.fillMaxSize().background(LocalAnstelleTheme.current.background))
        }
    }
}
