package tv.anstelle.core.data.themes

import tv.anstelle.core.model.FontChoice
import tv.anstelle.core.model.GuideDensity
import tv.anstelle.core.model.ProgressStyle
import tv.anstelle.core.model.ThemeColors
import tv.anstelle.core.model.ThemeContrast
import tv.anstelle.core.model.ThemeSpec
import kotlin.math.abs
import kotlin.math.roundToInt

/** A color role as the designer names it. */
class ColorRole(val key: String, val label: String, val about: String)

fun colorRoles(colors: ThemeColors): List<ColorRole> = listOf(
    ColorRole("background", "Background", "Behind every screen."),
    ColorRole("surface", "Rows", "Rows, panels, buttons, programs on now and the player's shading."),
    ColorRole("surfaceHigh", "Pressed rows", "Rows while pressed, and buttons in Settings."),
    ColorRole("line", "Lines", "Dividers, outlines and the empty part of progress bars."),
    ColorRole("text", "Text", "Titles, body text and the player's banner."),
    ColorRole("textMuted", "Secondary text", "Times, descriptions and other supporting text."),
    ColorRole("accent", "Accent", "Focus, the now line, channel numbers and on-air marks."),
    ColorRole("onAccent", "Text on accent", "Text and icons on the accent."),
    ColorRole("accentContainer", "Selected", "Selected items without focus."),
    ColorRole("onAccentContainer", "Text on selected", "Text on selected items."),
    ColorRole("guideCellPast", "Aired programs", "Guide programs that have finished."),
) + colors.guideRuns.indices.map { i -> ColorRole("guideRuns[$i]", "Guide color ${i + 1}", "Upcoming programs take these in turn.") }

fun ThemeColors.of(role: String): String = all().first { it.first == role }.second

fun ThemeColors.with(role: String, hex: String): ThemeColors = when (role) {
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
    "guideCellPast" -> copy(guideCellPast = hex)
    else -> {
        val index = role.removePrefix("guideRuns[").removeSuffix("]").toInt()
        copy(guideRuns = guideRuns.mapIndexed { i, run -> if (i == index) hex else run })
    }
}

/**
 * Whether [floor]'s two colors read well, in words instead of a contrast ratio, and when they
 * don't, the way to fix it: the lighter one brighter or the darker one darker. In a color's own
 * pane ([open] is its role) the fix starts with that color's Brightness ladder.
 */
fun readability(floor: ThemeContrast.Floor, colors: ThemeColors, open: String? = null): String {
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
 * A designer setting with a few values, the same in the app and on the web (which gets [labels]
 * and [selected] and asks the device to [apply] a pick). Values are indexes into [labels].
 */
class ThemeChoice(
    val key: String,
    val section: String,
    val title: String,
    val labels: List<String>,
    val selected: (ThemeSpec) -> Int,
    val apply: (ThemeSpec, Int) -> ThemeSpec,
)

private fun <T> choice(
    key: String,
    section: String,
    title: String,
    values: List<T>,
    label: (T) -> String,
    get: (ThemeSpec) -> T,
    set: (ThemeSpec, T) -> ThemeSpec,
) = ThemeChoice(key, section, title, values.map(label), { spec -> values.indexOf(get(spec)) }, { spec, i -> set(spec, values[i]) })

/** Scanline strengths: off, then light to strong. */
private val Scanlines = listOf("Off" to 0f, "Light" to 0.08f, "Medium" to 0.14f, "Strong" to 0.24f)

/** The designer's Type and Screen settings, in order. */
val themeChoices: List<ThemeChoice> = listOf(
    choice("display", "Type", "Headings", FontChoice.entries, { it.label }, { it.typography.display }) { s, font ->
        s.copy(typography = s.typography.copy(display = font))
    },
    choice("body", "Type", "Body text", FontChoice.entries, { it.label }, { it.typography.body }) { s, font ->
        s.copy(typography = s.typography.copy(body = font))
    },
    choice("scale", "Type", "Text size", TextSizes.sizes, { "${(it * 100).roundToInt()}%" }, { TextSizes.nearest(it.typography.scale) }) { s, size ->
        s.copy(typography = s.typography.copy(scale = size))
    },
    choice("density", "Screen", "Guide rows", GuideDensity.entries, { it.label }, { it.guide.density }) { s, density ->
        s.copy(guide = s.guide.copy(density = density))
    },
    choice("progress", "Screen", "Progress cuts", ProgressStyle.frequencies, { "$it cuts" }, { s ->
        ProgressStyle.frequencies.minBy { abs(it - s.progress.segmentFrequency) }
    }) { s, frequency ->
        s.copy(progress = s.progress.copy(segmentFrequency = frequency))
    },
    // Off keeps the strength last chosen, so turning the lines back on restores it.
    choice("scanlines", "Screen", "Scanlines", Scanlines, { it.first }, { s ->
        Scanlines.firstOrNull { (_, alpha) -> s.effects.scanlines && alpha == s.effects.scanlineAlpha } ?: Scanlines.first()
    }) { s, (_, alpha) ->
        s.copy(effects = s.effects.copy(scanlines = alpha > 0f, scanlineAlpha = if (alpha > 0f) alpha else s.effects.scanlineAlpha))
    },
)

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

object TextSizes {
    val sizes = listOf(0.9f, 0.95f, 1f, 1.05f, 1.1f, 1.15f, 1.2f, 1.25f, 1.3f)

    /** The offered size closest to [scale], so a stored value between steps still shows as one. */
    fun nearest(scale: Float): Float = sizes.minBy { abs(it - scale) }
}
