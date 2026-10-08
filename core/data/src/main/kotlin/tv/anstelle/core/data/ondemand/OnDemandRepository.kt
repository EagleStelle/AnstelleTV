package tv.anstelle.core.data.ondemand

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import tv.anstelle.core.common.AnstelleDispatchers
import tv.anstelle.core.common.AppLog
import tv.anstelle.core.common.Dispatcher
import tv.anstelle.core.data.Artwork
import tv.anstelle.core.data.channels.toModel
import tv.anstelle.core.data.guide.GuideRepository
import tv.anstelle.core.data.guide.ProgramBadges
import tv.anstelle.core.data.jellyfinImageUrl
import tv.anstelle.core.data.programDetail
import tv.anstelle.core.data.programTitle
import tv.anstelle.core.database.dao.ChannelDao
import tv.anstelle.core.database.dao.MediaItemDao
import tv.anstelle.core.database.dao.MediaServerDao
import tv.anstelle.core.database.dao.ScheduleDao
import tv.anstelle.core.database.entity.MediaItemEntity
import tv.anstelle.core.jellyfin.Playstate
import tv.anstelle.core.jellyfin.PlaystateFactory
import tv.anstelle.core.jellyfin.auth.TokenStore
import tv.anstelle.core.model.Channel
import tv.anstelle.core.model.MediaType
import javax.inject.Inject
import javax.inject.Singleton

/** What a channel airs at one moment, as an On demand tile or the banner shows it. */
data class ChannelNow(
    val channel: Channel,
    /** 0 while the channel is off air. */
    val mediaItemId: Long,
    val startUtcMs: Long,
    val endUtcMs: Long,
    /** The show's title for an episode, else the item's. */
    val title: String,
    /** "S1E4 - Pilot", a movie's year, or null. */
    val detail: String?,
    val isEpisode: Boolean,
    val overview: String?,
    val artwork: Artwork,
) {
    val offAir: Boolean get() = mediaItemId == 0L
}

/** One episode in a show's list. */
data class EpisodeEntry(
    val id: Long,
    val remoteId: String,
    val season: Int?,
    /** "S1E4 - Pilot". */
    val label: String,
    /** "24 min". */
    val facts: String?,
    val overview: String?,
    /** The episode's own still, or null. */
    val stillUrl: String?,
    val durationMs: Long,
)

/** A movie or episode, ready to play. */
data class ItemPage(
    val id: Long,
    /** The show's title for an episode. */
    val title: String,
    val detail: String?,
    val facts: String,
    val overview: String?,
    val badges: List<String>,
    /** An episode's still, or a movie's poster. */
    val imageUrl: String?,
    /** The movie's backdrop and logo, or the show's for an episode. */
    val artwork: Artwork,
    val durationMs: Long,
    val isEpisode: Boolean,
)

/**
 * A channel in On demand: the program it airs now (or next, while it is off air) and, for an
 * episode, every episode of its show.
 */
data class ChannelPage(
    val channel: Channel,
    /** The airing the page is about. */
    val startUtcMs: Long,
    val endUtcMs: Long,
    /** True when the channel airs it now; false when the channel is off air and it comes next. */
    val onNow: Boolean,
    val item: ItemPage,
    /** The show's episodes within the rating limit, in order; empty for a movie. */
    val episodes: List<EpisodeEntry>,
    /** The show's Jellyfin id, for its episodes' resume points; null for a movie. */
    val seriesRemoteId: String?,
)

/**
 * On demand from SQLite: what every channel airs, a channel's program and its show. Only
 * resume points ask the server. [maxRating] is the profile's rating limit (null for none);
 * unrated items never pass a limit.
 */
