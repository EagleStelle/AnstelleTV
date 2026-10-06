package tv.anstelle.feature.ondemand

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import tv.anstelle.core.data.ProfileRepository
import tv.anstelle.core.data.ondemand.OnDemandRepository
import tv.anstelle.core.data.ondemand.ShowPage
import tv.anstelle.core.theme.LocalAnstelleTheme
import tv.anstelle.core.theme.LocalIsTv
import tv.anstelle.core.theme.components.FocusTile
import javax.inject.Inject

@Immutable
data class ShowUiState(val page: ShowPage? = null, val loaded: Boolean = false)

@HiltViewModel
class ShowViewModel @Inject constructor(
    savedState: SavedStateHandle,
    profiles: ProfileRepository,
    repository: OnDemandRepository,
) : ViewModel() {
    /** Matches the `seriesId` argument of the navigation route. */
    private val seriesId: Long = checkNotNull(savedState.get<Long>("seriesId"))

    private val _state = MutableStateFlow(ShowUiState())
    val state: StateFlow<ShowUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val limit = profiles.ensureActiveProfile().maxRatingScore
            _state.value = ShowUiState(repository.show(seriesId, limit), loaded = true)
        }
    }
}

/** A show: start it from its first episode, add it to a channel, or pick an episode. */
@Composable
fun ShowScreen(
    onOpenItem: (itemId: Long) -> Unit,
    onPlay: (itemId: Long, positionMs: Long) -> Unit,
    onAddToChannel: (itemId: Long) -> Unit,
    onGone: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ShowViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val page = state.page
    if (page == null) {
        if (state.loaded) LaunchedEffect(Unit) { onGone() }
        return
    }
    val theme = LocalAnstelleTheme.current
    val isTv = LocalIsTv.current
    val firstFocus = rememberInitialFocus()
    val returnFocus = remember { FocusRequester() }
    var lastOpened by rememberSaveable { mutableStateOf<Long?>(null) }
    if (lastOpened != null && isTv) LaunchedEffect(Unit) { runCatching { returnFocus.requestFocus() } }
    val first = page.episodes.firstOrNull()

    Page(modifier) {
        item(key = "header") {
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                Poster(page.imageUrl, page.title, Modifier.width(if (isTv) 150.dp else 110.dp).aspectRatio(2f / 3f))
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(page.title, size = if (isTv) 30 else 22, weight = FontWeight.SemiBold)
                    Text(page.facts, size = 15, color = theme.textMuted)
                    page.overview?.let { Text(it, size = 15, color = theme.textMuted, maxLines = if (isTv) 5 else 6) }
                }
            }
        }
        if (first != null) {
            item(key = "start") {
                FocusTile(
                    title = "Start from beginning",
                    subtitle = "${first.label}, then each episode after it.",
                    icon = Icons.Filled.PlayArrow,
                    onClick = { onPlay(first.id, 0) },
                    modifier = Modifier.fillMaxWidth().focusRequester(firstFocus),
                )
            }
            item(key = "add") {
                FocusTile(
                    title = "Add to channel",
                    subtitle = "Always air ${page.title} on a channel of your choice.",
                    icon = Icons.AutoMirrored.Filled.PlaylistAdd,
                    onClick = { onAddToChannel(first.id) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        } else {
            item(key = "empty") { Text("No episodes you can watch.", size = 16, color = theme.textMuted) }
        }
        var season: Int? = Int.MIN_VALUE
        for (episode in page.episodes) {
            if (episode.season != season) {
                season = episode.season
                val label = when (season) {
                    null -> "Other episodes"
                    0 -> "Specials"
                    else -> "Season $season"
                }
                item(key = "season-$season") { Text(label, size = 18, weight = FontWeight.Medium) }
            }
            item(key = episode.id) {
                FocusTile(
                    title = episode.label,
                    subtitle = listOfNotNull(episode.facts, episode.overview).joinToString(" · ").ifEmpty { null },
                    onClick = {
                        lastOpened = episode.id
                        onOpenItem(episode.id)
                    },
                    modifier = Modifier.fillMaxWidth().let { if (episode.id == lastOpened) it.focusRequester(returnFocus) else it },
                )
            }
        }
    }
}
