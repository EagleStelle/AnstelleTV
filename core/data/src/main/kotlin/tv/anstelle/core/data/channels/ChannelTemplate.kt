package tv.anstelle.core.data.channels

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import tv.anstelle.core.model.PlaybackOrder
import tv.anstelle.core.model.RuleField
import tv.anstelle.core.model.RuleOp
import tv.anstelle.core.scheduler.MonthDayRange

/**
 * A prebuilt channel, defined as data in the JSON files in `assets/templates`. A template becomes a
 * channel when the library has enough matching content: at least [minItems] items from at
 * least [minShows] shows (a movie counts as a show).
 */
data class ChannelTemplate(
    val id: String,
    val name: String,
    /** Guide group the channel joins. */
    val category: String,
    val description: String,
    val order: PlaybackOrder,
    val blockSize: Int,
    val lineup: Lineup,
    val minItems: Int,
    val minShows: Int,
    /** When set, the channel only appears in the guide on these dates every year. */
    val visible: MonthDayRange?,
    /** Lineups that replace [lineup] on their dates. */
    val seasons: List<TemplateSeason>,
)

data class TemplateSeason(val name: String, val range: MonthDayRange, val lineup: Lineup)

class TemplateException(message: String) : Exception(message)

/**
 * Reads template files. A file holds `{"templates": [...]}`; every `*.json` file in the
 * templates folder is read, so adding a template needs no code change.
 *
 * ```json
 * {
 *   "id": "sitcoms", "name": "Sitcoms", "category": "Comedy",
 *   "order": "BLOCKS", "blockSize": 2, "era": {"from": 1980, "to": 1999},
 *   "minItems": 20, "minShows": 3,
 *   "groups": [
 *     {"rules": [
 *       {"field": "GENRE", "op": "ANY_OF", "values": ["Comedy", "Sitcom"]},
 *       {"field": "MEDIA_TYPE", "op": "EQUALS", "values": ["EPISODE"]}
 *     ]}
 *   ],
 *   "visible": {"from": "12-01", "to": "12-31"}
 * }
 * ```
 *
 * Groups are alternatives (OR); the rules inside a group must all hold (AND). `era` adds a
 * year range to every group. `seasons` replace the lineup on their dates.
 */
object ChannelTemplates {
    private val json = Json { ignoreUnknownKeys = false }

    /** Parses one file. Throws [TemplateException] naming the file and template on any problem. */
    fun parse(fileName: String, text: String): List<ChannelTemplate> {
        val file = try {
            json.decodeFromString(TemplateFileJson.serializer(), text)
        } catch (e: SerializationException) {
            throw TemplateException("$fileName: ${e.message}")
        } catch (e: IllegalArgumentException) {
            throw TemplateException("$fileName: ${e.message}")
        }
        return file.templates.map { template ->
            try {
                template.toTemplate()
            } catch (e: IllegalArgumentException) {
                throw TemplateException("$fileName, template ${template.id}: ${e.message}")
            }
        }
    }

    /**
     * Parses every file, in file-name order. Files that fail to parse and templates whose id
     * is already taken are reported to [onProblem] and skipped.
     */
    fun parseAll(files: List<Pair<String, String>>, onProblem: (String) -> Unit = {}): List<ChannelTemplate> {
        val seen = HashSet<String>()
        val templates = ArrayList<ChannelTemplate>()
        for ((name, text) in files.sortedBy { it.first }) {
            val parsed = try {
                parse(name, text)
            } catch (e: TemplateException) {
                onProblem(e.message.orEmpty())
                continue
            }
            for (template in parsed) {
                if (seen.add(template.id)) templates += template else onProblem("$name: duplicate template id ${template.id}")
            }
        }
        return templates
    }

    private fun TemplateJson.toTemplate(): ChannelTemplate {
        require(Id.matches(id)) { "id must be lowercase letters, digits and dashes" }
        require(name.isNotBlank() && category.isNotBlank()) { "name and category are required" }
        require(blockSize in 1..4) { "blockSize must be 1–4" }
        require(minItems >= 1 && minShows >= 1) { "minItems and minShows must be at least 1" }
        require(groups.isNotEmpty()) { "at least one group is required" }
        era?.let { require(it.from <= it.to) { "era runs backwards" } }
        val lineup = lineupOf(groups, era)
        return ChannelTemplate(
            id = id,
            name = name.trim(),
            category = category.trim(),
            description = description.trim(),
            order = order,
            blockSize = blockSize,
            lineup = lineup,
            minItems = minItems,
            minShows = minShows,
            visible = visible?.let { range(it.from, it.to) },
            seasons = seasons.map { TemplateSeason(it.name, range(it.from, it.to), lineupOf(it.groups, era)) },
        )
    }

    private fun lineupOf(groups: List<GroupJson>, era: EraJson?): Lineup {
        val eraRule = era?.let { Rule(RuleField.YEAR, RuleOp.BETWEEN, it.from, it.to) }
        val lineup = Lineup(
            groups.map { group ->
                val rules = group.rules.map { Rule(it.field, it.op, it.values.map(JsonPrimitive::content)) }
                RuleGroup(if (eraRule == null) rules else rules + eraRule, group.weight)
            },
        )
        lineup.problem()?.let { throw IllegalArgumentException(it) }
        return lineup
    }

    /** `"12-01"` → December 1. */
    private fun range(from: String, to: String): MonthDayRange {
        fun parse(value: String): Pair<Int, Int> {
            val match = requireNotNull(MonthDay.matchEntire(value.trim())) { "dates are written MM-DD: $value" }
            return match.groupValues[1].toInt() to match.groupValues[2].toInt()
        }
        val (startMonth, startDay) = parse(from)
        val (endMonth, endDay) = parse(to)
        return MonthDayRange(startMonth, startDay, endMonth, endDay)
    }

    private val Id = Regex("[a-z0-9]+(-[a-z0-9]+)*")
    private val MonthDay = Regex("(\\d{2})-(\\d{2})")
}

@Serializable
private class TemplateFileJson(val templates: List<TemplateJson>)

@Serializable
private class TemplateJson(
    val id: String,
    val name: String,
    val category: String,
    val description: String = "",
    val order: PlaybackOrder = PlaybackOrder.SHUFFLE,
    val blockSize: Int = 1,
    val era: EraJson? = null,
    val minItems: Int = 10,
    val minShows: Int = 2,
    val groups: List<GroupJson>,
    val visible: RangeJson? = null,
    val seasons: List<SeasonJson> = emptyList(),
)

@Serializable
private class GroupJson(val rules: List<RuleJson>, val weight: Float = 1f)

@Serializable
private class RuleJson(val field: RuleField, val op: RuleOp, val values: List<JsonPrimitive>)

@Serializable
private class EraJson(val from: Int, val to: Int)

@Serializable
private class RangeJson(val from: String, val to: String)

@Serializable
private class SeasonJson(val name: String, val from: String, val to: String, val groups: List<GroupJson>)
