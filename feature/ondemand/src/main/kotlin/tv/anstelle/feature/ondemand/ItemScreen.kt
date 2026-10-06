package tv.anstelle.feature.ondemand

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
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

    Page(modifier) {
        item(key = "header") {
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                val wide = page.showId != null
                Poster(
                    page.imageUrl,
                    page.title,
                    Modifier.width(if (wide) (if (isTv) 280.dp else 180.dp) else (if (isTv) 150.dp else 110.dp))
                        .aspectRatio(if (wide) 16f / 9f else 2f / 3f),
                )
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(page.title, size = if (isTv) 30 else 22, weight = FontWeight.SemiBold)
                    page.detail?.let { Text(it, size = 18) }
                    Text(page.facts, size = 15, color = theme.textMuted)
                    if (page.badges.isNotEmpty()) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { page.badges.forEach { Badge(it) } }
                    }
                    page.overview?.let { Text(it, size = 15, color = theme.textMuted, maxLines = if (isTv) 5 else 8) }
                }
            }
        }
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
                    onClick = { onOpenShow(showId) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
