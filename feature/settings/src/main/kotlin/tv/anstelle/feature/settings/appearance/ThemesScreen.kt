package tv.anstelle.feature.settings.appearance

import android.content.ActivityNotFoundException
import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
import tv.anstelle.core.theme.LocalAnstelleTheme
import tv.anstelle.core.theme.LocalIsTv
import tv.anstelle.core.theme.components.ActionDialog
import tv.anstelle.core.theme.components.DialogAction
import tv.anstelle.core.theme.components.FocusSurface
import tv.anstelle.core.theme.components.FocusTile
import tv.anstelle.core.theme.components.MenuPage
import tv.anstelle.core.theme.components.Notice
import tv.anstelle.core.theme.components.SectionLabel
import tv.anstelle.core.theme.components.requestWhenReady
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
    private val profileFlow = (savedState.get<Long>("profileId") ?: 0L).let { id ->
        if (id > 0) profiles.observeProfile(id) else profiles.observeActiveProfile()
    }

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
            exporting != null -> "This device can't save files. In a browser paired with Web control, open $base/api/themes/$exporting to save it."
            else -> "This device can't open files. From a browser paired with Web control, send the file to $base/api/themes."
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
 * The themes a profile can wear, each with its colors at a glance. OK or a tap puts one on at
 * once, so the whole app shows it; holding it (or Menu) offers changing a copy, saving it as
 * a file and deleting one of the user's own. On TV and tablets a panel beside the list shows
 * the focused theme without putting it on.
 */
