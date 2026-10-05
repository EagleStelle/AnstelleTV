package tv.anstelle.core.theme.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import tv.anstelle.core.theme.LocalAnstelleTheme
import tv.anstelle.core.theme.LocalIsTv

@Immutable
data class PlaceholderAction(
    val title: String,
    val subtitle: String?,
    val icon: ImageVector?,
    val onClick: () -> Unit,
)

/**
 * Shared layout for screens whose content arrives in a later phase: a header and a grid
 * of focusable tiles. On TV the first tile takes focus so the D-pad works immediately.
 */
@Composable
fun PlaceholderScreen(
    title: String,
    description: String,
    actions: List<PlaceholderAction>,
    modifier: Modifier = Modifier,
) {
    val isTv = LocalIsTv.current
    val theme = LocalAnstelleTheme.current
    val firstFocus = remember { FocusRequester() }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(theme.background)
            .padding(horizontal = if (isTv) 48.dp else 16.dp, vertical = if (isTv) 32.dp else 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (isTv) {
            androidx.tv.material3.Text(title, style = androidx.tv.material3.MaterialTheme.typography.headlineMedium)
            androidx.tv.material3.Text(
                description,
                style = androidx.tv.material3.MaterialTheme.typography.bodyLarge,
                color = theme.textMuted,
            )
        } else {
            androidx.compose.material3.Text(title, style = androidx.compose.material3.MaterialTheme.typography.headlineSmall)
            androidx.compose.material3.Text(
                description,
                style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                color = theme.textMuted,
            )
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(if (isTv) 240.dp else 160.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp),
        ) {
            itemsIndexed(actions, key = { _, action -> action.title }) { index, action ->
                FocusTile(
                    title = action.title,
                    subtitle = action.subtitle,
                    icon = action.icon,
                    onClick = action.onClick,
                    modifier = if (index == 0) Modifier.focusRequester(firstFocus) else Modifier,
                )
            }
        }
    }

    if (isTv && actions.isNotEmpty()) {
        LaunchedEffect(Unit) { runCatching { firstFocus.requestFocus() } }
    }
}
