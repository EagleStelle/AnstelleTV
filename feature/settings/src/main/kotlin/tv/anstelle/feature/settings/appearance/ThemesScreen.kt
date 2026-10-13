package tv.anstelle.feature.settings.appearance

import android.content.ActivityNotFoundException
import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tv.anstelle.core.data.ProfileRepository
import tv.anstelle.core.data.ThemeImportException
import tv.anstelle.core.data.ThemeRepository
import tv.anstelle.core.data.ThemeSummary
import tv.anstelle.core.data.WebServerController
import tv.anstelle.core.model.Profile
import tv.anstelle.core.theme.LocalIsTv
import tv.anstelle.core.theme.components.ConfirmSheet
import tv.anstelle.core.theme.components.LocalPageFocus
import tv.anstelle.core.theme.components.LocalSettingsSheets
import tv.anstelle.core.theme.components.MenuAction
import tv.anstelle.core.theme.components.OptionRow
import tv.anstelle.core.theme.components.OsdButton
import tv.anstelle.core.theme.components.SettingsButtons
import tv.anstelle.core.theme.components.SettingsNotice
import tv.anstelle.core.theme.components.SettingsPage
import tv.anstelle.core.theme.components.SettingsSearch
import tv.anstelle.core.theme.components.requestWhenReady
import tv.anstelle.core.theme.components.section
import javax.inject.Inject

/** The theme every profile starts with. */
internal const val DEFAULT_THEME_KEY = "rose"

@Immutable
data class ThemesUiState(
    val profile: Profile? = null,
    /** The default first, then the rest built in, then the profile's own. */
    val themes: List<ThemeSummary> = emptyList(),
    val loaded: Boolean = false,
) {
    /** The theme the profile wears: its own pick, else the default. */
    val inUseId: Long? get() = profile?.themeId ?: themes.firstOrNull { it.spec.key == DEFAULT_THEME_KEY }?.id
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ThemesViewModel @Inject constructor(
    savedState: SavedStateHandle,
    @ApplicationContext private val context: Context,
    private val themes: ThemeRepository,
    profiles: ProfileRepository,
    private val webServer: WebServerController,
) : ViewModel() {
    /** The profile whose theme this picks; 0 in the route means the one watching. */
    private val profileId = savedState.get<Long>("profileId") ?: 0L

    /** Opened from a profile's page rather than from Settings. */
    val forProfile: Boolean get() = profileId > 0

    private val profileFlow = if (profileId > 0) profiles.observeProfile(profileId) else profiles.observeActiveProfile()

    val state: StateFlow<ThemesUiState> = flow { themes.seedBuiltIns(); emit(Unit) }
        .flatMapLatest { combine(profileFlow, themes.observeThemes()) { profile, list -> ThemesUiState(profile, ordered(list), loaded = true) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ThemesUiState())

    private val _message = MutableStateFlow<Pair<String, Boolean>?>(null)

    /** A note and whether it is a problem. */
    val message: StateFlow<Pair<String, Boolean>?> = _message

    fun use(themeId: Long) {
        val profile = state.value.profile ?: return
        viewModelScope.launch {
            // The default is stored as no pick, so it follows whatever the default becomes.
            val isDefault = state.value.themes.firstOrNull { it.id == themeId }?.spec?.key == DEFAULT_THEME_KEY
            themes.use(profile.id, if (isDefault) null else themeId)
        }
    }

    /** A copy of [themeId] to change; [onOpen] edits it. */
    fun copy(themeId: Long, onOpen: (Long) -> Unit) = act { onOpen(themes.duplicate(themeId)) }

    fun delete(themeId: Long) = act { themes.delete(themeId) }

    fun importFrom(uri: Uri) = act {
        val text = withContext(Dispatchers.IO) { context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() } }
            ?: throw ThemeImportException("That file couldn't be read")
        val id = themes.import(text)
        _message.value = "Added ${state.value.themes.firstOrNull { it.id == id }?.spec?.name ?: themes.observeTheme(id).first()?.spec?.name}." to false
    }

    fun exportTo(themeId: Long, uri: Uri) = act {
        val json = themes.export(themeId) ?: return@act
        withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(json.toByteArray()) } }
        _message.value = "Saved." to false
    }

    /** Where a browser on the network trades theme files, for devices without a file picker. */
    fun noFilePicker(exporting: Long?) {
        val base = webServer.status.value.urls.firstOrNull()
        _message.value = when {
            base == null -> "This device can't open files. Turn on Web control in Settings to trade themes from a browser."
            exporting != null -> "This device can't save files. In a browser paired with Web control, open $base/themes and save it there."
            else -> "This device can't open files. In a browser paired with Web control, open $base/themes and import it there."
        } to false
    }

    private fun act(action: suspend () -> Unit) {
        _message.value = null
        viewModelScope.launch {
            try {
                action()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _message.value = (e.message ?: "That didn't work.") to true
            }
        }
    }

    private fun ordered(list: List<ThemeSummary>) =
        list.sortedWith(compareBy<ThemeSummary>({ !it.isBuiltIn }, { it.spec.key != DEFAULT_THEME_KEY }, { it.spec.name.lowercase() }))
}

