package tv.anstelle.feature.guide

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class GuideAutoPreviewTest {
    private class Screen : LifecycleOwner {
        override val lifecycle = LifecycleRegistry.createUnsafe(this)
    }

    @Test fun waitsFiveHundredMillisecondsAndDoesNotRepeatOnTheSameSelection() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val screen = Screen().apply { lifecycle.currentState = Lifecycle.State.STARTED }
            var tunes = 0
            val job = launch { screen.lifecycle.autoPreview { tunes++ } }
            runCurrent()
            advanceTimeBy(499); runCurrent()
            assertEquals(0, tunes)
            advanceTimeBy(1); runCurrent()
            assertEquals(1, tunes)
            advanceTimeBy(2_000); runCurrent()
            assertEquals(1, tunes)
            job.cancel()
        } finally { Dispatchers.resetMain() }
    }

    @Test fun stoppingCancelsPendingPreviewAndReturningRequiresANewPause() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val screen = Screen().apply { lifecycle.currentState = Lifecycle.State.STARTED }
            var tunes = 0
            val job = launch { screen.lifecycle.autoPreview { tunes++ } }
            runCurrent()
            advanceTimeBy(400)
            screen.lifecycle.currentState = Lifecycle.State.CREATED
            runCurrent(); advanceTimeBy(1_000); runCurrent()
            assertEquals(0, tunes)
            screen.lifecycle.currentState = Lifecycle.State.STARTED
            runCurrent(); advanceTimeBy(499); runCurrent()
            assertEquals(0, tunes)
            advanceTimeBy(1); runCurrent()
            assertEquals(1, tunes)
            job.cancel()
        } finally { Dispatchers.resetMain() }
    }

    @Test fun selectionOrDialogChangeCancelsOldRequestAndDisposalCancelsReplacement() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val screen = Screen().apply { lifecycle.currentState = Lifecycle.State.STARTED }
            val tunes = mutableListOf<Long>()
            val old = launch { screen.lifecycle.autoPreview { tunes += 1L } }
            runCurrent(); advanceTimeBy(400)
            old.cancel(); runCurrent()
            val replacement = launch { screen.lifecycle.autoPreview { tunes += 2L } }
            runCurrent(); advanceTimeBy(499); runCurrent()
            assertEquals(emptyList(), tunes)
            advanceTimeBy(1); runCurrent()
            assertEquals(listOf(2L), tunes)
            replacement.cancel()
            val disposed = launch { screen.lifecycle.autoPreview { tunes += 3L } }
            runCurrent(); advanceTimeBy(400)
            disposed.cancel(); runCurrent(); advanceTimeBy(1_000); runCurrent()
            assertEquals(listOf(2L), tunes)
        } finally { Dispatchers.resetMain() }
    }
}
