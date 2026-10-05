package tv.anstelle.core.theme

/**
 * Fallback theme used for the first frame, before the profile's theme is read from the
 * database. It mirrors `assets/themes/clean_modern.json`; a unit test keeps them equal.
 */
object DefaultTheme {
    val spec = ThemeSpec(
        key = "clean_modern",
        name = "Clean Modern",
        colors = ThemeColors(
            background = "#0E1116",
            surface = "#171B22",
            surfaceVariant = "#222834",
            primary = "#4FB3FF",
            onPrimary = "#04121F",
            secondary = "#FFB547",
            text = "#ECEFF4",
            textMuted = "#9AA4B2",
            focus = "#FFFFFF",
            guideCell = "#1B2029",
            guideCellAlt = "#1F2530",
            guideCellFocused = "#2E6FA3",
            nowLine = "#FF5A5F",
            banner = "#E6171B22",
            bannerText = "#ECEFF4",
        ),
    )

    val resolved: ResolvedTheme by lazy(LazyThreadSafetyMode.PUBLICATION) { ResolvedTheme.from(spec) }
}
