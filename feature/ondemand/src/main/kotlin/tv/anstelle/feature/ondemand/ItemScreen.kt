package tv.anstelle.feature.ondemand

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Tv
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import tv.anstelle.core.data.ondemand.ItemPage
import tv.anstelle.core.data.ondemand.OnDemandRepository
import tv.anstelle.core.theme.LocalAnstelleTheme
import tv.anstelle.core.theme.LocalIsTv
import tv.anstelle.core.theme.components.FocusTile
import tv.anstelle.core.theme.components.TileTrailing

import javax.inject.Inject

@Immutable
data class ItemUiState(
    val page: ItemPage? = null,
    val loaded: Boolean = false,
    /** Where the viewer stopped last time in Jellyfin; 0 when there is no resume point. */
    val resumeMs: Long = 0,
)

@HiltViewModel
class ItemViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val repository: OnDemandRepository,
) : ViewModel() {
    /** Matches the `itemId` argument of the navigation route. */
    val itemId: Long = checkNotNull(savedState.get<Long>("itemId"))

    private val _state = MutableStateFlow(ItemUiState())
    val state: StateFlow<ItemUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch { _state.update { it.copy(page = repository.item(itemId), loaded = true) } }
    }

    /** Reads the resume point again, e.g. after watching some of it. */
    fun refreshResume() {
        viewModelScope.launch {
            val position = repository.resumePositionMs(itemId)
            _state.update { it.copy(resumeMs = position) }
        }
    }
}

/** A movie or an episode: watch it from the beginning or from where you stopped, or add it to a channel. */
@Composable
fun ItemScreen(
    onPlay: (itemId: Long, positionMs: Long) -> Unit,
    onAddToChannel: (itemId: Long) -> Unit,
    onOpenShow: (seriesId: Long) -> Unit,
    onGone: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ItemViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LifecycleResumeEffect(viewModel) {
        viewModel.refreshResume()
        onPauseOrDispose {}
    }
    val page = state.page
    if (page == null) {
        if (state.loaded) LaunchedEffect(Unit) { onGone() }
        return
    }
    val theme = LocalAnstelleTheme.current
    val isTv = LocalIsTv.current
    val firstFocus = rememberInitialFocus(state.resumeMs > 0)
    val resume = state.resumeMs.takeIf { it > 0 && it < page.durationMs * 95 / 100 }

    val hero: @Composable ColumnScope.() -> Unit = {
        // An episode shows its own still, wide; a movie its poster.
        val wide = page.showId != null
        Row(horizontalArrangement = Arrangement.spacedBy(if (isTv) 28.dp else 16.dp), verticalAlignment = Alignment.Bottom) {
            Poster(
                page.imageUrl,
                page.title,
                Modifier.width(if (wide) (if (isTv) 300.dp else 160.dp) else (if (isTv) 160.dp else 104.dp))
                    .aspectRatio(if (wide) 16f / 9f else 2f / 3f),
            )
            Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.weight(1f)) {
                HeroTitle(page.artwork.logoUrl, page.title)
                page.detail?.let { androidx.compose.material3.Text(it, style = androidx.compose.material3.MaterialTheme.typography.titleLarge, color = theme.text) }
                if (isTv) Facts(page.facts, page.badges)
            }
        }
        if (!isTv) Facts(page.facts, page.badges)
        page.overview?.let { Overview(it, Modifier.padding(top = 4.dp)) }
    }

    Page(page.artwork.backdropUrl, hero, modifier) {
        if (resume != null) {
            item(key = "resume") {
                FocusTile(
                    title = "Watch from here",
                    subtitle = "Pick up at ${clock(resume)}, where you stopped.",
                    icon = Icons.Filled.PlayArrow,
                    onClick = { onPlay(page.id, resume) },
                    modifier = Modifier.fillMaxWidth().focusRequester(firstFocus),
                )
            }
        }
        item(key = "start") {
            FocusTile(
                title = "Start from beginning",
                subtitle = if (page.showId != null) "Then the episodes after it." else null,
                icon = if (resume != null) Icons.Filled.Replay else Icons.Filled.PlayArrow,
                onClick = { onPlay(page.id, 0) },
                modifier = Modifier.fillMaxWidth().let { if (resume == null) it.focusRequester(firstFocus) else it },
            )
        }
        item(key = "add") {
            FocusTile(
                title = "Add to channel",
                subtitle = "Always air ${page.title} on a channel of your choice.",
                icon = Icons.AutoMirrored.Filled.PlaylistAdd,
                onClick = { onAddToChannel(page.id) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        page.showId?.let { showId ->
            item(key = "show") {
                FocusTile(
                    title = "All episodes of ${page.title}",
                    icon = Icons.Filled.Tv,
                    trailing = TileTrailing.Chevron,
                    onClick = { onOpenShow(showId) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