/**
 * The themes a profile can wear, each with its colors at a glance: the user's own first, then the
 * built-in ones. Picking one puts it on at once, so the whole app shows it; each theme's menu
 * copies or edits it, saves it as a file, or deletes one of the user's own. The preview beside
 * the list (above it on phones) shows the focused theme without putting it on.
 */
@Composable
fun ThemesScreen(
    onEdit: (themeId: Long) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ThemesViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val isTv = LocalIsTv.current
    var focusedId by remember { mutableLongStateOf(0L) }
    var exportId by remember { mutableLongStateOf(0L) }
    val firstFocus = remember { FocusRequester() }
    val listState = rememberLazyListState()

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(viewModel::importFrom) }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri?.let { viewModel.exportTo(exportId, it) }
    }

    fun importFile() = try {
        importer.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
    } catch (e: ActivityNotFoundException) {
        viewModel.noFilePicker(exporting = null)
    }

    fun export(summary: ThemeSummary) {
        exportId = summary.id
        try {
            exporter.launch(summary.spec.name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-') + ".json")
        } catch (e: ActivityNotFoundException) {
            viewModel.noFilePicker(exporting = summary.id)
        }
    }

    val inUse = state.inUseId
    val profileName = state.profile?.name
    var query by rememberSaveable { mutableStateOf("") }
    val shownThemes = query.trim().let { needle -> if (needle.isEmpty()) state.themes else state.themes.filter { it.spec.name.contains(needle, ignoreCase = true) } }
    val own = shownThemes.filter { !it.isBuiltIn }
    val builtIn = shownThemes.filter { it.isBuiltIn }
    // On TV focus starts on the theme worn, once the list is there to hold it; back from the
    // designer, it returns to the theme that was opened.
    val page = LocalPageFocus.current
    if (isTv) {
        LaunchedEffect(state.loaded) {
            if (!state.loaded || inUse == null) return@LaunchedEffect
            if (page?.restore() == true) return@LaunchedEffect
            // Message and buttons, then each section's heading and its themes.
            val ownIndex = own.indexOfFirst { it.id == inUse }
            val index = if (ownIndex >= 0) {
                LIST_ITEMS_BEFORE_THEMES + 1 + ownIndex
            } else {
                LIST_ITEMS_BEFORE_THEMES + (if (own.isEmpty()) 0 else 1 + own.size) + 1 + builtIn.indexOfFirst { it.id == inUse }
            }
            listState.scrollToItem(index.coerceAtLeast(0))
            firstFocus.requestWhenReady()
        }
    }
    val shown = state.themes.firstOrNull { it.id == focusedId } ?: state.themes.firstOrNull { it.id == inUse }

    SettingsPage(
        title = "Themes",
        trail = if (viewModel.forProfile && profileName != null) listOf("Profiles", profileName) else listOf("Settings"),
        listState = listState,
        // Always there, even before the themes load: a phone's list keeps its first item in place,
        // so a preview that arrived later would sit above it, scrolled away.
        aside = { shown?.let { ThemePreview(it.spec, Modifier.fillMaxWidth()) } },
        modifier = modifier,
    ) {
        item(key = "message") { message?.let { (text, problem) -> SettingsNotice(text, isError = problem) } }
        item(key = "search") { SettingsSearch(query, onQueryChange = { query = it }, label = "Find a theme") }
        item(key = "actions") {
            SettingsButtons {
                OsdButton("New theme", onClick = { inUse?.let { viewModel.copy(it, onEdit) } }, icon = Icons.Filled.Add)
                OsdButton("Import", onClick = ::importFile, icon = Icons.Filled.FileUpload)
            }
        }
        if (own.isNotEmpty()) {
            section("Custom themes")
            items(own, key = { it.id }) { summary ->
                ThemeOption(summary, summary.id == inUse, viewModel, onEdit, ::export, firstFocus, onFocused = { focusedId = summary.id })
            }
        }
        if (builtIn.isNotEmpty()) section("Built in")
        if (own.isEmpty() && builtIn.isEmpty()) item(key = "none") { SettingsNotice("No theme has that name.") }
        items(builtIn, key = { it.id }) { summary ->
            ThemeOption(summary, summary.id == inUse, viewModel, onEdit, ::export, firstFocus, onFocused = { focusedId = summary.id })
        }
    }
}

