package tv.anstelle.core.data.channels

import tv.anstelle.core.data.episodes
import tv.anstelle.core.data.item
import tv.anstelle.core.model.MediaType
import tv.anstelle.core.model.PlaybackOrder
import tv.anstelle.core.model.RuleField
import tv.anstelle.core.model.RuleOp
import tv.anstelle.core.scheduler.MonthDayRange
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ChannelTemplatesTest {
    /** The templates that ship in the APK, read from the module's assets folder. */
    private val shippedFiles: List<Pair<String, String>> =
        File("src/main/assets/templates").listFiles { file -> file.extension == "json" }.orEmpty()
            .map { it.name to it.readText() }

    private fun shipped(extra: List<Pair<String, String>> = emptyList()): List<ChannelTemplate> {
        val problems = ArrayList<String>()
        val templates = ChannelTemplates.parseAll(shippedFiles + extra) { problems += it }
        assertEquals(emptyList(), problems)
        return templates
    }

    @Test
    fun `every shipped template is valid and ids are unique`() {
        val templates = shipped()
        assertTrue(templates.size >= 90, "only ${templates.size} templates")
        assertEquals(templates.size, templates.map { it.id }.toSet().size)
        for (template in templates) {
            assertEquals(null, template.lineup.problem(), template.id)
            assertTrue(template.lineup.groups.isNotEmpty(), template.id)
            assertTrue(template.category.isNotBlank() && template.description.isNotBlank(), template.id)
            assertTrue(template.minShows <= template.minItems, template.id)
        }
        assertTrue(templates.count { it.visible != null } >= 5, "seasonal templates")
    }

    @Test
    fun `adding a template file creates a channel without code changes`() {
        val catalog = Catalog(
            episodes("s-pattern", 0, 12, genres = listOf("Experimental")) +
                episodes("s-bars", 100, 12, genres = listOf("Experimental")),
        )
        val custom = "zz-custom.json" to """
            {"templates": [{
              "id": "test-pattern", "name": "Test Pattern", "category": "Off Hours",
              "description": "Bars and tones.", "order": "BLOCKS", "blockSize": 3,
              "minItems": 20, "minShows": 2,
              "groups": [{"rules": [{"field": "GENRE", "op": "ANY_OF", "values": ["experimental"]}]}]
            }]}
        """.trimIndent()

        assertTrue(ChannelPlanner.plan(shipped(), catalog, emptySet()).none { it.template.id == "test-pattern" })
        val planned = ChannelPlanner.plan(shipped(listOf(custom)), catalog, emptySet()).single { it.template.id == "test-pattern" }
        assertEquals("Off Hours", planned.template.category)
        assertEquals(PlaybackOrder.BLOCKS, planned.template.order)
        assertEquals(3, planned.template.blockSize)
        assertEquals(24, planned.match.itemCount)
    }

    @Test
    fun `era adds a year range to every group`() {
        val (template) = ChannelTemplates.parse(
            "era.json",
            """{"templates": [{"id": "retro", "name": "Retro", "category": "Decades", "era": {"from": 1980, "to": 1989},
               "groups": [{"rules": [{"field": "GENRE", "op": "ANY_OF", "values": ["Comedy"]}]},
                          {"rules": [{"field": "MEDIA_TYPE", "op": "EQUALS", "values": ["MOVIE"]}], "weight": 2}]}]}""",
        )
        val era = Rule(RuleField.YEAR, RuleOp.BETWEEN, 1980, 1989)
        assertTrue(template.lineup.groups.all { era in it.rules })
        assertEquals(listOf(1f, 2f), template.lineup.groups.map { it.weight })
        assertEquals(PlaybackOrder.SHUFFLE, template.order)
        assertEquals(10, template.minItems)
    }

    @Test
    fun `visibility and season dates may wrap the year`() {
        val (template) = ChannelTemplates.parse(
            "seasons.json",
            """{"templates": [{"id": "winter", "name": "Winter", "category": "Seasonal",
               "visible": {"from": "12-26", "to": "01-02"},
               "groups": [{"rules": [{"field": "KEYWORD", "op": "ANY_OF", "values": ["snow"]}]}],
               "seasons": [{"name": "Holidays", "from": "12-20", "to": "12-25",
                            "groups": [{"rules": [{"field": "KEYWORD", "op": "ANY_OF", "values": ["christmas"]}]}]}]}]}""",
        )
        assertEquals(MonthDayRange(12, 26, 1, 2), template.visible)
        assertTrue(template.visible!!.contains(1, 1))
        assertEquals("Holidays", template.seasons.single().name)
        assertEquals(listOf("christmas"), template.seasons.single().lineup.groups.single().rules.single().values)
    }

    @Test
    fun `broken templates name the file and template`() {
        fun failure(json: String) = assertFailsWith<TemplateException> { ChannelTemplates.parse("bad.json", json) }.message.orEmpty()

        val group = """[{"rules": [{"field": "GENRE", "op": "ANY_OF", "values": ["x"]}]}]"""
        assertTrue(failure("""{"templates": [{"id": "a", "name": "A", "category": "C", "groups": [{"rules": [{"field": "PLANET", "op": "ANY_OF", "values": ["x"]}]}]}]}""").startsWith("bad.json"))
        assertTrue("template b" in failure("""{"templates": [{"id": "b", "name": "B", "category": "C", "groups": [{"rules": [{"field": "GENRE", "op": "AT_MOST", "values": [3]}]}]}]}"""))
        assertTrue("id" in failure("""{"templates": [{"id": "Bad Id", "name": "B", "category": "C", "groups": $group}]}"""))
        assertTrue("MM-DD" in failure("""{"templates": [{"id": "c", "name": "C", "category": "C", "visible": {"from": "Dec 1", "to": "12-31"}, "groups": $group}]}"""))
        assertTrue("blockSize" in failure("""{"templates": [{"id": "d", "name": "D", "category": "C", "blockSize": 9, "groups": $group}]}"""))
        assertTrue("group" in failure("""{"templates": [{"id": "e", "name": "E", "category": "C", "groups": []}]}"""))

        // One broken file doesn't stop the others; a repeated id is skipped.
        val problems = ArrayList<String>()
        val good = """{"templates": [{"id": "ok", "name": "OK", "category": "C", "groups": $group}]}"""
        val templates = ChannelTemplates.parseAll(listOf("a.json" to good, "b.json" to "{nope", "c.json" to good)) { problems += it }
        assertEquals(listOf("ok"), templates.map { it.id })
        assertEquals(2, problems.size)
    }
}

