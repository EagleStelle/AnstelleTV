package tv.anstelle.core.data.ondemand

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import tv.anstelle.core.common.AnstelleDispatchers
import tv.anstelle.core.common.AppLog
import tv.anstelle.core.common.ApplicationScope
import tv.anstelle.core.common.Dispatcher
import tv.anstelle.core.data.Artwork
import tv.anstelle.core.data.DatabaseChanges
import tv.anstelle.core.data.Settings
import tv.anstelle.core.data.SettingsRepository
import tv.anstelle.core.data.channels.ChannelRepository
import tv.anstelle.core.data.channels.LineupMatcher
import tv.anstelle.core.data.guide.ProgramBadges
import tv.anstelle.core.data.jellyfinImageUrl
import tv.anstelle.core.database.dao.MediaItemDao
import tv.anstelle.core.database.dao.MediaServerDao
import tv.anstelle.core.database.dao.TitleRow
import tv.anstelle.core.database.entity.MediaItemEntity
import tv.anstelle.core.jellyfin.ResumePoint
import tv.anstelle.core.jellyfin.sync.ItemMapper
import tv.anstelle.core.model.Channel
import tv.anstelle.core.model.MediaType
import tv.anstelle.core.model.ProfileAccess
import javax.inject.Inject
import javax.inject.Singleton
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicLong

/**
 * On Demand's catalog: the movies and series the profile may watch, read from SQLite, the rows
 * of each kind's tab (its fixed rows and each library channel's titles), and what the profile's
 * Jellyfin user watched of them, which only the server knows (Continue watching, Next up, the
 * posters' marks). Each [CatalogKind] is handled the same way, so a new kind needs no new code
 * here beyond its entry.
 */
