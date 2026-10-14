package tv.anstelle.benchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.TraceSectionMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Surfing in the full-screen player: each channel change to its first frame, the "Tune" trace
 * section (`TuneTrace`). The first press tunes cold, the later ones mostly from the standby.
 * Needs a library on a reachable server: the frames come from it.
 */
@OptIn(ExperimentalMetricApi::class)
@RunWith(AndroidJUnit4::class)
@LargeTest
class ChannelSwitchBenchmark {
    @get:Rule
    val rule = MacrobenchmarkRule()

    @Test
    fun surf() = rule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(
            TraceSectionMetric(TUNE, TraceSectionMetric.Mode.Average),
            TraceSectionMetric(TUNE, TraceSectionMetric.Mode.Max),
            TraceSectionMetric(TUNE, TraceSectionMetric.Mode.Count),
        ),
        compilationMode = CompilationMode.Partial(),
        startupMode = StartupMode.WARM,
        iterations = 3,
        setupBlock = {
            openGuide()
            watchFullScreen()
            // Let the standby warm the next channel up.
            Thread.sleep(SETTLE_MS)
        },
    ) {
        repeat(SURFS) {
            device.pressKeyCode(android.view.KeyEvent.KEYCODE_CHANNEL_UP)
            Thread.sleep(SETTLE_MS)
        }
    }

    private companion object {
        const val TUNE = "Tune"
        const val SURFS = 5
        /** Long enough for a tune's first frame and the standby to warm the next channel. */
        const val SETTLE_MS = 6_000L
    }
}