class ChannelPlannerTest {
    private val sitcoms = ChannelTemplate(
        id = "sitcoms", name = "Sitcoms", category = "Comedy", description = "", order = PlaybackOrder.EPISODE_ORDER, blockSize = 1,
        lineup = Lineup(listOf(RuleGroup(listOf(Rule(RuleField.GENRE, RuleOp.ANY_OF, "Comedy"))))),
        minItems = 20, minShows = 3, visible = null, seasons = emptyList(),
    )
    private val movies = sitcoms.copy(
        id = "movies", lineup = Lineup(listOf(RuleGroup(listOf(Rule(RuleField.MEDIA_TYPE, RuleOp.EQUALS, "MOVIE"))))), minItems = 3, minShows = 3,
    )

    @Test
    fun `templates need enough items from enough shows`() {
        val twoShows = Catalog(episodes("a", 0, 15, genres = listOf("Comedy")) + episodes("b", 100, 15, genres = listOf("Comedy")))
        assertEquals(emptyList(), ChannelPlanner.plan(listOf(sitcoms), twoShows, emptySet()))

        val threeShows = Catalog(twoShows.items.map { it.row } + episodes("c", 200, 2, genres = listOf("Comedy")))
        assertEquals(listOf("sitcoms"), ChannelPlanner.plan(listOf(sitcoms), threeShows, emptySet()).map { it.template.id })
        assertEquals(emptyList(), ChannelPlanner.plan(listOf(sitcoms), threeShows, setOf("sitcoms")))
    }

    @Test
    fun `a rating limit counts only what the profile may watch`() {
        val catalog = Catalog(List(4) { item(it + 1L, type = MediaType.MOVIE, score = if (it < 2) 7 else 17) } + item(9, type = MediaType.MOVIE))
        assertEquals(listOf("movies"), ChannelPlanner.plan(listOf(movies), catalog, emptySet()).map { it.template.id })
        // Two movies are rated within 10 and one is unrated: not enough for a kid profile.
        assertEquals(emptyList(), ChannelPlanner.plan(listOf(movies), catalog, emptySet(), maxRatingScore = 10))
    }
}
