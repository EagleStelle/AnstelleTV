package tv.anstelle.feature.ondemand

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Tv
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import tv.anstelle.core.theme.components.PlaceholderAction
import tv.anstelle.core.theme.components.PlaceholderScreen

/** Movies and TV browsing (Phase 6). */
@Composable
fun OnDemandScreen(modifier: Modifier = Modifier) {
    val actions = remember {
        listOf(
            PlaceholderAction("Movies", "Browse your movie libraries", Icons.Filled.Movie) {},
            PlaceholderAction("TV Shows", "Browse series and episodes", Icons.Filled.Tv) {},
        )
    }
    PlaceholderScreen(
        title = "On demand",
        description = "Start from the beginning or watch from here, straight from your libraries.",
        actions = actions,
        modifier = modifier,
    )
}
