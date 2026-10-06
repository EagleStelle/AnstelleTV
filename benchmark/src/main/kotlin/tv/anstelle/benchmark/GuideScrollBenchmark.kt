package tv.anstelle.benchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * D-pad scrolling through the guide grid: down through the channels, across the hours and
 * back. Frame timing must stay inside the 60 fps budget. The benchmark build seeds 100
 * channels with 30 days of schedule in Phase 11; until then it scrolls the device's own
 * channels.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class GuideScrollBenchmark {
    @get:Rule
    val rule = MacrobenchmarkRule()

    @Test
    fun scrollGuide() = rule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(FrameTimingMetric()),
        compilationMode = CompilationMode.Partial(),
        startupMode = StartupMode.WARM,
        iterations = 5,
        setupBlock = {
            pressHome()
            startActivityAndWait()
            device.wait(Until.hasObject(By.res(GUIDE_GRID)), GRID_TIMEOUT_MS)
        },
    ) {
        repeat(30) { device.pressDPadDown() }
        repeat(10) { device.pressDPadRight() }
        repeat(30) { device.pressDPadUp() }
        repeat(10) { device.pressDPadLeft() }
        device.waitForIdle()
    }
}

internal const val TARGET_PACKAGE = "tv.anstelle.app"

/** The guide grid's test tag, exposed to UiAutomator as a resource id. */
internal const val GUIDE_GRID = "guide-grid"
internal const val GRID_TIMEOUT_MS = 10_000L
