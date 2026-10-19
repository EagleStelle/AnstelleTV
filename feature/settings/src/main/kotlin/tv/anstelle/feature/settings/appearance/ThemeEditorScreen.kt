package tv.anstelle.feature.settings.appearance

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.TableRows
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Title
import androidx.compose.material.icons.filled.ViewWeek
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import tv.anstelle.core.common.ApplicationScope
import tv.anstelle.core.data.ProfileRepository
import tv.anstelle.core.data.ThemeRepository
import tv.anstelle.core.data.themes.ColorRole
import tv.anstelle.core.data.themes.ThemeChoice
import tv.anstelle.core.data.themes.colorRoles
import tv.anstelle.core.data.themes.of
import tv.anstelle.core.data.themes.readability
import tv.anstelle.core.data.themes.themeChoices
import tv.anstelle.core.data.themes.with
import tv.anstelle.core.model.Oklch
import tv.anstelle.core.model.ThemeContrast
import tv.anstelle.core.model.ThemeSpec
import tv.anstelle.core.theme.ControlShape
import tv.anstelle.core.theme.LocalAnstelleTheme
import tv.anstelle.core.theme.ResolvedTheme
import tv.anstelle.core.theme.components.AnstelleTextField
import tv.anstelle.core.theme.components.ConfirmSheet
import tv.anstelle.core.theme.components.DangerButton
import tv.anstelle.core.theme.components.FocusSurface
import tv.anstelle.core.theme.components.LinkRow
import tv.anstelle.core.theme.components.OptionRow
import tv.anstelle.core.theme.components.OsdButton
import tv.anstelle.core.theme.components.PageFocusScope
import tv.anstelle.core.theme.components.SegmentLadder
import tv.anstelle.core.theme.components.SettingsButtons
import tv.anstelle.core.theme.components.SettingsNotice
import tv.anstelle.core.theme.components.SettingsPage
import tv.anstelle.core.theme.components.TextRow
import tv.anstelle.core.theme.components.foot
import tv.anstelle.core.theme.components.rememberInitialFocus
import tv.anstelle.core.theme.components.rememberSettingsSheets
import tv.anstelle.core.theme.components.section
import tv.anstelle.core.theme.components.settingsEdge
import javax.inject.Inject
import kotlin.math.roundToInt

@HiltViewModel
class ThemeEditorViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val themes: ThemeRepository,
    private val profiles: ProfileRepository,
    @ApplicationScope private val appScope: CoroutineScope,
) : ViewModel() {
    /** Matches the `themeId` argument of the navigation route. */
    private val themeId: Long = checkNotNull(savedState.get<Long>("themeId"))

    private val _spec = MutableStateFlow<ThemeSpec?>(null)
    val spec: StateFlow<ThemeSpec?> = _spec.asStateFlow()

    /** The theme as last saved; [spec] differs from it while there are edits to save. */
    private val saved = MutableStateFlow<ThemeSpec?>(null)

    val unsaved: StateFlow<Boolean> = combine(_spec, saved) { edited, kept -> edited != kept }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** True once it turns out there is no theme of the user's own to edit. */
    private val _gone = MutableStateFlow(false)
    val gone: StateFlow<Boolean> = _gone.asStateFlow()

    val inUse: StateFlow<Boolean> = profiles.observeActiveProfile().map { it.themeId == themeId }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    init {
        viewModelScope.launch {
            val theme = themes.observeTheme(themeId).first()
            if (theme == null || theme.isBuiltIn) {
                _gone.value = true
            } else {
                saved.value = theme.spec
                _spec.value = theme.spec
            }
        }
    }

    /** Changes the theme here; nothing is kept until [save]. */
    fun change(transform: (ThemeSpec) -> ThemeSpec) {
        _spec.value = transform(_spec.value ?: return)
    }

    /** Keeps the edits; the app scope finishes the write even if the page closes. A failed write leaves Save on. */
    fun save() {
        appScope.launch { runCatching { saveEdits() } }
    }

    /** Saves any edits first, so the theme put on is the one on screen. */
    fun use() {
        appScope.launch {
            if (runCatching { saveEdits() }.isSuccess) themes.use(profiles.activeProfile().id, themeId)
        }
    }

    fun delete() {
        appScope.launch {
            themes.delete(themeId)
            _gone.value = true
        }
    }

    private suspend fun saveEdits() {
        val edited = _spec.value ?: return
        if (edited == saved.value) return
        themes.save(themeId, edited)
        saved.value = edited
    }
}

