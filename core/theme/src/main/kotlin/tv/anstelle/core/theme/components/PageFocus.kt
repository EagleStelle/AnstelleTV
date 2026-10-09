package tv.anstelle.core.theme.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged

/**
 * Which row of a page had focus, so coming back to the page (from a screen or pane it opened)
 * lands on that row, with the list still scrolled to it, instead of on the first. Every
 * [FocusSurface] inside a [PageFocusScope] reports to it; [rememberInitialFocus] reads it.
 */
@Stable
class PageFocus internal constructor(private val last: MutableState<Long?>) {
    /** The row focused when the viewer left; null on a page shown for the first time. */
    private var returnTo: Long? = last.value
    private val rows = HashMap<Long, FocusRequester>()

    internal fun Modifier.track(row: Long): Modifier =
        focusRequester(rows.getOrPut(row) { FocusRequester() }).onFocusChanged { if (it.isFocused) last.value = row }

    /**
     * On a page come back to, focuses the row it was left on, once per visit. False on a first
     * visit, or when that row is gone; the page's own first row takes focus then.
     */
    suspend fun restore(): Boolean {
        val row = returnTo ?: return false
        returnTo = null
        return rows.getOrPut(row) { FocusRequester() }.requestWhenReady(frames = RESTORE_FRAMES)
    }

    private companion object {
        /** A row come back to is laid out at once if it still exists: don't wait long for a deleted one. */
        const val RESTORE_FRAMES = 10
    }
}

/** The [PageFocus] of the page being composed, if it keeps one. */
val LocalPageFocus = staticCompositionLocalOf<PageFocus?> { null }

/**
 * Gives [content] its own [PageFocus]: one around each settings screen, and one around each
 * pane a screen swaps in place. It keeps the row through the page's saved state, so a pane
 * needs a [androidx.compose.runtime.saveable.SaveableStateHolder] to come back to its row.
 */
@Composable
fun PageFocusScope(content: @Composable () -> Unit) {
    val last = rememberSaveable { mutableStateOf<Long?>(null) }
    val focus = remember { PageFocus(last) }
    CompositionLocalProvider(LocalPageFocus provides focus, content = content)
}
