package tv.anstelle.core.theme.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import tv.anstelle.core.theme.LocalAnstelleTheme
import tv.anstelle.core.theme.LocalIsTv

private val TileShape = RoundedCornerShape(12.dp)

/**
 * A clickable tile that works with both D-pad and touch: on TV it is a Compose-for-TV
 * surface with focus scale and border, on phones a Material 3 surface.
 */
@Composable
fun FocusTile(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
) {
    if (LocalIsTv.current) {
        val theme = LocalAnstelleTheme.current
        androidx.tv.material3.Surface(
            onClick = onClick,
            modifier = modifier,
            shape = ClickableSurfaceDefaults.shape(TileShape),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = theme.surface,
                contentColor = theme.text,
                focusedContainerColor = theme.guideCellFocused,
                focusedContentColor = theme.text,
            ),
            border = ClickableSurfaceDefaults.border(
                focusedBorder = Border(androidx.compose.foundation.BorderStroke(2.dp, theme.focus)),
            ),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
        ) {
            TileContent(title, subtitle, icon, tv = true)
        }
    } else {
        androidx.compose.material3.Surface(
            onClick = onClick,
            modifier = modifier,
            shape = TileShape,
            color = LocalAnstelleTheme.current.surface,
        ) {
            TileContent(title, subtitle, icon, tv = false)
        }
    }
}

@Composable
private fun TileContent(title: String, subtitle: String?, icon: ImageVector?, tv: Boolean) {
    Column(
        modifier = Modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (icon != null) {
            if (tv) {
                androidx.tv.material3.Icon(icon, contentDescription = null, modifier = Modifier.size(32.dp))
            } else {
                androidx.compose.material3.Icon(icon, contentDescription = null, modifier = Modifier.size(28.dp))
            }
        }
        if (tv) {
            androidx.tv.material3.Text(
                title,
                style = androidx.tv.material3.MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                androidx.tv.material3.Text(
                    subtitle,
                    style = androidx.tv.material3.MaterialTheme.typography.bodyMedium,
                    color = LocalAnstelleTheme.current.textMuted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        } else {
            androidx.compose.material3.Text(
                title,
                style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                androidx.compose.material3.Text(
                    subtitle,
                    style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                    color = LocalAnstelleTheme.current.textMuted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
