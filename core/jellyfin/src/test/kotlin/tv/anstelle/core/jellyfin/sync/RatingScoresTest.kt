package tv.anstelle.core.jellyfin.sync

import org.jellyfin.sdk.model.api.ParentalRating
import tv.anstelle.core.jellyfin.Fixtures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RatingScoresTest {
    private val scores = RatingScores.from(Fixtures.decode<List<ParentalRating>>("parental_ratings.json"))

    @Test
    fun `ratings from the server table`() {
        assertEquals(0, scores.score("TV-Y"))
        assertEquals(10, scores.score("TV-PG"))
        assertEquals(13, scores.score("PG-13"))
        assertEquals(14, scores.score("tv-14"))
        assertEquals(17, scores.score("TV-MA"))
        assertEquals(1000, scores.score("XXX"))
    }

    @Test
    fun `ratings seen on real items that the table does not list`() {
        assertEquals(13, scores.score("PG13"))
        assertEquals(17, scores.score("US-R"))
        assertEquals(16, scores.score("NC16"))
        assertEquals(18, scores.score("M18"))
        assertEquals(12, scores.score("12+"))
        assertEquals(6, scores.score("6+"))
        assertEquals(15, scores.score("15"))
    }

    @Test
    fun `unknown or missing ratings have no score`() {
        assertNull(scores.score("Unrated"))
        assertNull(scores.score("M"))
        assertNull(scores.score(""))
        assertNull(scores.score(null))
        assertNull(scores.score("99"))
    }
}
