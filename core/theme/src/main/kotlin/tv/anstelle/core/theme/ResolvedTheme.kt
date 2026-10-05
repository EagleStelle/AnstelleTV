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
    val surfaceVariant: Color,
    val primary: Color,
    val onPrimary: Color,
    val secondary: Color,
    val text: Color,
    val textMuted: Color,
    val focus: Color,
    val guideCell: Color,
    val guideCellAlt: Color,
    val guideCellFocused: Color,
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
                surfaceVariant = parseColor(c.surfaceVariant),
                primary = parseColor(c.primary),
                onPrimary = parseColor(c.onPrimary),
                secondary = parseColor(c.secondary),
                text = parseColor(c.text),
                textMuted = parseColor(c.textMuted),
                focus = parseColor(c.focus),
                guideCell = parseColor(c.guideCell),
                guideCellAlt = parseColor(c.guideCellAlt),
                guideCellFocused = parseColor(c.guideCellFocused),
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

        // Bundled OFL fonts arrive with the theme designer (Phase 8); system families until then.
        private fun fontFamily(choice: FontChoice): FontFamily = when (choice) {
            FontChoice.SANS -> FontFamily.SansSerif
            FontChoice.SERIF -> FontFamily.Serif
            FontChoice.MONO -> FontFamily.Monospace
            FontChoice.CONDENSED -> FontFamily.SansSerif
        }
    }
}
