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
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material.icons.filled.RoundedCorner
import androidx.compose.material.icons.filled.TableRows
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Title
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
import tv.anstelle.core.model.FontChoice
import tv.anstelle.core.model.GuideDensity
import tv.anstelle.core.model.MAX_RADIUS_DP
import tv.anstelle.core.model.Oklch
import tv.anstelle.core.model.ThemeColors
import tv.anstelle.core.model.ThemeContrast
import tv.anstelle.core.model.ThemeSpec
import tv.anstelle.core.theme.ControlShape
import tv.anstelle.core.theme.LocalAnstelleTheme
import tv.anstelle.core.theme.ResolvedTheme
import tv.anstelle.core.theme.components.AnstelleTextField
import tv.anstelle.core.theme.components.ChoiceRow
import tv.anstelle.core.theme.components.DangerButton
import tv.anstelle.core.theme.components.FocusSurface
import tv.anstelle.core.theme.components.LinkRow
import tv.anstelle.core.theme.components.OsdButton
import tv.anstelle.core.theme.components.PageFocusScope
import tv.anstelle.core.theme.components.SegmentLadder
import tv.anstelle.core.theme.components.SettingsButtons
import tv.anstelle.core.theme.components.SettingsNotice
import tv.anstelle.core.theme.components.SettingsPage
import tv.anstelle.core.theme.components.TextRow
import tv.anstelle.core.theme.components.foot
import tv.anstelle.core.theme.components.rememberInitialFocus
import tv.anstelle.core.theme.components.section
import tv.anstelle.core.theme.components.settingsEdge
import tv.anstelle.core.theme.components.settingsGuided
import javax.inject.Inject
import kotlin.math.roundToInt

/** A color role as the designer names it. */
internal class ColorRole(val key: String, val label: String, val about: String)

internal fun colorRoles(colors: ThemeColors): List<ColorRole> = listOf(
    ColorRole("background", "Ground", "Every screen sits on it."),
    ColorRole("surface", "Rows", "Menu rows, panels and buttons at rest."),
    ColorRole("surfaceHigh", "Pressed rows", "A row while a finger presses it."),
    ColorRole("line", "Lines", "Hairlines, empty slots and empty ladder segments."),
    ColorRole("text", "Text", "Titles and everything you read."),
    ColorRole("textMuted", "Quiet text", "Times, descriptions and what is less important."),
    ColorRole("accent", "Focus and signal", "Focus, the now line, number blocks and on-air marks."),
    ColorRole("onAccent", "Text on focus", "Text and icons on the focus color."),
    ColorRole("accentContainer", "Selected", "Selected things that don't have focus."),
    ColorRole("onAccentContainer", "Text on selected", "Text on the selected color."),
    ColorRole("guideCell", "Guide: on now", "The program on air and its later airings."),
    ColorRole("guideCellPast", "Guide: aired", "Programs that have finished."),
    ColorRole("nowLine", "Now line", "The line through the guide at the current time."),
    ColorRole("banner", "Banner", "The player's banner and panels over video."),
    ColorRole("bannerText", "Banner text", "Text on the banner."),
) + colors.guideRuns.indices.map { i -> ColorRole("guideRuns[$i]", "Guide: later ${i + 1}", "Later programs of other shows take these in turn.") }

internal fun ThemeColors.of(role: String): String = all().first { it.first == role }.second

internal fun ThemeColors.with(role: String, hex: String): ThemeColors = when (role) {
    "background" -> copy(background = hex)
    "surface" -> copy(surface = hex)
    "surfaceHigh" -> copy(surfaceHigh = hex)
    "line" -> copy(line = hex)
    "text" -> copy(text = hex)
    "textMuted" -> copy(textMuted = hex)
    "accent" -> copy(accent = hex)
    "onAccent" -> copy(onAccent = hex)
    "accentContainer" -> copy(accentContainer = hex)
    "onAccentContainer" -> copy(onAccentContainer = hex)
    "guideCell" -> copy(guideCell = hex)
    "guideCellPast" -> copy(guideCellPast = hex)
    "nowLine" -> copy(nowLine = hex)
    "banner" -> copy(banner = hex)
    "bannerText" -> copy(bannerText = hex)
    else -> {
        val index = role.removePrefix("guideRuns[").removeSuffix("]").toInt()
        copy(guideRuns = guideRuns.mapIndexed { i, run -> if (i == index) hex else run })
    }
}

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

    /** True once it turns out there is no theme of the user's own to edit. */
    private val _gone = MutableStateFlow(false)
    val gone: StateFlow<Boolean> = _gone.asStateFlow()

    val inUse: StateFlow<Boolean> = profiles.observeActiveProfile().map { it.themeId == themeId }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private var saveJob: Job? = null

    init {
        viewModelScope.launch {
            val theme = themes.observeTheme(themeId).first()
            if (theme == null || theme.isBuiltIn) _gone.value = true else _spec.value = theme.spec
        }
    }

    /** Changes the theme here at once and saves it a moment later, so stepping a value writes once. */
    fun change(transform: (ThemeSpec) -> ThemeSpec) {
        val next = transform(_spec.value ?: return)
        _spec.value = next
        saveJob?.cancel()
        saveJob = appScope.launch {
            delay(SAVE_DELAY_MS)
            runCatching { themes.save(themeId, next) }
        }
    }

    fun use() {
        viewModelScope.launch { themes.use(profiles.activeProfile().id, themeId) }
    }

    fun delete() {
        saveJob?.cancel()
        appScope.launch {
            themes.delete(themeId)
            _gone.value = true
        }
    }

    private companion object {
        const val SAVE_DELAY_MS = 300L
    }
}