@Composable
fun ThemesScreen(
    onEdit: (themeId: Long) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ThemesViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val theme = LocalAnstelleTheme.current
    val isTv = LocalIsTv.current
    val wide = isTv || LocalConfiguration.current.screenWidthDp >= WIDE_DP
    val context = LocalContext.current
    var focusedId by remember { mutableLongStateOf(0L) }
    var actionsFor by remember { mutableStateOf<ThemeSummary?>(null) }
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
    val subtitle = when {
        isTv -> "Press OK to use a theme${profileName?.let { " on $it" }.orEmpty()}. Press Menu to copy, change or save one."
        else -> "Tap a theme to use it${profileName?.let { " on $it" }.orEmpty()}. Touch and hold one to copy, change or save it."
    }
    // On TV focus starts on the theme worn, once the list is there to hold it.
    if (isTv) {
        LaunchedEffect(state.loaded) {
            val index = state.themes.indexOfFirst { it.id == inUse }
            if (!state.loaded || index < 0) return@LaunchedEffect
            listState.scrollToItem(index + LIST_ITEMS_BEFORE_THEMES)
            firstFocus.requestWhenReady()
        }
    }
    Row(modifier.fillMaxSize().background(theme.background)) {
        Box(if (wide) Modifier.weight(0.56f) else Modifier.weight(1f)) {
            MenuPage(title = "Themes", subtitle = subtitle, listState = listState) {
                item(key = "message") { message?.let { (text, problem) -> Notice(text, isError = problem) } }
                if (state.themes.isNotEmpty()) item(key = "built-in") { SectionLabel("Built in") }
                items(state.themes, key = { it.id }) { summary ->
                    if (!summary.isBuiltIn && summary == state.themes.first { !it.isBuiltIn }) SectionLabel("Designed here")
                    ThemeRow(
                        summary = summary,
                        inUse = summary.id == inUse,
                        onClick = { viewModel.use(summary.id) },
                        onMore = { actionsFor = summary },
                        modifier = Modifier
                            .fillMaxWidth()
                            .onFocusChanged { if (it.isFocused) focusedId = summary.id }
                            .let { if (summary.id == inUse) it.focusRequester(firstFocus) else it },
                    )
                }
                item(key = "own") { SectionLabel("Your own") }
                item(key = "new") {
                    FocusTile(
                        title = "Design a theme",
                        subtitle = "Starts as a copy of the theme in use.",
                        icon = Icons.Filled.Add,
                        onClick = { inUse?.let { viewModel.copy(it, onEdit) } },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                item(key = "import") {
                    FocusTile(
                        title = "Add from a file",
                        subtitle = "A theme saved from AnstelleTV on this or another device.",
                        icon = Icons.Filled.FileUpload,
                        onClick = ::importFile,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
        if (wide) {
            Box(Modifier.width(1.dp).fillMaxHeight().background(theme.line))
            val shown = state.themes.firstOrNull { it.id == focusedId } ?: state.themes.firstOrNull { it.id == inUse }
            LazyColumn(
                Modifier.weight(0.44f).fillMaxHeight().background(theme.surface),
                contentPadding = PaddingValues(if (isTv) 40.dp else 24.dp),
            ) {
                if (shown != null) item { ThemePreview(shown.spec, Modifier.fillMaxWidth()) }
            }
        }
    }

    actionsFor?.let { summary ->
        ActionDialog(
            actions = buildList {
                add(DialogAction("Use this theme", profileName?.let { "On $it, right away." } ?: "Right away.", Icons.Filled.Check) { actionsFor = null; viewModel.use(summary.id) })
                if (summary.isBuiltIn) {
                    add(DialogAction("Copy and change", "Built-in themes stay as they are; you change a copy.", Icons.Filled.Edit) { actionsFor = null; viewModel.copy(summary.id, onEdit) })
                } else {
                    add(DialogAction("Change", "Its colors, fonts, guide rows and effects.", Icons.Filled.Edit) { actionsFor = null; onEdit(summary.id) })
                }
                add(DialogAction("Save as a file", "To keep a copy, or use it on another device.", Icons.Filled.FileDownload) { actionsFor = null; export(summary) })
                if (!summary.isBuiltIn) {
                    add(DialogAction("Delete", "Profiles using it go back to the default.", Icons.Filled.Delete) { actionsFor = null; viewModel.delete(summary.id) })
                }
            },
            onDismiss = { actionsFor = null },
        ) {
            androidx.compose.material3.Text(summary.spec.name, style = androidx.compose.material3.MaterialTheme.typography.headlineSmall, color = theme.text)
            ThemeSwatches(summary.spec)
        }
    }
}

/** A theme in the list: its colors, its name, whether it's built in, and a check when worn. */
@Composable
private fun ThemeRow(summary: ThemeSummary, inUse: Boolean, onClick: () -> Unit, onMore: () -> Unit, modifier: Modifier) {
    val theme = LocalAnstelleTheme.current
    val isTv = LocalIsTv.current
    FocusSurface(
        onClick = onClick,
        onLongClick = onMore,
        modifier = modifier.onPreviewKeyEvent { event ->
            if (event.type == KeyEventType.KeyDown && event.key == Key.Menu) onMore().let { true } else false
        },
    ) { focused ->
        Row(
            Modifier
                .defaultMinSize(minHeight = if (isTv) 64.dp else 56.dp)
                .padding(horizontal = if (isTv) 20.dp else 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ThemeSwatches(summary.spec)
            Column(Modifier.weight(1f)) {
                androidx.compose.material3.Text(
                    summary.spec.name,
                    style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
                    color = if (inUse && !focused) theme.accent else androidx.compose.material3.LocalContentColor.current,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val about = listOfNotNull(
                    if (summary.spec.isLight) "Light" else "Dark",
                    "Scanlines".takeIf { summary.spec.effects.scanlines },
                    "Default".takeIf { summary.spec.key == DEFAULT_THEME_KEY },
                ).joinToString(" · ")
                androidx.compose.material3.Text(
                    about,
                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                    color = if (focused) theme.onAccent.copy(alpha = 0.76f) else theme.textMuted,
                    maxLines = 1,
                )
            }
            if (inUse) {
                androidx.compose.material3.Icon(
                    Icons.Filled.Check,
                    contentDescription = "In use",
                    tint = if (focused) theme.onAccent else theme.accent,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

private const val WIDE_DP = 600

/** The message and the first section label come before the themes in the list (TV has no subtitle row). */
private const val LIST_ITEMS_BEFORE_THEMES = 2
