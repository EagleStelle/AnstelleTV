package tv.anstelle.core.data.themes

import tv.anstelle.core.model.FontChoice
import tv.anstelle.core.model.GuideDensity
import tv.anstelle.core.model.ThemeColors
import tv.anstelle.core.model.ThemeContrast

/** A color role as the designer names it. */
class ColorRole(val key: String, val label: String, val about: String)

fun colorRoles(colors: ThemeColors): List<ColorRole> = listOf(
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

val FontChoice.label get() = when (this) {
    FontChoice.CONDENSED -> "Condensed"
    FontChoice.SANS -> "Sans"
    FontChoice.SERIF -> "Serif"
    FontChoice.MONO -> "Mono"
}

val GuideDensity.label get() = when (this) {
    GuideDensity.COMPACT -> "Compact"
    GuideDensity.COMFORTABLE -> "Comfortable"
    GuideDensity.SPACIOUS -> "Spacious"
}

object TextSizes {
    val sizes = listOf(0.9f, 0.95f, 1f, 1.05f, 1.1f, 1.15f, 1.2f, 1.25f, 1.3f)

    /** The offered size closest to [scale], so a stored value between steps still shows as one. */
    fun nearest(scale: Float): Float = sizes.minBy { kotlin.math.abs(it - scale) }
}

/** Scanline strengths: off, then light to strong. */
val Scanlines = listOf("Off" to 0f, "Light" to 0.08f, "Medium" to 0.14f, "Strong" to 0.24f)