/**
 * The theme designer: the theme's name, each color role (opened into brightness, saturation
 * and hue ladders, or a hex code), then each Type and Screen choice (opened into its values),
 * every one on its own page beside the preview, kept by Done and dropped by Discard or Back. Edits wait for
 * Save; leaving with edits unsaved asks to discard them. A worn theme changes the whole app once saved. Colors that are hard to read
 * against each other are named at the top, with which one to make brighter or darker.
 */
@Composable
fun ThemeEditorScreen(
    onGone: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ThemeEditorViewModel = hiltViewModel(),
) {
    val spec by viewModel.spec.collectAsStateWithLifecycle()
    val gone by viewModel.gone.collectAsStateWithLifecycle()
    val inUse by viewModel.inUse.collectAsStateWithLifecycle()
    val unsaved by viewModel.unsaved.collectAsStateWithLifecycle()
    LaunchedEffect(gone) { if (gone) onGone() }
    val current = spec ?: return
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    // The menu keeps its scroll and focused row while a color or choice is open, so Done lands back on it.
    val panes = rememberSaveableStateHolder()

    Box(modifier) {
        val role = editing?.let { key -> colorRoles(current.colors).firstOrNull { it.key == key } }
        val choice = editing?.let { key -> themeChoices.firstOrNull { it.key == key } }
        val close = { editing = null }
        // A pane's edits reach the theme only through Done.
        val apply = { edited: ThemeSpec ->
            viewModel.change { edited }
            editing = null
        }
        when {
            role != null -> PageFocusScope { ColorEditor(current, role, onApply = apply, onClose = close) }
            choice != null -> PageFocusScope { ChoiceEditor(current, choice, onApply = apply, onClose = close) }
            else -> panes.SaveableStateProvider("menu") {
                PageFocusScope { EditorMenu(current, inUse, unsaved, viewModel, onOpen = { editing = it }, onDiscard = onGone) }
            }
        }
    }
}

@Composable
private fun EditorMenu(
    spec: ThemeSpec,
    inUse: Boolean,
    unsaved: Boolean,
    viewModel: ThemeEditorViewModel,
    onOpen: (String) -> Unit,
    onDiscard: () -> Unit,
) {
    val firstFocus = rememberInitialFocus()
    val sheets = rememberSettingsSheets()
    BackHandler(enabled = unsaved) {
        sheets.show(ConfirmSheet("Discard changes?", "Edits since you last saved will be lost.", "Discard", Icons.Filled.Delete, onDiscard))
    }
    SettingsPage(
        title = spec.name,
        trail = listOf("Settings", "Themes"),
        aside = { Preview(spec) },
        sheets = sheets,
    ) {
        val problems = ThemeContrast.problems(spec.colors)
        for ((floor, _) in problems) {
            item(key = "problem-${floor.foreground}-${floor.background}") {
                SettingsNotice(readability(floor, spec.colors), isError = true)
            }
        }
        item(key = "actions") {
            SettingsButtons {
                OsdButton("Save", onClick = viewModel::save, icon = Icons.Filled.Save, primary = true, enabled = unsaved)
                if (!inUse) OsdButton("Use theme", onClick = viewModel::use, icon = Icons.Filled.Check)
            }
        }
        item(key = "name") {
            TextRow(
                title = "Name",
                value = spec.name,
                filter = { it.take(32) },
                onSave = { name ->
                    if (name.isBlank()) {
                        "Give the theme a name."
                    } else {
                        viewModel.change { it.copy(name = name) }
                        null
                    }
                },
                icon = Icons.Filled.Title,
                modifier = Modifier.focusRequester(firstFocus),
            )
        }
        section("Colors")
        colorRows(spec, onOpen)
        for ((heading, choices) in themeChoices.groupBy { it.section }) {
            section(heading)
            for (choice in choices) {
                item(key = "choice-${choice.key}") {
                    LinkRow(
                        title = choice.title,
                        summary = choice.labels.getOrNull(choice.selected(spec)),
                        icon = ChoiceIcons[choice.key],
                        onClick = { onOpen(choice.key) },
                    )
                }
            }
        }
        foot {
            DangerButton(
                text = "Delete theme",
                question = "Delete ${spec.name}?",
                explanation = "Profiles using it switch back to Rose.",
                confirm = "Delete",
                onConfirm = viewModel::delete,
            )
        }
    }
}

