package tv.anstelle.feature.settings.playback

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.ClosedCaptionDisabled
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
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
import tv.anstelle.core.model.SubtitleMode
import tv.anstelle.core.model.TrackPrefs
import tv.anstelle.core.theme.LocalIsTv
import tv.anstelle.core.theme.components.FocusTile
import tv.anstelle.feature.settings.activeProfileId
import tv.anstelle.feature.settings.servers.MessageText
import tv.anstelle.feature.settings.servers.SettingsPage
import javax.inject.Inject

/** The two ordered language lists. */
enum class LanguageList { AUDIO, SUBTITLES }

@Immutable
data class PlaybackLanguagesUiState(val prefs: TrackPrefs = TrackPrefs(), val loaded: Boolean = false)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class PlaybackLanguagesViewModel @Inject constructor(
    private val profiles: ProfileRepository,
    private val tracks: TrackChoices,
) : ViewModel() {
    val state: StateFlow<PlaybackLanguagesUiState> = profiles.activeProfileId()
        .flatMapLatest { tracks.observePrefs(it) }
        .map { PlaybackLanguagesUiState(it, loaded = true) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PlaybackLanguagesUiState())

    /** Steps through: off → always on → when the audio is in another language. */
    fun nextMode() = update {
        it.copy(
            subtitleMode = when (it.subtitleMode) {
                SubtitleMode.OFF -> SubtitleMode.ALWAYS
                SubtitleMode.ALWAYS -> SubtitleMode.WHEN_AUDIO_DIFFERS
                SubtitleMode.WHEN_AUDIO_DIFFERS -> SubtitleMode.OFF
            },
        )
    }

    fun move(list: LanguageList, code: String, delta: Int) = update { prefs ->
        prefs.with(list) { codes ->
            val from = codes.indexOf(code)
            val to = from + delta
            if (from < 0 || to !in codes.indices) codes else codes.toMutableList().apply { add(to, removeAt(from)) }
        }
    }

    fun remove(list: LanguageList, code: String) = update { prefs -> prefs.with(list) { it - code } }

    private fun update(change: (TrackPrefs) -> TrackPrefs) {
        viewModelScope.launch { tracks.update(profiles.activeProfileId().first(), change) }
    }
}

internal fun TrackPrefs.languages(list: LanguageList): List<String> = when (list) {
    LanguageList.AUDIO -> audioLanguages
    LanguageList.SUBTITLES -> subtitleLanguages
}

internal fun TrackPrefs.with(list: LanguageList, change: (List<String>) -> List<String>): TrackPrefs = when (list) {
    LanguageList.AUDIO -> copy(audioLanguages = change(audioLanguages))
    LanguageList.SUBTITLES -> copy(subtitleLanguages = change(subtitleLanguages))
}

/**
 * Audio and subtitle defaults for the active profile: the audio languages in order, when
 * subtitles show, and the subtitle languages in order. Every program starts on them.
 */
@Composable
fun PlaybackLanguagesScreen(
    onAddLanguage: (LanguageList) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PlaybackLanguagesViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val prefs = state.prefs
    val firstFocus = remember { FocusRequester() }
    if (LocalIsTv.current) LaunchedEffect(state.loaded) { if (state.loaded) runCatching { firstFocus.requestFocus() } }
    var open by rememberSaveable { mutableStateOf<String?>(null) }

    SettingsPage(
        title = "Audio and subtitles",
        subtitle = "Every program starts on these. Picking a track while watching changes only the program on now.",
        modifier = modifier,
    ) {
        section("Audio languages", first = true)
        languageRows(LanguageList.AUDIO, prefs.audioLanguages, open, onOpen = { open = it }, viewModel, Modifier.focusRequester(firstFocus))
        item(key = "add-audio") {
            FocusTile(
                title = "Add a language",
                subtitle = if (prefs.audioLanguages.isEmpty()) "Without one, each program plays its default audio track." else "When a program has none of these, its default audio track plays.",
                icon = Icons.Filled.Add,
                onClick = { onAddLanguage(LanguageList.AUDIO) },
                modifier = Modifier.fillMaxWidth().let { if (prefs.audioLanguages.isEmpty()) it.focusRequester(firstFocus) else it },
            )
        }

        section("Subtitles")
        item(key = "mode") {
            FocusTile(
                title = modeTitle(prefs.subtitleMode),
                subtitle = modeHint(prefs.subtitleMode) + " Select to change.",
                icon = if (prefs.subtitleMode == SubtitleMode.OFF) Icons.Filled.ClosedCaptionDisabled else Icons.Filled.ClosedCaption,
                onClick = viewModel::nextMode,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (prefs.subtitleMode != SubtitleMode.OFF && prefs.subtitleLanguages.isEmpty()) {
            item(key = "no-subtitles") { MessageText("Add a language so subtitles can show.", isError = true) }
        }
        languageRows(LanguageList.SUBTITLES, prefs.subtitleLanguages, open, onOpen = { open = it }, viewModel)
        item(key = "add-subtitles") {
            FocusTile(
                title = "Add a language",
                subtitle = "Subtitles show in the first of these a program has, or not at all.",
                icon = Icons.Filled.Add,
                onClick = { onAddLanguage(LanguageList.SUBTITLES) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** A heading over a group of rows, with more room above it than below unless it opens the page. */
internal fun LazyListScope.section(title: String, first: Boolean = false) {
    item(key = "section-$title") {
        androidx.compose.material3.Text(
            title,
            style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
            color = tv.anstelle.core.theme.LocalAnstelleTheme.current.text,
            modifier = if (first) Modifier else Modifier.padding(top = if (LocalIsTv.current) 18.dp else 12.dp),
        )
    }
}

/** Numbered rows; selecting one opens Move up, Move down and Remove below it. */
private fun LazyListScope.languageRows(
    list: LanguageList,
    codes: List<String>,
    open: String?,
    onOpen: (String?) -> Unit,
    viewModel: PlaybackLanguagesViewModel,
    firstModifier: Modifier = Modifier,
) {
    codes.forEachIndexed { index, code ->
        val key = "${list.name}-$code"
        val isOpen = open == key
        item(key = key) {
            FocusTile(
                title = "${index + 1}. ${languageName(code)}",
                subtitle = if (isOpen) "Select to close" else "Select to move or remove",
                icon = if (list == LanguageList.AUDIO) Icons.Filled.Audiotrack else Icons.Filled.Subtitles,
                onClick = { onOpen(if (isOpen) null else key) },
                modifier = Modifier.fillMaxWidth().let { if (index == 0) it.then(firstModifier) else it },
            )
        }
        if (!isOpen) return@forEachIndexed
        if (index > 0) {
            item(key = "$key-up") {
                FocusTile("Move up", onClick = { viewModel.move(list, code, -1) }, icon = Icons.Filled.ArrowUpward, modifier = Modifier.fillMaxWidth())
            }
        }
        if (index < codes.lastIndex) {
            item(key = "$key-down") {
                FocusTile("Move down", onClick = { viewModel.move(list, code, 1) }, icon = Icons.Filled.ArrowDownward, modifier = Modifier.fillMaxWidth())
            }
        }
        item(key = "$key-remove") {
            FocusTile(
                title = "Remove",
                icon = Icons.Filled.Delete,
                onClick = {
                    viewModel.remove(list, code)
                    onOpen(null)
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
