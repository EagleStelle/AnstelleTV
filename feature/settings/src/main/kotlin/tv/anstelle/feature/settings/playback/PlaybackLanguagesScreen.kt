package tv.anstelle.feature.settings.playback

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.Delete
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.TextStyle
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
import tv.anstelle.core.theme.LocalAnstelleTheme
import tv.anstelle.core.theme.components.ChoiceRow
import tv.anstelle.core.theme.components.MenuAction
import tv.anstelle.core.theme.components.MenuRow
import tv.anstelle.core.theme.components.OsdButton
import tv.anstelle.core.theme.components.SettingsButtons
import tv.anstelle.core.theme.components.SettingsNotice
import tv.anstelle.core.theme.components.SettingsPage
import tv.anstelle.core.theme.components.rememberInitialFocus
import tv.anstelle.core.theme.components.section
import tv.anstelle.feature.settings.activeProfileId
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

    fun setMode(mode: SubtitleMode) = update { it.copy(subtitleMode = mode) }

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
 * The watching profile's playback defaults: the audio languages in order, when subtitles show,
 * and the subtitle languages in order. Every program starts on them. A language's menu moves it
 * or removes it; Add language opens the list of the rest.
 */
@Composable
fun PlaybackLanguagesScreen(
    onAddLanguage: (LanguageList) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PlaybackLanguagesViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val prefs = state.prefs
    val firstFocus = rememberInitialFocus(ready = state.loaded)

    SettingsPage(
        title = "Playback",
        trail = listOf("Settings"),
        about = "Every program starts on these. One with none of your audio languages plays its default audio track. " +
            "Picking a track while watching changes only the program on now.",
        modifier = modifier,
    ) {
        section("Audio")
        languageRows(LanguageList.AUDIO, prefs.audioLanguages, viewModel, Modifier.focusRequester(firstFocus))
        item(key = "add-audio") {
            SettingsButtons {
                OsdButton(
                    "Add audio language",
                    onClick = { onAddLanguage(LanguageList.AUDIO) },
                    icon = Icons.Filled.Add,
                    modifier = if (prefs.audioLanguages.isEmpty()) Modifier.focusRequester(firstFocus) else Modifier,
                )
            }
        }

        section("Subtitles")
        item(key = "mode") {
            ChoiceRow(
                title = "Subtitle mode",
                value = prefs.subtitleMode,
                choices = ModeOrder,
                label = ::modeName,
                detail = ::modeHint,
                onSelect = viewModel::setMode,
                icon = Icons.Filled.ClosedCaption,
            )
        }
        if (prefs.subtitleMode != SubtitleMode.OFF && prefs.subtitleLanguages.isEmpty()) {
            item(key = "no-subtitles") { SettingsNotice("Add a language so subtitles can show.", isError = true) }
        }
        languageRows(LanguageList.SUBTITLES, prefs.subtitleLanguages, viewModel)
        item(key = "add-subtitles") {
            SettingsButtons {
                OsdButton("Add subtitle language", onClick = { onAddLanguage(LanguageList.SUBTITLES) }, icon = Icons.Filled.Add)
            }
        }
    }
}

/** The list in order, numbered; each language's menu moves it up or down or removes it. */
private fun LazyListScope.languageRows(
    list: LanguageList,
    codes: List<String>,
    viewModel: PlaybackLanguagesViewModel,
    firstModifier: Modifier = Modifier,
) {
    codes.forEachIndexed { index, code ->
        item(key = "${list.name}-$code") {
            MenuRow(
                title = languageName(code),
                leading = { OrderNumber(index + 1) },
                actions = buildList {
                    if (index > 0) add(MenuAction("Move up", Icons.Filled.ArrowUpward) { viewModel.move(list, code, -1) })
                    if (index < codes.lastIndex) add(MenuAction("Move down", Icons.Filled.ArrowDownward) { viewModel.move(list, code, 1) })
                    add(MenuAction("Remove", Icons.Filled.Delete, danger = true) { viewModel.remove(list, code) })
                },
                modifier = if (index == 0) firstModifier else Modifier,
            )
        }
    }
}

/** A language's place in its list, in the icon's slot: tabular, so the column of numbers lines up. */
@Composable
private fun OrderNumber(number: Int) {
    val theme = LocalAnstelleTheme.current
    Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
        androidx.compose.material3.Text(
            "$number",
            style = androidx.compose.material3.MaterialTheme.typography.titleMedium.merge(TextStyle(fontFamily = theme.displayFont, fontFeatureSettings = "tnum")),
            color = androidx.compose.material3.LocalContentColor.current,
        )
    }
}
