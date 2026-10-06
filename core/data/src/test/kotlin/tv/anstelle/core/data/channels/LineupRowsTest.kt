package tv.anstelle.core.data.channels

import tv.anstelle.core.database.entity.ChannelRuleEntity
import tv.anstelle.core.model.RuleField
import tv.anstelle.core.model.RuleOp
import kotlin.test.Test
import kotlin.test.assertEquals

class LineupRowsTest {
    private val lineup = Lineup(
        groups = listOf(
            RuleGroup(listOf(Rule(RuleField.GENRE, RuleOp.ANY_OF, "Comedy", "Sitcom"), Rule(RuleField.MEDIA_TYPE, RuleOp.EQUALS, "EPISODE")), weight = 2f),
            RuleGroup(listOf(Rule(RuleField.YEAR, RuleOp.BETWEEN, 1980, 1989))),
        ),
        includes = listOf(Pick(RuleField.SERIES, "s-1", weight = 3f)),
        excludes = listOf(Pick(RuleField.ITEM, "item-9")),
    )

    @Test
    fun `lineups round-trip through rule rows`() {
        val rows = LineupRows.encode(channelId = 4, seasonId = null, lineup = lineup)
        assertEquals(5, rows.size)
        assertEquals("Comedy|Sitcom", rows[0].value)
        assertEquals(listOf(0, 0, 1, LineupRows.PICK_GROUP, LineupRows.PICK_GROUP), rows.map { it.logicGroup })
        assertEquals(listOf(2f, 2f, 1f, 3f, 1f), rows.map { it.weight })
        assertEquals(lineup, LineupRows.decode(rows).lineup())
    }

    @Test
    fun `groups can start after the ones dayparts use, and be left out`() {
        val rows = LineupRows.encode(4, null, lineup, firstGroup = 3)
        val daypartOnly = ChannelRuleEntity(channelId = 4, seasonId = null, logicGroup = 1, field = RuleField.OFFICIAL_RATING, op = RuleOp.AT_MOST, value = "7", weight = 1f)
        val decoded = LineupRows.decode(rows + daypartOnly)
        assertEquals(setOf(1, 3, 4), decoded.groups.keys)
        assertEquals(lineup, decoded.lineup(except = setOf(1)))
    }

    @Test
    fun `pipes in values are replaced and bad rows are skipped`() {
        val rows = LineupRows.encode(1, null, Lineup(listOf(RuleGroup(listOf(Rule(RuleField.TAG, RuleOp.ANY_OF, "a|b", " c "))))))
        assertEquals("a/b|c", rows.single().value)
        val broken = rows.single().copy(op = RuleOp.AT_MOST)
        assertEquals(Lineup.EMPTY, LineupRows.decode(listOf(broken)).lineup())
    }
}
