package tv.anstelle.core.data.ondemand

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import tv.anstelle.core.common.AnstelleDispatchers
import tv.anstelle.core.common.AppLog
import tv.anstelle.core.common.Dispatcher
import tv.anstelle.core.data.Artwork
import tv.anstelle.core.data.ProfileRepository
import tv.anstelle.core.data.channels.toModel
import tv.anstelle.core.data.guide.GuideRepository
import tv.anstelle.core.data.guide.ProgramBadges
import tv.anstelle.core.data.jellyfinImageUrl
import tv.anstelle.core.data.programDetail
import tv.anstelle.core.data.profiles.Restriction
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
import tv.anstelle.core.model.ProfileAccess
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
    /**
     * Set when the profile may not watch the program: then [artwork] keeps only the logo
     * (screens blur it with the title), [overview] is blacked out, and [endUtcMs] is the end of
     * the whole run of restricted programs.
     */
    val restriction: Restriction? = null,
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
) {
    /** The tab the episode's season goes under. */
    val seasonLabel: String get() = when (season) {
        null -> "Other episodes"
        0 -> "Specials"
        else -> "Season $season"
    }
}

/**
 * What the profile's Jellyfin user has watched of a program and its show's episodes, by local
 * item id: where it stopped in each, and which it has marked played.
 */
data class WatchMarks(val resume: Map<Long, Long> = emptyMap(), val played: Set<Long> = emptySet()) {
    /** Where to pick item [id] up again, or 0 to play it from the start. */
    fun resumeMs(id: Long, durationMs: Long): Long = resume[id]?.takeIf { it < nearEnd(durationMs) } ?: 0

    /** Whether item [id] was watched through: marked played, or left this close to its end. */
    fun watched(id: Long, durationMs: Long): Boolean = id in played || (durationMs > 0 && (resume[id] ?: 0) >= nearEnd(durationMs))

    private fun nearEnd(durationMs: Long) = durationMs * RESUME_LIMIT_PERCENT / 100

    private companion object {
        /** A resume point this close to the end counts as watched through. */
        const val RESUME_LIMIT_PERCENT = 95
    }
}

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
    /** The show's episodes the profile may watch, in order; empty for a movie. */
    val episodes: List<EpisodeEntry>,
    /**
     * Set when the profile may not watch the program: then its pictures and overview are
     * blacked out and it can't be played on demand.
     */
    val restriction: Restriction? = null,
)

/**
 * On demand from SQLite: what every channel airs, a channel's program and its show. Only
 * resume points and played marks ask the server. `access` is what the watching profile may watch: its maturity
 * rating limit (unrated items never pass one) and what its Jellyfin users can't see. Channels
 * are the same for every profile, so what a channel airs beyond that is still listed, blacked
 * out.
 */
