package tv.anstelle.core.theme.components

import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
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
 * A menu page, the one every settings screen uses: a header, then menu rows. On wide screens
 * the column stops at a readable width instead of stretching rows across the TV. The header
 * stays put while the rows scroll, so it is never left half clipped; phones get a back bar in
 * its place, TV has the remote's Back.
 */
@Composable
fun MenuPage(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    listState: LazyListState = rememberLazyListState(),
    content: LazyListScope.() -> Unit,
) {
    val theme = LocalAnstelleTheme.current
    val isTv = LocalIsTv.current
    val back = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
    Column(modifier.fillMaxSize().background(theme.background)) {
        if (isTv) {
            PageHeader(title, subtitle = subtitle, modifier = Modifier.widthIn(max = 960.dp).padding(start = pagePadding, end = pagePadding, top = 32.dp, bottom = 10.dp))
        } else {
            BackBar(onBack = { back?.onBackPressed() }, title = title)
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.widthIn(max = 960.dp).weight(1f),
            contentPadding = PaddingValues(start = pagePadding, end = pagePadding, top = if (isTv) 4.dp else 0.dp, bottom = if (isTv) 32.dp else 16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (!isTv && subtitle != null) {
                item(key = "subtitle") {
                    androidx.compose.material3.Text(
                        subtitle,
                        style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                        color = theme.textMuted,
                        modifier = Modifier.padding(bottom = 10.dp),
                    )
                }
            }
            content()
        }
    }
}

/** On TV, focuses the requester's element once it is laid out. */
@Composable
fun rememberInitialFocus(): FocusRequester {
    val requester = remember { FocusRequester() }
    if (LocalIsTv.current) LaunchedEffect(Unit) { requester.requestWhenReady() }
    return requester
}

/**
 * Requests focus over the next frames until it takes: a screen just launched or coming back
 * into view isn't attached, laid out or in a focused window on its first frame. Whether it
 * took is read by capturing focus, which only succeeds on the focused element, and freeing it.
 */
suspend fun FocusRequester.requestWhenReady(frames: Int = 30) {
    repeat(frames) {
        withFrameNanos { }
        runCatching { requestFocus() }
        withFrameNanos { }
        if (runCatching { captureFocus() }.getOrDefault(false)) {
            freeFocus()
            return
        }
    }
}

/** A line of status or help. A problem carries a warning mark as well as the accent, so it reads without color. */
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
