package tv.anstelle.core.theme.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import tv.anstelle.core.theme.LocalAnstelleTheme

/** How much room a [RestrictedMark] has. */
enum class RestrictedSize { TILE, PANEL, SCREEN }

/**
 * What stands in for a program the profile may not watch, on the blacked-out ground where its
 * picture would be: a lock, [title] (AGE RESTRICTED, NO ACCESS) and [detail] ("Rated R-16 ·
 * Limit PG-13"). The lock carries the state, so it never rests on color alone.
 */
@Composable
fun RestrictedMark(
    title: String,
    detail: String?,
    modifier: Modifier = Modifier,
    size: RestrictedSize = RestrictedSize.PANEL,
    extra: String? = null,
    alignStart: Boolean = false,
) {
    val theme = LocalAnstelleTheme.current
    val typography = androidx.compose.material3.MaterialTheme.typography
    val titleStyle = when (size) {
        RestrictedSize.TILE -> typography.labelMedium
        RestrictedSize.PANEL -> typography.headlineSmall
        RestrictedSize.SCREEN -> typography.displayMedium
    }
    val icon = when (size) {
        RestrictedSize.TILE -> 14.dp
        RestrictedSize.PANEL -> 22.dp
        RestrictedSize.SCREEN -> 40.dp
    }
    Column(
        modifier,
        horizontalAlignment = if (alignStart) Alignment.Start else Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(if (size == RestrictedSize.SCREEN) 14.dp else 4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(if (size == RestrictedSize.SCREEN) 14.dp else 6.dp)) {
            androidx.compose.material3.Icon(Icons.Filled.Lock, contentDescription = null, tint = theme.textMuted, modifier = Modifier.size(icon))
            androidx.compose.material3.Text(title.uppercase(), style = titleStyle, color = if (size == RestrictedSize.TILE) theme.textMuted else theme.text, maxLines = 1)
        }
        detail?.let {
            androidx.compose.material3.Text(
                if (size == RestrictedSize.TILE) it.uppercase() else it,
                style = when (size) {
                    RestrictedSize.TILE -> typography.labelSmall
                    RestrictedSize.PANEL -> typography.bodyMedium
                    RestrictedSize.SCREEN -> typography.titleLarge
                },
                color = theme.textMuted,
                textAlign = if (alignStart) TextAlign.Start else TextAlign.Center,
                maxLines = if (size == RestrictedSize.TILE) 1 else 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        extra?.let { androidx.compose.material3.Text(it, style = typography.labelMedium, color = theme.textMuted, maxLines = 1) }
    }
}