/** A theme in the list: a radio when worn, its colors, its name and kind, and its menu. */
@Composable
private fun ThemeOption(
    summary: ThemeSummary,
    inUse: Boolean,
    viewModel: ThemesViewModel,
    onEdit: (Long) -> Unit,
    export: (ThemeSummary) -> Unit,
    firstFocus: FocusRequester,
    onFocused: () -> Unit,
) {
    val sheets = LocalSettingsSheets.current
    OptionRow(
        title = summary.spec.name,
        summary = listOfNotNull(
            if (summary.spec.isLight) "Light" else "Dark",
            "scanlines".takeIf { summary.spec.effects.scanlines },
            "the default".takeIf { summary.spec.key == DEFAULT_THEME_KEY },
        ).joinToString(", "),
        selected = inUse,
        onClick = { viewModel.use(summary.id) },
        leading = { ThemeSwatches(summary.spec) },
        actions = buildList {
            if (summary.isBuiltIn) {
                add(MenuAction("Copy and edit", Icons.Filled.Edit, detail = "Built-in themes stay as they are; you edit a copy.") { viewModel.copy(summary.id, onEdit) })
            } else {
                add(MenuAction("Edit", Icons.Filled.Edit, detail = "Its colors, type, guide rows and effects.") { onEdit(summary.id) })
            }
            add(MenuAction("Save as file", Icons.Filled.FileDownload, detail = "To keep a copy, or use it on another device.") { export(summary) })
            if (!summary.isBuiltIn) {
                add(
                    MenuAction("Delete", Icons.Filled.Delete, detail = "Profiles using it go back to the default.", danger = true) {
                        sheets.show(ConfirmSheet("Delete ${summary.spec.name}?", "Profiles using it go back to the default.", "Delete", Icons.Filled.Delete) { viewModel.delete(summary.id) })
                    },
                )
            }
        },
        modifier = Modifier
            .onFocusChanged { if (it.hasFocus) onFocused() }
            .let { if (inUse) it.focusRequester(firstFocus) else it },
    )
}

/** The message, the search and the buttons come before the themes in the list. */
private const val LIST_ITEMS_BEFORE_THEMES = 3
