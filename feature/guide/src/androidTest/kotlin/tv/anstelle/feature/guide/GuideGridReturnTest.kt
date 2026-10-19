package tv.anstelle.feature.guide

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import tv.anstelle.core.model.ProfileAccess
import tv.anstelle.core.theme.AnstelleTheme

@RunWith(AndroidJUnit4::class)
class GuideGridReturnTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun finalChannelCanBeFirstVisibleRowOnTv() = verifyLastRow(touch = false)
    @Test fun finalChannelCanBeFirstVisibleRowOnTouch() = verifyLastRow(touch = true)

    private fun verifyLastRow(touch: Boolean) {
        val channels = (1L..12L).map { GuideChannel(it, it.toInt(), "Channel $it", null, false) }
        val now = System.currentTimeMillis()
        val grid = GuideGridState(GuideGridState.earliest(now), 4f / 60_000f)
        val focus = FocusRequester()
        lateinit var list: LazyListState
        compose.setContent {
            list = rememberLazyListState()
            AnstelleTheme(isTv = !touch) {
                GuideGrid(
                    channels = channels,
                    strips = remember { mutableStateOf(emptyMap()) },
                    access = ProfileAccess.OPEN,
                    grid = grid,
                    listState = list,
                    now = remember { mutableStateOf(now) },
                    metrics = GridMetrics(48.dp, 132.dp, 28.dp, 1, 26.dp, 4f, touch),
                    focusRequester = focus,
                    onActivate = {}, onActions = {}, onCellTap = {}, onChannelTap = {},
                    modifier = Modifier.height(270.dp),
                )
                LaunchedEffect(Unit) {
                    grid.selectNow(channels.last().id, now)
                    list.scrollToItem(channels.lastIndex)
                }
            }
        }
        compose.waitForIdle()
        if (!touch) compose.runOnIdle { focus.requestFocus() }
        compose.runOnIdle {
            assertEquals(channels.lastIndex, list.firstVisibleItemIndex)
            assertEquals(0, list.firstVisibleItemScrollOffset)
            assertEquals(channels.last().id, grid.selectedChannel)
            assertEquals(now, grid.selectedTime)
            if (!touch) assertTrue(grid.focused)
        }
        // A completed return does not keep dragging the viewport back while browsing.
        if (touch) {
            compose.onNodeWithTag("guide-grid").performTouchInput { swipeDown() }
        } else {
            compose.runOnIdle { runBlocking { list.scrollToItem(0) } }
        }
        compose.waitForIdle()
        compose.runOnIdle { assertTrue(list.firstVisibleItemIndex < channels.lastIndex) }
    }
}
