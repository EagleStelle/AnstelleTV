package tv.anstelle.core.theme.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.unit.dp
import tv.anstelle.core.theme.LocalAnstelleTheme
import tv.anstelle.core.theme.LocalIsTv

/**
 * On TV, focuses the requester's element once it is laid out and the page is [ready] (its
 * rows loaded). Coming back to a page that keeps a [PageFocus], the row it was left on takes
 * focus instead.
 */
@Composable
fun rememberInitialFocus(ready: Boolean = true): FocusRequester {
    val requester = remember { FocusRequester() }
    val page = LocalPageFocus.current
    if (LocalIsTv.current) {
        LaunchedEffect(ready) {
            if (ready && page?.restore() != true) requester.requestWhenReady()
        }
    }
    return requester
}

/**
 * Requests focus over the next frames until it takes: a screen just launched or coming back
 * into view isn't attached, laid out or in a focused window on its first frame. Whether it
 * took is read by capturing focus, which only succeeds on the focused element, and freeing it.
 * False if it never took.
 */
suspend fun FocusRequester.requestWhenReady(frames: Int = 30): Boolean {
    repeat(frames) {
        withFrameNanos { }
        runCatching { requestFocus() }
        withFrameNanos { }
        if (runCatching { captureFocus() }.getOrDefault(false)) {
            freeFocus()
            return true
        }
    }
    return false
}

/** A line of status or help. A problem carries a mark as well. */
@Composable
fun Notice(text: String, modifier: Modifier = Modifier, isError: Boolean = false) {
    val theme = LocalAnstelleTheme.current
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = modifier.padding(vertical = 2.dp)) {
        if (isError) {
            androidx.compose.material3.Icon(
                Icons.Filled.ErrorOutline,
                contentDescription = "Problem",
                tint = theme.accent,
                modifier = Modifier.size(18.dp),
            )
        }
        androidx.compose.material3.Text(
            text,
            style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
            color = if (isError) theme.text else theme.textMuted,
        )
    }
}
