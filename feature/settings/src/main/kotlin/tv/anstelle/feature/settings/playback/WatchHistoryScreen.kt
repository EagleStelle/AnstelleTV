package tv.anstelle.feature.settings.playback

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.OndemandVideo
import androidx.compose.material.icons.filled.Tv
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import tv.anstelle.core.data.SettingKey
import tv.anstelle.core.data.Settings
import tv.anstelle.core.data.SettingsRepository
import tv.anstelle.core.model.WatchSync
import tv.anstelle.core.model.codeOf
import tv.anstelle.core.theme.LocalIsTv
import tv.anstelle.core.theme.components.FocusTile
import tv.anstelle.feature.settings.servers.SettingsPage
import javax.inject.Inject

/** Where playback happens, each with its own [WatchSync] setting. */
enum class SyncPlace(val title: String, val icon: ImageVector, val key: SettingKey.IntKey) {
    LIVE_TV("Live TV", Icons.Filled.Tv, Settings.LiveTvSync),
    ON_DEMAND("On demand", Icons.Filled.OndemandVideo, Settings.OnDemandSync),
}

@Immutable
data class WatchHistoryUiState(
    val liveTv: WatchSync = codeOf(Settings.LiveTvSync.default),
    val onDemand: WatchSync = codeOf(Settings.OnDemandSync.default),
    val loaded: Boolean = false,
) {
    fun of(place: SyncPlace) = when (place) {
        SyncPlace.LIVE_TV -> liveTv
        SyncPlace.ON_DEMAND -> onDemand
    }
}

@HiltViewModel
class WatchHistoryViewModel @Inject constructor(
    private val settings: SettingsRepository,
) : ViewModel() {
    val state: StateFlow<WatchHistoryUiState> = combine(
        settings.observe(Settings.LiveTvSync),
        settings.observe(Settings.OnDemandSync),
    ) { live, onDemand ->
        WatchHistoryUiState(codeOf(live), codeOf(onDemand), loaded = true)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WatchHistoryUiState())

    /** Steps through [SyncOrder], wrapping at either end. */
    fun step(place: SyncPlace, by: Int) {
        val next = SyncOrder[(SyncOrder.indexOf(state.value.of(place)) + by).mod(SyncOrder.size)]
        viewModelScope.launch { settings.set(place.key, next.code) }
    }
}

/**
 * What Live TV and On demand save to the server: one row each, its choice on the right and
 * what that choice does under the title. A tap or OK moves to the next choice (Status,
 * Progress, Both, Off, then round again); on TV left and right step either way.
 */
@Composable
fun WatchHistoryScreen(
    modifier: Modifier = Modifier,
    viewModel: WatchHistoryViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val isTv = LocalIsTv.current
    val firstFocus = remember { FocusRequester() }
    if (isTv) LaunchedEffect(state.loaded) { if (state.loaded) runCatching { firstFocus.requestFocus() } }

    SettingsPage(
        title = "Watch history",
        subtitle = "Status marks what you watched. Progress saves where you stopped.",
        modifier = modifier,
    ) {
        for (place in SyncPlace.entries) {
            item(key = place.name) {
                val sync = state.of(place)
                FocusTile(
                    title = place.title,
                    subtitle = syncDescription(place, sync),
                    icon = place.icon,
                    value = syncName(sync),
                    onClick = { viewModel.step(place, 1) },
                    onPrevious = if (isTv) ({ viewModel.step(place, -1) }) else null,
                    onNext = if (isTv) ({ viewModel.step(place, 1) }) else null,
                    modifier = Modifier.fillMaxWidth().let { if (place == SyncPlace.entries.first()) it.focusRequester(firstFocus) else it },
                )
            }
        }
    }
}
