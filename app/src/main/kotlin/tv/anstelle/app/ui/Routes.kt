package tv.anstelle.app.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.graphics.vector.ImageVector
import kotlinx.serialization.Serializable

@Serializable data object ProfilesRoute
@Serializable data object GuideRoute
@Serializable data object OnDemandRoute
@Serializable data object DashboardRoute
@Serializable data object SettingsRoute
@Serializable data class PlayerRoute(val channelNumber: Int)

/** Destinations reachable from the TV navigation drawer and the phone navigation bar. */
enum class TopLevelDestination(val label: String, val icon: ImageVector, val route: Any) {
    Guide("Guide", Icons.Filled.LiveTv, GuideRoute),
    OnDemand("On demand", Icons.Filled.Movie, OnDemandRoute),
    Dashboard("Dashboard", Icons.Filled.Dashboard, DashboardRoute),
    Settings("Settings", Icons.Filled.Settings, SettingsRoute),
    Profiles("Profiles", Icons.Filled.People, ProfilesRoute),
}
