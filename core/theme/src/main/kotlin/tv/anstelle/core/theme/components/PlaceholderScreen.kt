package tv.anstelle.core.theme.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
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
 * A screen with little to show yet: the channel bug, a title, what to do about it and the
 * actions that do it, as rows. On TV the first row takes focus so the D-pad works at once.
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
            .padding(horizontal = pagePadding, vertical = if (isTv) 40.dp else 24.dp),
        verticalArrangement = Arrangement.spacedBy(if (isTv) 20.dp else 16.dp),
    ) {
        AnstelleMark(Modifier.size(if (isTv) 56.dp else 44.dp))
        PageHeader(title, subtitle = description, modifier = Modifier.widthIn(max = 720.dp))
        Column(Modifier.widthIn(max = 720.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            actions.forEachIndexed { index, action ->
                FocusTile(
                    title = action.title,
                    subtitle = action.subtitle,
                    icon = action.icon,
                    onClick = action.onClick,
                    trailing = TileTrailing.Chevron,
                    modifier = Modifier.fillMaxWidth().let { if (index == 0) it.focusRequester(firstFocus) else it },
                )
            }
        }
    }

    if (isTv && actions.isNotEmpty()) {
        LaunchedEffect(Unit) { runCatching { firstFocus.requestFocus() } }
    }
}
