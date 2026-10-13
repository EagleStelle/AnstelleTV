package tv.anstelle.core.data.themes

import tv.anstelle.core.model.ThemeColors
import tv.anstelle.core.model.ThemeContrast
import kotlin.test.Test
import kotlin.test.assertEquals

class ThemeDesignTest {
    private val colors = ThemeColors(
        background = "#130C0F", surface = "#20161A", surfaceHigh = "#291E23", line = "#34272C",
        text = "#F9F1F4", textMuted = "#BFABB3", accent = "#F978B6", onAccent = "#130C0F",
        accentContainer = "#4F1A35", onAccentContainer = "#FFCEE2", guideCell = "#4F1A35", guideCellPast = "#20161A",
        guideRuns = listOf("#291E23", "#34272C"), nowLine = "#F978B6", banner = "#130C0F", bannerText = "#F9F1F4",
    )

    private val textOnGround = ThemeContrast.Floors.first { it.foreground == "text" && it.background == "background" }

    @Test
    fun `every role reads and writes back`() {
        for (role in colorRoles(colors)) {
            val changed = colors.with(role.key, "#123456")
            assertEquals("#123456", changed.of(role.key))
        }
        assertEquals(17, colorRoles(colors).size)
    }

    @Test
    fun `readability is told in words with the way to fix it`() {
        assertEquals("Text on the ground is easy to read.", readability(textOnGround, colors))
        val dim = colors.with("text", "#2A2024")
        assertEquals("Text on the ground is hard to read. Make Text brighter or Ground darker.", readability(textOnGround, dim))
        assertEquals("Text on the ground is hard to read. Raise its brightness, or make Ground darker.", readability(textOnGround, dim, open = "text"))
        assertEquals("Text on the ground is hard to read. Lower its brightness, or make Text brighter.", readability(textOnGround, dim, open = "background"))
    }

    @Test
    fun `stored text sizes show as the nearest offered`() {
        assertEquals(1.05f, TextSizes.nearest(1.04f))
    }
}
