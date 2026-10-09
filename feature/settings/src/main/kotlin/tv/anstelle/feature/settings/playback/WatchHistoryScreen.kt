package tv.anstelle.feature.settings.playback

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import tv.anstelle.core.data.ProfileRepository
import tv.anstelle.core.data.stats.DashboardHistory
import tv.anstelle.feature.dashboard.DashboardOffDialog
import tv.anstelle.feature.settings.activeProfileId
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.InsertChart
import androidx.compose.material.icons.filled.OndemandVideo
import androidx.compose.material.icons.filled.Tv
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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

/** Where playback happens, each with its own [WatchSync] setting for each profile. */
enum class SyncPlace(val title: String, val icon: ImageVector, val key: (profileId: Long) -> SettingKey.IntKey) {
    LIVE_TV("Live TV", Icons.Filled.Tv, Settings::liveTvSync),
    ON_DEMAND("On Demand", Icons.Filled.OndemandVideo, Settings::onDemandSync),
}

@Immutable
data class WatchHistoryUiState(
    val liveTv: WatchSync = codeOf(Settings.liveTvSync(0).default),
    val onDemand: WatchSync = codeOf(Settings.onDemandSync(0).default),
    /** Whether the profile keeps what it watches for its dashboard. */
    val dashboard: Boolean = Settings.dashboard(0).default,
    val loaded: Boolean = false,
) {
    fun of(place: SyncPlace) = when (place) {
        SyncPlace.LIVE_TV -> liveTv
        SyncPlace.ON_DEMAND -> onDemand
    }
}

/**
 * The watching profile's own choices: each profile's history goes to its own Jellyfin user, and
 * its dashboard keeps what it watches on this device.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class WatchHistoryViewModel @Inject constructor(
    private val settings: SettingsRepository,
    private val profiles: ProfileRepository,
    private val history: DashboardHistory,
) : ViewModel() {
    val state: StateFlow<WatchHistoryUiState> = profiles.activeProfileId().flatMapLatest { profileId ->
        combine(
            settings.observe(Settings.liveTvSync(profileId)),
            settings.observe(Settings.onDemandSync(profileId)),
            history.observeEnabled(profileId),
        ) { live, onDemand, dashboard -> WatchHistoryUiState(codeOf(live), codeOf(onDemand), dashboard, loaded = true) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WatchHistoryUiState())

    fun turnOnDashboard() {
        viewModelScope.launch { history.turnOn(profiles.activeProfileId().first()) }
    }

    fun turnOffDashboard(keepHistory: Boolean) {
        viewModelScope.launch { history.turnOff(profiles.activeProfileId().first(), keepHistory) }
    }

    /** Steps through [SyncOrder], wrapping at either end. */
    fun step(place: SyncPlace, by: Int) {
        val next = SyncOrder[(SyncOrder.indexOf(state.value.of(place)) + by).mod(SyncOrder.size)]
        viewModelScope.launch { settings.set(place.key(profiles.activeProfileId().first()), next.code) }
    }
}

/**
 * What Live TV and On demand save to the server: one row each, its choice on the right and
 * what that choice does under the title. A tap or OK moves to the next choice (Status,
 * Progress, Both, Off, then round again); on TV left and right step either way. Then the
 * dashboard, kept on this device: turning it off asks whether to keep or delete its history.
 */
@Composable
fun WatchHistoryScreen(
    modifier: Modifier = Modifier,
    viewModel: WatchHistoryViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val isTv = LocalIsTv.current
    val firstFocus = remember { FocusRequester() }
    var asking by remember { mutableStateOf(false) }
    if (isTv) LaunchedEffect(state.loaded) { if (state.loaded) runCatching { firstFocus.requestFocus() } }

    Box(modifier) {
        SettingsPage(
            title = "Watch history",
            subtitle = "What Live TV and On Demand save to this profile's Jellyfin user, and what its dashboard keeps.",
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
            item(key = "dashboard") {
                val toggle = { if (state.dashboard) asking = true else viewModel.turnOnDashboard() }
                FocusTile(
                    title = "Dashboard",
                    subtitle = if (state.dashboard) "Your watch time and favorites, kept on this device." else "Nothing is kept for your dashboard.",
                    icon = Icons.Filled.InsertChart,
                    value = if (state.dashboard) "On" else "Off",
                    toggle = state.dashboard,
                    onClick = toggle,
                    onPrevious = if (isTv) toggle else null,
                    onNext = if (isTv) toggle else null,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        if (asking) {
            DashboardOffDialog(
                onKeep = { asking = false; viewModel.turnOffDashboard(keepHistory = true) },
                onDelete = { asking = false; viewModel.turnOffDashboard(keepHistory = false) },
                onDismiss = { asking = false },
            )
        }
    }
}
