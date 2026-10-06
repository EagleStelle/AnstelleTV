package tv.anstelle.core.data.channels

import androidx.room.withTransaction
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tv.anstelle.core.common.AnstelleDispatchers
import tv.anstelle.core.common.AppLog
import tv.anstelle.core.common.ApplicationScope
import tv.anstelle.core.common.Dispatcher
import tv.anstelle.core.common.TimeSource
import tv.anstelle.core.data.SettingKey
import tv.anstelle.core.data.SettingsRepository
import tv.anstelle.core.database.AnstelleDatabase
import tv.anstelle.core.database.dao.ChannelDao
import tv.anstelle.core.database.dao.MediaItemDao
import tv.anstelle.core.database.dao.ProfileDao
import tv.anstelle.core.database.dao.ScheduleDao
import tv.anstelle.core.database.entity.ChannelEntity
import tv.anstelle.core.database.entity.ChannelGroupEntity
import tv.anstelle.core.database.entity.ChannelSeasonEntity
import tv.anstelle.core.model.Channel
import tv.anstelle.core.model.ChannelGroup
import tv.anstelle.core.model.ChannelKind
import tv.anstelle.core.model.PlaybackOrder
import tv.anstelle.core.model.RuleField
import tv.anstelle.core.model.RuleOp
import tv.anstelle.core.model.SeasonMode
import tv.anstelle.core.scheduler.MonthDayRange
import java.io.IOException
import java.time.LocalDate
import java.time.Month
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

/** A channel as the channel list shows it. */
data class ChannelSummary(
    val channel: Channel,
    val groupName: String?,
    /** "Dec 1 – Dec 31" for a seasonal channel, which only appears in the guide on those dates. */
    val seasonLabel: String?,
    /** False for a seasonal channel outside its dates. */
    val inSeason: Boolean,
)

/** One airing on a channel; [title] is null while the channel is off air. */
data class Airing(val startUtcMs: Long, val endUtcMs: Long, val title: String?)

/** What "Add to channel" adds for an item: the show for an episode, else the item itself. */
data class ChannelPick(val pick: Pick, val title: String)

