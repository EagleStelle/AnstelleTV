package tv.anstelle.core.data.channels

import androidx.room.withTransaction
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import tv.anstelle.core.common.AnstelleDispatchers
import tv.anstelle.core.common.AppLog
import tv.anstelle.core.common.ApplicationScope
import tv.anstelle.core.common.Dispatcher
import tv.anstelle.core.common.TimeSource
import tv.anstelle.core.data.Settings
import tv.anstelle.core.data.SettingsRepository
import tv.anstelle.core.database.AnstelleDatabase
import tv.anstelle.core.database.dao.ChannelDao
import tv.anstelle.core.database.dao.LibraryDao
import tv.anstelle.core.database.dao.MediaItemDao
import tv.anstelle.core.database.dao.ScheduleDao
import tv.anstelle.core.database.entity.ChannelEntity
import tv.anstelle.core.database.entity.ChannelGroupEntity
import tv.anstelle.core.database.entity.ChannelSeasonEntity
import tv.anstelle.core.jellyfin.sync.LibraryRules
import tv.anstelle.core.model.Channel
import tv.anstelle.core.model.ChannelGroup
import tv.anstelle.core.model.ChannelKind
import tv.anstelle.core.model.Playback
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
    val groupName: String,
    /** "Dec 1 – Dec 31" for a seasonal channel, which only appears in the guide on those dates. */
    val seasonLabel: String?,
    /** False for a seasonal channel outside its dates. */
    val inSeason: Boolean,
)

/** One airing on a channel; [title] is null while the channel is off air. */
data class Airing(val startUtcMs: Long, val endUtcMs: Long, val title: String?)

/** What "Add to channel" adds for an item: the series for an episode, else the item itself. */
data class ChannelPick(val pick: Pick, val title: String)

/** Thrown when an operation other than hiding or favoriting targets a library channel. */
class LibraryChannelLockedException : IllegalStateException("Library channels can only be hidden or favorited.")

/**
 * Channels, their lineups and groups, and the channels made from the library. A group is a Live
 * TV tab and the block of numbers its channels count up from: the library's groups hold the
 * library channels, and channels built by hand go into groups someone made.
 */
