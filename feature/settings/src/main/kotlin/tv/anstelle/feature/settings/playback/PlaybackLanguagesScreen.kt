package tv.anstelle.feature.settings.playback

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.Translate
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import tv.anstelle.core.common.ApplicationScope
import tv.anstelle.core.data.ProfileRepository
import tv.anstelle.core.data.playback.TrackChoices
import tv.anstelle.core.model.PlaybackDefaults
import tv.anstelle.core.theme.components.LinkRow
import tv.anstelle.core.theme.components.SettingsPage
import tv.anstelle.core.theme.components.rememberInitialFocus
import tv.anstelle.core.theme.components.rememberSettingsSheets
import tv.anstelle.feature.settings.Draft
import tv.anstelle.feature.settings.Edits
import tv.anstelle.feature.settings.PanedPage
import tv.anstelle.feature.settings.SaveButtons
import tv.anstelle.feature.settings.SaveOnBack
import tv.anstelle.feature.settings.activeProfileId
import javax.inject.Inject

@HiltViewModel
class PlaybackLanguagesViewModel @Inject constructor(
    private val profiles: ProfileRepository,
    private val tracks: TrackChoices,
    @ApplicationScope appScope: CoroutineScope,
) : ViewModel() {
    private val edits = Edits<PlaybackDefaults>(appScope) { tracks.save(profiles.activeProfileId().first(), it) }
    val draft: StateFlow<Draft<PlaybackDefaults>?> = edits.draft

    init {
        viewModelScope.launch { edits.load(tracks.defaults(profiles.activeProfileId().first())) }
    }

    fun change(transform: (PlaybackDefaults) -> PlaybackDefaults) = edits.change(transform)

    fun save() = edits.save()

    fun discard() = edits.discard()
}

/** What the Playback page sets, a row each, each opened as a pane named like its row. */
internal enum class PlaybackPane(val title: String, val icon: ImageVector) {
    AUDIO_LANGUAGES("Audio languages", Icons.Filled.Audiotrack),
    SUBTITLE_LANGUAGES("Subtitle languages", Icons.Filled.Translate),
    SUBTITLE_MODE("Subtitle mode", Icons.Filled.ClosedCaption),
    SUBTITLE_STYLE("Subtitle style", Icons.Filled.Subtitles),
    SKIPPING("Skipping", Icons.Filled.FastForward),
}

/** The pages above every Playback pane. */
internal val PlaybackTrail = listOf("Settings", "Playback")

/**
 * The watching profile's playback defaults, every program's start: one row per setting saying
 * what it is set to, each opening its own pane (a language list to order and pick from, a radio
 * list, the subtitle style beside its sample), kept there by Done. Edits wait for Save, beside
 * Discard across the page's top; leaving with edits unsaved asks to save or discard them ([onLeave]).
 */
@Composable
fun PlaybackLanguagesScreen(
    onLeave: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PlaybackLanguagesViewModel = hiltViewModel(),
) {
    val draft by viewModel.draft.collectAsStateWithLifecycle()
    val edits = draft ?: return
    val defaults = edits.value
    var open by rememberSaveable { mutableStateOf<PlaybackPane?>(null) }
    val close = { open = null }
    val apply = { transform: (PlaybackDefaults) -> PlaybackDefaults ->
        viewModel.change(transform)
        open = null
    }
    val pane: (@Composable () -> Unit)? = open?.let { opened -> { PlaybackPaneContent(opened, defaults, apply, close) } }
    PanedPage(pane, modifier) {
        PlaybackMenu(defaults, edits.unsaved, viewModel::save, viewModel::discard, onOpen = { open = it }, onLeave = onLeave)
    }
}

@Composable
private fun PlaybackPaneContent(
    pane: PlaybackPane,
    defaults: PlaybackDefaults,
    apply: ((PlaybackDefaults) -> PlaybackDefaults) -> Unit,
    onClose: () -> Unit,
) {
    val prefs = defaults.prefs
    when (pane) {
        PlaybackPane.AUDIO_LANGUAGES, PlaybackPane.SUBTITLE_LANGUAGES -> {
            val list = if (pane == PlaybackPane.AUDIO_LANGUAGES) LanguageList.AUDIO else LanguageList.SUBTITLES
            LanguagesPane(pane, list, prefs.languages(list), onClose) { codes -> apply { it.copy(prefs = it.prefs.with(list, codes)) } }
        }
        PlaybackPane.SUBTITLE_MODE -> SubtitleModePane(pane, prefs.subtitleMode, prefs.subtitleLanguages, onClose) { mode -> apply { it.copy(prefs = it.prefs.copy(subtitleMode = mode)) } }
        PlaybackPane.SUBTITLE_STYLE -> SubtitleStylePane(pane, defaults.look, onClose) { look -> apply { it.copy(look = look) } }
        PlaybackPane.SKIPPING -> SkippingPane(pane, defaults.skipping, onClose) { skipping -> apply { it.copy(skipping = skipping) } }
    }
}

@Composable
private fun PlaybackMenu(
    defaults: PlaybackDefaults,
    unsaved: Boolean,
    onSave: () -> Unit,
    onDiscard: () -> Unit,
    onOpen: (PlaybackPane) -> Unit,
    onLeave: () -> Unit,
) {
    val firstFocus = rememberInitialFocus()
    val sheets = rememberSettingsSheets()
    SaveOnBack(unsaved, sheets, onSave, onLeave)
    SettingsPage(
        title = "Playback",
        trail = listOf("Settings"),
        about = "Every program, on a channel or on demand, starts on these. A track you pick while watching lasts until that program ends.",
        actions = { SaveButtons(unsaved, onSave, onDiscard) },
        sheets = sheets,
    ) {
        for (pane in PlaybackPane.entries) {
            item(key = pane.name) {
                LinkRow(
                    title = pane.title,
                    onClick = { onOpen(pane) },
                    summary = paneSummary(pane, defaults),
                    icon = pane.icon,
                    modifier = if (pane.ordinal == 0) Modifier.focusRequester(firstFocus) else Modifier,
                )
            }
        }
    }
}

/** What a row says its pane is set to. */
private fun paneSummary(pane: PlaybackPane, defaults: PlaybackDefaults): String = when (pane) {
    PlaybackPane.AUDIO_LANGUAGES -> languagesSummary(LanguageList.AUDIO, defaults.prefs.audioLanguages)
    PlaybackPane.SUBTITLE_LANGUAGES -> languagesSummary(LanguageList.SUBTITLES, defaults.prefs.subtitleLanguages)
    PlaybackPane.SUBTITLE_MODE -> modeName(defaults.prefs.subtitleMode)
    PlaybackPane.SUBTITLE_STYLE -> lookSummary(defaults.look)
    PlaybackPane.SKIPPING -> skippingSummary(defaults.skipping)
}
