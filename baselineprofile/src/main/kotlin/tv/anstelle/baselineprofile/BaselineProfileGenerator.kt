package tv.anstelle.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Generates the app's baseline profile:
 *
 *     ./gradlew :app:generateBaselineProfile
 *
 * Journeys cover cold start to the guide, D-pad movement through the guide and tuning a
 * channel. They grow with the guide (Phase 6) and player (Phase 5); Phase 11 runs this on a
 * Fire TV Stick.
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

        // Guide: move focus around the tiles.
        repeat(3) { device.pressDPadRight() }
        repeat(2) { device.pressDPadLeft() }
        device.waitForIdle()

        // Tune: open the first channel, surf up and down, then return to the guide.
        device.pressDPadCenter()
        device.waitForIdle()
        device.pressDPadUp()
        device.pressDPadDown()
        device.pressBack()
        device.waitForIdle()
    }
}

internal const val TARGET_PACKAGE = "tv.anstelle.app"
