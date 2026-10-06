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
/** [channelNumber] 0 resumes the channel watched last. */
@Serializable data class PlayerRoute(val channelNumber: Int = 0)

/** Settings and the screens below it, so the Settings tab stays selected on all of them. */
@Serializable data object SettingsGraph
@Serializable data object SettingsRoute
@Serializable data object ServersRoute
@Serializable data class ServerDetailRoute(val serverId: Long)

/** [serverId] 0 adds a new server; otherwise signs in to that server again. */
@Serializable data class AddServerRoute(val serverId: Long = 0)

@Serializable data object ChannelsRoute
@Serializable data class ChannelDetailRoute(val channelId: Long)

/** [channelId] 0 builds a new channel. */
@Serializable data class ChannelLineupRoute(val channelId: Long = 0)
@Serializable data object ChannelTemplatesRoute
@Serializable data object ChannelGroupsRoute

/** Destinations reachable from the TV navigation drawer and the phone navigation bar. */
enum class TopLevelDestination(val label: String, val icon: ImageVector, val route: Any) {
    Guide("Guide", Icons.Filled.LiveTv, GuideRoute),
    OnDemand("On demand", Icons.Filled.Movie, OnDemandRoute),
    Dashboard("Dashboard", Icons.Filled.Dashboard, DashboardRoute),
    Settings("Settings", Icons.Filled.Settings, SettingsGraph),
    Profiles("Profiles", Icons.Filled.People, ProfilesRoute),
}
