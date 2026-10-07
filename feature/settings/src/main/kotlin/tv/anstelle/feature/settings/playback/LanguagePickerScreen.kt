package tv.anstelle.feature.settings.playback

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Language
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import tv.anstelle.core.data.ProfileRepository
import tv.anstelle.core.data.playback.TrackChoices
import tv.anstelle.core.model.Language
import tv.anstelle.core.model.Languages
import tv.anstelle.core.theme.LocalIsTv
import tv.anstelle.core.theme.components.AnstelleTextField
import tv.anstelle.core.theme.components.FocusTile
import tv.anstelle.feature.settings.channels.activeProfileId
import tv.anstelle.feature.settings.servers.SettingsPage
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class LanguagePickerViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val profiles: ProfileRepository,
    private val tracks: TrackChoices,
) : ViewModel() {
    /** Matches the `subtitles` argument of the navigation route. */
    val list = if (savedState.get<Boolean>("subtitles") == true) LanguageList.SUBTITLES else LanguageList.AUDIO

    private val all = Languages.all()

    /** Every language not on the list yet, or null while loading. */
    val choices: StateFlow<List<Language>?> = profiles.activeProfileId()
        .flatMapLatest { tracks.observePrefs(it) }
        .map { prefs -> prefs.languages(list).toSet().let { chosen -> all.filter { it.code !in chosen } } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun add(code: String, then: () -> Unit) {
        viewModelScope.launch {
            tracks.update(profiles.activeProfileId().first()) { prefs -> prefs.with(list) { it + code } }
            then()
        }
    }
}

/** Every language, filtered by what is typed; picking one adds it to the end of the list. */
@Composable
fun LanguagePickerScreen(
    onPicked: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: LanguagePickerViewModel = hiltViewModel(),
) {
    val choices by viewModel.choices.collectAsStateWithLifecycle()
    var filter by rememberSaveable { mutableStateOf("") }
    val firstFocus = remember { FocusRequester() }
    // Focus starts on the list, so the keyboard only opens when the filter is chosen.
    if (LocalIsTv.current) LaunchedEffect(choices != null) { if (choices != null) runCatching { firstFocus.requestFocus() } }
    val shown = choices.orEmpty().filter { filter.isBlank() || it.name.contains(filter.trim(), ignoreCase = true) }

    SettingsPage(
        title = if (viewModel.list == LanguageList.AUDIO) "Add an audio language" else "Add a subtitle language",
        subtitle = "It goes to the end of the list; move it up afterwards if it should win over the others.",
        modifier = modifier,
    ) {
        item(key = "filter") {
            AnstelleTextField(
                value = filter,
                onValueChange = { filter = it },
                label = "Find a language",
                isLast = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        items(shown, key = { it.code }) { language ->
            FocusTile(
                title = language.name,
                icon = Icons.Filled.Language,
                onClick = { viewModel.add(language.code, onPicked) },
                modifier = Modifier.fillMaxWidth().let { if (language == shown.first()) it.focusRequester(firstFocus) else it },
            )
        }
    }
}