@Singleton
class OnDemandRepository @Inject constructor(
    private val channelDao: ChannelDao,
    private val scheduleDao: ScheduleDao,
    private val itemDao: MediaItemDao,
    private val serverDao: MediaServerDao,
    private val tokens: TokenStore,
    private val playstates: PlaystateFactory,
    @Dispatcher(AnstelleDispatchers.IO) private val io: CoroutineDispatcher,
) {
    /** What each of [channels] airs at [atUtcMs], in one read; a channel with no schedule is left out. */
    suspend fun nowOn(channels: List<Channel>, atUtcMs: Long): Map<Long, ChannelNow> = withContext(io) {
        if (channels.isEmpty()) return@withContext emptyMap()
        val byId = channels.associateBy { it.id }
        val bases = baseUrls()
        val maxSlot = scheduleDao.maxSlotMs()
        // SQLite on older Android allows 999 bound values per statement.
        channels.map { it.id }.chunked(MAX_IDS_PER_QUERY)
            .flatMap { scheduleDao.airingsAt(it, atUtcMs, maxSlot) }
            .mapNotNull { row ->
                val channel = byId[row.channelId] ?: return@mapNotNull null
                val item = row.mediaItemId
                val title = row.title
                val remoteId = row.remoteId
                val now = if (item == null || title == null || remoteId == null) {
                    ChannelNow(channel, 0, row.startUtcMs, row.endUtcMs, GuideRepository.OFF_AIR_TITLE, null, false, null, Artwork.None)
                } else {
                    ChannelNow(
                        channel = channel,
                        mediaItemId = item,
                        startUtcMs = row.startUtcMs,
                        endUtcMs = row.endUtcMs,
                        title = programTitle(title, row.showTitle),
                        detail = programDetail(title, row.showTitle, row.season, row.episode, row.year),
                        isEpisode = row.showTitle != null,
                        overview = row.overview?.takeIf { it.isNotBlank() },
                        artwork = Artwork.of(
                            row.serverId?.let(bases::get),
                            remoteId, row.backdropImageTag, row.logoImageTag,
                            row.seriesRemoteId, row.seriesBackdropImageTag, row.seriesLogoImageTag,
                        ),
                    )
                }
                row.channelId to now
            }
            .toMap()
    }

    /**
     * [channelId]'s page at [atUtcMs]: the program on now, or the next one while the channel
     * is off air. Null when the channel is gone or has nothing scheduled.
     */
    suspend fun channelPage(channelId: Long, atUtcMs: Long, maxRating: Int?): ChannelPage? = withContext(io) {
        val channel = channelDao.byId(channelId)?.toModel() ?: return@withContext null
        val current = scheduleDao.slotAt(channelId, atUtcMs)?.takeIf { atUtcMs < it.endUtcMs }
        val onNow = current?.mediaItemId != null
        val slot = if (onNow) {
            current
        } else {
            scheduleDao.slotsFrom(channelId, current?.endUtcMs ?: atUtcMs, LOOK_AHEAD_SLOTS).firstOrNull { it.mediaItemId != null }
        } ?: return@withContext null
        val entity = itemDao.byId(slot.mediaItemId ?: return@withContext null) ?: return@withContext null
        val base = serverDao.byId(entity.serverId)?.baseUrl
        val series = entity.seriesRemoteId?.let { itemDao.byRemoteId(entity.serverId, it) }
        val episodes = entity.seriesRemoteId?.let { itemDao.episodes(entity.serverId, it, MediaType.EPISODE, maxRating) }.orEmpty()
        ChannelPage(
            channel = channel,
            startUtcMs = slot.startUtcMs,
            endUtcMs = slot.endUtcMs,
            onNow = onNow,
            item = itemPage(entity, series, base),
            episodes = episodes.map { episode ->
                EpisodeEntry(
                    id = episode.id,
                    remoteId = episode.remoteId,
                    season = episode.season,
                    label = programDetail(episode.title, episode.showTitle ?: "", episode.season, episode.episode, episode.year) ?: episode.title,
                    facts = GuideRepository.durationLabel(episode.durationMs),
                    overview = episode.overview?.takeIf { it.isNotBlank() },
                    stillUrl = base?.let { jellyfinImageUrl(it, episode.remoteId, "Primary", episode.primaryImageTag, maxWidth = STILL_WIDTH) },
                    durationMs = episode.durationMs,
                )
            },
            seriesRemoteId = entity.seriesRemoteId,
        )
    }

    /**
     * Where the signed-in user stopped in the page's program and, for a show, in each of its
     * episodes, by local item id. Empty when the server can't be reached.
     */
    suspend fun resumePoints(page: ChannelPage): Map<Long, Long> = withContext(io) {
        try {
            val item = itemDao.byId(page.item.id) ?: return@withContext emptyMap()
            val playstate = playstate(item.serverId) ?: return@withContext emptyMap()
            val series = page.seriesRemoteId
            if (series == null) {
                mapOf(item.id to playstate.resumePositionMs(item.remoteId)).filterValues { it > 0 }
            } else {
                val ids = page.episodes.associate { it.remoteId to it.id }
                playstate.resumePoints(series).mapNotNull { (remoteId, position) -> ids[remoteId]?.let { it to position } }.toMap()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.w(TAG, "No resume points for item ${page.item.id}: ${e.message}")
            emptyMap()
        }
    }

    /** Where the signed-in user stopped watching [itemId], or 0. */
    suspend fun resumePositionMs(itemId: Long): Long = withContext(io) {
        try {
            val item = itemDao.byId(itemId) ?: return@withContext 0L
            playstate(item.serverId)?.resumePositionMs(item.remoteId) ?: 0L
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.w(TAG, "No resume point for item $itemId: ${e.message}")
            0L
        }
    }

    /** Every episode of [item]'s show within [maxRating], in order, [item] among them; empty for a movie. */
    suspend fun showEpisodes(item: MediaItemEntity, maxRating: Int?): List<MediaItemEntity> = withContext(io) {
        val series = item.seriesRemoteId ?: return@withContext emptyList()
        itemDao.episodes(item.serverId, series, MediaType.EPISODE, maxRating)
    }

    private suspend fun playstate(serverId: Long): Playstate? {
        val server = serverDao.byId(serverId) ?: return null
        val userId = server.userId ?: return null
        val token = tokens.get(server.jellyfinServerId) ?: return null
        return playstates.create(server.baseUrl, token, userId)
    }

    private fun itemPage(item: MediaItemEntity, series: MediaItemEntity?, base: String?): ItemPage {
        val image = when {
            base == null -> null
            item.primaryImageTag != null -> posterUrl(base, item.remoteId, item.primaryImageTag)
            series != null -> posterUrl(base, series.remoteId, series.primaryImageTag)
            else -> null
        }
        return ItemPage(
            id = item.id,
            title = programTitle(item.title, item.showTitle),
            detail = programDetail(item.title, item.showTitle, item.season, item.episode, item.year),
            facts = GuideRepository.facts(item),
            overview = item.overview?.takeIf { it.isNotBlank() },
            badges = ProgramBadges.of(item.hdrType, item.width, item.height, item.audioFormat, item.audioChannels),
            imageUrl = image,
            artwork = Artwork.of(base, item, series),
            durationMs = item.durationMs,
            isEpisode = item.showTitle != null,
        )
    }

    private suspend fun baseUrls(): Map<Long, String> = serverDao.all().associate { it.id to it.baseUrl }

    companion object {
        private const val TAG = "OnDemand"
        private const val MAX_IDS_PER_QUERY = 500

        /** How many slots past an off-air stretch to look for the next program. */
        private const val LOOK_AHEAD_SLOTS = 4

        /** Poster height asked of the server, in pixels: enough for a hero on a 1080p TV. */
        private const val POSTER_HEIGHT = 480

        /** An episode still's width in a list row. */
        private const val STILL_WIDTH = 400

        /** The item's primary image from Jellyfin, or null when it has none. */
        private fun posterUrl(baseUrl: String, remoteId: String, tag: String?): String? =
            jellyfinImageUrl(baseUrl, remoteId, "Primary", tag, maxHeight = POSTER_HEIGHT)
    }
}
