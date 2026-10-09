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
import tv.anstelle.core.database.dao.MediaItemDao
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

/** Thrown when an operation other than hiding or favoriting targets a library channel. */
class LibraryChannelLockedException : IllegalStateException("Library channels can only be hidden or favorited.")

/** Channels, their lineups and groups, and the channels made from the library. */
@Singleton
class ChannelRepository @Inject constructor(
    private val database: AnstelleDatabase,
    private val channelDao: ChannelDao,
    private val scheduleDao: ScheduleDao,
    private val itemDao: MediaItemDao,
    private val settings: SettingsRepository,
    private val words: ChannelWordsSource,
    private val logos: ChannelLogos,
    private val updater: ScheduleUpdater,
    private val time: TimeSource,
    @ApplicationScope private val scope: CoroutineScope,
    @Dispatcher(AnstelleDispatchers.IO) private val io: CoroutineDispatcher,
) {
    /** One library sync at a time: the worker and "Bring back" both run one. */
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
                    groupName = channel.groupId?.let(groups::get),
                    seasonLabel = season?.let(::seasonLabel),
                    inSeason = season == null || season.range()?.contains(today.monthValue, today.dayOfMonth) != false,
                )
            }
        }

    fun observeChannel(channelId: Long): Flow<Channel?> = channelDao.observe(channelId).map { it?.toModel() }

    fun observeGroups(): Flow<List<ChannelGroup>> =
        channelDao.observeGroups().map { list -> list.map { ChannelGroup(it.id, it.name, it.sortOrder, it.numberStart) } }

    /** The segments that number channels: the library's, then the ones someone made, in guide order. */
    fun observeSegments(): Flow<List<Segment>> =
        combine(observeNumberStarts(), channelDao.observeGroups()) { starts, groups ->
            ChannelSegment.entries.map { Segment(it.key, it.groupName, starts.getValue(it)) } +
                groups.mapNotNull { group -> group.numberStart?.let { Segment(Segment.keyOf(group.id), group.name, it) } }
        }

    /** The group and segment the last channel someone built went into, which the next one starts with; null for none yet. */
    suspend fun lastPlacement(): Pair<Long?, String?> =
        settings.get(Settings.NewChannelGroup).takeIf { it != 0L } to settings.get(Settings.NewChannelSegment).ifEmpty { null }

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
            requireCustomChannel(channelId)
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
     * Creates a channel someone built, under [groupId]'s tab and numbered by [segment] (a
     * [Segment.key]); the next channel starts with the same two. Returns its id.
     */
    suspend fun create(name: String, lineup: Lineup, order: PlaybackOrder, blockSize: Int, groupId: Long, segment: String): Long {
        lineup.problem()?.let { throw IllegalArgumentException(it) }
        val id = withContext(io) {
            database.withTransaction {
                requireNotNull(channelDao.groupById(groupId)) { "Pick a group." }
                require(segmentExists(segment)) { "Pick a segment." }
                val id = channelDao.insert(
                    newChannel(channelDao.maxNumber() + 1, name, order, groupId, autoKey = null, blockSize = blockSize, segment = segment),
                )
                channelDao.insertRules(LineupRows.encode(id, null, lineup))
                renumber()
                id
            }
        }
        settings.set(Settings.NewChannelGroup, groupId)
        settings.set(Settings.NewChannelSegment, segment)
        refresh(id)
        return id
    }

    suspend fun rename(channelId: Long, name: String) {
        edit(channelId, reschedule = false) { it.copy(name = name.trim().ifEmpty { it.name }) }
        // Numbers follow names.
        withContext(io) { database.withTransaction { renumber() } }
    }

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
        database.withTransaction {
            requireCustomChannel(channelId)
            channelDao.setGroup(channelId, groupId, time.nowUtcMs())
            channelDao.stopManaging(channelId)
        }
    }

    /** Moves the channel into [segment] (a [Segment.key]), which numbers it from then on. */
    suspend fun setSegment(channelId: Long, segment: String) = withContext(io) {
        database.withTransaction {
            val channel = requireCustomChannel(channelId)
            if (channel.segmentKey() == segment) return@withTransaction
            require(segmentExists(segment)) { "Pick a segment." }
            channelDao.setSegment(channelId, segment, time.nowUtcMs())
            channelDao.stopManaging(channelId)
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

    suspend fun createGroup(name: String): Long = withContext(io) {
        val order = (channelDao.groups().maxOfOrNull { it.sortOrder } ?: -1) + 1
        channelDao.insertGroup(ChannelGroupEntity(name = name.trim(), sortOrder = order))
    }

    suspend fun renameGroup(groupId: Long, name: String) = withContext(io) {
        val group = channelDao.groupById(groupId) ?: return@withContext
        if (name.isNotBlank()) channelDao.upsertGroup(group.copy(name = name.trim()))
    }

    /**
     * Creates a segment: a group whose channels count up from [start] (by default the next
     * thousand after every start and channel number, 5000 with the defaults). Returns its group's
     * id; [Segment.keyOf] names the segment.
     */
    suspend fun createSegment(name: String, start: Int? = null): Long {
        val first = start ?: nextThousand(segmentStarts() + withContext(io) { channelDao.all().map { it.number } })
        require(first in MIN_NUMBER..MAX_NUMBER) { "Starts are $MIN_NUMBER–$MAX_NUMBER" }
        return withContext(io) {
            val order = (channelDao.groups().maxOfOrNull { it.sortOrder } ?: -1) + 1
            channelDao.insertGroup(ChannelGroupEntity(name = name.trim(), sortOrder = order, numberStart = first))
        }
    }

    /** Numbers the segment's channels from [start]. */
    suspend fun setSegmentStart(groupId: Long, start: Int) = withContext(io) {
        require(start in MIN_NUMBER..MAX_NUMBER) { "Starts are $MIN_NUMBER–$MAX_NUMBER" }
        val group = channelDao.groupById(groupId) ?: return@withContext
        database.withTransaction {
            channelDao.upsertGroup(group.copy(numberStart = start))
            renumber()
        }
    }

    /** The starts of the library's segments and of the ones someone made. */
    private suspend fun segmentStarts(): List<Int> =
        ChannelSegment.entries.map { settings.get(Settings.channelNumberStart(it)) } +
            withContext(io) { channelDao.groups().mapNotNull { it.numberStart } }

    /** The thousand after the highest of [numbers]: 5000 after 4000 or 4036. */
    private fun nextThousand(numbers: List<Int>): Int = (((numbers.maxOrNull() ?: 0) / 1000 + 1) * 1000).coerceAtMost(MAX_NUMBER)

    /** Whether [key] names one of the library's segments or one someone made. */
    private suspend fun segmentExists(key: String): Boolean =
        ChannelSegment.entries.any { it.key == key } || channelDao.groups().any { it.numberStart != null && Segment.keyOf(it.id) == key }

    /**
     * Deletes the group; its channels stay, without a group. A segment's channels leave it too:
     * library channels go back to their own segment, the rest are numbered after every segment.
     */
    suspend fun deleteGroup(groupId: Long) = withContext(io) {
        val group = channelDao.groupById(groupId) ?: return@withContext
        database.withTransaction {
            channelDao.deleteGroup(group)
            if (group.numberStart != null) {
                channelDao.clearSegment(Segment.keyOf(groupId))
                renumber()
            }
        }
    }

    /** Moves a group one place up ([delta] -1) or down (+1) in the guide. */
    suspend fun moveGroup(groupId: Long, delta: Int) = withContext(io) {
        val group = channelDao.groupById(groupId) ?: return@withContext
        val groups = channelDao.groups().toMutableList()
        val from = groups.indexOfFirst { it.id == groupId }
        val to = (from + delta).coerceIn(0, groups.lastIndex)
        if (from == to) return@withContext
        groups.add(to, groups.removeAt(from))
        channelDao.updateGroups(groups.mapIndexed { i, g -> g.copy(sortOrder = i) })
    }

    /** Where each segment's channel numbers start. */
    fun observeNumberStarts(): Flow<Map<ChannelSegment, Int>> =
        combine(ChannelSegment.entries.map { segment -> settings.observe(Settings.channelNumberStart(segment)).map { segment to it } }) { it.toMap() }

    /** Numbers the segment's channels from [start]. */
    suspend fun setNumberStart(segment: ChannelSegment, start: Int) {
        require(start in MIN_NUMBER..MAX_NUMBER) { "Starts are $MIN_NUMBER–$MAX_NUMBER" }
        settings.set(Settings.channelNumberStart(segment), start)
        withContext(io) { database.withTransaction { renumber() } }
    }

    /** How many library channels were deleted (some may no longer fit the library). */
    fun observeRemovedCount(): Flow<Int> = channelDao.observeDismissedCount()

    /** Deleted library channels the library still has content for, in guide order. */
    suspend fun removedChannels(): List<GeneratedChannel> = withContext(io) {
        val dismissed = channelDao.dismissedKeys().toSet()
        if (dismissed.isEmpty()) return@withContext emptyList()
        ChannelGenerator(words.words()).generate(Catalog(itemDao.catalog(), words.words().types)).filter { it.key in dismissed }
    }

    /** Restores deleted library channels and schedules them. */
    suspend fun bringBack(keys: Collection<String>) {
        withContext(io) { keys.forEach { channelDao.undismiss(it) } }
        if (syncGenerated() > 0) {
            scope.launch(io) {
                try {
                    updater.updateAll()
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    AppLog.e(TAG, "Scheduling restored channels failed", e)
                }
            }
        }
    }

    /**
     * Brings the library channels in line with the whole library (they are every profile's):
     * adds the channels it newly has content for, updates the managed ones, removes the managed
     * ones that lost their content and renumbers them in guide order. Edited and deleted channels
     * are left alone. Returns how many channels were added, updated or removed; the caller
     * schedules them.
     */
    suspend fun syncGenerated(): Int = syncLock.withLock {
        withContext(io) {
            val catalog = Catalog(itemDao.catalog(), words.words().types)
            val generator = ChannelGenerator(words.words())
            val keepable = ChannelGenerator(words.words(), scale = ChannelGenerator.KEEP_SCALE).generate(catalog).mapTo(HashSet()) { it.key }
            sync(generator.generate(catalog), keepable)
        }
    }

    /** [keepable] are the keys made at [ChannelGenerator.KEEP_SCALE] of the thresholds: a managed channel among them stays. */
    private suspend fun sync(generated: List<GeneratedChannel>, keepable: Set<String>): Int {
        val existing = channelDao.all()
        val keep = existing.filter { it.isManaged && it.autoKey in keepable }.mapTo(HashSet()) { it.id }
        val actions = GeneratedChannelSync.plan(existing.map { it.synced() }, generated, channelDao.dismissedKeys().toSet(), keep)
        val byId = existing.associateBy { it.id }
        val removed = actions.remove.mapNotNull(byId::get)

        val changed = database.withTransaction {
            val groups = segmentGroups((actions.add + actions.refresh.values).mapTo(HashSet()) { it.segment })
            removed.forEach { channelDao.delete(it) }
            val updated = actions.refresh.count { (id, channel) -> refresh(byId.getValue(id), channel, groups[channel.segment]) }
            var number = channelDao.maxNumber() + 1
            for (channel in actions.add) {
                val id = channelDao.insert(newChannel(number++, channel.name, channel.order, groups[channel.segment], channel.key))
                channelDao.insertRules(LineupRows.encode(id, null, channel.lineup))
            }
            renumber()
            actions.add.size + updated + removed.size
        }
        removed.forEach { logos.delete(it.logoUri) }
        if (changed > 0) AppLog.i(TAG, "${actions.add.size} channels added, ${changed - actions.add.size - removed.size} updated, ${removed.size} removed")
        return changed
    }

    /** Rewrites a managed channel where it differs from [channel]. Returns true when anything changed. */
    private suspend fun refresh(current: ChannelEntity, channel: GeneratedChannel, groupId: Long?): Boolean {
        val lineupChanged = LineupRows.decode(channelDao.rules(current.id).filter { it.seasonId == null }).lineup() != channel.lineup
        if (lineupChanged) {
            val keep = channelDao.dayparts(current.id).mapNotNull { it.ruleLogicGroup }
            val first = (keep.maxOrNull() ?: -1) + 1
            channelDao.replaceBaseRules(current.id, LineupRows.encode(current.id, null, channel.lineup, firstGroup = first), keep)
        }
        val next = current.copy(name = channel.name, playbackOrder = channel.order, blockSize = 1, groupId = groupId)
        if (!lineupChanged && next == current) return false
        channelDao.update(next.copy(updatedAtUtcMs = time.nowUtcMs()))
        return true
    }

    /** The guide group of each segment in [needed], created in guide order when missing. */
    private suspend fun segmentGroups(needed: Set<ChannelSegment>): Map<ChannelSegment, Long> {
        val groups = channelDao.groups().associateTo(HashMap()) { it.name.lowercase(Locale.ROOT) to it.id }
        return ChannelSegment.entries.filter { it in needed }.associateWith { segment ->
            groups.getOrPut(segment.groupName.lowercase(Locale.ROOT)) {
                channelDao.insertGroup(ChannelGroupEntity(name = segment.groupName, sortOrder = groups.size))
            }
        }
    }

    /**
     * Numbers every channel A to Z from its segment's start, around the numbers lower segments
     * gave out; a channel in no segment counts from the next thousand after every start.
     */
    private suspend fun renumber() {
        val starts = LinkedHashMap<String, Int>()
        for (segment in ChannelSegment.entries) starts[segment.key] = settings.get(Settings.channelNumberStart(segment))
        for (group in channelDao.groups()) group.numberStart?.let { starts[Segment.keyOf(group.id)] = it }
        val channels = channelDao.all().map { NumberedChannel(it.id, it.segmentKey(), it.name, it.number) }
        val now = time.nowUtcMs()
        for ((id, number) in ChannelNumbering.numbers(channels, starts, outside = nextThousand(starts.values.toList()))) {
            channelDao.setNumber(id, number, now)
        }
    }

    /** Applies an edit to a channel built by hand. */
    private suspend fun edit(channelId: Long, reschedule: Boolean, change: (ChannelEntity) -> ChannelEntity) {
        withContext(io) {
            val channel = requireCustomChannel(channelId)
            channelDao.update(change(channel).copy(updatedAtUtcMs = time.nowUtcMs()))
        }
        if (reschedule) refresh(channelId)
    }

    /** Marks a lineup edit on a channel built by hand. */
    private suspend fun touch(channelId: Long) {
        val channel = requireCustomChannel(channelId)
        channelDao.update(channel.copy(updatedAtUtcMs = time.nowUtcMs()))
    }

    /** A library origin is permanent, including for legacy channels edited before this lock. */
    private suspend fun requireCustomChannel(channelId: Long): ChannelEntity =
        requireNotNull(channelDao.byId(channelId)) { "Channel not found." }.also { channel ->
            if (channel.autoKey != null) throw LibraryChannelLockedException()
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


    /** A channel made from the library ([autoKey] set, managed, in its own segment) or built by hand. */
    private fun newChannel(
        number: Int,
        name: String,
        order: PlaybackOrder,
        groupId: Long?,
        autoKey: String?,
        blockSize: Int = 1,
        segment: String? = null,
    ): ChannelEntity {
        val now = time.nowUtcMs()
        return ChannelEntity(
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
            autoKey = autoKey,
            isManaged = autoKey != null,
            createdAtUtcMs = now,
            updatedAtUtcMs = now,
            segment = segment,
        )
    }

    private fun seasonLabel(season: ChannelSeasonEntity): String {
        fun date(month: Int, day: Int) = Month.of(month).getDisplayName(TextStyle.SHORT, Locale.getDefault()) + " " + day
        return "${date(season.startMonth, season.startDay)} – ${date(season.endMonth, season.endDay)}"
    }

    private fun ChannelSeasonEntity.range(): MonthDayRange? =
        runCatching { MonthDayRange(startMonth, startDay, endMonth, endDay) }.getOrNull()

    companion object {
        private const val TAG = "Channels"
        const val MIN_NUMBER = 0
        const val MAX_NUMBER = 9_999
    }
}

internal fun ChannelEntity.toModel() = Channel(
    id = id,
    number = number,
    name = name,
    logoUri = logoUri,
    kind = kind,
    playbackOrder = playbackOrder,
    blockSize = blockSize,
    isHidden = isHidden,
    isFavorite = isFavorite,
    groupId = groupId,
    isManaged = isManaged && autoKey != null,
    fromLibrary = autoKey != null,
    segment = segmentKey(),
)

/** The segment that numbers it: the one picked for it, else a library channel's own. */
private fun ChannelEntity.segmentKey(): String? = segment ?: autoKey?.let(ChannelGenerator::segmentOf)?.key

private fun ChannelEntity.synced() = SyncedChannel(id, autoKey, isManaged)
