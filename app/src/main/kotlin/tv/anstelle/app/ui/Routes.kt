package tv.anstelle.app.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.LiveTv
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.People
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.ui.graphics.vector.ImageVector
import kotlinx.serialization.Serializable

/** Who's watching. [atLaunch] is the full-screen picker the app opens with, where every PIN is asked. */
@Serializable data class ProfilesRoute(val atLaunch: Boolean = false)
@Serializable data class ProfileEditRoute(val profileId: Long)
@Serializable data object GuideRoute
@Serializable data object DashboardRoute
/** No channel asked for: the player resumes the last one. 0 is a channel number like any other. */
const val PlayerRouteNoChannel = -1

/** [restart] takes the program on now back to its start once the channel tunes (Start over). */
@Serializable data class PlayerRoute(val channelNumber: Int = PlayerRouteNoChannel, val restart: Boolean = false)

/** On demand and the channel pages below it, so the On demand tab stays selected on all of them. */
@Serializable data object OnDemandGraph
@Serializable data object OnDemandRoute
@Serializable data class OnDemandChannelRoute(val channelId: Long)

/** Full-screen on-demand playback of [itemId] from [positionMs]. */
@Serializable data class OnDemandPlayRoute(val itemId: Long, val positionMs: Long = 0)

/** Makes a channel always air the show (or movie) of [itemId]. */
@Serializable data class AddToChannelRoute(val itemId: Long)

/** Settings and the screens below it, so the Settings tab stays selected on all of them. */
@Serializable data object SettingsGraph
@Serializable data object SettingsRoute
@Serializable data object ServersRoute
@Serializable data class ServerDetailRoute(val serverId: Long)

/**
 * [serverId] 0 adds a new server; otherwise signs in to that server again. With
 * [linkProfileId], that profile signs in as its own Jellyfin user instead; with
 * [newProfile], signing in makes a profile from the user.
 */
@Serializable data class AddServerRoute(val serverId: Long = 0, val linkProfileId: Long = 0, val newProfile: Boolean = false)

@Serializable data object ChannelsRoute
@Serializable data class ChannelDetailRoute(val channelId: Long)

/** [channelId] 0 builds a new channel. */
@Serializable data class ChannelLineupRoute(val channelId: Long = 0)
@Serializable data object ChannelGroupsRoute
@Serializable data object PlaybackLanguagesRoute
@Serializable data object WatchHistoryRoute
@Serializable data object WebControlRoute

/** [firstRun]: opened before any profile exists, to copy a backup in instead of adding a server. */
@Serializable data class BackupRoute(val firstRun: Boolean = false)

/** The themes [profileId] can wear; 0 for the profile watching. */
@Serializable data class ThemesRoute(val profileId: Long = 0)
@Serializable data class ThemeEditorRoute(val themeId: Long)

/** Adds a language to the subtitle list when [subtitles], else to the audio list. */
@Serializable data class LanguagePickerRoute(val subtitles: Boolean)

/**
 * Destinations reachable from the TV navigation drawer and the phone navigation bar. Phones
 * show the filled [icon] for the open destination and the [outlinedIcon] for the rest.
 */
enum class TopLevelDestination(val label: String, val icon: ImageVector, val outlinedIcon: ImageVector, val route: Any) {
    Guide("Live TV", Icons.Filled.LiveTv, Icons.Outlined.LiveTv, GuideRoute),
    OnDemand("On Demand", Icons.Filled.Movie, Icons.Outlined.Movie, OnDemandGraph),
    Dashboard("Dashboard", Icons.Filled.Dashboard, Icons.Outlined.Dashboard, DashboardRoute),
    Settings("Settings", Icons.Filled.Settings, Icons.Outlined.Settings, SettingsGraph),
    Profiles("Profiles", Icons.Filled.People, Icons.Outlined.People, ProfilesRoute()),
}
