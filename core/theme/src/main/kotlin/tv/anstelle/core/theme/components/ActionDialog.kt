package tv.anstelle.core.theme.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import tv.anstelle.core.theme.LocalAnstelleTheme
import tv.anstelle.core.theme.LocalIsTv

/** One choice in an [ActionDialog]: what it says, one line on what happens, and what it does; [danger] marks a destructive one. */
class DialogAction(val title: String, val detail: String, val icon: ImageVector, val danger: Boolean, val onClick: () -> Unit) {
    constructor(title: String, detail: String, icon: ImageVector, onClick: () -> Unit) : this(title, detail, icon, false, onClick)
}

/**
 * A short list of choices (the ways to watch a program, the server a new profile signs in on)
 * in an OSD panel over the washed screen: [header] says what it is about, then one row per
 * action. Back or a tap outside closes it. The first action is the
 * likeliest: focus starts on it on TV, and touch screens fill it rose as the primary.
 */
@Composable
fun ActionDialog(actions: List<DialogAction>, onDismiss: () -> Unit, header: @Composable ColumnScope.() -> Unit) {
    val theme = LocalAnstelleTheme.current
    val isTv = LocalIsTv.current
    val first = remember { FocusRequester() }
    BackHandler(onBack = onDismiss)
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    // Its rows close with it: the page behind keeps the row the dialog was opened from as its place.
    CompositionLocalProvider(LocalPageFocus provides null) {
        Box(
            Modifier
                .fillMaxSize()
                .background(theme.background.copy(alpha = BACKDROP_WASH))
                .pointerInput(Unit) { detectTapGestures { onDismiss() } },
            contentAlignment = Alignment.Center,
        ) {
            OsdPanel(
                Modifier
                    .widthIn(max = 560.dp)
                    .padding(24.dp)
                    // Taps on the panel itself don't close it.
                    .pointerInput(Unit) { detectTapGestures { } },
            ) {
                header()
                actions.forEachIndexed { i, action ->
                    FocusTile(
                        title = action.title,
                        subtitle = action.detail,
                        icon = action.icon,
                        onClick = action.onClick,
                        primary = i == 0 && !isTv && !action.danger,
                        danger = action.danger,
                        modifier = Modifier.fillMaxWidth().let { if (i == 0) it.focusRequester(first) else it },
                    )
                }
            }
        }
    }
}
