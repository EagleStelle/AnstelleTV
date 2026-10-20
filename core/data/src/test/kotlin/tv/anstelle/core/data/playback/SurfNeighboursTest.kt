package tv.anstelle.core.data.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class SurfNeighboursTest {
    private val ids = listOf(10L, 20L, 30L, 40L)

    @Test fun surfingDirectionComesFirstThenTheOtherWay() {
        assertEquals(listOf(30L, 10L), surfNeighbours(ids, index = 1, step = 1, wrap = true))
        assertEquals(listOf(10L, 30L), surfNeighbours(ids, index = 1, step = -1, wrap = true))
    }

    @Test fun channelKeysGoRoundTheEnds() {
        assertEquals(listOf(10L, 30L), surfNeighbours(ids, index = 3, step = 1, wrap = true))
        assertEquals(listOf(40L, 20L), surfNeighbours(ids, index = 0, step = -1, wrap = true))
    }

    @Test fun guideRowsStopAtTheEnds() {
        assertEquals(listOf(30L), surfNeighbours(ids, index = 3, step = 1, wrap = false))
        assertEquals(listOf(20L), surfNeighbours(ids, index = 0, step = -1, wrap = false))
    }

    @Test fun smallLineupsNeverRepeatOrListTheChannelOnNow() {
        assertEquals(listOf(20L), surfNeighbours(listOf(10L, 20L), index = 0, step = 1, wrap = true))
        assertEquals(emptyList<Long>(), surfNeighbours(listOf(10L), index = 0, step = 1, wrap = true))
        assertEquals(emptyList<Long>(), surfNeighbours(ids, index = -1, step = 1, wrap = true))
    }
}
