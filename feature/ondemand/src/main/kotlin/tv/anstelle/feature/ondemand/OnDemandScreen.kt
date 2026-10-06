package tv.anstelle.feature.ondemand

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import tv.anstelle.core.data.ProfileRepository
import tv.anstelle.core.data.ondemand.OnDemandRepository
import tv.anstelle.core.data.ondemand.OnDemandTitle
import tv.anstelle.core.theme.LocalAnstelleTheme
import tv.anstelle.core.theme.LocalIsTv
import javax.inject.Inject

enum class OnDemandTab { SHOWS, MOVIES }

@Immutable
data class OnDemandUiState(
    val shows: List<OnDemandTitle> = emptyList(),
    val movies: List<OnDemandTitle> = emptyList(),
    val loaded: Boolean = false,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class OnDemandViewModel @Inject constructor(
    profiles: ProfileRepository,
    repository: OnDemandRepository,
) : ViewModel() {
    val tab = MutableStateFlow<OnDemandTab?>(null)

    val state: StateFlow<OnDemandUiState> = flow { emit(profiles.ensureActiveProfile().maxRatingScore) }
        .flatMapLatest { limit ->
            combine(repository.observeShows(limit), repository.observeMovies(limit)) { shows, movies ->
                OnDemandUiState(shows, movies, loaded = true)
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), OnDemandUiState())
}

/** Movies and TV shows from the library, to play whenever. */
@Composable
fun OnDemandScreen(
    onOpenShow: (seriesId: Long) -> Unit,
    onOpenItem: (itemId: Long) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: OnDemandViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val chosen by viewModel.tab.collectAsStateWithLifecycle()
    val theme = LocalAnstelleTheme.current
    val isTv = LocalIsTv.current
    if (!state.loaded) return
    // Open on whichever the library has, shows first.
    val tab = chosen ?: if (state.shows.isEmpty() && state.movies.isNotEmpty()) OnDemandTab.MOVIES else OnDemandTab.SHOWS
    val titles = if (tab == OnDemandTab.SHOWS) state.shows else state.movies
    val firstFocus = rememberInitialFocus(tab)
    val gridState = rememberLazyGridState()
    var lastOpened by rememberSaveable { mutableStateOf<Long?>(null) }

    Column(
        modifier
            .fillMaxSize()
            .background(theme.background)
            .padding(horizontal = if (isTv) 48.dp else 12.dp, vertical = if (isTv) 28.dp else 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("On demand", size = if (isTv) 28 else 22, weight = FontWeight.SemiBold)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TabChip("TV shows (${state.shows.size})", tab == OnDemandTab.SHOWS) { viewModel.tab.value = OnDemandTab.SHOWS }
            TabChip("Movies (${state.movies.size})", tab == OnDemandTab.MOVIES) { viewModel.tab.value = OnDemandTab.MOVIES }
        }
        if (titles.isEmpty()) {
            Text(
                if (tab == OnDemandTab.SHOWS) "No TV shows in your libraries yet." else "No movies in your libraries yet.",
                size = 16,
                color = theme.textMuted,
            )
            return@Column
        }
        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Adaptive(if (isTv) 128.dp else 112.dp),
            horizontalArrangement = Arrangement.spacedBy(if (isTv) 20.dp else 10.dp),
            verticalArrangement = Arrangement.spacedBy(if (isTv) 20.dp else 10.dp),
            contentPadding = PaddingValues(vertical = 8.dp),
        ) {
            itemsIndexed(titles, key = { _, title -> title.id }) { i, title ->
                val focusHere = if (lastOpened != null) title.id == lastOpened else i == 0
                PosterCard(
                    title = title.title,
                    subtitle = title.subtitle,
                    imageUrl = title.imageUrl,
                    onClick = {
                        lastOpened = title.id
                        if (title.isShow) onOpenShow(title.id) else onOpenItem(title.id)
                    },
                    modifier = if (focusHere) Modifier.focusRequester(firstFocus) else Modifier,
                )
            }
        }
    }
}

@kotlin.OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)
@Composable
private fun TabChip(label: String, selected: Boolean, onClick: () -> Unit) {
    if (LocalIsTv.current) {
        androidx.tv.material3.FilterChip(selected = selected, onClick = onClick) { androidx.tv.material3.Text(label) }
    } else {
        androidx.compose.material3.FilterChip(selected = selected, onClick = onClick, label = { androidx.compose.material3.Text(label) })
    }
}
