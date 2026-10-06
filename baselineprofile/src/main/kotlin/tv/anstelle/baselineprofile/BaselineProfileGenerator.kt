package tv.anstelle.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Generates the app's baseline profile:
 *
 *     ./gradlew :app:generateBaselineProfile
 *
 * Journeys cover cold start to the guide, D-pad movement through the guide grid and tuning a
 * channel full screen. Phase 11 runs this on a Fire TV Stick.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class BaselineProfileGenerator {
    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun generate() = rule.collect(packageName = TARGET_PACKAGE, includeInStartupProfile = true) {
        pressHome()
        startActivityAndWait()
        device.wait(Until.hasObject(By.res(GUIDE_GRID)), GRID_TIMEOUT_MS)

        // Guide: through the channels and across the hours.
        repeat(8) { device.pressDPadDown() }
        repeat(4) { device.pressDPadRight() }
        repeat(4) { device.pressDPadLeft() }
        repeat(8) { device.pressDPadUp() }
        device.waitForIdle()

        // Tune: the first OK previews the selected channel, the second watches it full screen.
        device.pressDPadCenter()
        device.pressDPadCenter()
        device.waitForIdle()
        device.pressDPadUp()
        device.pressDPadDown()
        device.pressBack()
        device.waitForIdle()
    }
}

internal const val TARGET_PACKAGE = "tv.anstelle.app"
internal const val GUIDE_GRID = "guide-grid"
internal const val GRID_TIMEOUT_MS = 10_000L