/**
 * The theme designer: the theme's name, each color role (opened into brightness, saturation
 * and hue ladders, or a hex code), type, guide rows and scanlines. Every change saves itself;
 * a theme being worn changes the whole app as it is edited. Colors that are hard to read
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
    LaunchedEffect(gone) { if (gone) onGone() }
    val current = spec ?: return
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    // The menu keeps its scroll and focused row while a color is open, so Done lands back on that color.
    val panes = rememberSaveableStateHolder()

    Box(modifier) {
        val role = editing?.let { key -> colorRoles(current.colors).firstOrNull { it.key == key } }
        if (role != null) {
            PageFocusScope {
                ColorEditor(current, role, onChange = { hex -> viewModel.change { it.copy(colors = it.colors.with(role.key, hex)) } }, onDone = { editing = null })
            }
        } else {
            panes.SaveableStateProvider("menu") {
                PageFocusScope { EditorMenu(current, inUse, viewModel, onEditColor = { editing = it }) }
            }
        }
    }
}

@Composable
private fun EditorMenu(spec: ThemeSpec, inUse: Boolean, viewModel: ThemeEditorViewModel, onEditColor: (String) -> Unit) {
    val firstFocus = rememberInitialFocus()
    SettingsPage(
        title = spec.name,
        trail = listOf("Settings", "Themes"),
        about = "Edits save as you go. If this theme is in use, every screen shows them right away.",
        aside = { ThemePreview(spec, Modifier.fillMaxWidth()) },
    ) {
        val problems = ThemeContrast.problems(spec.colors)
        for ((floor, _) in problems) {
            item(key = "problem-${floor.foreground}-${floor.background}") {
                SettingsNotice(readability(floor, spec.colors), isError = true)
            }
        }
        if (!inUse) {
            item(key = "use") {
                SettingsButtons { OsdButton("Use theme", onClick = viewModel::use, icon = Icons.Filled.Check, primary = true) }
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
        colorRows(spec, onEditColor)
        section("Type")
        item(key = "display") {
            ChoiceRow(
                title = "Headings",
                value = spec.typography.display,
                choices = FontChoice.entries,
                label = { it.label },
                about = "Page titles, channel numbers, times and field labels.",
                onSelect = { font -> viewModel.change { it.copy(typography = it.typography.copy(display = font)) } },
                icon = Icons.Filled.Title,
            )
        }
        item(key = "body") {
            ChoiceRow(
                title = "Body text",
                value = spec.typography.body,
                choices = FontChoice.entries,
                label = { it.label },
                onSelect = { font -> viewModel.change { it.copy(typography = it.typography.copy(body = font)) } },
                icon = Icons.Filled.TextFields,
            )
        }
        item(key = "scale") {
            ChoiceRow(
                title = "Text size",
                value = TextSizes.nearest(spec.typography.scale),
                choices = TextSizes.sizes,
                label = { "${(it * 100).roundToInt()}%" },
                onSelect = { size -> viewModel.change { it.copy(typography = it.typography.copy(scale = size)) } },
                icon = Icons.Filled.FormatSize,
            )
        }
        section("Guide")
        item(key = "density") {
            ChoiceRow(
                title = "Rows",
                value = spec.guide.density,
                choices = GuideDensity.entries,
                label = { it.label },
                about = "How tall the guide's channel rows are.",
                onSelect = { density -> viewModel.change { it.copy(guide = it.guide.copy(density = density)) } },
                icon = Icons.Filled.TableRows,
            )
        }
        item(key = "radius") {
            ChoiceRow(
                title = "Cell corners",
                value = spec.guide.cellRadiusDp,
                choices = (0..MAX_RADIUS_DP).toList(),
                label = { "$it dp" },
                onSelect = { radius -> viewModel.change { it.copy(guide = it.guide.copy(cellRadiusDp = radius)) } },
                icon = Icons.Filled.RoundedCorner,
            )
        }
        section("Effects")
        item(key = "scanlines") {
            val strength = Scanlines.indexOfFirst { (_, alpha) -> spec.effects.scanlines && alpha == spec.effects.scanlineAlpha }.coerceAtLeast(0)
            ChoiceRow(
                title = "Scanlines",
                value = Scanlines[strength],
                choices = Scanlines,
                label = { it.first },
                about = "A picture tube's lines over the whole screen.",
                onSelect = { (_, alpha) ->
                    viewModel.change { it.copy(effects = it.effects.copy(scanlines = alpha > 0f, scanlineAlpha = if (alpha > 0f) alpha else it.effects.scanlineAlpha)) }
                },
                icon = Icons.Filled.AutoAwesome,
            )
        }
        foot {
            DangerButton(
                text = "Delete theme",
                question = "Delete ${spec.name}?",
                explanation = "Profiles using it go back to the default.",
                confirm = "Delete",
                onConfirm = viewModel::delete,
            )
        }
    }
}

private fun LazyListScope.colorRows(spec: ThemeSpec, onEditColor: (String) -> Unit) {
    for (role in colorRoles(spec.colors)) {
        item(key = "color-${role.key}") {
            val hex = spec.colors.of(role.key)
            LinkRow(
                title = role.label,
                summary = role.about,
                value = hex.uppercase(),
                leading = { Swatch(hex, Modifier.size(24.dp)) },
                onClick = { onEditColor(role.key) },
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
private fun ColorEditor(spec: ThemeSpec, role: ColorRole, onChange: (String) -> Unit, onDone: () -> Unit) {
    BackHandler(onBack = onDone)
    val hex = spec.colors.of(role.key)
    // The ladders' own position: a hex code alone loses the hue of a grey and the chroma sRGB can't hold.
    var color by remember(role.key) { mutableStateOf(Oklch.fromHex(hex)) }
    LaunchedEffect(hex) { if (color.toHex() != hex.uppercase().takeLast(7)) color = Oklch.fromHex(hex) }
    fun set(next: Oklch) {
        color = next
        onChange(next.toHex())
    }
    val firstFocus = rememberInitialFocus()
    val guided = settingsGuided
    SettingsPage(
        title = role.label,
        trail = listOf("Settings", "Themes", spec.name),
        about = role.about,
        aside = {
            Swatch(hex, Modifier.fillMaxWidth().height(56.dp))
            if (guided) ThemePreview(spec, Modifier.fillMaxWidth())
        },
    ) {
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
                        onChange(candidate)
                    }
                },
                label = "Hex code",
                keyboardType = KeyboardType.Ascii,
                isLast = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = settingsEdge, vertical = 8.dp),
            )
        }
        val pairs = ThemeContrast.Floors.filter { it.foreground == role.key || it.background == role.key }
        if (pairs.isNotEmpty()) section("Readability")
        for (floor in pairs) {
            item(key = "floor-${floor.foreground}-${floor.background}") {
                val byRole = spec.colors.all().toMap()
                val ratio = ThemeContrast.ratio(byRole.getValue(floor.foreground), byRole.getValue(floor.background))
                SettingsNotice(readability(floor, spec.colors, open = role.key), isError = ratio < floor.minimum)
            }
        }
        item(key = "done") { SettingsButtons { OsdButton("Done", onClick = onDone, icon = Icons.Filled.Check, primary = true) } }
    }
}

/**
 * Whether [floor]'s two colors read well, in words instead of a contrast ratio, and when they
 * don't, the way to fix it: the lighter one brighter or the darker one darker. In a color's own
 * pane ([open] is its role) the fix starts with that color's Brightness ladder.
 */