@Singleton
class CatalogRepository @Inject constructor(
    private val itemDao: MediaItemDao,
    private val serverDao: MediaServerDao,
    private val channels: ChannelRepository,
    private val onDemand: OnDemandRepository,
    private val settings: SettingsRepository,
    changes: DatabaseChanges,
    @ApplicationScope scope: CoroutineScope,
    @Dispatcher(AnstelleDispatchers.IO) private val io: CoroutineDispatcher,
) {
    val watchChanges = onDemand.watchChanges

    private val revision = AtomicLong()
    private val titleLock = Mutex()
    private val channelLock = Mutex()
    private val titleCache = HashMap<CatalogKind, Pair<ProfileAccess, List<Title>>>()
    private var titleRevision = -1L
    private var channelRevision = -1L
    private var channelDay: LocalDate? = null
    private var channelCache = emptyMap<CatalogKind, List<Pair<Channel, Set<Long>>>>()

    init {
        scope.launch {
            changes.tables.collect { tables ->
                if (tables.any { it in LIBRARY_TABLES }) revision.incrementAndGet()
            }
        }
    }

    /** Every title of every kind the profile may watch, by kind, in no particular order. */
    suspend fun titles(access: ProfileAccess): Map<CatalogKind, List<Title>> =
        CatalogKind.entries.associateWith { titles(it, access) }

    /** Only the open tab's titles; cached across visits until metadata or access changes. */
    suspend fun titles(kind: CatalogKind, access: ProfileAccess): List<Title> = withContext(io) { titleLock.withLock {
        val seen = revision.get()
        if (titleRevision != seen) {
            titleCache.clear()
            titleRevision = seen
        }
        titleCache[kind]?.takeIf { it.first == access }?.let { return@withLock it.second }
        val bases = serverDao.all().associate { it.id to it.baseUrl }
        val lastAdded = if (kind.episodic) itemDao.seriesLastAdded(kind.airedType)
            .associate { (it.serverId to it.seriesRemoteId) to it.addedUtcMs } else emptyMap()
        val titles = itemDao.titles(listOf(kind.titleType))
            .filter { access.allows(it.id, it.ratingScore) }
            .mapNotNull { row ->
                val added = maxOf(row.dateAddedUtcMs ?: 0L, lastAdded[row.serverId to row.remoteId] ?: 0L)
                row.toTitle(kind, bases[row.serverId], added)
            }
        if (revision.get() == seen) titleCache[kind] = access to titles
        titles
    } }

    /**
     * The library channels airing titles of [kind], by number, each with the ids of every such
     * title it airs, whatever the profile may watch. Hidden and out-of-season channels are left
     * out, as Live TV leaves them out.
     */
    suspend fun channelTitles(kind: CatalogKind): List<Pair<Channel, Set<Long>>> = withContext(io) { channelLock.withLock {
        val seen = revision.get()
        val day = LocalDate.now()
        if (channelRevision == seen && channelDay == day) return@withLock channelCache[kind].orEmpty()
        val list = channels.observeChannels().first()
            .filter { it.channel.fromLibrary && !it.channel.isHidden && it.inSeason }
            .map { it.channel }
            .sortedBy { it.number }
        if (list.isEmpty()) {
            if (revision.get() == seen) {
                channelCache = emptyMap()
                channelRevision = seen
                channelDay = day
            }
            return@withLock emptyList()
        }
        val catalog = channels.catalog()
        val seriesIds = itemDao.titles(listOf(MediaType.SERIES)).associate { it.remoteId to it.id }
        val found = CatalogKind.entries.associateWith { ArrayList<Pair<Channel, Set<Long>>>() }
        for (channel in list) {
            // One matcher and one library read serve both kinds, rather than matching every channel twice.
            val matches = LineupMatcher(channels.lineup(channel.id)).match(catalog).matches
            for (entry in CatalogKind.entries) {
                val ids = matches.mapNotNullTo(LinkedHashSet()) { match ->
                    val row = match.item.row
                    when {
                        row.type != entry.airedType -> null
                        entry.episodic -> row.seriesRemoteId?.let(seriesIds::get)
                        else -> row.id
                    }
                }
                if (ids.isNotEmpty()) found.getValue(entry).add(channel to CompactTitleIds(ids))
            }
        }
        if (revision.get() == seen) {
            channelCache = found
            channelRevision = seen
            channelDay = day
        }
        found[kind].orEmpty()
    } }

    /**
     * The rows of [kind]'s tab in the stored order: its fixed rows and every library channel
     * airing its titles. A row with nothing in it for the profile is left out by the tab.
     */
    suspend fun shelves(kind: CatalogKind, channelTitles: List<Pair<Channel, Set<Long>>>? = null): List<Shelf> {
        val available = kind.fixedShelves.map { Shelf(it) } +
            (channelTitles ?: channelTitles(kind)).map { (channel, _) -> Shelf(ShelfType.CHANNEL, channel) }
        return ShelfOrder.arrange(available, ShelfOrder.decode(settings.get(Settings.catalogShelves(kind))))
    }

    /** Stores [kind]'s row order. */
    suspend fun setShelfOrder(kind: CatalogKind, keys: List<String>) {
        settings.set(Settings.catalogShelves(kind), ShelfOrder.encode(keys))
    }

    /**
     * What [profileId]'s Jellyfin users watched of every movie and series, by local id. Not
     * [CatalogWatch.known] when no server could tell.
     */
    suspend fun watch(profileId: Long, kind: CatalogKind? = null): CatalogWatch = withContext(io) {
        var known = false
        val byId = HashMap<Long, TitleProgress>()
        for (server in serverDao.all()) {
            try {
                val playstate = onDemand.playstate(server.id, profileId) ?: continue
                val watch = playstate.titleWatch(kind?.episodic)
                known = true
                val local = itemDao.storedFor(server.id, watch.keys.toList()).associate { it.remoteId to it.id }
                for ((remoteId, progress) in watch) {
                    val id = local[remoteId] ?: continue
                    byId[id] = TitleProgress(progress.positionMs, progress.played, progress.unplayedCount)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.w(TAG, "No watch marks from ${server.name}: ${e.message}")
            }
        }
        CatalogWatch(byId, known)
    }

    /** The movies, or episodes for [kind] series, the profile left part-way, last played first. */
    suspend fun continueWatching(kind: CatalogKind, profileId: Long, access: ProfileAccess): List<WatchCard> = withContext(io) {
        val points = fromServers("Continue watching", profileId) { playstate ->
            playstate.resumeItems(episodes = kind.episodic, limit = ROW_LIMIT)
        }.sortedByDescending { (_, point) -> point.lastPlayedUtcMs }
        cards(kind, points, access)
    }

    /** The next episode of each series the profile is watching. */
    suspend fun nextUp(profileId: Long, access: ProfileAccess, continuing: List<WatchCard>? = null): List<WatchCard> = withContext(io) {
        val resume = continuing ?: continueWatching(CatalogKind.SERIES, profileId, access)
        val continuingTitles = resume.mapTo(HashSet()) { it.titleId }
        val ids = fromServers("Next up", profileId) { it.nextUp(ROW_LIMIT) }
        cards(CatalogKind.SERIES, ids.map { (serverId, id) -> serverId to ResumePoint(id, 0, 0) }, access)
            .filterNot { it.titleId in continuingTitles }
    }

    /** A movie's or series' page; null when it is gone or the profile may not watch it. */
    suspend fun titlePage(titleId: Long, access: ProfileAccess): TitlePage? = withContext(io) {
        val item = itemDao.byId(titleId) ?: return@withContext null
        val kind = CatalogKind.of(item.type) ?: return@withContext null
        if (!access.allows(item.id, item.ratingScore)) return@withContext null
        val base = serverDao.byId(item.serverId)?.baseUrl
        val episodes = if (kind.episodic) {
            itemDao.episodes(item.serverId, item.remoteId, kind.airedType, access.limit)
                .filter { !access.isHidden(it.id) }
                .map { with(onDemand) { it.toEntry(base) } }
        } else {
            emptyList()
        }
        TitlePage(
            id = item.id,
            kind = kind,
            title = item.title,
            year = item.year,
            rating = item.officialRating?.takeIf { it.isNotBlank() },
            genres = ItemMapper.pipeSplit(item.genres),
            durationMs = if (kind.episodic) 0 else item.durationMs,
            overview = item.overview?.takeIf { it.isNotBlank() },
            badges = if (kind.episodic) emptyList() else ProgramBadges.of(item.hdrType, item.width, item.height, item.audioFormat, item.audioChannels),
            posterUrl = base?.let { jellyfinImageUrl(it, item.remoteId, "Primary", item.primaryImageTag, maxHeight = HERO_POSTER_HEIGHT) },
            artwork = Artwork.of(base, item, null),
            episodes = episodes,
        )
    }

    /** The series of episode [itemId], or the item itself for a movie: the page it belongs to. */
    suspend fun titleOf(itemId: Long): Long? = withContext(io) {
        val item = itemDao.byId(itemId) ?: return@withContext null
        val series = item.seriesRemoteId ?: return@withContext item.id.takeIf { CatalogKind.of(item.type) != null }
        itemDao.byRemoteId(item.serverId, series)?.id
    }

    /** The first playable episode of a series, using the same regular-season preference as its page. */
    suspend fun firstEpisodeId(titleId: Long, access: ProfileAccess): Long? = withContext(io) {
        val item = itemDao.byId(titleId) ?: return@withContext null
        val episodes = itemDao.episodes(item.serverId, item.remoteId, MediaType.EPISODE, access.limit)
            .filter { !access.isHidden(it.id) }
        (episodes.firstOrNull { it.season != 0 } ?: episodes.firstOrNull())?.id
    }

    /** [ask] of every server the profile keeps its watch history on, each answer with its server. */
    private suspend fun <T> fromServers(what: String, profileId: Long, ask: suspend (tv.anstelle.core.jellyfin.Playstate) -> List<T>): List<Pair<Long, T>> {
        val found = ArrayList<Pair<Long, T>>()
        for (server in serverDao.all()) {
            try {
                val playstate = onDemand.playstate(server.id, profileId) ?: continue
                ask(playstate).mapTo(found) { server.id to it }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.w(TAG, "No $what from ${server.name}: ${e.message}")
            }
        }
        return found
    }

    /** Cards for [points] in their order, dropping what isn't synced or the profile may not watch. */
    private suspend fun cards(kind: CatalogKind, points: List<Pair<Long, ResumePoint>>, access: ProfileAccess): List<WatchCard> {
        val bases = serverDao.all().associate { it.id to it.baseUrl }
        val local = points.groupBy({ it.first }, { it.second.itemId }).flatMap { (serverId, ids) ->
            itemDao.storedFor(serverId, ids).map { (serverId to it.remoteId) to it.id }
        }.toMap()
        val items = itemDao.byIds(local.values.toList()).associateBy { it.id }
        val series = HashMap<Pair<Long, String>, MediaItemEntity?>()
        return points.mapNotNull { (serverId, point) ->
            val item = local[serverId to point.itemId]?.let(items::get) ?: return@mapNotNull null
            if (item.type != kind.airedType || !access.allows(item.id, item.ratingScore)) return@mapNotNull null
            val base = bases[serverId]
            val show = item.seriesRemoteId?.let { remote -> series.getOrPut(serverId to remote) { itemDao.byRemoteId(serverId, remote) } }
            if (kind.episodic && (show == null || !access.allows(show.id, show.ratingScore))) return@mapNotNull null
            val still = base?.let { jellyfinImageUrl(it, item.remoteId, "Primary", item.primaryImageTag, maxWidth = STILL_WIDTH) }
            WatchCard(
                itemId = item.id,
                titleId = show?.id ?: item.id,
                kind = kind,
                title = show?.title ?: item.showTitle ?: item.title,
                number = if (item.season != null && item.episode != null) "S${item.season}E${item.episode}" else null,
                episodeTitle = item.title.takeIf { kind.episodic && it.isNotBlank() },
                // An episode's own still; a movie's poster, matching the tile that displays it.
                imageUrl = if (kind.episodic) {
                    still ?: Artwork.of(base, item, show).backdropUrl
                } else {
                    base?.let { jellyfinImageUrl(it, item.remoteId, "Primary", item.primaryImageTag, maxWidth = POSTER_WIDTH) }
                        ?: Artwork.of(base, item, null).backdropUrl
                },
                rating = item.officialRating?.takeIf { !kind.episodic && it.isNotBlank() },
                durationMs = item.durationMs,
                positionMs = point.positionMs,
            )
        }
    }

    private fun TitleRow.toTitle(kind: CatalogKind, base: String?, added: Long) = Title(
        id = id,
        serverId = serverId,
        remoteId = remoteId,
        kind = kind,
        title = title,
        sortKey = CatalogSearch.normalize(sortTitle?.takeIf { it.isNotBlank() } ?: title),
        year = year,
        rating = officialRating?.takeIf { it.isNotBlank() },
        genres = ItemMapper.pipeSplit(genres),
        overview = overview?.takeIf { it.isNotBlank() },
        durationMs = if (kind.episodic) 0 else durationMs,
        posterUrl = base?.let { jellyfinImageUrl(it, remoteId, "Primary", primaryImageTag, maxWidth = POSTER_WIDTH) },
        artwork = Artwork.of(base, remoteId, backdropImageTag, logoImageTag, null, null, null),
        addedUtcMs = added,
    )

    private companion object {
        const val TAG = "Catalog"
        val LIBRARY_TABLES = setOf("media_item", "media_server", "channel", "channel_group", "channel_rule", "channel_daypart", "channel_season")

        /** How many cards Continue watching and Next up hold. */
        const val ROW_LIMIT = 24

        /** A grid poster's width asked of the server, in pixels: sharp at about 140 dp on a 1080p TV. */
        const val POSTER_WIDTH = 320

        /** A page hero's poster height, as a channel page asks for. */
        const val HERO_POSTER_HEIGHT = 480

        /** A card's still width, as an episode row asks for. */
        const val STILL_WIDTH = 400
    }
}

/** Cached memberships avoid keeping a hash table and boxed Long for every channel/title pair. */
private class CompactTitleIds(ids: Collection<Long>) : AbstractSet<Long>() {
    private val values = ids.toLongArray().apply { sort() }
    override val size: Int get() = values.size
    override fun contains(element: Long): Boolean = values.binarySearch(element) >= 0
    override fun iterator(): Iterator<Long> = values.iterator()
}
