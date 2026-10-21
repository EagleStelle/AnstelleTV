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

/** Vertical D-pad destinations follow the readout: description, watch keys, season tabs, episodes. */
internal class EpisodeNavigation(
    private val page: LazyListState,
    private val scope: CoroutineScope,
    private val primary: FocusRequester,
    private val hasSeasons: Boolean,
    private val hasDescription: Boolean,
) {
    val description = FocusRequester()
    val season = FocusRequester()
    val episode = FocusRequester()
    var seasonRow: LazyListState? = null
    var selectedSeasonIndex: Int = 0
    var episodeRow: TvRowFocus? = null
    private var moving: Job? = null

    // The season heading precedes the final episode carousel.
    private val episodesIndex get() = (page.layoutInfo.totalItemsCount - 1).coerceAtLeast(0)
    private val seasonsIndex get() = (episodesIndex - 1).coerceAtLeast(0)

    fun downFromKeys() { if (hasSeasons) move(seasonsIndex, season) }
    fun upFromKeys() { if (hasDescription) move(0, description) }
    fun downFromDescription() = move(0, primary)
    fun downFromSeasons() = move(episodesIndex, episode)
    fun upFromEpisodes() = move(seasonsIndex, season)
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
            if (requester == episode) {
                withFrameNanos { }
                episodeRow?.restore() ?: requester.requestWhenReady()
            } else requester.requestWhenReady()
        }
    }
}

@Composable
internal fun rememberEpisodeNavigation(
    page: LazyListState,
    seasonCount: Int,
    primary: FocusRequester,
    hasDescription: Boolean = false,
): EpisodeNavigation {
    val scope = rememberCoroutineScope()
    return remember(page, seasonCount, primary, hasDescription) {
        EpisodeNavigation(page, scope, primary, seasonCount > 0, hasDescription)
    }
}