private fun readability(floor: ThemeContrast.Floor, colors: ThemeColors, open: String? = null): String {
    val byRole = colors.all().toMap()
    val fore = byRole.getValue(floor.foreground)
    val back = byRole.getValue(floor.background)
    if (ThemeContrast.ratio(fore, back) >= floor.minimum) return "${floor.label} is easy to read."
    val names = colorRoles(colors).associate { it.key to it.label }
    val (light, dark) = if (ThemeContrast.luminance(fore) >= ThemeContrast.luminance(back)) {
        floor.foreground to floor.background
    } else {
        floor.background to floor.foreground
    }
    val fix = when (open) {
        light -> "Raise its brightness, or make ${names.getValue(dark)} darker."
        dark -> "Lower its brightness, or make ${names.getValue(light)} brighter."
        else -> "Make ${names.getValue(light)} brighter or ${names.getValue(dark)} darker."
    }
    return "${floor.label} is hard to read. $fix"
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
                SegmentLadder(fraction, segments = 32, color = if (focused) theme.onAccent else theme.accent)
            }
        }
    }
}

private val FontChoice.label get() = when (this) {
    FontChoice.CONDENSED -> "Condensed"
    FontChoice.SANS -> "Sans"
    FontChoice.SERIF -> "Serif"
    FontChoice.MONO -> "Mono"
}

private val GuideDensity.label get() = when (this) {
    GuideDensity.COMPACT -> "Compact"
    GuideDensity.COMFORTABLE -> "Comfortable"
    GuideDensity.SPACIOUS -> "Spacious"
}

private object TextSizes {
    val sizes = listOf(0.9f, 0.95f, 1f, 1.05f, 1.1f, 1.15f, 1.2f, 1.25f, 1.3f)

    /** The offered size closest to [scale], so a stored value between steps still shows as one. */
    fun nearest(scale: Float): Float = sizes.minBy { kotlin.math.abs(it - scale) }
}

/** Scanline strengths: off, then light to strong. */
private val Scanlines = listOf("Off" to 0f, "Light" to 0.08f, "Medium" to 0.14f, "Strong" to 0.24f)
