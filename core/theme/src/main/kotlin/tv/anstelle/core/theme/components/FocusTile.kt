package tv.anstelle.core.theme.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import tv.anstelle.core.theme.ControlShape
import tv.anstelle.core.theme.LocalAnstelleTheme
import tv.anstelle.core.theme.LocalIsTv

/**
 * The one clickable surface every control is built from, for D-pad and touch alike. Focus
 * is a fill: the surface turns [focusColor] and its content [focusContentColor], with no
 * zoom, glow or outline. Touch gets a ripple; the D-pad gets the fill. [content] receives
 * whether the surface is focused, for text that has to change with it.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FocusSurface(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    color: Color = LocalAnstelleTheme.current.surface,
    contentColor: Color = LocalAnstelleTheme.current.text,
    focusColor: Color = LocalAnstelleTheme.current.accent,
    focusContentColor: Color = LocalAnstelleTheme.current.onAccent,
    shape: Shape = ControlShape,
    content: @Composable BoxScope.(focused: Boolean) -> Unit,
) {
    val theme = LocalAnstelleTheme.current
    val isTv = LocalIsTv.current
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val pressed by interaction.collectIsPressedAsState()
    val container = when {
        focused -> focusColor
        pressed && !isTv -> theme.surfaceHigh.takeIf { color == theme.surface } ?: color
        else -> color
    }
    val foreground = when {
        focused -> focusContentColor
        !enabled -> theme.textMuted
        else -> contentColor
    }
    Box(
        modifier
            .clip(shape)
            .background(container)
            .combinedClickable(
                interactionSource = interaction,
                indication = if (isTv) null else ripple(color = contentColor),
                enabled = enabled,
                onLongClick = onLongClick,
                onClick = onClick,
            ),
    ) {
        CompositionLocalProvider(
            androidx.compose.material3.LocalContentColor provides foreground,
            androidx.tv.material3.LocalContentColor provides foreground,
        ) { content(focused) }
    }
}

/** What sits at the end of a [FocusTile]. */
enum class TileTrailing { None, Chevron }

/**
 * A menu row, the TV's setup-menu line: icon, title and an optional description on the
 * left, the current [value] on the right. With [onPrevious] and [onNext] the value is a
 * choice that left and right step through on the D-pad (OK steps forward too); [selected]
 * marks the chosen item of a list with a check. A [toggle] shows as a switch on touch
 * screens and as its [value] (On, Off) on TV.
 */
@Composable
fun FocusTile(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    value: String? = null,
    toggle: Boolean? = null,
    selected: Boolean = false,
    trailing: TileTrailing = TileTrailing.None,
    enabled: Boolean = true,
    onLongClick: (() -> Unit)? = null,
    onPrevious: (() -> Unit)? = null,
    onNext: (() -> Unit)? = null,
) {
    val theme = LocalAnstelleTheme.current
    val isTv = LocalIsTv.current
    val stepping = onPrevious != null && onNext != null
    FocusSurface(
        onClick = onClick,
        onLongClick = onLongClick,
        enabled = enabled,
        modifier = if (stepping) {
            modifier.onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionLeft -> onPrevious!!().let { true }
                    Key.DirectionRight -> onNext!!().let { true }
                    else -> false
                }
            }
        } else {
            modifier
        },
    ) { focused ->
        val muted = if (focused) theme.onAccent.copy(alpha = 0.76f) else theme.textMuted
        Row(
            Modifier
                .defaultMinSize(minHeight = if (isTv) 64.dp else 56.dp)
                .padding(horizontal = if (isTv) 20.dp else 16.dp, vertical = if (isTv) 12.dp else 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(if (isTv) 16.dp else 14.dp),
        ) {
            if (icon != null) {
                androidx.compose.material3.Icon(
                    icon,
                    contentDescription = null,
                    tint = if (selected && !focused) theme.accent else androidx.compose.material3.LocalContentColor.current,
                    modifier = Modifier.size(if (isTv) 26.dp else 22.dp),
                )
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                androidx.compose.material3.Text(
                    title,
                    style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
                    color = if (selected && !focused) theme.accent else androidx.compose.material3.LocalContentColor.current,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle != null) {
                    androidx.compose.material3.Text(
                        subtitle,
                        style = if (isTv) {
                            androidx.compose.material3.MaterialTheme.typography.bodyMedium
                        } else {
                            androidx.compose.material3.MaterialTheme.typography.bodySmall
                        },
                        color = muted,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (toggle != null && !isTv) {
                // Touch gets the platform switch; the row's click flips it, so the switch itself takes no input.
                androidx.compose.material3.Switch(
                    checked = toggle,
                    onCheckedChange = null,
                    colors = androidx.compose.material3.SwitchDefaults.colors(
                        checkedThumbColor = theme.onAccent,
                        checkedTrackColor = theme.accent,
                        checkedBorderColor = theme.accent,
                        uncheckedThumbColor = theme.textMuted,
                        uncheckedTrackColor = theme.surfaceHigh,
                        uncheckedBorderColor = theme.textMuted,
                    ),
                )
            } else if (value != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // The arrows say left and right step the value: a D-pad promise, so TV only.
                    if (stepping && focused && isTv) {
                        androidx.compose.material3.Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = null, modifier = Modifier.size(18.dp))
                    }
                    androidx.compose.material3.Text(
                        value,
                        style = androidx.compose.material3.MaterialTheme.typography.labelLarge,
                        color = if (focused) theme.onAccent else theme.text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (stepping && focused && isTv) {
                        androidx.compose.material3.Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, modifier = Modifier.size(18.dp))
                    }
                }
            }
            when {
                selected -> androidx.compose.material3.Icon(
                    Icons.Filled.Check,
                    contentDescription = "Selected",
                    tint = if (focused) theme.onAccent else theme.accent,
                    modifier = Modifier.size(20.dp),
                )
                trailing == TileTrailing.Chevron -> androidx.compose.material3.Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = muted,
                    modifier = Modifier.size(20.dp),
                )
                else -> Unit
            }
        }
    }
}

/**
 * A button: [primary] is the screen's one main action (rose, turning bone when focused so
 * focus still shows); the rest sit on the surface step.
 */
@Composable
fun OsdButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    primary: Boolean = false,
    enabled: Boolean = true,
) {
    val theme = LocalAnstelleTheme.current
    val isTv = LocalIsTv.current
    FocusSurface(
        onClick = onClick,
        enabled = enabled,
        color = if (primary) theme.accent else theme.surface,
        contentColor = if (primary) theme.onAccent else theme.text,
        focusColor = if (primary) theme.text else theme.accent,
        focusContentColor = theme.onAccent,
        modifier = modifier.then(if (primary) Modifier else Modifier.border(1.dp, theme.line, ControlShape)),
    ) {
        Row(
            Modifier
                .defaultMinSize(minHeight = if (isTv) 52.dp else 48.dp)
                .padding(horizontal = if (isTv) 24.dp else 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (icon != null) androidx.compose.material3.Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
            androidx.compose.material3.Text(text, style = androidx.compose.material3.MaterialTheme.typography.labelLarge, maxLines = 1)
        }
    }
}