@Singleton
class ChannelRepository @Inject constructor(
    private val database: AnstelleDatabase,
    private val channelDao: ChannelDao,
    private val scheduleDao: ScheduleDao,
    private val itemDao: MediaItemDao,
    private val libraryDao: LibraryDao,
    private val settings: SettingsRepository,
    private val words: ChannelWordsSource,
    private val logos: ChannelLogos,
    private val updater: ScheduleUpdater,
    private val time: TimeSource,
    @ApplicationScope private val scope: CoroutineScope,
    @Dispatcher(AnstelleDispatchers.IO) private val io: CoroutineDispatcher,
) {
    /** One library sync at a time. */
    private val syncLock = Mutex()

    fun observeChannels(): Flow<List<ChannelSummary>> =
        combine(channelDao.observeAll(), channelDao.observeGroups()) { channels, groupList ->
            val groups = groupList.associate { it.id to it.name }
            val visible = channelDao.seasonsFor(channels.map { it.id })
                .filter { it.mode == SeasonMode.VISIBLE_IN_RANGE }
                .associateBy { it.channelId }
            val today = LocalDate.now(ZoneId.systemDefault())
            channels.map { channel ->
                val season = visible[channel.id]
                ChannelSummary(
                    channel = channel.toModel(),
                    groupName = groups[channel.groupId].orEmpty(),
                    seasonLabel = season?.let(::seasonLabel),
                    inSeason = season == null || season.range()?.contains(today.monthValue, today.dayOfMonth) != false,
                )
            }
        }

    fun observeChannel(channelId: Long): Flow<Channel?> = channelDao.observe(channelId).map { it?.toModel() }

    /** Every group in guide order: the library's and the ones someone made. */
    fun observeGroups(): Flow<List<ChannelGroup>> = channelDao.observeGroups().map { list -> list.map { it.toModel() } }

    /** Where a new group starts unless someone picks another ([GroupRules.nextStart]). */
    fun observeNextGroupStart(): Flow<Int> =
        combine(channelDao.observeGroups(), channelDao.observeAll()) { groups, channels ->
            GroupRules.nextStart(groups.map { it.toModel() }, channels.map { it.number })
        }

    /** The group the last channel someone built went into, while it is still one of theirs. */
    suspend fun lastGroup(): Long? = withContext(io) {
        channelDao.groupById(settings.get(Settings.NewChannelGroup))?.takeIf { it.libraryKey == null }?.id
    }

    /** The whole library as channel rules see it. */
    suspend fun catalog(): Catalog = withContext(io) { Catalog(itemDao.catalog(), words.words().types) }

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
            val channel = requireCustomChannel(channelId)
            replaceLineup(channelId, lineup)
            channelDao.update(channel.copy(updatedAtUtcMs = time.nowUtcMs()))
        }
        refresh(channelId)
    }

    /** The series (for an episode) or item that "Add to channel" adds for [itemId]. */
    suspend fun pickFor(itemId: Long): ChannelPick? = withContext(io) {
        val item = itemDao.byId(itemId) ?: return@withContext null
        val series = item.seriesRemoteId
        if (series != null) {
            ChannelPick(Pick(RuleField.SERIES, series), item.showTitle ?: item.title)
        } else {
            ChannelPick(Pick(RuleField.ITEM, item.remoteId), item.title)
        }
    }

    /** Channels that always include [remoteId]. */
    suspend fun channelsIncluding(remoteId: String): Set<Long> =
        withContext(io) { channelDao.channelsIncluding(remoteId, RuleOp.INCLUDE).toSet() }

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

    /**
     * Creates a channel someone built in [groupId], one of the groups someone made, which numbers
     * it; the next channel starts in the same group. It plays series in episode order and shuffles
     * its movies once a day. Returns its id.
     */
    suspend fun create(name: String, lineup: Lineup, groupId: Long): Long {
        lineup.problem()?.let { throw IllegalArgumentException(it) }
        val id = withContext(io) {
            database.withTransaction {
                requireCustomGroup(groupId)
                val id = channelDao.insert(newChannel(channelDao.maxNumber() + 1, name, groupId, autoKey = null))
                channelDao.insertRules(LineupRows.encode(id, null, lineup))
                renumber()
                id
            }
        }
        settings.set(Settings.NewChannelGroup, groupId)
        refresh(id)
        return id
    }

    suspend fun rename(channelId: Long, name: String) {
        edit(channelId, reschedule = false) { it.copy(name = name.trim().ifEmpty { it.name }) }
        // Numbers follow names.
        withContext(io) { database.withTransaction { renumber() } }
    }

    suspend fun setSeriesPlayback(channelId: Long, playback: Playback) =
        edit(channelId, reschedule = true) { it.copy(seriesOrder = playback.order, seriesBlock = playback.blockSize) }

    suspend fun setMoviePlayback(channelId: Long, playback: Playback) =
        edit(channelId, reschedule = true) { it.copy(movieOrder = playback.order, movieBlock = playback.blockSize) }

    suspend fun setMoviesOncePerDay(channelId: Long, oncePerDay: Boolean) =
        edit(channelId, reschedule = true) { it.copy(moviesOncePerDay = oncePerDay) }

    suspend fun setFavorite(channelId: Long, favorite: Boolean) = withContext(io) {
        channelDao.setFavorite(channelId, favorite, time.nowUtcMs())
    }

    suspend fun setHidden(channelId: Long, hidden: Boolean) {
        withContext(io) { channelDao.setHidden(channelId, hidden, time.nowUtcMs()) }
        // A shown channel gets its schedule back right away.
        if (!hidden) refresh(channelId)
    }

    /** Moves a channel built by hand into [groupId], one of the groups someone made, which numbers it from then on. */
    suspend fun setGroup(channelId: Long, groupId: Long) = withContext(io) {
        database.withTransaction {
            val channel = requireCustomChannel(channelId)
            if (channel.groupId == groupId) return@withTransaction
            requireCustomGroup(groupId)
            channelDao.setGroup(channelId, groupId, time.nowUtcMs())
            renumber()
        }
    }

    /** Deletes a channel built by hand. */
    suspend fun delete(channelId: Long) = withContext(io) {
        val channel = requireCustomChannel(channelId)
        database.withTransaction {
            channelDao.delete(channel)
            renumber()
        }
        logos.delete(channel.logoUri)
        AppLog.i(TAG, "Deleted channel ${channel.number} ${channel.name}")
    }

    /** Stores an uploaded logo. Throws [IOException] when [bytes] is not an image. */
    suspend fun setLogo(channelId: Long, bytes: ByteArray) = withContext(io) {
        if (bytes.size > ChannelLogos.MAX_UPLOAD_BYTES) throw IOException("The image is larger than ${ChannelLogos.MAX_UPLOAD_BYTES / (1024 * 1024)} MB")
        val channel = requireCustomChannel(channelId)
        val uri = logos.store(channelId, bytes)
        channelDao.setLogo(channelId, uri, time.nowUtcMs())
        logos.delete(channel.logoUri)
    }

    suspend fun clearLogo(channelId: Long) = withContext(io) {
        val channel = requireCustomChannel(channelId)
        channelDao.setLogo(channelId, null, time.nowUtcMs())
        logos.delete(channel.logoUri)
    }

    /** The channel's stored logo file, or null. */
    suspend fun logoFile(channelId: Long) = withContext(io) { logos.file(channelDao.byId(channelId)?.logoUri) }

    /** Makes every logo stored before the current standard again (see [ChannelLogos]). Cheap when there is none. */
    suspend fun standardizeLogos() = withContext(io) {
        for (channel in channelDao.all()) {
            val uri = channel.logoUri ?: continue
            if (ChannelLogos.isStandard(uri)) continue
            val file = logos.file(uri) ?: continue
            val standard = try {
                logos.store(channel.id, file.readBytes())
            } catch (e: Exception) {
                // A logo that can't be made again stays as it was; it must never stop the channel sync after it.
                AppLog.w(TAG, "Kept channel ${channel.number}'s logo as it was: ${e.message}")
                continue
            }
            channelDao.setLogo(channel.id, standard, time.nowUtcMs())
            logos.delete(uri)
        }
    }

    /** Makes a group whose channels count up from [start], at the end of the guide. Returns its id. */
    suspend fun createGroup(name: String, start: Int): Long = withContext(io) {
        database.withTransaction {
            val groups = channelDao.groups()
            requireFits(GroupRules.nameProblem(name, groups.map { it.toModel() }) ?: GroupRules.startProblem(start, groups.map { it.toModel() }))
            val order = (groups.maxOfOrNull { it.sortOrder } ?: -1) + 1
            channelDao.insertGroup(ChannelGroupEntity(name = name.trim(), sortOrder = order, numberStart = start))
        }
    }

    /** Renames a group someone made. */
    suspend fun renameGroup(groupId: Long, name: String) = withContext(io) {
        database.withTransaction {
            val group = requireCustomGroup(groupId)
            requireFits(GroupRules.nameProblem(name, channelDao.groups().map { it.toModel() }, groupId))
            channelDao.upsertGroup(group.copy(name = name.trim()))
        }
    }

    /** Numbers the group's channels from [start]; the library's groups too. */
    suspend fun setGroupStart(groupId: Long, start: Int) = withContext(io) {
        database.withTransaction {
            val group = requireNotNull(channelDao.groupById(groupId)) { "Group not found." }
            if (group.numberStart == start) return@withTransaction
            requireFits(GroupRules.startProblem(start, channelDao.groups().map { it.toModel() }, groupId))
            channelDao.upsertGroup(group.copy(numberStart = start))
            renumber()
        }
    }

    /** Deletes a group someone made once it holds no channels. */
    suspend fun deleteGroup(groupId: Long) = withContext(io) {
        database.withTransaction {
            val group = requireCustomGroup(groupId)
            require(channelDao.channelCount(groupId) == 0) { "Move or delete its channels first." }
            channelDao.deleteGroup(group)
        }
    }

    /** Moves a group one place up ([delta] -1) or down (+1) in the guide. */
    suspend fun moveGroup(groupId: Long, delta: Int) = withContext(io) {
        val groups = channelDao.groups().toMutableList()
        val from = groups.indexOfFirst { it.id == groupId }
        if (from < 0) return@withContext
        val to = (from + delta).coerceIn(0, groups.lastIndex)
        if (from == to) return@withContext
        groups.add(to, groups.removeAt(from))
        channelDao.updateGroups(groups.mapIndexed { i, g -> g.copy(sortOrder = i) })
    }

    /**
     * Brings the library channels in line with the whole library (they are every profile's):
     * adds the channels it newly has content for, updates the rest, removes the ones that lost
     * their content and renumbers every channel. Returns how many channels were added, updated or
     * removed; the caller schedules them.
     */
    suspend fun syncGenerated(): Int = syncLock.withLock {
        withContext(io) {
            val catalog = Catalog(itemDao.catalog(), words.words().types)
            val generator = ChannelGenerator(words.words())
            val keepable = ChannelGenerator(words.words(), scale = ChannelGenerator.KEEP_SCALE).generate(catalog).mapTo(HashSet()) { it.key }
            // A library still on its first fetch (new, turned on again, or restored) may hold what a channel needs.
            val settled = libraryDao.unsynced().none { LibraryRules.itemKinds(it.collectionType).isNotEmpty() }
            sync(generator.generate(catalog), keepable, settled)
        }
    }

    /** Runs [block] while the library channels can't change; a sync of them waits until it ends. */
    suspend fun <T> paused(block: suspend () -> T): T = syncLock.withLock { block() }

    /** [keepable] are the keys made at [ChannelGenerator.KEEP_SCALE] of the thresholds: a library channel among them stays. */
    private suspend fun sync(generated: List<GeneratedChannel>, keepable: Set<String>, settled: Boolean): Int {
        val existing = channelDao.all()
        val keep = existing.filter { it.autoKey in keepable }.mapTo(HashSet()) { it.id }
        val actions = GeneratedChannelSync.plan(existing.map { SyncedChannel(it.id, it.autoKey) }, generated, keep, settled)
        val byId = existing.associateBy { it.id }
        val removed = actions.remove.mapNotNull(byId::get)

        val changed = database.withTransaction {
            val groups = libraryGroups((actions.add + actions.refresh.values).mapTo(HashSet()) { it.segment })
            removed.forEach { channelDao.delete(it) }
            val updated = actions.refresh.count { (id, channel) -> refresh(byId.getValue(id), channel, groups.getValue(channel.segment)) }
            var number = channelDao.maxNumber() + 1
            for (channel in actions.add) {
                val id = channelDao.insert(newChannel(number++, channel.name, groups.getValue(channel.segment), channel.key))
                channelDao.insertRules(LineupRows.encode(id, null, channel.lineup))
            }
            renumber()
            actions.add.size + updated + removed.size
        }
        if (changed > 0) AppLog.i(TAG, "${actions.add.size} channels added, ${changed - actions.add.size - removed.size} updated, ${removed.size} removed")
        return changed
    }

    /** Rewrites a library channel where it differs from [channel]. Returns true when anything changed. */
    private suspend fun refresh(current: ChannelEntity, channel: GeneratedChannel, groupId: Long): Boolean {
        val lineupChanged = LineupRows.decode(channelDao.rules(current.id).filter { it.seasonId == null }).lineup() != channel.lineup
        if (lineupChanged) replaceLineup(current.id, channel.lineup)
        val next = current.copy(name = channel.name, groupId = groupId).withDefaultPlayback()
        if (!lineupChanged && next == current) return false
        channelDao.update(next.copy(updatedAtUtcMs = time.nowUtcMs()))
        return true
    }

    /** Replaces the channel's own rules, keeping its seasons' and the groups its dayparts use. */
    private suspend fun replaceLineup(channelId: Long, lineup: Lineup) {
        val keep = channelDao.dayparts(channelId).mapNotNull { it.ruleLogicGroup }
        val first = (keep.maxOrNull() ?: -1) + 1
        channelDao.replaceBaseRules(channelId, LineupRows.encode(channelId, null, lineup, firstGroup = first), keep)
    }

    /** The library group of each segment in [needed], made at the end of the guide when missing. */
    private suspend fun libraryGroups(needed: Set<ChannelSegment>): Map<ChannelSegment, Long> {
        val groups = channelDao.groups()
        val byKey = groups.mapNotNull { group -> group.libraryKey?.let { it to group.id } }.toMap(HashMap())
        var order = (groups.maxOfOrNull { it.sortOrder } ?: -1) + 1
        return ChannelSegment.entries.filter { it in needed }.associateWith { segment ->
            byKey.getOrPut(segment.key) {
                channelDao.insertGroup(
                    ChannelGroupEntity(name = segment.groupName, sortOrder = order++, numberStart = segment.defaultStart, libraryKey = segment.key),
                )
            }
        }
    }

    /** Numbers every channel A to Z from its group's start, around the numbers lower groups gave out. */
    private suspend fun renumber() {
        val starts = channelDao.groups().associateTo(LinkedHashMap()) { it.id to it.numberStart }
        val channels = channelDao.all().map { NumberedChannel(it.id, it.groupId, it.name, it.number) }
        val now = time.nowUtcMs()
        for ((id, number) in ChannelNumbering.numbers(channels, starts)) channelDao.setNumber(id, number, now)
    }

    /** Throws a [GroupRules] problem. */
    private fun requireFits(problem: String?) {
        if (problem != null) throw IllegalArgumentException(problem)
    }

    /** Applies an edit to a channel built by hand. */
    private suspend fun edit(channelId: Long, reschedule: Boolean, change: (ChannelEntity) -> ChannelEntity) {
        withContext(io) {
            val channel = requireCustomChannel(channelId)
            channelDao.update(change(channel).copy(updatedAtUtcMs = time.nowUtcMs()))
        }
        if (reschedule) refresh(channelId)
    }

    /** Only channels built by hand change beyond hiding and favoriting. */
    private suspend fun requireCustomChannel(channelId: Long): ChannelEntity =
        requireNotNull(channelDao.byId(channelId)) { "Channel not found." }.also { channel ->
            if (channel.autoKey != null) throw LibraryChannelLockedException()
        }

    /** Channels built by hand only go into groups someone made. */
    private suspend fun requireCustomGroup(groupId: Long): ChannelGroupEntity {
        val group = requireNotNull(channelDao.groupById(groupId)) { "Pick a group." }
        require(group.libraryKey == null) { "${group.name} only holds library channels." }
        return group
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

    /** A channel made from the library ([autoKey] set) or built by hand, playing the way every channel starts. */
    private fun newChannel(number: Int, name: String, groupId: Long, autoKey: String?): ChannelEntity {
        val now = time.nowUtcMs()
        return ChannelEntity(
            number = number,
            name = name.trim(),
            logoUri = null,
            kind = ChannelKind.GENERATED,
            seed = Random.nextLong(),
            seriesOrder = DefaultSeries.order,
            seriesBlock = DefaultSeries.blockSize,
            movieOrder = DefaultMovies.order,
            movieBlock = DefaultMovies.blockSize,
            moviesOncePerDay = true,
            isHidden = false,
            isFavorite = false,
            groupId = groupId,
            autoKey = autoKey,
            createdAtUtcMs = now,
            updatedAtUtcMs = now,
        )
    }

    private fun seasonLabel(season: ChannelSeasonEntity): String {
        fun date(month: Int, day: Int) = Month.of(month).getDisplayName(TextStyle.SHORT, Locale.getDefault()) + " " + day
        return "${date(season.startMonth, season.startDay)} – ${date(season.endMonth, season.endDay)}"
    }

    private fun ChannelSeasonEntity.range(): MonthDayRange? =
        runCatching { MonthDayRange(startMonth, startDay, endMonth, endDay) }.getOrNull()

    private companion object {
        const val TAG = "Channels"
    }
}

/** How every channel starts playing, and how library channels always play: movies once a day too. */
private val DefaultSeries = Playback(PlaybackOrder.EPISODE_ORDER)
private val DefaultMovies = Playback(PlaybackOrder.SHUFFLE)

private fun ChannelEntity.withDefaultPlayback() = copy(
    seriesOrder = DefaultSeries.order,
    seriesBlock = DefaultSeries.blockSize,
    movieOrder = DefaultMovies.order,
    movieBlock = DefaultMovies.blockSize,
    moviesOncePerDay = true,
)

internal fun ChannelEntity.toModel() = Channel(
    id = id,
    number = number,
    name = name,
    logoUri = logoUri,
    kind = kind,
    series = Playback(seriesOrder, seriesBlock),
    movies = Playback(movieOrder, movieBlock),
    moviesOncePerDay = moviesOncePerDay,
    isHidden = isHidden,
    isFavorite = isFavorite,
    groupId = groupId,
    fromLibrary = autoKey != null,
)

private fun ChannelGroupEntity.toModel() = ChannelGroup(id, name, sortOrder, numberStart, fromLibrary = libraryKey != null)
