package tv.anstelle.feature.ondemand

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import tv.anstelle.core.theme.LocalAnstelleTheme
import tv.anstelle.core.theme.LocalIsTv
import tv.anstelle.core.theme.PanelShape
import tv.anstelle.core.theme.components.FocusSurface
import tv.anstelle.core.theme.components.OsdButton
import tv.anstelle.core.theme.components.requestWhenReady

/**
 * One subordinate description style for browsing banners and detail readouts. It is only as tall
 * as its text (up to [maxLines]), so whatever follows it, such as the progress ladder, sits right under it.
 */
@Composable
internal fun DescriptionText(text: String, maxLines: Int, modifier: Modifier = Modifier, color: Color = LocalAnstelleTheme.current.textMuted) {
    Text(text, modifier, style = MaterialTheme.typography.bodySmall, color = color,
        maxLines = maxLines, overflow = TextOverflow.Ellipsis)
}

/** A banner slide's overview, last in the slide's stack, which sits on the banner's foot. */
@Composable
internal fun BannerDescription(text: String) {
    DescriptionText(text, maxLines = if (LocalIsTv.current) 4 else 2, modifier = Modifier.widthIn(max = 620.dp))
}

/** How far a synopsis' words sit inside its focus fill, above and below. */
internal val SYNOPSIS_INSET = 4.dp

/** Collapsed to at most four lines, or what the readout leaves; touch expands inline, while a remote opens the complete text in a dialog. */
@Composable
internal fun Synopsis(text: String, modifier: Modifier = Modifier, navigation: EpisodeNavigation? = null) {
    val theme = LocalAnstelleTheme.current
    val isTv = LocalIsTv.current
    val focus = navigation?.description ?: remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    var expanded by rememberSaveable(text) { mutableStateOf(false) }
    var dialog by remember(text) { mutableStateOf(false) }
    val open = { if (isTv) dialog = true else expanded = !expanded }
    FocusSurface(
        onClick = open,
        color = Color.Transparent,
        modifier = modifier.fillMaxWidth().focusRequester(focus)
            .semantics {
                stateDescription = if (expanded) "Expanded" else "Collapsed"
                onClick(label = if (expanded) "Show less" else "Read full description") { open(); true }
            }
            .onPreviewKeyEvent { event ->
                if (!isTv || navigation == null || event.type != KeyEventType.KeyDown) false else when (event.key) {
                    Key.DirectionUp -> true
                    Key.DirectionDown -> { navigation.downFromDescription(); true }
                    else -> false
                }
            },
    ) { focused ->
        BoxWithConstraints(Modifier.padding(vertical = SYNOPSIS_INSET)) {
            val ink = if (focused) theme.onAccent else theme.textMuted
            val lineHeight = with(LocalDensity.current) { MaterialTheme.typography.bodySmall.lineHeight.toDp() }
            val lines = if (constraints.hasBoundedHeight) (maxHeight / lineHeight).toInt().coerceIn(1, 4) else 4
            if (expanded) Text(text, style = MaterialTheme.typography.bodySmall, color = ink)
            else DescriptionText(text, maxLines = lines, color = ink)
        }
    }
    if (dialog) {
        val closeFocus = remember { FocusRequester() }
        LaunchedEffect(Unit) { closeFocus.requestWhenReady() }
        val dismiss = {
            dialog = false
            scope.launch { focus.requestWhenReady() }
            Unit
        }
        AlertDialog(
            onDismissRequest = dismiss,
            shape = PanelShape,
            containerColor = theme.surface,
            titleContentColor = theme.text,
            textContentColor = theme.text,
            title = { Text("Description", style = MaterialTheme.typography.titleLarge) },
            text = { Text(text, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) },
            confirmButton = { OsdButton("Close", onClick = dismiss, modifier = Modifier.focusRequester(closeFocus)) },
        )
    }
}
