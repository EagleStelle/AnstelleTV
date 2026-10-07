package tv.anstelle.core.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily

/** A [ThemeSpec] with colors parsed once, ready for drawing. */
@Immutable
class ResolvedTheme(
    val spec: ThemeSpec,
    val background: Color,
    val surface: Color,
    val surfaceHigh: Color,
    val line: Color,
    val text: Color,
    val textMuted: Color,
    val accent: Color,
    val onAccent: Color,
    val accentContainer: Color,
    val onAccentContainer: Color,
    val guideCell: Color,
    val guideCellPast: Color,
    val guideRuns: List<Color>,
    val nowLine: Color,
    val banner: Color,
    val bannerText: Color,
    val displayFont: FontFamily,
    val bodyFont: FontFamily,
) {
    companion object {
        fun from(spec: ThemeSpec): ResolvedTheme {
            val c = spec.colors
            return ResolvedTheme(
                spec = spec,
                background = parseColor(c.background),
                surface = parseColor(c.surface),
                surfaceHigh = parseColor(c.surfaceHigh),
                line = parseColor(c.line),
                text = parseColor(c.text),
                textMuted = parseColor(c.textMuted),
                accent = parseColor(c.accent),
                onAccent = parseColor(c.onAccent),
                accentContainer = parseColor(c.accentContainer),
                onAccentContainer = parseColor(c.onAccentContainer),
                guideCell = parseColor(c.guideCell),
                guideCellPast = parseColor(c.guideCellPast),
                guideRuns = c.guideRuns.map(::parseColor),
                nowLine = parseColor(c.nowLine),
                banner = parseColor(c.banner),
                bannerText = parseColor(c.bannerText),
                displayFont = fontFamily(spec.typography.display),
                bodyFont = fontFamily(spec.typography.body),
            )
        }

        /** Parses `#RRGGBB` or `#AARRGGBB`. */
        fun parseColor(hex: String): Color {
            val digits = hex.removePrefix("#")
            val value = digits.toLong(16)
            return when (digits.length) {
                6 -> Color(0xFF000000L or value)
                8 -> Color(value)
                else -> throw IllegalArgumentException("Bad color: $hex")
            }
        }

        private fun fontFamily(choice: FontChoice): FontFamily = when (choice) {
            FontChoice.SANS -> AnstelleFonts.Barlow
            FontChoice.CONDENSED -> AnstelleFonts.BarlowCondensed
            FontChoice.SERIF -> FontFamily.Serif
            FontChoice.MONO -> FontFamily.Monospace
        }
    }
}