@Singleton
class OnDemandRepository @Inject constructor(
    private val channelDao: ChannelDao,
    private val scheduleDao: ScheduleDao,
    private val itemDao: MediaItemDao,
    private val serverDao: MediaServerDao,
    private val tokens: TokenStore,
    private val profiles: ProfileRepository,
    private val playstates: PlaystateFactory,
    @Dispatcher(AnstelleDispatchers.IO) private val io: CoroutineDispatcher,
) {
    /** What each of [channels] airs at [atUtcMs], in one read; a channel with no schedule is left out. */
    suspend fun nowOn(channels: List<Channel>, atUtcMs: Long, access: ProfileAccess): Map<Long, ChannelNow> = withContext(io) {
        if (channels.isEmpty()) return@withContext emptyMap()
        val byId = channels.associateBy { it.id }
        val bases = baseUrls()
        val maxSlot = scheduleDao.maxSlotMs()
        // SQLite on older Android allows 999 bound values per statement.
        val rows = channels.map { it.id }.chunked(MAX_IDS_PER_QUERY).flatMap { scheduleDao.airingsAt(it, atUtcMs, maxSlot) }
        rows
            .mapNotNull { row ->
                val channel = byId[row.channelId] ?: return@mapNotNull null
                val item = row.mediaItemId
                val title = row.title
                val remoteId = row.remoteId
                val now = if (item == null || title == null || remoteId == null) {
                    ChannelNow(channel, 0, row.startUtcMs, row.endUtcMs, GuideRepository.OFF_AIR_TITLE, null, false, null, Artwork.None)
                } else {
                    val restriction = Restriction.of(item, row.ratingScore, access)
                    val artwork = Artwork.of(
                        row.serverId?.let(bases::get),
                        remoteId, row.backdropImageTag, row.logoImageTag,
                        row.seriesRemoteId, row.seriesBackdropImageTag, row.seriesLogoImageTag,
                    )
                    ChannelNow(
                        channel = channel,
                        mediaItemId = item,
                        startUtcMs = row.startUtcMs,
                        endUtcMs = if (restriction != null) restrictedUntil(row.channelId, row.endUtcMs, access) else row.endUtcMs,
                        title = programTitle(title, row.showTitle),
                        detail = programDetail(title, row.showTitle, row.season, row.episode, row.year),
                        isEpisode = row.showTitle != null,
                        overview = row.overview?.takeIf { it.isNotBlank() && restriction == null },
                        artwork = if (restriction != null) artwork.copy(backdropUrl = null) else artwork,
                        restriction = restriction,
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
    suspend fun channelPage(channelId: Long, atUtcMs: Long, access: ProfileAccess): ChannelPage? = withContext(io) {
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
        val episodes = showEpisodes(entity, access)
        val restriction = Restriction.of(entity.id, entity.ratingScore, access)
        ChannelPage(
            channel = channel,
            startUtcMs = slot.startUtcMs,
            endUtcMs = if (restriction != null) restrictedUntil(channelId, slot.endUtcMs, access) else slot.endUtcMs,
            onNow = onNow,
            item = itemPage(entity, series, base).let { page ->
                if (restriction == null) page else page.copy(overview = null, imageUrl = null, artwork = page.artwork.copy(backdropUrl = null))
            },
            episodes = episodes.map { it.toEntry(base) },
            restriction = restriction,
        )
    }

    /** Where the run of restricted programs ending one at [fromUtcMs] on [channelId] ends. */
    private suspend fun restrictedUntil(channelId: Long, fromUtcMs: Long, access: ProfileAccess): Long =
        scheduleDao.restrictedUntil(channelId, fromUtcMs, access.limit, access.profileId)?.coerceAtLeast(fromUtcMs) ?: fromUtcMs

    /**
     * What [profileId]'s Jellyfin user watched of the page's program and, for a show, of each of
     * its episodes. Empty when the server can't be reached, or when the profile keeps no watch
     * history there.
     */
    suspend fun watchMarks(page: ChannelPage, profileId: Long): WatchMarks = watchMarks(page.item.id, page.episodes, profileId)

    /**
     * What [profileId]'s Jellyfin user watched of [itemId] or, for an episode, of each of
     * [episodes] of its show, in one request; empty as for a channel's page.
     */
    suspend fun watchMarks(itemId: Long, episodes: List<EpisodeEntry>, profileId: Long): WatchMarks = withContext(io) {
        try {
            val item = itemDao.byId(itemId) ?: return@withContext WatchMarks()
            val playstate = playstate(item.serverId, profileId) ?: return@withContext WatchMarks()
            val series = item.seriesRemoteId
            if (series == null) {
                WatchMarks(resume = mapOf(item.id to playstate.resumePositionMs(item.remoteId)).filterValues { it > 0 })
            } else {
                val ids = episodes.associate { it.remoteId to it.id }
                val watched = playstate.episodeWatch(series).mapNotNull { (remoteId, watch) -> ids[remoteId]?.let { it to watch } }
                WatchMarks(
                    resume = watched.filter { it.second.positionMs > 0 }.associate { (id, watch) -> id to watch.positionMs },
                    played = watched.filter { it.second.played }.map { it.first }.toSet(),
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.w(TAG, "No watch marks for item $itemId: ${e.message}")
            WatchMarks()
        }
    }

    /** The show of [itemId] as an episode list shows it: every episode the profile may watch, in order; empty for a movie. */
    suspend fun episodeList(itemId: Long, access: ProfileAccess): List<EpisodeEntry> = withContext(io) {
        val item = itemDao.byId(itemId) ?: return@withContext emptyList()
        val base = serverDao.byId(item.serverId)?.baseUrl
        showEpisodes(item, access).map { it.toEntry(base) }
    }

    private fun MediaItemEntity.toEntry(base: String?) = EpisodeEntry(
        id = id,
        remoteId = remoteId,
        season = season,
        label = programDetail(title, showTitle ?: "", season, episode, year) ?: title,
        facts = GuideRepository.durationLabel(durationMs),
        overview = overview?.takeIf { it.isNotBlank() },
        stillUrl = base?.let { jellyfinImageUrl(it, remoteId, "Primary", primaryImageTag, maxWidth = STILL_WIDTH) },
        durationMs = durationMs,
    )

    /** Where [profileId]'s Jellyfin user stopped watching [itemId], or 0. */
    suspend fun resumePositionMs(itemId: Long, profileId: Long): Long = withContext(io) {
        try {
            val item = itemDao.byId(itemId) ?: return@withContext 0L
            playstate(item.serverId, profileId)?.resumePositionMs(item.remoteId) ?: 0L
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.w(TAG, "No resume point for item $itemId: ${e.message}")
            0L
        }
    }

    /** Every episode of [item]'s show the profile may watch, in order, [item] among them when allowed; empty for a movie. */
    suspend fun showEpisodes(item: MediaItemEntity, access: ProfileAccess): List<MediaItemEntity> = withContext(io) {
        val series = item.seriesRemoteId ?: return@withContext emptyList()
        itemDao.episodes(item.serverId, series, MediaType.EPISODE, access.limit).filter { !access.isHidden(it.id) }
    }

    /** The profile's Jellyfin user on the server, when its watch history goes there. */
    private suspend fun playstate(serverId: Long, profileId: Long): Playstate? {
        val server = serverDao.byId(serverId) ?: return null
        val token = tokens.get(server.jellyfinServerId) ?: return null
        val account = profiles.watchAccount(profileId, server, token)?.takeIf { it.keepsHistory } ?: return null
        return playstates.create(server.baseUrl, account.accessToken, account.userId, account.device)
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
