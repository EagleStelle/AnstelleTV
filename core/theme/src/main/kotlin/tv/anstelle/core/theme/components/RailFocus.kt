package tv.anstelle.core.theme.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type

/** Explicit focus bridge between the app rail and a screen's primary controls. */
class RailFocus {
    /** The selected rail tab's focus target. */
    val rail = FocusRequester()
    private var target: FocusRequester? by mutableStateOf(null)
    var content: FocusRequester?
        get() = target
        set(value) {
            target = value
            onEnterContent = null
        }
    /** Reveals a lazy screen's entry controls before requesting their focus. */
    var onEnterContent: (() -> Unit)? = null
}

val LocalRailFocus = staticCompositionLocalOf<RailFocus?> { null }

/** A lazy page can reveal its controls before focus leaves the rail. */
fun Modifier.enterContentFromRail(railFocus: RailFocus): Modifier = onPreviewKeyEvent { event ->
    if (event.key != Key.DirectionRight) return@onPreviewKeyEvent false
    val enter = railFocus.onEnterContent
    val target = railFocus.content
    if (enter == null && target == null) return@onPreviewKeyEvent false
    if (event.type == KeyEventType.KeyDown) {
        if (enter != null) enter() else target?.requestFocus()
    }
    true
}

/** Routes a D-pad key directly, consuming it before spatial focus search can choose another group. */
fun Modifier.focusOnKey(key: Key, target: () -> FocusRequester?): Modifier =
    onPreviewKeyEvent { event ->
        if (event.key != key) return@onPreviewKeyEvent false
        val requester = target() ?: return@onPreviewKeyEvent false
        if (event.type == KeyEventType.KeyDown) requester.requestFocus()
        true
    }
