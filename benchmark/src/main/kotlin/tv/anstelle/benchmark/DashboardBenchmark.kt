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
 * The dashboard's first open after a cold start: both of its reads, the "Stats" trace section
 * (`DashboardStats`). Target: under 50 ms on a Fire TV Stick 4K. The watching profile needs some
 * history for the reads to do anything.
 */
@OptIn(ExperimentalMetricApi::class)
@RunWith(AndroidJUnit4::class)
@LargeTest
class DashboardBenchmark {
    @get:Rule
    val rule = MacrobenchmarkRule()

    @Test
    fun firstOpen() = rule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(
            TraceSectionMetric(STATS, TraceSectionMetric.Mode.Sum),
            TraceSectionMetric(STATS, TraceSectionMetric.Mode.Max),
        ),
        compilationMode = CompilationMode.Partial(),
        startupMode = StartupMode.COLD,
        iterations = 5,
        // A cold start kills the app after the setup, so the journey starts it.
        setupBlock = { pressHome() },
    ) {
        openGuide()
        openTab("Dashboard")
        Thread.sleep(SETTLE_MS)
    }

    private companion object {
        const val STATS = "Stats"
        const val SETTLE_MS = 2_000L
    }
}
