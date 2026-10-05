package tv.anstelle.feature.guide

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import tv.anstelle.core.theme.components.PlaceholderAction
import tv.anstelle.core.theme.components.PlaceholderScreen

/**
 * Channel guide. The virtualized EPG grid, preview window and details panel arrive in
 * Phase 6; for now it links to the other screens so navigation can be exercised.
 */
@Composable
fun GuideScreen(
    onWatchChannel: (channelNumber: Int) -> Unit,
    onOpenOnDemand: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val actions = remember(onWatchChannel, onOpenOnDemand, onOpenSettings) {
        listOf(
            PlaceholderAction("Watch channel 2", "Tune in full screen", Icons.Filled.LiveTv) { onWatchChannel(2) },
            PlaceholderAction("Favorites", "Starred channels", Icons.Filled.Star) { onWatchChannel(3) },
            PlaceholderAction("On demand", "Movies and TV shows", Icons.Filled.Movie, onOpenOnDemand),
            PlaceholderAction("Settings", "Servers, web control", Icons.Filled.Settings, onOpenSettings),
        )
    }
    PlaceholderScreen(
        title = "Guide",
        description = "Your channels will appear here once a Jellyfin server is connected.",
        actions = actions,
        modifier = modifier,
    )
}
