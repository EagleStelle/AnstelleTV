package tv.anstelle.benchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
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
        metrics = listOf(RenderedTuneMetric(SURFS)),
        compilationMode = CompilationMode.Partial(),
        startupMode = StartupMode.WARM,
        iterations = 3,
        setupBlock = {
            openGuide()
            watchFullScreen()
            pinStartChannel()
            // Let the standby warm the next channel up.
            Thread.sleep(SETTLE_MS)
        },
    ) {
        repeat(SURFS) {
            device.pressKeyCode(android.view.KeyEvent.KEYCODE_CHANNEL_UP)
            Thread.sleep(SETTLE_MS)
        }
    }

    /** Cold guide preview includes preparation and the actual rendered first frame. */
    @Test
    fun previewCold() = rule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(RenderedTuneMetric(1)),
        compilationMode = CompilationMode.Partial(),
        startupMode = StartupMode.COLD,
        iterations = 3,
        setupBlock = {
            openGuide()
            watchFullScreen()
            pinStartChannel()
            Thread.sleep(SETTLE_MS)
            pressHome()
            killProcess()
        },
    ) {
        startActivityAndWait()
        Thread.sleep(SETTLE_MS)
    }

    /** Pin the same channel sequence when comparing builds on a real server library. */
    private fun MacrobenchmarkScope.pinStartChannel() {
        val number = InstrumentationRegistry.getArguments().getString("benchmarkStartChannel") ?: return
        require(number.length in 1..4 && number.all(Char::isDigit))
        for (digit in number) device.pressKeyCode(android.view.KeyEvent.KEYCODE_0 + digit.digitToInt())
        Thread.sleep(2_500)
        device.pressKeyCode(android.view.KeyEvent.KEYCODE_INFO)
        check(device.wait(Until.hasObject(By.text(number)), WAIT_MS)) { "Could not tune benchmark start channel $number" }
    }

    private companion object {
        const val SURFS = 5
        /** Long enough for a tune's first frame and the standby to warm the next channel. */
        const val SETTLE_MS = 10_000L
    }
}