/** Channels, their lineups and groups, and the template channels built from the library. */
@Singleton
class ChannelRepository @Inject constructor(
    private val database: AnstelleDatabase,
    private val channelDao: ChannelDao,
    private val scheduleDao: ScheduleDao,
    private val itemDao: MediaItemDao,
    private val profileDao: ProfileDao,
    private val settings: SettingsRepository,
    private val templates: TemplateSource,
    private val logos: ChannelLogos,
    private val updater: ScheduleUpdater,
    private val time: TimeSource,
    @ApplicationScope private val scope: CoroutineScope,
    @Dispatcher(AnstelleDispatchers.IO) private val io: CoroutineDispatcher,
) {
    fun observeChannels(profileId: Long): Flow<List<ChannelSummary>> =
        combine(channelDao.observeAll(profileId), channelDao.observeGroups(profileId)) { channels, groupList ->
            val groups = groupList.associate { it.id to it.name }
            val visible = channelDao.seasonsFor(channels.map { it.id })
                .filter { it.mode == SeasonMode.VISIBLE_IN_RANGE }
                .associateBy { it.channelId }
            val today = LocalDate.now(ZoneId.systemDefault())
            channels.map { channel ->
                val season = visible[channel.id]
                ChannelSummary(
                    channel = channel.toModel(),
                    groupName = channel.groupId?.let(groups::get),
                    seasonLabel = season?.let(::seasonLabel),
                    inSeason = season == null || season.range()?.contains(today.monthValue, today.dayOfMonth) != false,
                )
            }
        }

    fun observeChannel(channelId: Long): Flow<Channel?> = channelDao.observe(channelId).map { it?.toModel() }

    fun observeGroups(profileId: Long): Flow<List<ChannelGroup>> =
        channelDao.observeGroups(profileId).map { list -> list.map { ChannelGroup(it.id, it.profileId, it.name, it.sortOrder) } }

    /** The whole library as channel rules see it. */
    suspend fun catalog(): Catalog = withContext(io) { Catalog(itemDao.catalog()) }

    /** The airing on now and the ones after it. Empty when the channel has no schedule yet. */
    suspend fun upcoming(channelId: Long, count: Int): List<Airing> = withContext(io) {
        val now = time.nowUtcMs()
        val current = scheduleDao.slotAt(channelId, now)?.takeIf { now < it.endUtcMs }
        val slots = listOfNotNull(current) + scheduleDao.slotsFrom(channelId, current?.endUtcMs ?: now, count - (if (current == null) 0 else 1))
        val items = itemDao.byIds(slots.mapNotNull { it.mediaItemId }).associateBy { it.id }
        slots.map { slot ->
            val title = slot.mediaItemId?.let(items::get)?.let { item ->
                when {
                    item.showTitle != null && item.season != null && item.episode != null ->
                        "${item.showTitle} · S${item.season}E${item.episode} · ${item.title}"
                    item.showTitle != null -> "${item.showTitle} · ${item.title}"
                    item.year != null -> "${item.title} (${item.year})"
                    else -> item.title
                }
            }
            Airing(slot.startUtcMs, slot.endUtcMs, title)
        }
    }

    /** The channel's own lineup (without season lineups and daypart-only groups). */
    suspend fun lineup(channelId: Long): Lineup = withContext(io) {
        val daypartGroups = channelDao.dayparts(channelId).mapNotNullTo(HashSet()) { it.ruleLogicGroup }
        LineupRows.decode(channelDao.rules(channelId).filter { it.seasonId == null }).lineup(except = daypartGroups)
    }

    /** Replaces the channel's lineup and regenerates its schedule after the current and next airings. */
    suspend fun setLineup(channelId: Long, lineup: Lineup) {
        lineup.problem()?.let { throw IllegalArgumentException(it) }
        withContext(io) {
            val keep = channelDao.dayparts(channelId).mapNotNull { it.ruleLogicGroup }
            val first = (keep.maxOrNull() ?: -1) + 1
            channelDao.replaceBaseRules(channelId, LineupRows.encode(channelId, null, lineup, firstGroup = first), keep)
            touch(channelId)
        }
        refresh(channelId)
    }

    /** The show (for an episode) or item that "Add to channel" adds for [itemId]. */
    suspend fun pickFor(itemId: Long): ChannelPick? = withContext(io) {
        val item = itemDao.byId(itemId) ?: return@withContext null
        val series = item.seriesRemoteId
        if (series != null) {
            ChannelPick(Pick(RuleField.SERIES, series), item.showTitle ?: item.title)
        } else {
            ChannelPick(Pick(RuleField.ITEM, item.remoteId), item.title)
        }
    }

    /** Channels of [profileId] that always include [remoteId]. */
    suspend fun channelsIncluding(profileId: Long, remoteId: String): Set<Long> =
        withContext(io) { channelDao.channelsIncluding(profileId, remoteId, RuleOp.INCLUDE).toSet() }

    /**
     * Makes [channelId] always air [pick] (and no longer exclude it). The schedule changes
     * after the airing on now and the next one, like any lineup edit.
     */
    suspend fun include(channelId: Long, pick: Pick) {
        val current = lineup(channelId)
        val same = { other: Pick -> other.remoteId.equals(pick.remoteId, ignoreCase = true) }
        if (current.includes.any(same) && current.excludes.none(same)) return
        setLineup(channelId, current.copy(includes = current.includes.filterNot(same) + pick, excludes = current.excludes.filterNot(same)))
    }

    /** Creates a custom channel with the next free number. Returns its id. */
    suspend fun create(profileId: Long, name: String, lineup: Lineup, order: PlaybackOrder, blockSize: Int): Long {
        lineup.problem()?.let { throw IllegalArgumentException(it) }
        val id = withContext(io) {
            database.withTransaction {
                val id = channelDao.insert(newChannel(profileId, nextNumber(profileId), name, order, blockSize, groupId = null, templateId = null))
                channelDao.insertRules(LineupRows.encode(id, null, lineup))
                id
            }
        }
        refresh(id)
        return id
    }

    suspend fun rename(channelId: Long, name: String) = edit(channelId, reschedule = false) { it.copy(name = name.trim().ifEmpty { it.name }) }

    suspend fun setPlayback(channelId: Long, order: PlaybackOrder, blockSize: Int) =
        edit(channelId, reschedule = true) { it.copy(playbackOrder = order, blockSize = blockSize.coerceIn(1, 4)) }

    suspend fun setFavorite(channelId: Long, favorite: Boolean) = withContext(io) {
        channelDao.setFavorite(channelId, favorite, time.nowUtcMs())
    }

    suspend fun setHidden(channelId: Long, hidden: Boolean) {
        withContext(io) { channelDao.setHidden(channelId, hidden, time.nowUtcMs()) }
        // A shown channel gets its schedule back right away.
        if (!hidden) refresh(channelId)
    }

    suspend fun setGroup(channelId: Long, groupId: Long?) = withContext(io) {
        channelDao.setGroup(channelId, groupId, time.nowUtcMs())
    }

    /** Gives the channel [number]; a channel that had it takes the old number. */
    suspend fun setNumber(channelId: Long, number: Int) = withContext(io) {
        require(number in 1..MAX_NUMBER) { "Channel numbers are 1–$MAX_NUMBER" }
        val channel = channelDao.byId(channelId) ?: return@withContext
        channelDao.renumber(channel, number, time.nowUtcMs())
    }

    suspend fun delete(channelId: Long) = withContext(io) {
        val channel = channelDao.byId(channelId) ?: return@withContext
        channelDao.delete(channel)
        logos.delete(channel.logoUri)
        AppLog.i(TAG, "Deleted channel ${channel.number} ${channel.name}")
    }

    /** Stores an uploaded logo. Throws [IOException] when [bytes] is not an image. */
    suspend fun setLogo(channelId: Long, bytes: ByteArray) = withContext(io) {
        if (bytes.size > ChannelLogos.MAX_UPLOAD_BYTES) throw IOException("The image is larger than ${ChannelLogos.MAX_UPLOAD_BYTES / (1024 * 1024)} MB")
        val channel = channelDao.byId(channelId) ?: return@withContext
        val uri = logos.store(channelId, bytes)
        channelDao.setLogo(channelId, uri, time.nowUtcMs())
        logos.delete(channel.logoUri)
    }

    suspend fun clearLogo(channelId: Long) = withContext(io) {
        val channel = channelDao.byId(channelId) ?: return@withContext
        channelDao.setLogo(channelId, null, time.nowUtcMs())
        logos.delete(channel.logoUri)
    }

    /** The channel's stored logo file, or null. */
    suspend fun logoFile(channelId: Long) = withContext(io) { logos.file(channelDao.byId(channelId)?.logoUri) }

    suspend fun createGroup(profileId: Long, name: String): Long = withContext(io) {
        val order = (channelDao.groups(profileId).maxOfOrNull { it.sortOrder } ?: -1) + 1
        channelDao.insertGroup(ChannelGroupEntity(profileId = profileId, name = name.trim(), sortOrder = order))
    }

    suspend fun renameGroup(groupId: Long, name: String) = withContext(io) {
        val group = channelDao.groupById(groupId) ?: return@withContext
        if (name.isNotBlank()) channelDao.upsertGroup(group.copy(name = name.trim()))
    }

    /** Deletes the group; its channels stay, without a group. */
    suspend fun deleteGroup(groupId: Long) = withContext(io) {
        channelDao.groupById(groupId)?.let { channelDao.deleteGroup(it) }
    }

    /** Moves a group one place up ([delta] -1) or down (+1) in the guide. */
    suspend fun moveGroup(groupId: Long, delta: Int) = withContext(io) {
        val group = channelDao.groupById(groupId) ?: return@withContext
        val groups = channelDao.groups(group.profileId).toMutableList()
        val from = groups.indexOfFirst { it.id == groupId }
        val to = (from + delta).coerceIn(0, groups.lastIndex)
        if (from == to) return@withContext
        groups.add(to, groups.removeAt(from))
        channelDao.updateGroups(groups.mapIndexed { i, g -> g.copy(sortOrder = i) })
    }

    /** Matching templates the profile has no channel for yet, in template order. */
    suspend fun availableTemplates(profileId: Long): List<PlannedChannel> =
        withContext(io) { plan(profileId, Catalog(itemDao.catalog())) }

    private suspend fun plan(profileId: Long, catalog: Catalog): List<PlannedChannel> {
        val limit = profileDao.byId(profileId)?.maxRatingScore
        return ChannelPlanner.plan(templates.templates(), catalog, channelDao.templateIds(profileId).toSet(), limit)
    }

    /** Creates channels for [planned] templates and schedules them. Returns the new channel ids. */
    suspend fun addTemplates(profileId: Long, planned: List<PlannedChannel>): List<Long> {
        val ids = insertTemplates(profileId, planned)
        if (ids.isNotEmpty()) {
            scope.launch(io) {
                try {
                    updater.updateAll()
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    AppLog.e(TAG, "Scheduling new channels failed", e)
                }
            }
        }
        return ids
    }

    private suspend fun insertTemplates(profileId: Long, planned: List<PlannedChannel>): List<Long> {
        if (planned.isEmpty()) return emptyList()
        val ids = withContext(io) {
            database.withTransaction {
                val groups = channelDao.groups(profileId).associateTo(HashMap()) { it.name.lowercase(Locale.ROOT) to it.id }
                var number = nextNumber(profileId)
                planned.map { plan ->
                    val template = plan.template
                    val groupId = groups.getOrPut(template.category.lowercase(Locale.ROOT)) {
                        channelDao.insertGroup(ChannelGroupEntity(profileId = profileId, name = template.category, sortOrder = groups.size))
                    }
                    val id = channelDao.insert(newChannel(profileId, number++, template.name, template.order, template.blockSize, groupId, template.id))
                    channelDao.insertRules(LineupRows.encode(id, null, template.lineup))
                    template.visible?.let { range -> channelDao.insertSeason(season(id, "Visible", SeasonMode.VISIBLE_IN_RANGE, range)) }
                    for (season in template.seasons) {
                        val seasonId = channelDao.insertSeason(season(id, season.name, SeasonMode.OVERRIDE_RULES, season.range))
                        channelDao.insertRules(LineupRows.encode(id, seasonId, season.lineup))
                    }
                    id
                }
            }
        }
        AppLog.i(TAG, "Added ${ids.size} template channels: ${planned.joinToString { it.template.id }}")
        return ids
    }

    /**
     * After the first library sync, creates every template channel the library has enough
     * content for, once per profile. Returns how many channels were created; the caller
     * schedules them.
     */
    suspend fun autoGenerateIfNeeded(): Int {
        val pending = withContext(io) { profileDao.all() }.filter { !settings.get(autoGeneratedKey(it.id)) }
        if (pending.isEmpty()) return 0
        val catalog = catalog()
        // Nothing synced yet: try again after the next sync.
        if (catalog.isEmpty) return 0
        var created = 0
        for (profile in pending) {
            created += insertTemplates(profile.id, withContext(io) { plan(profile.id, catalog) }).size
            settings.set(autoGeneratedKey(profile.id), true)
        }
        return created
    }

    private suspend fun edit(channelId: Long, reschedule: Boolean, change: (ChannelEntity) -> ChannelEntity) {
        withContext(io) {
            val channel = channelDao.byId(channelId) ?: return@withContext
            channelDao.update(change(channel).copy(updatedAtUtcMs = time.nowUtcMs()))
        }
        if (reschedule) refresh(channelId)
    }

    private suspend fun touch(channelId: Long) {
        val channel = channelDao.byId(channelId) ?: return
        channelDao.update(channel.copy(updatedAtUtcMs = time.nowUtcMs()))
    }

    /** Brings the channel's schedule up to date in the background, so the guide follows edits at once. */
    private fun refresh(channelId: Long) {
        scope.launch(io) {
            try {
                updater.update(channelId)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                AppLog.e(TAG, "Scheduling channel $channelId failed", e)
            }
        }
    }

    private suspend fun nextNumber(profileId: Long) = channelDao.maxNumber(profileId) + 1

    private fun newChannel(
        profileId: Long,
        number: Int,
        name: String,
        order: PlaybackOrder,
        blockSize: Int,
        groupId: Long?,
        templateId: String?,
    ): ChannelEntity {
        val now = time.nowUtcMs()
        return ChannelEntity(
            profileId = profileId,
            number = number,
            name = name.trim(),
            logoUri = null,
            kind = ChannelKind.GENERATED,
            seed = Random.nextLong(),
            playbackOrder = order,
            blockSize = blockSize.coerceIn(1, 4),
            isHidden = false,
            isFavorite = false,
            groupId = groupId,
            templateId = templateId,
            createdAtUtcMs = now,
            updatedAtUtcMs = now,
        )
    }

    private fun season(channelId: Long, name: String, mode: SeasonMode, range: MonthDayRange) = ChannelSeasonEntity(
        channelId = channelId,
        name = name,
        mode = mode,
        startMonth = range.startMonth,
        startDay = range.startDay,
        endMonth = range.endMonth,
        endDay = range.endDay,
    )

    private fun seasonLabel(season: ChannelSeasonEntity): String {
        fun date(month: Int, day: Int) = Month.of(month).getDisplayName(TextStyle.SHORT, Locale.getDefault()) + " " + day
        return "${date(season.startMonth, season.startDay)} – ${date(season.endMonth, season.endDay)}"
    }

    private fun ChannelSeasonEntity.range(): MonthDayRange? =
        runCatching { MonthDayRange(startMonth, startDay, endMonth, endDay) }.getOrNull()

    companion object {
        private const val TAG = "Channels"
        const val MAX_NUMBER = 9_999

        private fun autoGeneratedKey(profileId: Long) = SettingKey.BoolKey("channels.generated.$profileId", false)
    }
}

internal fun ChannelEntity.toModel() = Channel(
    id = id,
    profileId = profileId,
    number = number,
    name = name,
    logoUri = logoUri,
    kind = kind,
    playbackOrder = playbackOrder,
    blockSize = blockSize,
    isHidden = isHidden,
    isFavorite = isFavorite,
    groupId = groupId,
)