/** Each Type and Screen choice's icon, by [ThemeChoice.key]. */
private val ChoiceIcons = mapOf(
    "display" to Icons.Filled.Title,
    "body" to Icons.Filled.TextFields,
    "scale" to Icons.Filled.FormatSize,
    "density" to Icons.Filled.TableRows,
    "progress" to Icons.Filled.ViewWeek,
    "scanlines" to Icons.Filled.AutoAwesome,
)

/** The theme as it would look; one picture for every setting in the designer. */
@Composable
private fun Preview(spec: ThemeSpec) = ThemePreview(spec, Modifier.fillMaxWidth())

/**
 * A color or choice opened from the menu ([id]): its own page beside the preview, editing its own
 * copy of [spec]. [content] reads the copy and changes it through `edit`. Done hands the copy to
 * the theme; Discard, red and on only while there are edits, or Back drops it. The two head the
 * page on every device, as Save and Use theme head the menu.
 */
@Composable
private fun DesignerPane(
    title: String,
    id: String,
    spec: ThemeSpec,
    onApply: (ThemeSpec) -> Unit,
    onClose: () -> Unit,
    content: LazyListScope.(working: ThemeSpec, edit: ((ThemeSpec) -> ThemeSpec) -> Unit) -> Unit,
) {
    var working by remember(id) { mutableStateOf(spec) }
    BackHandler(onBack = onClose)
    SettingsPage(title = title, trail = listOf("Settings", "Themes", spec.name), aside = { Preview(working) }) {
        item(key = "actions") {
            SettingsButtons {
                OsdButton("Done", onClick = { onApply(working) }, icon = Icons.Filled.Check, primary = true)
                OsdButton("Discard", onClick = onClose, icon = Icons.Filled.Delete, enabled = working != spec, danger = true)
            }
        }
        content(working) { transform -> working = transform(working) }
    }
}

/** One choice, opened: its values as a radio list; a pick shows in the preview, and Done keeps it. */
@Composable
private fun ChoiceEditor(spec: ThemeSpec, choice: ThemeChoice, onApply: (ThemeSpec) -> Unit, onClose: () -> Unit) {
    val firstFocus = rememberInitialFocus()
    DesignerPane(choice.title, choice.key, spec, onApply, onClose) { working, edit ->
        val selected = choice.selected(working)
        choice.labels.forEachIndexed { i, label ->
            item(key = "option-$i") {
                OptionRow(
                    title = label,
                    selected = i == selected,
                    onClick = { edit { choice.apply(it, i) } },
                    modifier = if (i == selected.coerceAtLeast(0)) Modifier.focusRequester(firstFocus) else Modifier,
                )
            }
        }
    }
}

private fun LazyListScope.colorRows(spec: ThemeSpec, onOpen: (String) -> Unit) {
    for (role in colorRoles(spec.colors)) {
        item(key = "color-${role.key}") {
            val hex = spec.colors.of(role.key)
            LinkRow(
                title = role.label,
                summary = role.about,
                value = hex.uppercase(),
                leading = { Swatch(hex, Modifier.size(24.dp)) },
                onClick = { onOpen(role.key) },
            )
        }
    }
}

/** A color as a flat square with a hairline, so a color close to the ground still shows its edge. */
@Composable
private fun Swatch(hex: String, modifier: Modifier) {
    Box(modifier.background(ResolvedTheme.parseColor(hex), ControlShape).border(1.dp, LocalAnstelleTheme.current.line, ControlShape))
}

/**
 * One color, opened: its swatch, brightness, saturation and hue as ladders (left and right
 * on the D-pad, tap or drag on touch) and its hex code, with whether it is easy to read
 * against the colors it has to work with.
 */
