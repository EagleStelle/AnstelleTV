package tv.anstelle.feature.profiles

import tv.anstelle.core.model.ProfileRole
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import tv.anstelle.core.theme.ControlShape
import tv.anstelle.core.theme.LocalAnstelleTheme
import tv.anstelle.core.theme.LocalIsTv
import tv.anstelle.core.theme.components.FocusSurface
import tv.anstelle.core.theme.components.OsdChip
import tv.anstelle.core.theme.components.quietInk

/** The tile's side on this device. */
internal val tileSize: Dp @Composable get() = if (LocalIsTv.current) 148.dp else 104.dp

/**
 * A profile on the picker: the Jellyfin user's picture on a square (the name's initial when
 * there is none), the name under it, a lock when a PIN is set and OWNER or ADMIN for those
 * roles. Focus fills the whole tile, caption included, so the picture sits in an accent frame.
 * [editing] marks the tile as opening the profile's settings instead of switching to it.
 */
@Composable
internal fun ProfileTile(
    name: String,
    picture: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    detail: String? = null,
    locked: Boolean = false,
    role: ProfileRole = ProfileRole.MEMBER,
    editing: Boolean = false,
) {
    val theme = LocalAnstelleTheme.current
    val isTv = LocalIsTv.current
    FocusSurface(onClick = onClick, color = androidx.compose.ui.graphics.Color.Transparent, modifier = modifier.width(tileSize)) { focused ->
        Column(Modifier.padding(4.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(
                Modifier.fillMaxWidth().aspectRatio(1f).background(if (focused) theme.onAccent else theme.surface, ControlShape),
                contentAlignment = Alignment.Center,
            ) {
                if (picture != null) {
                    AsyncImage(
                        model = picture,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.matchParentSize().clip(ControlShape),
                    )
                } else {
                    androidx.compose.material3.Text(
                        name.trim().take(1).uppercase(),
                        style = if (isTv) androidx.compose.material3.MaterialTheme.typography.displayLarge else androidx.compose.material3.MaterialTheme.typography.displayMedium,
                        color = if (focused) theme.accent else theme.text,
                    )
                }
                if (editing) {
                    androidx.compose.material3.Icon(
                        Icons.Filled.Edit,
                        contentDescription = null,
                        tint = if (focused) theme.accent else theme.text,
                        modifier = Modifier
                            .background(if (focused) theme.onAccent else theme.surface, ControlShape)
                            .padding(10.dp)
                            .size(if (isTv) 40.dp else 32.dp),
                    )
                }
                if (locked) {
                    androidx.compose.material3.Icon(
                        Icons.Filled.Lock,
                        contentDescription = "PIN",
                        tint = if (focused) theme.accent else theme.textMuted,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(6.dp)
                            .background(if (focused) theme.onAccent else theme.surface, ControlShape)
                            .padding(3.dp)
                            .size(16.dp),
                    )
                }
                if (role != ProfileRole.MEMBER) {
                    OsdChip(
                        role.name,
                        fill = if (focused) theme.accent else theme.accentContainer,
                        ink = if (focused) theme.onAccent else theme.onAccentContainer,
                        modifier = Modifier.align(Alignment.BottomStart).padding(8.dp),
                    )
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                androidx.compose.material3.Text(
                    name,
                    style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
                    color = if (focused) theme.onAccent else theme.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
                Row(Modifier.padding(bottom = 2.dp)) {
                    androidx.compose.material3.Text(
                        detail ?: " ",
                        style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                        color = quietInk(focused),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
