package tv.anstelle.core.theme

import tv.anstelle.core.model.ThemeContrast
import tv.anstelle.core.model.ThemeEffects
import tv.anstelle.core.model.ProgressStyle
import tv.anstelle.core.model.MIN_PROGRESS_FREQUENCY
import tv.anstelle.core.model.MAX_PROGRESS_FREQUENCY
import tv.anstelle.core.model.ThemeSpec
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ThemeSpecTest {
    @Test
    fun progressFrequencyDefaultsForExistingThemesAndRoundTripsWhenCustomized() {
        val existing = File("src/main/assets/themes/rose.json").readText()
        assertEquals(20, ThemeSpec.parse(existing).progress.segmentFrequency)
        for (frequency in MIN_PROGRESS_FREQUENCY..MAX_PROGRESS_FREQUENCY) {
            val spec = DefaultTheme.spec.copy(progress = ProgressStyle(frequency))
            spec.validate()
            assertEquals(spec, ThemeSpec.parse(spec.toJson()))
        }
        for (frequency in listOf(MIN_PROGRESS_FREQUENCY - 1, MAX_PROGRESS_FREQUENCY + 1)) {
            assertFailsWith<IllegalArgumentException> { DefaultTheme.spec.copy(progress = ProgressStyle(frequency)).validate() }
        }
    }

    private val bundled = File("src/main/assets/themes").listFiles { file -> file.extension == "json" }.orEmpty().sortedBy { it.name }

    @Test
    fun bundledRoseMatchesCodeFallback() {
        val text = File("src/main/assets/themes/rose.json").readText()
        assertEquals(DefaultTheme.spec, ThemeSpec.parse(text))
    }

    @Test
    fun sixteenThemesShipEachNamedByItsKey() {
        assertEquals(16, bundled.size)
        val specs = bundled.map { it to ThemeSpec.parse(it.readText()) }
        for ((file, spec) in specs) {
            assertEquals(file.nameWithoutExtension, spec.key, "${file.name} names another key")
            spec.validate()
        }
        assertEquals(specs.size, specs.map { it.second.name }.toSet().size, "two themes share a name")
    }

    @Test
    fun everyBundledThemeMeetsTheContrastFloors() {
        for (file in bundled) {
            val problems = ThemeContrast.problems(ThemeSpec.parse(file.readText()).colors)
            assertTrue(problems.isEmpty(), "${file.name}: ${problems.joinToString { (floor, ratio) -> "${floor.label} %.2f:1".format(ratio) }}")
        }
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
