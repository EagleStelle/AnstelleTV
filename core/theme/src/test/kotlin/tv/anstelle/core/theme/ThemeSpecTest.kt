package tv.anstelle.core.theme

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ThemeSpecTest {
    @Test
    fun bundledCleanModernMatchesCodeFallback() {
        val text = File("src/main/assets/themes/clean_modern.json").readText()
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
        assertEquals(0xFF0E1116.toInt(), ResolvedTheme.parseColor("#0E1116").value.shr(32).toInt())
        assertEquals(0xE6171B22.toInt(), ResolvedTheme.parseColor("#E6171B22").value.shr(32).toInt())
        assertFailsWith<IllegalArgumentException> { ResolvedTheme.parseColor("#123") }
    }
}
