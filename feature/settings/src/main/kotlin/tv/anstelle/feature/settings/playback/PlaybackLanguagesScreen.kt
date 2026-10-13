package tv.anstelle.feature.settings.playback

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FastForward
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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import tv.anstelle.core.data.ProfileRepository
import tv.anstelle.core.data.Settings
import tv.anstelle.core.data.SettingsRepository
import tv.anstelle.core.data.playback.TrackChoices
import tv.anstelle.core.model.SegmentAction
import tv.anstelle.core.model.SegmentType
import tv.anstelle.core.model.SubtitleMode
import tv.anstelle.core.model.TrackPrefs
import tv.anstelle.core.model.codeOf
import tv.anstelle.core.theme.LocalAnstelleTheme
import tv.anstelle.core.theme.components.ChoiceRow
import tv.anstelle.core.theme.components.InfoRow
import tv.anstelle.core.theme.components.OsdButton
import tv.anstelle.core.theme.components.RemoveKey
import tv.anstelle.core.theme.components.ReorderList
import tv.anstelle.core.theme.components.ReorderRow
import tv.anstelle.core.theme.components.SettingsButtons
import tv.anstelle.core.theme.components.SettingsPage
import tv.anstelle.core.theme.components.rememberInitialFocus
import tv.anstelle.core.theme.components.rememberReorderList
import tv.anstelle.core.theme.components.reorderItems
import tv.anstelle.core.theme.components.section
import tv.anstelle.feature.settings.activeProfileId
import javax.inject.Inject

/** The two ordered language lists. */
enum class LanguageList { AUDIO, SUBTITLES }

@Immutable
data class PlaybackLanguagesUiState(
    val prefs: TrackPrefs = TrackPrefs(),
    /** What On Demand does while each kind of segment plays. */
    val skipping: Map<SegmentType, SegmentAction> = emptyMap(),
    val loaded: Boolean = false,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class PlaybackLanguagesViewModel @Inject constructor(
    private val profiles: ProfileRepository,
    private val tracks: TrackChoices,
    private val settings: SettingsRepository,
) : ViewModel() {
    val state: StateFlow<PlaybackLanguagesUiState> = profiles.activeProfileId()
        .flatMapLatest { profileId ->
            val skipping = combine(
                SegmentType.entries.map { type -> settings.observe(Settings.segmentAction(profileId, type)).map { type to codeOf<SegmentAction>(it) } },
            ) { it.toMap() }
            combine(tracks.observePrefs(profileId), skipping) { prefs, skip -> PlaybackLanguagesUiState(prefs, skip, loaded = true) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PlaybackLanguagesUiState())

    fun setSkip(type: SegmentType, action: SegmentAction) {
        viewModelScope.launch { settings.set(Settings.segmentAction(profiles.activeProfileId().first(), type), action.code) }
    }

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
 * and the subtitle languages in order. Every program starts on them. A language moves by its
 * handle, its menu removes it, and Add language opens the list of the rest. Then what On Demand
 * does with each kind of segment Jellyfin marks; channels play like TV, so they skip nothing.
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
    val listState = rememberLazyListState()
    val audio = rememberLanguageList(LanguageList.AUDIO, prefs.audioLanguages, listState, viewModel)
    val subtitles = rememberLanguageList(LanguageList.SUBTITLES, prefs.subtitleLanguages, listState, viewModel)

    SettingsPage(
        title = "Playback",
        trail = listOf("Settings"),
        about = "Every program starts on these. Move a language by its handle to change the order. " +
            "A program with none of your audio languages plays its default audio track. " +
            "Picking a track while watching changes only the program on now. " +
            "Skipping works in On Demand, for the intros, outros and other parts your Jellyfin server marks " +
            "(with a plugin such as Intro Skipper). Channels play straight through, like TV.",
        listState = listState,
        modifier = modifier,
    ) {
        section("Audio")
        languageRows(LanguageList.AUDIO, audio, viewModel, Modifier.focusRequester(firstFocus))
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
        languageRows(LanguageList.SUBTITLES, subtitles, viewModel)
        item(key = "add-subtitles") {
            SettingsButtons {
                OsdButton("Add subtitle language", onClick = { onAddLanguage(LanguageList.SUBTITLES) }, icon = Icons.Filled.Add)
            }
        }

        section("Skipping")
        for (type in SegmentType.entries) {
            item(key = "skip-${type.key}") {
                ChoiceRow(
                    title = segmentName(type),
                    value = state.skipping[type] ?: SegmentAction.ASK_TO_SKIP,
                    choices = SkipOrder,
                    label = ::skipName,
                    detail = { skipHint(type, it) },
                    about = "What On Demand does with ${segmentName(type).lowercase()}.",
                    onSelect = { viewModel.setSkip(type, it) },
                    icon = Icons.Filled.FastForward,
                )
            }
        }
    }
}

/** One language list, put in order by dragging; a language set down elsewhere moves there. */
@Composable
private fun rememberLanguageList(list: LanguageList, codes: List<String>, listState: LazyListState, viewModel: PlaybackLanguagesViewModel) =
    rememberReorderList(codes, listState, key = { "${list.name}-$it" }) { code, from, to -> viewModel.move(list, code, to - from) }

/** The list in order, numbered, each language with its handle; its menu removes it. */
private fun LazyListScope.languageRows(
    list: LanguageList,
    languages: ReorderList<String>,
    viewModel: PlaybackLanguagesViewModel,
    firstModifier: Modifier = Modifier,
) {
    reorderItems(languages) { index, code ->
        ReorderRow(
            list = languages,
            item = code,
            title = languageName(code),
            modifier = if (index == 0) firstModifier else Modifier,
        ) { rowModifier ->
            // The language is a fact; its red bin removes it, and takes the page's first focus on the first row.
            Row(rowModifier, verticalAlignment = Alignment.CenterVertically) {
                InfoRow(title = languageName(code), leading = { OrderNumber(index + 1) }, modifier = Modifier.weight(1f))
                RemoveKey("Remove ${languageName(code)}", onClick = { viewModel.remove(list, code) })
            }
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