@Composable
private fun ColorEditor(spec: ThemeSpec, role: ColorRole, onApply: (ThemeSpec) -> Unit, onClose: () -> Unit) {
    // The ladders' own position: a hex code alone loses the hue of a grey and the chroma sRGB can't hold.
    var color by remember(role.key) { mutableStateOf(Oklch.fromHex(spec.colors.of(role.key))) }
    val firstFocus = rememberInitialFocus()
    DesignerPane(role.label, role.key, spec, onApply, onClose) { working, edit ->
        val hex = working.colors.of(role.key)
        fun put(value: String) = edit { it.copy(colors = it.colors.with(role.key, value)) }
        fun set(next: Oklch) {
            color = next
            put(next.toHex())
        }
        item(key = "lightness") {
            LadderRow("Brightness", "${(color.lightness * 100).roundToInt()}%", color.lightness.toFloat(),
                onStep = { by -> set(color.copy(lightness = (color.lightness + by * 0.01).coerceIn(0.0, 1.0))) },
                onSet = { f -> set(color.copy(lightness = f.toDouble())) },
                modifier = Modifier.focusRequester(firstFocus),
            )
        }
        item(key = "chroma") {
            LadderRow("Saturation", "${(color.chroma / Oklch.MAX_CHROMA * 100).roundToInt()}%", (color.chroma / Oklch.MAX_CHROMA).toFloat(),
                onStep = { by -> set(color.copy(chroma = (color.chroma + by * 0.005).coerceIn(0.0, Oklch.MAX_CHROMA))) },
                onSet = { f -> set(color.copy(chroma = f * Oklch.MAX_CHROMA)) },
            )
        }
        item(key = "hue") {
            LadderRow("Hue", "${color.hue.roundToInt()}°", (color.hue / 360).toFloat(),
                onStep = { by -> set(color.copy(hue = (color.hue + by * 5).mod(360.0))) },
                onSet = { f -> set(color.copy(hue = f * 359.0)) },
            )
        }
        item(key = "hex") {
            var text by remember(hex) { mutableStateOf(hex.uppercase()) }
            AnstelleTextField(
                value = text,
                onValueChange = { value ->
                    text = value.uppercase().filter { it == '#' || it.isDigit() || it in 'A'..'F' }.take(7)
                    val candidate = if (text.startsWith("#")) text else "#$text"
                    if (candidate.length == 7 && ThemeContrast.isColor(candidate)) {
                        color = Oklch.fromHex(candidate)
                        put(candidate)
                    }
                },
                label = "Hex code",
                keyboardType = KeyboardType.Ascii,
                isLast = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = settingsEdge, vertical = 8.dp),
            )
        }
        val pairs = ThemeContrast.floors(working.colors).filter { it.foreground == role.key || it.background == role.key }
        if (pairs.isNotEmpty()) section("Readability")
        for (floor in pairs) {
            item(key = "floor-${floor.foreground}-${floor.background}") {
                SettingsNotice(readability(floor, working.colors, open = role.key), isError = ThemeContrast.ratio(floor, working.colors) < floor.minimum)
            }
        }
    }
}

/**
 * A value on a ladder: left and right step it on the D-pad; on touch a tap or a drag along
 * the ladder sets it. Flat on its ground like every settings row; focus fills it.
 */
@Composable
private fun LadderRow(title: String, value: String, fraction: Float, onStep: (Int) -> Unit, onSet: (Float) -> Unit, modifier: Modifier = Modifier) {
    val theme = LocalAnstelleTheme.current
    FocusSurface(
        onClick = {},
        color = Color.Transparent,
        modifier = modifier.fillMaxWidth().onPreviewKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            when (event.key) {
                Key.DirectionLeft -> onStep(-1).let { true }
                Key.DirectionRight -> onStep(1).let { true }
                else -> false
            }
        },
    ) { focused ->
        Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.Text(title, style = androidx.compose.material3.MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium), modifier = Modifier.weight(1f))
                androidx.compose.material3.Text(value, style = androidx.compose.material3.MaterialTheme.typography.labelLarge)
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(24.dp)
                    .pointerInput(onSet) { detectTapGestures { onSet((it.x / size.width).coerceIn(0f, 1f)) } }
                    .pointerInput(onSet) { detectHorizontalDragGestures { change, _ -> onSet((change.position.x / size.width).coerceIn(0f, 1f)) } },
                contentAlignment = Alignment.Center,
            ) {
                SegmentLadder(fraction, color = if (focused) theme.onAccent else theme.accent)
            }
        }
    }
}
