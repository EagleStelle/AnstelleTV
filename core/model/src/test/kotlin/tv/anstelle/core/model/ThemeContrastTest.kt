package tv.anstelle.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ThemeContrastTest {
    private val colors = ThemeColors(
        background = "#000000", surface = "#1C1C1C", surfaceHigh = "#2E2E2E", line = "#7A7A7A",
        text = "#FFFFFF", textMuted = "#D6D6D6", accent = "#FFE14D", onAccent = "#000000",
        accentContainer = "#4A4000", onAccentContainer = "#FFF3B0", guideCell = "#1C1C1C",
        guideCellPast = "#0E0E0E", guideRuns = listOf("#00363A", "#3F2A00"), nowLine = "#FFE14D",
        banner = "#1C1C1C", bannerText = "#FFFFFF",
    )

    @Test
    fun ratiosFollowWcag() {
        assertEquals(21.0, ThemeContrast.ratio("#000000", "#FFFFFF"), 0.001)
        assertEquals(1.0, ThemeContrast.ratio("#F978B6", "#F978B6"), 0.001)
        // Order doesn't matter, and alpha is ignored.
        assertEquals(ThemeContrast.ratio("#130C0F", "#F9F1F4"), ThemeContrast.ratio("#80F9F1F4", "#130C0F"), 0.001)
        assertEquals(4.54, ThemeContrast.ratio("#767676", "#FFFFFF"), 0.01)
    }

    @Test
    fun problemsNameEachMissedFloor() {
        assertTrue(ThemeContrast.problems(colors).isEmpty())
        val dim = colors.copy(onAccent = "#E0C040", guideRuns = listOf("#00363A", "#C0C0C0"))
        val labels = ThemeContrast.problems(dim).map { it.first.label }
        assertEquals(listOf("Text on focus", "Text on Guide: later 2"), labels)
    }

    @Test
    fun validationRejectsWhatAThemeCantHave() {
        val spec = ThemeSpec(key = "test", name = "Test", colors = colors)
        spec.validate()
        assertFailsWith<IllegalArgumentException> { spec.copy(name = " ").validate() }
        assertFailsWith<IllegalArgumentException> { spec.copy(colors = colors.copy(text = "white")).validate() }
        assertFailsWith<IllegalArgumentException> { spec.copy(colors = colors.copy(guideRuns = listOf("#000000"))).validate() }
        assertFailsWith<IllegalArgumentException> { spec.copy(typography = ThemeTypography(scale = 2f)).validate() }
        assertFailsWith<IllegalArgumentException> { spec.copy(guide = GuideStyle(cellRadiusDp = 9)).validate() }
    }

    @Test
    fun oklchRoundTripsAndStaysInGamut() {
        for (hex in listOf("#130C0F", "#F978B6", "#FFFFFF", "#000000", "#4BF677", "#0568A4")) {
            assertEquals(hex, Oklch.fromHex(hex).toHex())
        }
        val rose = Oklch.fromHex("#F978B6")
        assertEquals(0.74, rose.lightness, 0.01)
        assertEquals(352.0, rose.hue, 1.0)
        // Far more chroma than sRGB holds still makes a color, at the most the gamut allows.
        assertTrue(ThemeContrast.isColor(rose.copy(chroma = Oklch.MAX_CHROMA).toHex()))
    }

    @Test
    fun lightnessDecidesTheSystemBars() {
        val spec = ThemeSpec(key = "test", name = "Test", colors = colors)
        assertFalse(spec.isLight)
        assertTrue(spec.copy(colors = colors.copy(background = "#FBF5F7")).isLight)
    }
}
