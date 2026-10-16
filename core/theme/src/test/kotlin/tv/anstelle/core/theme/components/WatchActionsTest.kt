package tv.anstelle.core.theme.components

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import tv.anstelle.core.model.WatchChoice
import tv.anstelle.core.model.WatchKind

class WatchActionsTest {
    private val start = WatchChoice(WatchKind.PLAY, "Start", "Beginning", fromAiring = true)
    private val resume = start.copy(title = "Resume", positionMs = 120_000)
    private val add = WatchChoice(WatchKind.ADD_TO_CHANNEL, "Add", "Choose channel")

    @Test
    fun detailsFollowsPlaybackAndPreservesEveryExistingActionAndCallback() {
        val choices = listOf(start, resume, add)
        val called = mutableListOf<WatchChoice>()
        var opened = 0
        val actions = choices.dialogActions(isEpisode = true, onOpenDetails = { opened++ }, onAction = called::add)

        assertEquals(listOf("Start", "Resume", "Open on Demand", "Add"), actions.map { it.title })
        assertSame(WatchIcons.Details, actions[2].icon)
        assertEquals("The episode's page, with its synopsis.", actions[2].detail)
        actions[2].onClick()
        assertEquals(1, opened)
        assertEquals(emptyList(), called)
        actions.filterIndexed { index, _ -> index != 2 }.forEach { it.onClick() }
        assertEquals(choices, called)
    }

    @Test
    fun movieDetailsUsesSameIconAndDoesNotNeedAResumeChoice() {
        val actions = listOf(start).dialogActions(onOpenDetails = {}, onAction = {})
        assertEquals(listOf("Start", "Open on Demand"), actions.map { it.title })
        assertEquals("The movie's page, with its synopsis.", actions.last().detail)
        assertSame(WatchIcons.Details, actions.last().icon)
    }

    @Test
    fun restrictedOffAirAndOrdinaryPlaybackDoNotGainDetails() {
        for (choices in listOf(emptyList(), listOf(add), listOf(start.copy(fromAiring = false)))) {
            val actions = choices.dialogActions(onOpenDetails = { error("Unavailable") }, onAction = {})
            assertEquals(choices.map { it.title }, actions.map { it.title })
            assertFalse(actions.any { it.icon == WatchIcons.Details })
        }
        assertEquals(1, listOf(start).dialogActions(onAction = {}).size)
    }

    @Test
    fun sharedIconsKeepRestartAndResumeDistinct() {
        assertSame(WatchIcons.StartOver, resume.copy(startsOver = true).icon())
        assertSame(WatchIcons.Resume, resume.icon())
        assertSame(WatchIcons.OnDemand, start.icon())
    }

    @Test
    fun seriesNavigationOpensWithoutStartingPlayback() {
        var opened = 0
        val action = goToSeriesAction { opened++ }
        assertEquals("Go to Series", action.title)
        assertEquals("Every season and episode, in one place.", action.detail)
        assertSame(WatchIcons.Details, action.icon)
        action.onClick()
        assertEquals(1, opened)
    }
}
