package tv.anstelle.core.data.channels

import tv.anstelle.core.database.entity.ChannelDaypartEntity
import tv.anstelle.core.database.entity.ChannelEntity
import tv.anstelle.core.database.entity.ChannelRuleEntity
import tv.anstelle.core.database.entity.ChannelSeasonEntity
import tv.anstelle.core.model.Playback
import tv.anstelle.core.model.SeasonMode
import tv.anstelle.core.scheduler.Candidate
import tv.anstelle.core.scheduler.CandidatePool
import tv.anstelle.core.scheduler.ChannelConfig
import tv.anstelle.core.scheduler.Daypart
import tv.anstelle.core.scheduler.MonthDayRange
import tv.anstelle.core.scheduler.ScheduleInputs
import tv.anstelle.core.scheduler.SeasonalPool
import java.time.Year
import java.time.ZoneId
import java.time.ZoneOffset

/** Everything stored about one channel that its schedule depends on. */
class ChannelSetup(
    val channel: ChannelEntity,
    val rules: List<ChannelRuleEntity>,
    val seasons: List<ChannelSeasonEntity>,
    val dayparts: List<ChannelDaypartEntity>,
)

/** Turns a stored channel and the library into scheduler inputs. Pure. */
object ChannelInputs {
    /**
     * - The base lineup is matched against [catalog]. Every profile shares the schedule, so no
     *   profile's rating limit applies here; what a profile may not watch is blacked out on air.
     * - Seasons that override the rules become seasonal pools, keyed by the season's id.
     *   Seasons that only control visibility don't affect the schedule.
     * - A daypart may name a rule group (`ruleLogicGroup`). That group then only applies
     *   inside the daypart, as an extra filter, and is left out of the base lineup.
     * - Movies keep to [OncePerDay] when the channel asks for it and its base lineup fits it.
     */
    fun build(setup: ChannelSetup, catalog: Catalog, zone: ZoneId): ScheduleInputs {
        val channel = setup.channel
        val items = catalog.items
        val base = LineupRows.decode(setup.rules.filter { it.seasonId == null })
        val daypartGroups = setup.dayparts.mapNotNullTo(HashSet()) { it.ruleLogicGroup }

        val seasons = setup.seasons.filter { it.mode == SeasonMode.OVERRIDE_RULES }.mapNotNull { season ->
            val range = runCatching { MonthDayRange(season.startMonth, season.startDay, season.endMonth, season.endDay) }.getOrNull()
                ?: return@mapNotNull null
            val lineup = LineupRows.decode(setup.rules.filter { it.seasonId == season.id }).lineup()
            SeasonalPool(key = season.id, range = range, pool = poolOf(LineupMatcher(lineup).match(items)))
        }
        val dayparts = setup.dayparts.mapNotNull { daypart ->
            val allowed = daypart.ruleLogicGroup?.let { group ->
                base.groups[group]?.let { rules ->
                    LineupMatcher(Lineup(listOf(rules))).match(items).matches.map { it.item.id }.toLongArray()
                }
            }
            runCatching {
                Daypart(daypart.startMinuteOfDay, daypart.endMinuteOfDay, daypart.minRatingScore, daypart.maxRatingScore, allowed)
            }.getOrNull()
        }
        val matched = LineupMatcher(base.lineup(except = daypartGroups)).match(items)
        return ScheduleInputs(
            config = ChannelConfig(
                seed = channel.seed,
                series = Playback(channel.seriesOrder, channel.seriesBlock),
                movies = Playback(channel.movieOrder, channel.movieBlock),
                movieGapMs = if (channel.moviesOncePerDay && OncePerDay.fits(matched)) OncePerDay.GAP_MS else 0,
            ),
            pool = poolOf(matched),
            seasons = seasons,
            dayparts = dayparts,
            zone = zone,
        )
    }

    fun poolOf(result: MatchResult): CandidatePool = CandidatePool.of(
        result.matches.map { match ->
            val row = match.item.row
            Candidate(
                id = row.id,
                durationMs = row.durationMs,
                showKey = row.seriesRemoteId,
                season = row.season,
                episode = row.episode,
                weight = match.weight,
                ratingScore = row.ratingScore,
                releaseUtcMs = row.premiereDateUtcMs ?: row.year?.let { Year.of(it).atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() },
            )
        },
    )
}
