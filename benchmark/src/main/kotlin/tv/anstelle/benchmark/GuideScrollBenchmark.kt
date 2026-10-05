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
 * D-pad scrolling through the guide; frame timing must stay inside the 60 fps budget.
 * Stub journey until the EPG grid lands (Phase 6); the benchmark build seeds 100 channels
 * with 30 days of schedule in Phase 11.
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
        },
    ) {
        repeat(10) { device.pressDPadDown() }
        repeat(10) { device.pressDPadRight() }
        repeat(10) { device.pressDPadUp() }
        device.waitForIdle()
    }
}

internal const val TARGET_PACKAGE = "tv.anstelle.app"
