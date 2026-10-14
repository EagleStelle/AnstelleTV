package tv.anstelle.feature.ondemand

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.focus.FocusRequester
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import tv.anstelle.core.theme.components.requestWhenReady

/** Vertical D-pad destinations follow the visible order: watch keys, season tabs, episodes. */
internal class EpisodeNavigation(
    private val page: LazyListState,
    private val scope: CoroutineScope,
    private val headingIndex: Int,
    private val hasSeasons: Boolean,
    private val primary: FocusRequester,
) {
    val season = FocusRequester()
    val episode = FocusRequester()
    var seasonRow: LazyListState? = null
    var selectedSeasonIndex: Int = 0
    private var moving: Job? = null

    fun downFromKeys() = move(if (hasSeasons) headingIndex else headingIndex + 1, if (hasSeasons) season else episode)
    fun downFromSeasons() = move(headingIndex + 1, episode)
    fun upFromEpisodes() = move(if (hasSeasons) headingIndex else 0, if (hasSeasons) season else primary)
    fun upFromSeasons() = move(0, primary)

    private fun move(index: Int, requester: FocusRequester) {
        if (moving?.isActive == true) return
        moving = scope.launch {
            page.scrollToItem(index)
            if (requester == season) {
                // The heading may have been outside the page's lazy composition until this move.
                withFrameNanos { }
                seasonRow?.scrollToItem(selectedSeasonIndex)
            }
            requester.requestWhenReady()
        }
    }
}

@Composable
internal fun rememberEpisodeNavigation(
    page: LazyListState,
    hasSynopsis: Boolean,
    seasonCount: Int,
    primary: FocusRequester,
): EpisodeNavigation? {
    val scope = rememberCoroutineScope()
    return remember(page, hasSynopsis, seasonCount, primary) {
        if (seasonCount == 0) null else EpisodeNavigation(page, scope, if (hasSynopsis) 2 else 1, seasonCount > 1, primary)
    }
}
