package tv.anstelle.feature.settings.appearance

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
import androidx.compose.material.icons.filled.FormatSize
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
import tv.anstelle.core.theme.components.DangerButton
import tv.anstelle.core.theme.components.FocusSurface
import tv.anstelle.core.theme.components.LinkRow
import tv.anstelle.core.theme.components.OsdButton
import tv.anstelle.core.theme.components.SegmentLadder
import tv.anstelle.core.theme.components.SettingsNotice
import tv.anstelle.core.theme.components.SettingsPage
import tv.anstelle.core.theme.components.TextRow
import tv.anstelle.core.theme.components.foot
import tv.anstelle.core.theme.components.rememberInitialFocus
import tv.anstelle.core.theme.components.rememberSettingsSheets
import tv.anstelle.core.theme.components.section
import tv.anstelle.core.theme.components.settingsEdge
import tv.anstelle.feature.settings.Draft
import tv.anstelle.feature.settings.EditPane
import tv.anstelle.feature.settings.Edits
import tv.anstelle.feature.settings.PanedPage
import tv.anstelle.feature.settings.SaveButtons
import tv.anstelle.feature.settings.SaveOnBack
import tv.anstelle.feature.settings.choiceRows
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

    private val edits = Edits<ThemeSpec>(appScope) { themes.save(themeId, it) }
    val draft: StateFlow<Draft<ThemeSpec>?> = edits.draft

    /** True once it turns out there is no theme of the user's own to edit. */
    private val _gone = MutableStateFlow(false)
    val gone: StateFlow<Boolean> = _gone.asStateFlow()

    val inUse: StateFlow<Boolean> = profiles.observeActiveProfile().map { it.themeId == themeId }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    init {
        viewModelScope.launch {
            val theme = themes.observeTheme(themeId).first()
            if (theme == null || theme.isBuiltIn) _gone.value = true else edits.load(theme.spec)
        }
    }

    fun change(transform: (ThemeSpec) -> ThemeSpec) = edits.change(transform)

    fun save() = edits.save()

    fun discard() = edits.discard()

    /** Saves any edits first, so the theme put on is the one on screen. */
    fun use() = edits.save { themes.use(profiles.activeProfile().id, themeId) }

    fun delete() {
        appScope.launch {
            themes.delete(themeId)
            _gone.value = true
        }
    }
}

/**
 * The theme designer: the theme's name, each color role (opened into brightness, saturation
 * and hue ladders, or a hex code), then each Type and Screen choice (opened into its values),
 * every one on its own page beside the preview, kept by Done and dropped by Discard or Back. Edits wait for
 * Save (Discard drops them); leaving with edits unsaved asks to save or discard them. A worn theme changes the whole app once saved. Colors that are hard to read
 * against each other are named at the top, with which one to make brighter or darker.
 */
@Composable
fun ThemeEditorScreen(
    onGone: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ThemeEditorViewModel = hiltViewModel(),
) {
    val draft by viewModel.draft.collectAsStateWithLifecycle()
    val gone by viewModel.gone.collectAsStateWithLifecycle()
    val inUse by viewModel.inUse.collectAsStateWithLifecycle()
    LaunchedEffect(gone) { if (gone) onGone() }
    val edits = draft ?: return
    val current = edits.value
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    val role = editing?.let { key -> colorRoles(current.colors).firstOrNull { it.key == key } }
    val choice = editing?.let { key -> themeChoices.firstOrNull { it.key == key } }
    val close = { editing = null }
    // A pane's edits reach the theme only through Done.
    val apply = { edited: ThemeSpec ->
        viewModel.change { edited }
        editing = null
    }
    val pane: (@Composable () -> Unit)? = when {
        role != null -> { { ColorEditor(current, role, onApply = apply, onClose = close) } }
        choice != null -> { { ChoiceEditor(current, choice, onApply = apply, onClose = close) } }
        else -> null
    }
    PanedPage(pane, modifier) {
        EditorMenu(current, inUse, edits.unsaved, viewModel, onOpen = { editing = it }, onLeave = onGone)
    }
}

@Composable
private fun EditorMenu(
    spec: ThemeSpec,
    inUse: Boolean,
    unsaved: Boolean,
    viewModel: ThemeEditorViewModel,
    onOpen: (String) -> Unit,
    onLeave: () -> Unit,
) {
    val firstFocus = rememberInitialFocus()
    val sheets = rememberSettingsSheets()
    SaveOnBack(unsaved, sheets, viewModel::save, onLeave)
    SettingsPage(
        title = spec.name,
        trail = listOf("Settings", "Themes"),
        aside = { Preview(spec) },
        actions = {
            SaveButtons(unsaved, viewModel::save, viewModel::discard)
            if (!inUse) OsdButton("Use theme", onClick = viewModel::use, icon = Icons.Filled.Check)
        },
        sheets = sheets,
    ) {
        val problems = ThemeContrast.problems(spec.colors)
        for ((floor, _) in problems) {
            item(key = "problem-${floor.foreground}-${floor.background}") {
                SettingsNotice(readability(floor, spec.colors), isError = true)
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

/** A color or choice opened from the menu ([id]), beside the preview of its own copy of [spec]. */
@Composable
private fun DesignerPane(
    title: String,
    id: String,
    spec: ThemeSpec,
    onApply: (ThemeSpec) -> Unit,
    onClose: () -> Unit,
    content: LazyListScope.(working: ThemeSpec, edit: ((ThemeSpec) -> ThemeSpec) -> Unit) -> Unit,
) = EditPane(title, id, spec, listOf("Settings", "Themes", spec.name), onApply, onClose, aside = { Preview(it) }, content = content)

/** One choice, opened: its values as a radio list; a pick shows in the preview, and Done keeps it. */
@Composable
private fun ChoiceEditor(spec: ThemeSpec, choice: ThemeChoice, onApply: (ThemeSpec) -> Unit, onClose: () -> Unit) {
    val firstFocus = rememberInitialFocus()
    DesignerPane(choice.title, choice.key, spec, onApply, onClose) { working, edit ->
        choiceRows("option", choice.labels.indices.toList(), choice.selected(working), choice.labels::get, onPick = { i -> edit { choice.apply(it, i) } }, firstFocus = firstFocus)
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
