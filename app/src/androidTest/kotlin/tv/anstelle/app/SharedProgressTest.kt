package tv.anstelle.app

import android.content.Intent
import android.graphics.Bitmap
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tv.anstelle.core.model.MediaProgress
import tv.anstelle.core.model.ProgressStyle
import tv.anstelle.core.theme.AnstelleTheme
import tv.anstelle.core.theme.DefaultTheme
import tv.anstelle.core.theme.LocalAnstelleTheme
import tv.anstelle.core.theme.ResolvedTheme
import tv.anstelle.core.theme.components.SegmentLadder
import tv.anstelle.core.theme.components.WatchStrip
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Synthetic progress states: no library, watch history or theme settings are changed. */
@RunWith(AndroidJUnit4::class)
class SharedProgressTest {
    @Test fun artworkProgressAndSegmentDensity() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as MainActivity
        val isTv = instrumentation.targetContext.isTvDevice()
        try {
            instrumentation.runOnMainSync {
                activity.setContent {
                    AnstelleTheme(isTv) {
                        Column(
                            Modifier.fillMaxSize().background(LocalAnstelleTheme.current.background).padding(24.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            // Use actual episode-card widths so full-screen space cannot hide wrapping bugs.
                            Column(
                                Modifier.width(if (isTv) 232.dp else 168.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text("Unstarted")
                                WatchStrip(MediaProgress(1_440_000, 0), watched = false)
                                Text("Started")
                                WatchStrip(MediaProgress(1_440_000, 720_000), watched = false)
                                Text("Watched")
                                WatchStrip(MediaProgress(1_440_000, 0), watched = true)
                                Text("Watching a played episode")
                                WatchStrip(MediaProgress(1_440_000, 1_080_000), watched = true)
                            }
                            Text("Movie poster")
                            WatchStrip(MediaProgress(1_440_000, 720_000), watched = false, remaining = null)
                            Text("Unknown duration")
                            WatchStrip(MediaProgress(0, 720_000), watched = false)
                            for (frequency in listOf(8, 20, 48)) {
                                val theme = ResolvedTheme.from(DefaultTheme.spec.copy(progress = ProgressStyle(frequency)))
                                AnstelleTheme(isTv, theme) {
                                    Text("Cut frequency: $frequency")
                                    SegmentLadder(0.5f)
                                }
                            }
                        }
                    }
                }
            }
            instrumentation.waitForIdleSync()
            instrumentation.uiAutomation.waitForIdle(500, 5_000)
            val root = requireNotNull(instrumentation.uiAutomation.rootInActiveWindow)
            val nodes = buildList {
                fun collect(node: AccessibilityNodeInfo) {
                    add(node)
                    for (i in 0 until node.childCount) node.getChild(i)?.let(::collect)
                }
                collect(root)
            }
            assertEquals("Only started strips plus the three density previews have progress", 6, nodes.count { it.rangeInfo != null })
            assertEquals("Only the started episode has a time-left label", 1, nodes.count { it.text?.toString() == "12 MIN" })
            assertEquals("A played episode being watched keeps its own time left", 1, nodes.count { it.text?.toString() == "6 MIN" })
            assertTrue("Watched episodes retain their marker", nodes.any { it.text?.toString() == "WATCHED" })
            assertTrue("Started ladders keep a visible width", nodes.filter { it.rangeInfo != null }.all { node ->
                val bounds = android.graphics.Rect()
                node.getBoundsInScreen(bounds)
                bounds.width() > 0 && bounds.height() > 0
            })
            // Accessibility can be ready before the first composed frame reaches the display.
            val drawn = CountDownLatch(1)
            activity.window.decorView.postOnAnimation {
                activity.window.decorView.postOnAnimation { drawn.countDown() }
            }
            assertTrue("Progress content reached the display", drawn.await(5, TimeUnit.SECONDS))
            val file = File(instrumentation.targetContext.getExternalFilesDir(null), "shared-progress.png")
            val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }
}
