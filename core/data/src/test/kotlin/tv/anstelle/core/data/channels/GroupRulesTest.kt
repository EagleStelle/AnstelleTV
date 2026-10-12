package tv.anstelle.core.data.channels

import tv.anstelle.core.model.ChannelGroup
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class GroupRulesTest {
    private val productions = ChannelGroup(1, "Productions", 0, 1000, fromLibrary = true)
    private val genres = ChannelGroup(2, "Genres", 1, 3000, fromLibrary = true)
    private val kids = ChannelGroup(3, "Kids", 2, 5000, fromLibrary = false)
    private val classics = ChannelGroup(4, "Classics", 3, 6000, fromLibrary = false)

    @Test
    fun `a new group starts at the thousand after every start, the library's included before they exist`() {
        assertEquals(5000, GroupRules.nextStart(emptyList(), emptyList()))
        assertEquals(5000, GroupRules.nextStart(listOf(productions, genres), listOf(1000, 3000, 3001)))
        assertEquals(7000, GroupRules.nextStart(listOf(productions, genres, kids, classics), emptyList()))
        // A channel numbered past every start pushes it on.
        assertEquals(6000, GroupRules.nextStart(listOf(productions, genres, kids), listOf(5000, 5999)))
        assertEquals(GroupRules.MAX_START, GroupRules.nextStart(listOf(kids.copy(numberStart = 9500)), emptyList()))
    }

    @Test
    fun `starts are in range and no two groups share one`() {
        val groups = listOf(productions, genres, kids)
        assertNull(GroupRules.startProblem(6000, groups))
        assertEquals("Kids starts at 5000.", GroupRules.startProblem(5000, groups))
        // The library's Decades and Themes keep their starts before they are made.
        assertEquals("Decades starts at 2000.", GroupRules.startProblem(2000, groups))
        assertEquals("Themes starts at 4000.", GroupRules.startProblem(4000, groups))
        assertNotNull(GroupRules.startProblem(10_000, groups))
        // A group keeps its own start.
        assertNull(GroupRules.startProblem(5000, groups, groupId = kids.id))
    }

    @Test
    fun `names are set and no two groups share one, the library's included`() {
        val groups = listOf(productions, kids)
        assertNull(GroupRules.nameProblem("Saturday Morning", groups))
        assertEquals("Give the group a name.", GroupRules.nameProblem("  ", groups))
        assertEquals("There is a group called kids.", GroupRules.nameProblem(" kids ", groups))
        assertEquals("There is a group called Themes.", GroupRules.nameProblem("Themes", groups))
        assertNull(GroupRules.nameProblem("Kids", groups, groupId = kids.id))
    }
}
