package tv.anstelle.feature.settings.playback

import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.InsertChart
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import tv.anstelle.core.data.ProfileRepository
import tv.anstelle.core.data.SettingKey
import tv.anstelle.core.data.Settings
import tv.anstelle.core.data.SettingsRepository
import tv.anstelle.core.data.stats.DashboardHistory
import tv.anstelle.core.model.WatchSync
import tv.anstelle.core.model.codeOf
import tv.anstelle.core.theme.components.ChoiceRow
import tv.anstelle.core.theme.components.SettingsPage
import tv.anstelle.core.theme.components.SettingsNotice
import tv.anstelle.core.theme.components.SwitchRow
import tv.anstelle.core.theme.components.WatchIcons
import tv.anstelle.core.theme.components.rememberInitialFocus
import tv.anstelle.core.theme.components.section
import tv.anstelle.feature.dashboard.DashboardOffDialog
import tv.anstelle.feature.settings.activeProfileId
import javax.inject.Inject

/** Where playback happens, each with its own [WatchSync] setting for each profile. */
enum class SyncPlace(val title: String, val icon: ImageVector, val key: (profileId: Long) -> SettingKey.IntKey) {
    LIVE_TV("Live TV", WatchIcons.Live, Settings::liveTvSync),
    ON_DEMAND("On Demand", WatchIcons.OnDemand, Settings::onDemandSync),
}

@Immutable
data class WatchHistoryUiState(
    val liveTv: WatchSync = codeOf(Settings.liveTvSync(0).default),
    val onDemand: WatchSync = codeOf(Settings.onDemandSync(0).default),
    /** Whether the profile keeps what it watches for its dashboard. */
    val dashboard: Boolean = Settings.dashboard(0).default,
    val loaded: Boolean = false,
    val reconnect: Boolean = false,
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
            profiles.observeAccounts(profileId),
        ) { live, onDemand, dashboard, accounts ->
            WatchHistoryUiState(codeOf(live), codeOf(onDemand), dashboard, loaded = true,
                reconnect = (live != WatchSync.OFF.code || onDemand != WatchSync.OFF.code) &&
                    (accounts.isEmpty() || accounts.any { !it.signedIn }))
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WatchHistoryUiState())

    fun turnOnDashboard() {
        viewModelScope.launch { history.turnOn(profiles.activeProfileId().first()) }
    }

    fun turnOffDashboard(keepHistory: Boolean) {
        viewModelScope.launch { history.turnOff(profiles.activeProfileId().first(), keepHistory) }
    }

    fun set(place: SyncPlace, sync: WatchSync) {
        viewModelScope.launch { settings.set(place.key(profiles.activeProfileId().first()), sync.code) }
    }
}

/**
 * What Live TV and On Demand save to the profile's Jellyfin user, each a choice with what it does
 * under every option; then the dashboard, kept on this device. Turning it off asks whether to
 * keep or delete its history.
 */
@Composable
fun WatchHistoryScreen(
    modifier: Modifier = Modifier,
    viewModel: WatchHistoryViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val firstFocus = rememberInitialFocus(ready = state.loaded)
    var asking by remember { mutableStateOf(false) }

    Box(modifier) {
        SettingsPage(
            title = "Watch history",
            trail = listOf("Settings"),
            about = "Sent to your Jellyfin server. The dashboard stays on this device.",
        ) {
            section("Jellyfin")
            if (state.reconnect) {
                item(key = "reconnect") {
                    SettingsNotice("History sync needs a sign-in. Open your profile's Jellyfin account to reconnect.", isError = true)
                }
            }
            for (place in SyncPlace.entries) {
                item(key = place.name) {
                    ChoiceRow(
                        title = place.title,
                        value = state.of(place),
                        choices = SyncOrder,
                        label = ::syncName,
                        detail = { syncDescription(place, it) },
                        about = "What ${place.title} saves to your Jellyfin user.",
                        onSelect = { viewModel.set(place, it) },
                        icon = place.icon,
                        modifier = if (place == SyncPlace.entries.first()) Modifier.focusRequester(firstFocus) else Modifier,
                    )
                }
            }
            section("This device")
            item(key = "dashboard") {
                SwitchRow(
                    title = "Dashboard",
                    checked = state.dashboard,
                    onCheckedChange = { on -> if (on) viewModel.turnOnDashboard() else asking = true },
                    icon = Icons.Filled.InsertChart,
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
