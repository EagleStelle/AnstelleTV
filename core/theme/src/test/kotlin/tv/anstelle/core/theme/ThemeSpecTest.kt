package tv.anstelle.core.theme

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ThemeSpecTest {
    @Test
    fun bundledRoseMatchesCodeFallback() {
        val text = File("src/main/assets/themes/rose.json").readText()
        assertEquals(DefaultTheme.spec, ThemeSpec.parse(text))
    }

    @Test
    fun jsonRoundTrips() {
        val spec = DefaultTheme.spec.copy(effects = ThemeEffects(scanlines = true, scanlineAlpha = 0.3f))
        assertEquals(spec, ThemeSpec.parse(spec.toJson()))
    }

    @Test
    fun unknownKeysAreIgnoredForForwardCompatibility() {
        val text = DefaultTheme.spec.toJson().replaceFirst("{", "{\"futureField\": 1,")
        assertEquals(DefaultTheme.spec, ThemeSpec.parse(text))
    }

    @Test
    fun parsesRgbAndArgbColors() {
        assertEquals(0xFF130C0F.toInt(), ResolvedTheme.parseColor("#130C0F").value.shr(32).toInt())
        assertEquals(0xF5130C0F.toInt(), ResolvedTheme.parseColor("#F5130C0F").value.shr(32).toInt())
        assertFailsWith<IllegalArgumentException> { ResolvedTheme.parseColor("#123") }
    }
}
