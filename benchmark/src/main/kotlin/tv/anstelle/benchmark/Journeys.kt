package tv.anstelle.benchmark

import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until

internal const val TARGET_PACKAGE = "tv.anstelle.app"

/** The guide grid's test tag, exposed to UiAutomator as a resource id. */
internal const val GUIDE_GRID = "guide-grid"
internal const val WAIT_MS = 10_000L

/** Starts the app and waits for the guide's grid; the preview window starts playing the last channel. */
internal fun MacrobenchmarkScope.openGuide() {
    pressHome()
    startActivityAndWait()
    check(device.wait(Until.hasObject(By.res(GUIDE_GRID)), WAIT_MS)) { "The guide didn't open: a profile picker or an empty library?" }
}

/** From the guide: OK shows the selected channel in the preview, OK again (or the first, once it plays there) watches it full screen. */
internal fun MacrobenchmarkScope.watchFullScreen() {
    repeat(3) {
        device.pressDPadCenter()
        if (device.wait(Until.gone(By.res(GUIDE_GRID)), WAIT_MS / 2)) return
    }
    error("Full-screen playback did not open")
}

/** Opens a destination of the navigation rail (or bar) by its label. */
internal fun MacrobenchmarkScope.openTab(label: String) {
    val tab = device.wait(Until.findObject(By.text(label)), WAIT_MS) ?: error("No $label tab")
    tab.click()
    device.waitForIdle()
}
