package tv.anstelle.feature.ondemand

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.focus.FocusRequester
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import tv.anstelle.core.data.ondemand.EpisodeEntry
import tv.anstelle.core.data.ondemand.WatchChoices
import tv.anstelle.core.data.ondemand.WatchMarks
import tv.anstelle.core.model.WatchKind
import tv.anstelle.core.theme.components.EpisodeActionsDialog
import tv.anstelle.core.theme.components.requestWhenReady

/** Vertical D-pad destinations follow the readout: description, watch keys, season tabs, episodes. */
internal class EpisodeNavigation(
    private val page: LazyListState,
    private val scope: CoroutineScope,
    private val primary: FocusRequester,
    private val hasSeasons: Boolean,
) {
    val description = FocusRequester()
    val season = FocusRequester()
    val episode = FocusRequester()
    var seasonRow: LazyListState? = null
    var selectedSeasonIndex: Int = 0
    var episodeRow: TvRowFocus? = null

    /** Set by the page's [Synopsis] while it is cut short, so there is more to open. */
    var descriptionOpens: Boolean = false
    private var moving: Job? = null

    // The season heading precedes the final episode carousel.
    private val episodesIndex get() = (page.layoutInfo.totalItemsCount - 1).coerceAtLeast(0)
    private val seasonsIndex get() = (episodesIndex - 1).coerceAtLeast(0)

    fun downFromKeys() { if (hasSeasons) move(seasonsIndex, season) }
    fun upFromKeys() { if (descriptionOpens) move(0, description) }
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
): EpisodeNavigation {
    val scope = rememberCoroutineScope()
    return remember(page, seasonCount, primary) {
        EpisodeNavigation(page, scope, primary, seasonCount > 0)
    }
}

/**
 * Opens an episode's page, or the page of the channel airing it right now. A press while one is
 * still being looked up, or once this page has started to leave, is dropped, so a double tap
 * never opens two pages.
 */
@Composable
internal fun rememberEpisodeOpener(
    airingChannel: suspend (itemId: Long) -> Long?,
    onOpenChannel: (channelId: Long) -> Unit,
    onOpenEpisode: (itemId: Long) -> Unit,
): (itemId: Long) -> Unit {
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val opening = remember { mutableStateOf<Job?>(null) }
    return { id ->
        if (opening.value?.isActive != true && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            opening.value = scope.launch {
                val channel = airingChannel(id)
                if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                    if (channel != null) onOpenChannel(channel) else onOpenEpisode(id)
                }
            }
        }
    }
}

/** A held episode's quick ways to play it on a series' or episode's page: resume, from the start, add to a channel. */
@Composable
internal fun HeldEpisodeDialog(
    show: String,
    episode: EpisodeEntry,
    watch: WatchMarks,
    returnFocus: FocusRequester,
    onPlay: (itemId: Long, positionMs: Long) -> Unit,
    onAddToChannel: ((itemId: Long) -> Unit)?,
    onDismiss: () -> Unit,
) {
    EpisodeActionsDialog(
        show = show,
        label = episode.label,
        facts = episode.facts,
        choices = WatchChoices.episodeActions(watch.resumeMs(episode.id, episode.durationMs), canAddToChannel = onAddToChannel != null),
        returnFocus = returnFocus,
        onDismiss = onDismiss,
        onAction = { choice ->
            when (choice.kind) {
                WatchKind.ADD_TO_CHANNEL -> onAddToChannel?.invoke(episode.id)
                else -> onPlay(episode.id, choice.positionMs)
            }
        },
    )
}
