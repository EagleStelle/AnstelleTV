package tv.anstelle.core.theme.components

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type

/**
 * One step back per press of Back, from a remote or the system, while [enabled]. Left alone, the
 * focus system would spend a remote's key-down moving the highlight off its key, so the key is
 * taken from it. Android 16 still hands the press to [BackHandler] at key-up and then delivers the
 * key-up as well; older versions only deliver the key: [onBack] runs once, from whichever comes
 * first. Put the returned modifier on an element the focus sits inside.
 */
@Composable
fun rememberBackPress(enabled: Boolean = true, onBack: () -> Unit): Modifier {
    val back by rememberUpdatedState(onBack)
    // A remote's Back went down here and has not stepped back yet.
    var pressed by remember { mutableStateOf(false) }
    BackHandler(enabled) {
        pressed = false
        back()
    }
    return Modifier.onPreviewKeyEvent { event ->
        if (!enabled || event.key != Key.Back) return@onPreviewKeyEvent false
        if (event.type == KeyEventType.KeyDown) {
            pressed = true
        } else if (event.type == KeyEventType.KeyUp && pressed) {
            pressed = false
            back()
        }
        true
    }
}
