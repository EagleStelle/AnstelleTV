package tv.anstelle.feature.ondemand

import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch
import tv.anstelle.core.theme.LocalAnstelleTheme
import tv.anstelle.core.theme.LocalIsTv
import tv.anstelle.core.theme.components.ActionDialog
import tv.anstelle.core.theme.components.DialogAction
import tv.anstelle.core.theme.components.FocusSurface
import tv.anstelle.core.theme.components.requestWhenReady

/**
 * The one description style, on browsing banners and detail readouts alike: the app's reading
 * body, since a synopsis is read from the couch in the muted ink. Banner and readout sizes,
 * line counts and the readout's budget all follow it.
 */
internal val descriptionStyle: TextStyle
    @Composable @ReadOnlyComposable get() = MaterialTheme.typography.bodyMedium

/**
 * A description in [descriptionStyle], only as tall as its text (up to [maxLines]), so whatever
 * follows it, such as the progress ladder, sits right under it.
 */
@Composable
internal fun DescriptionText(
    text: String,
    maxLines: Int,
    modifier: Modifier = Modifier,
    color: Color = LocalAnstelleTheme.current.textMuted,
    onTextLayout: (TextLayoutResult) -> Unit = {},
) {
    Text(text, modifier, style = descriptionStyle, color = color,
        maxLines = maxLines, overflow = TextOverflow.Ellipsis, onTextLayout = onTextLayout)
}

/** A banner slide's overview, last in the slide's stack, which sits on the banner's foot. */
@Composable
internal fun BannerDescription(text: String) {
    DescriptionText(text, maxLines = if (LocalIsTv.current) 4 else 2, modifier = Modifier.widthIn(max = 620.dp))
}

/** How far a synopsis' words sit inside its focus fill, above and below. */
internal val SYNOPSIS_INSET = 4.dp

/**
 * Collapsed to at most four lines, or what the readout leaves. Cut short, it ends on an expand
 * chevron and opens: touch expands it inline, while a remote opens the complete text in a dialog.
 * Text that fits whole takes no focus or tap.
 */
@Composable
internal fun Synopsis(text: String, modifier: Modifier = Modifier, navigation: EpisodeNavigation? = null) {
    val theme = LocalAnstelleTheme.current
    val isTv = LocalIsTv.current
    val focus = navigation?.description ?: remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    var expanded by rememberSaveable(text) { mutableStateOf(false) }
    var clipped by remember(text) { mutableStateOf(false) }
    var dialog by remember(text) { mutableStateOf(false) }
    val opens = clipped || expanded
    DisposableEffect(navigation, opens) {
        navigation?.descriptionOpens = opens
        onDispose { navigation?.descriptionOpens = false }
    }
    val open = { if (isTv) dialog = true else expanded = !expanded }
    val words: @Composable (focused: Boolean) -> Unit = { focused ->
        BoxWithConstraints(Modifier.padding(vertical = SYNOPSIS_INSET)) {
            val ink = if (focused) theme.onAccent else theme.textMuted
            val lineHeight = with(LocalDensity.current) { descriptionStyle.lineHeight.toDp() }
            val lines = if (constraints.hasBoundedHeight) (maxHeight / lineHeight).toInt().coerceIn(1, 4) else 4
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                val fill = Modifier.weight(1f)
                if (expanded) Text(text, fill, style = descriptionStyle, color = ink)
                else DescriptionText(text, maxLines = lines, modifier = fill, color = ink, onTextLayout = { clipped = it.hasVisualOverflow })
                if (opens) Icon(
                    if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = null,
                    tint = ink,
                    modifier = Modifier.size(lineHeight),
                )
            }
        }
    }
    // Text that fits whole is only text: nothing to focus, tap or press.
    if (!opens) Box(modifier.fillMaxWidth()) { words(false) }
    else FocusSurface(
        onClick = open,
        color = Color.Transparent,
        modifier = modifier.fillMaxWidth().focusRequester(focus)
            .semantics {
                // A remote opens a dialog, so only touch has a collapsed and an expanded state.
                if (!isTv) stateDescription = if (expanded) "Expanded" else "Collapsed"
                onClick(label = if (expanded) "Show less" else "Read full description") { open(); true }
            }
            .onPreviewKeyEvent { event ->
                if (!isTv || navigation == null || event.type != KeyEventType.KeyDown) false else when (event.key) {
                    Key.DirectionUp -> true
                    Key.DirectionDown -> { navigation.downFromDescription(); true }
                    else -> false
                }
            },
    ) { focused -> words(focused) }
    if (dialog) {
        val scroll = rememberScrollState()
        val dismiss = {
            dialog = false
            scope.launch { focus.requestWhenReady() }
            Unit
        }
        Dialog(onDismissRequest = dismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
            // Focus stays on Close, where the remote can see it; Up and Down page through the text instead.
            Box(Modifier.onPreviewKeyEvent { event ->
                val step = when (event.key) {
                    Key.DirectionDown -> 1
                    Key.DirectionUp -> -1
                    else -> return@onPreviewKeyEvent false
                }
                if (event.type == KeyEventType.KeyDown) scope.launch {
                    scroll.animateScrollTo((scroll.value + step * scroll.viewportSize * 3 / 4).coerceIn(0, scroll.maxValue), tween(PAGE_MS))
                }
                true
            }) {
                ActionDialog(listOf(DialogAction("Close", null, null, false, onClick = dismiss)), onDismiss = dismiss) {
                    Text("Description", style = MaterialTheme.typography.headlineSmall, color = theme.text)
                    Text(text, style = descriptionStyle, color = theme.text,
                        modifier = Modifier.heightIn(max = 320.dp).verticalScroll(scroll))
                }
            }
        }
    }
}

/** One page of a description dialog's text: the OSD's short hard snap. */
private const val PAGE_MS = 160
