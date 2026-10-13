package tv.anstelle.core.theme.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import tv.anstelle.core.theme.LocalAnstelleTheme
import tv.anstelle.core.theme.LocalIsTv

private val HeldKeys = setOf(Key.DirectionCenter, Key.Enter, Key.NumPadEnter)

/** The D-pad and Tab moves that stay inside an open dialog. */
private val TrappedMoves = setOf(
    FocusDirection.Up, FocusDirection.Down, FocusDirection.Left, FocusDirection.Right,
    FocusDirection.Next, FocusDirection.Previous,
)

/**
 * One choice in an [ActionDialog]: what it says, one line on what happens, and what it does;
 * [danger] marks a destructive one, [selected] the one in use when the dialog picks a value.
 */
class DialogAction(
    val title: String,
    val detail: String?,
    val icon: ImageVector?,
    val danger: Boolean,
    val onClick: () -> Unit,
    val selected: Boolean = false,
) {
    constructor(title: String, detail: String, icon: ImageVector, onClick: () -> Unit) : this(title, detail, icon, false, onClick)

    companion object {
        /** A value to pick: its name, with a check while it is the one in use. */
        fun choice(title: String, selected: Boolean, onClick: () -> Unit) = DialogAction(title, null, null, false, onClick, selected)
    }
}

/**
 * A short list of choices (the ways to watch a program, the server a new profile signs in on)
 * in an OSD panel over the washed screen: [header] says what it is about, then one row per
 * action. Back or a tap outside closes it, and the D-pad never leaves it. The first action is
 * the likeliest: focus starts on it on TV, and touch screens fill it rose as the primary. When
 * the dialog picks a value, focus starts on the one in use and nothing is the primary.
 */
@Composable
fun ActionDialog(actions: List<DialogAction>, onDismiss: () -> Unit, header: @Composable ColumnScope.() -> Unit) {
    val theme = LocalAnstelleTheme.current
    val isTv = LocalIsTv.current
    val first = remember { FocusRequester() }
    // A dialog opened by holding OK appears while OK is still down; its repeats and release
    // must not reach the focused row, so the first choice waits for a fresh press.
    var armed by remember { mutableStateOf(false) }
    BackHandler(onBack = onDismiss)
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    // Its rows close with it: the page behind keeps the row the dialog was opened from as its place.
    CompositionLocalProvider(LocalPageFocus provides null) {
        Box(
            Modifier
                .fillMaxSize()
                .onPreviewKeyEvent { event ->
                    // Back closes in one press: left to the focus system, it would first only clear the focused row.
                    if (event.key == Key.Back) {
                        if (event.type == KeyEventType.KeyUp) onDismiss()
                        return@onPreviewKeyEvent true
                    }
                    if (armed || event.key !in HeldKeys) return@onPreviewKeyEvent false
                    val fresh = event.type == KeyEventType.KeyDown && event.nativeKeyEvent.repeatCount == 0
                    if (fresh) {
                        armed = true
                        false
                    } else {
                        if (event.type == KeyEventType.KeyUp) armed = true
                        true
                    }
                }
                .background(theme.background.copy(alpha = BACKDROP_WASH))
                .pointerInput(Unit) { detectTapGestures { onDismiss() } }
                // The page behind takes no focus while the dialog is up; closing it hands focus back by request.
                .focusProperties { onExit = { if (requestedFocusDirection in TrappedMoves) cancelFocusChange() } }
                .focusGroup(),
            contentAlignment = Alignment.Center,
        ) {
            OsdPanel(
                Modifier
                    .widthIn(max = 560.dp)
                    .padding(24.dp)
                    // Taps on the panel itself don't close it.
                    .pointerInput(Unit) { detectTapGestures { } }
                    // A long list of choices scrolls; focus brings the next one into view.
                    .verticalScroll(rememberScrollState()),
            ) {
                header()
                val picking = actions.any { it.selected }
                val lead = if (picking) actions.indexOfFirst { it.selected } else 0
                actions.forEachIndexed { i, action ->
                    FocusTile(
                        title = action.title,
                        subtitle = action.detail,
                        icon = action.icon,
                        onClick = action.onClick,
                        primary = i == 0 && !isTv && !action.danger && !picking,
                        danger = action.danger,
                        selected = action.selected,
                        modifier = Modifier.fillMaxWidth().let { if (i == lead) it.focusRequester(first) else it },
                    )
                }
            }
        }
    }
}
