package tv.anstelle.core.theme

import tv.anstelle.core.model.FontChoice
import tv.anstelle.core.model.ThemeColors
import tv.anstelle.core.model.ThemeSpec
import tv.anstelle.core.model.ThemeTypography

/**
 * Fallback theme used for the first frame, before the profile's theme is read from the
 * database. It mirrors `assets/themes/rose.json`; a unit test keeps them equal.
 */
object DefaultTheme {
    val spec = ThemeSpec(
        key = "rose",
        name = "Rose",
        colors = ThemeColors(
            background = "#130C0F",
            surface = "#20161A",
            surfaceHigh = "#291E23",
            line = "#34272C",
            text = "#F9F1F4",
            textMuted = "#BFABB3",
            accent = "#F978B6",
            onAccent = "#130C0F",
            accentContainer = "#4F1A35",
            onAccentContainer = "#FFCEE2",
            guideCellPast = "#181214",
            // Teal, ochre, slate, moss: neighbours in the list sit far apart in hue.
            guideRuns = listOf("#0D3737", "#402C12", "#1E3149", "#22361E"),
        ),
        typography = ThemeTypography(display = FontChoice.CONDENSED, body = FontChoice.SANS),
    )

    val resolved: ResolvedTheme by lazy(LazyThreadSafetyMode.PUBLICATION) { ResolvedTheme.from(spec) }
}
