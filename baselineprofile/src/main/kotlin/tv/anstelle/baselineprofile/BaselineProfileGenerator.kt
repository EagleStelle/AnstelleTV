package tv.anstelle.baselineprofile

import android.view.KeyEvent
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Generates the app's baseline profile on a device signed in to a server with channels:
 *
 *     ./gradlew :app:generateBaselineProfile
 *
 * Journeys cover cold start to the guide, D-pad movement through the guide grid, tuning a
 * channel full screen and surfing, On Demand and the dashboard.
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
        device.wait(Until.hasObject(By.res(GUIDE_GRID)), WAIT_MS)

        // Guide: through the channels and across the hours.
        repeat(8) { device.pressDPadDown() }
        repeat(4) { device.pressDPadRight() }
        repeat(4) { device.pressDPadLeft() }
        repeat(8) { device.pressDPadUp() }
        device.waitForIdle()

        // Tune: the first OK previews the selected channel, the second watches it full screen; then surf.
        device.pressDPadCenter()
        device.pressDPadCenter()
        device.waitForIdle()
        repeat(2) {
            device.pressKeyCode(KeyEvent.KEYCODE_CHANNEL_UP)
            Thread.sleep(SURF_MS)
        }
        device.pressKeyCode(KeyEvent.KEYCODE_CHANNEL_DOWN)
        Thread.sleep(SURF_MS)
        device.pressBack()
        device.waitForIdle()

        // On Demand and the dashboard, from the rail.
        for (tab in listOf("On Demand", "Dashboard")) {
            device.wait(Until.findObject(By.text(tab)), WAIT_MS)?.click()
            device.waitForIdle()
            Thread.sleep(SETTLE_MS)
        }
    }
}

internal const val TARGET_PACKAGE = "tv.anstelle.app"
internal const val GUIDE_GRID = "guide-grid"
internal const val WAIT_MS = 10_000L
private const val SURF_MS = 3_000L
private const val SETTLE_MS = 2_000L
