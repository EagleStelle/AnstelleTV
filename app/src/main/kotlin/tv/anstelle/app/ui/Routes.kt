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
@Serializable data object DashboardRoute
/** [channelNumber] 0 resumes the channel watched last. */
/** No channel asked for: the player resumes the last one. 0 is a channel number like any other. */
const val PlayerRouteNoChannel = -1

@Serializable data class PlayerRoute(val channelNumber: Int = PlayerRouteNoChannel)

/** On-demand browsing and the pages below it, so the On demand tab stays selected on all of them. */
@Serializable data object OnDemandGraph
@Serializable data object OnDemandRoute
@Serializable data class OnDemandShowRoute(val seriesId: Long)
@Serializable data class OnDemandItemRoute(val itemId: Long)

/** Full-screen on-demand playback of [itemId] from [positionMs]. */
@Serializable data class OnDemandPlayRoute(val itemId: Long, val positionMs: Long = 0)

/** Makes a channel always air the show (or movie) of [itemId]. */
@Serializable data class AddToChannelRoute(val itemId: Long)

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
@Serializable data object RemovedChannelsRoute
@Serializable data object ChannelNumbersRoute
@Serializable data object ChannelGroupsRoute
@Serializable data object PlaybackLanguagesRoute

/** Adds a language to the subtitle list when [subtitles], else to the audio list. */
@Serializable data class LanguagePickerRoute(val subtitles: Boolean)

/** Destinations reachable from the TV navigation drawer and the phone navigation bar. */
enum class TopLevelDestination(val label: String, val icon: ImageVector, val route: Any) {
    Guide("Guide", Icons.Filled.LiveTv, GuideRoute),
    OnDemand("On demand", Icons.Filled.Movie, OnDemandGraph),
    Dashboard("Dashboard", Icons.Filled.Dashboard, DashboardRoute),
    Settings("Settings", Icons.Filled.Settings, SettingsGraph),
    Profiles("Profiles", Icons.Filled.People, ProfilesRoute),
}
