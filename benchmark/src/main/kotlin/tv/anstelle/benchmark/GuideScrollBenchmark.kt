package tv.anstelle.benchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * D-pad scrolling through the guide grid: down through the channels, across the hours and
 * back, on the device's own channels. Frame timing must stay inside the 60 fps budget.
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
        setupBlock = { openGuide() },
    ) {
        repeat(30) { device.pressDPadDown() }
        repeat(10) { device.pressDPadRight() }
        repeat(30) { device.pressDPadUp() }
        repeat(10) { device.pressDPadLeft() }
        device.waitForIdle()
    }
}
