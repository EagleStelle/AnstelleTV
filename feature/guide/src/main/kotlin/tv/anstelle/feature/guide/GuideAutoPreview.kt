package tv.anstelle.feature.guide

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay

/** How long browsing rests on a program airing now before its channel shows in the preview. */
internal const val AUTO_PREVIEW_MS = 500L

/** A stopped screen loses its pending request; returning requires a fresh pause on the selection. */
internal suspend fun Lifecycle.autoPreview(preview: () -> Unit) {
    repeatOnLifecycle(Lifecycle.State.STARTED) {
        delay(AUTO_PREVIEW_MS)
        preview()
    }
}
