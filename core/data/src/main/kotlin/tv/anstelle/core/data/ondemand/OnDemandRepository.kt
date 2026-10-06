package tv.anstelle.core.data.ondemand

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import tv.anstelle.core.common.AnstelleDispatchers
import tv.anstelle.core.common.AppLog
import tv.anstelle.core.common.Dispatcher
import tv.anstelle.core.data.guide.GuideRepository
import tv.anstelle.core.data.guide.ProgramBadges
import tv.anstelle.core.data.programDetail
import tv.anstelle.core.data.programTitle
import tv.anstelle.core.database.dao.MediaItemDao
import tv.anstelle.core.database.dao.MediaServerDao
import tv.anstelle.core.database.dao.OnDemandRow
import tv.anstelle.core.database.entity.MediaItemEntity
import tv.anstelle.core.jellyfin.PlaystateFactory
import tv.anstelle.core.jellyfin.auth.TokenStore
import tv.anstelle.core.model.MediaType
import javax.inject.Inject
import javax.inject.Singleton

/** A show or movie tile. */
data class OnDemandTitle(
    /** The local id of the movie, or of the show's series row. */
    val id: Long,
    val title: String,
    /** "24 episodes · 2004" for a show, the year for a movie. */
    val subtitle: String?,
    val imageUrl: String?,
    val isShow: Boolean,
)

/** One episode on a show's page. */
data class EpisodeEntry(
    val id: Long,
    /** "S1E4 · Pilot". */
    val label: String,
    val season: Int?,
    val facts: String?,
    val overview: String?,
)

data class ShowPage(
    val id: Long,
    val title: String,
    val facts: String,
    val overview: String?,
    val imageUrl: String?,
    val episodes: List<EpisodeEntry>,
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
    val imageUrl: String?,
    val durationMs: Long,
    /** The local id of the episode's show, or null for a movie. */
    val showId: Long?,
)

/**
 * The library for on-demand browsing, from SQLite. Only the resume point asks the server.
 * [maxRating] is the profile's rating limit (null for none); unrated items never pass a limit.
 */
@Singleton
class OnDemandRepository @Inject constructor(
    private val itemDao: MediaItemDao,
    private val serverDao: MediaServerDao,
    private val tokens: TokenStore,
    private val playstates: PlaystateFactory,
    @Dispatcher(AnstelleDispatchers.IO) private val io: CoroutineDispatcher,
) {
    fun observeShows(maxRating: Int?): Flow<List<OnDemandTitle>> =
        itemDao.observeShows(maxRating, MediaType.SERIES, MediaType.EPISODE).map { rows ->
            val bases = baseUrls()
            rows.map { row ->
                val count = "${row.itemCount} ${if (row.itemCount == 1) "episode" else "episodes"}"
                OnDemandTitle(row.id, row.title, listOfNotNull(count, row.year?.toString()).joinToString(" · "), row.poster(bases), isShow = true)
            }
        }

    fun observeMovies(maxRating: Int?): Flow<List<OnDemandTitle>> =
        itemDao.observeOfType(MediaType.MOVIE, maxRating).map { rows ->
            val bases = baseUrls()
            rows.map { row -> OnDemandTitle(row.id, row.title, row.year?.toString(), row.poster(bases), isShow = false) }
        }

    suspend fun show(seriesItemId: Long, maxRating: Int?): ShowPage? = withContext(io) {
        val series = itemDao.byId(seriesItemId)?.takeIf { it.type == MediaType.SERIES } ?: return@withContext null
        val base = serverDao.byId(series.serverId)?.baseUrl
        val episodes = itemDao.episodes(series.serverId, series.remoteId, MediaType.EPISODE, maxRating)
        ShowPage(
            id = series.id,
            title = series.title,
            facts = listOfNotNull(
                series.officialRating?.takeIf { it.isNotBlank() },
                series.year?.toString(),
                "${episodes.size} ${if (episodes.size == 1) "episode" else "episodes"}",
            ).joinToString(" · "),
            overview = series.overview?.takeIf { it.isNotBlank() },
            imageUrl = base?.let { imageUrl(it, series.remoteId, series.primaryImageTag, POSTER_HEIGHT) },
            episodes = episodes.map { episode ->
                EpisodeEntry(
                    id = episode.id,
                    label = programDetail(episode.title, series.title, episode.season, episode.episode, episode.year) ?: episode.title,
                    season = episode.season,
                    facts = GuideRepository.durationLabel(episode.durationMs),
                    overview = episode.overview?.takeIf { it.isNotBlank() },
                )
            },
        )
    }

    suspend fun item(itemId: Long): ItemPage? = withContext(io) {
        val item = itemDao.byId(itemId)?.takeIf { it.type != MediaType.SERIES } ?: return@withContext null
        val base = serverDao.byId(item.serverId)?.baseUrl
        val series = item.seriesRemoteId?.let { itemDao.byRemoteId(item.serverId, it) }
        val image = when {
            base == null -> null
            item.primaryImageTag != null -> imageUrl(base, item.remoteId, item.primaryImageTag, POSTER_HEIGHT)
            series != null -> imageUrl(base, series.remoteId, series.primaryImageTag, POSTER_HEIGHT)
            else -> null
        }
        ItemPage(
            id = item.id,
            title = programTitle(item.title, item.showTitle),
            detail = programDetail(item.title, item.showTitle, item.season, item.episode, item.year),
            facts = GuideRepository.facts(item),
            overview = item.overview?.takeIf { it.isNotBlank() },
            badges = ProgramBadges.of(item.hdrType, item.width, item.height, item.audioFormat, item.audioChannels),
            imageUrl = image,
            durationMs = item.durationMs,
            showId = series?.id,
        )
    }

    /** The episodes after [item] in its show, in order; empty for a movie. */
    suspend fun episodesAfter(item: MediaItemEntity, maxRating: Int?): List<MediaItemEntity> = withContext(io) {
        val series = item.seriesRemoteId ?: return@withContext emptyList()
        val episodes = itemDao.episodes(item.serverId, series, MediaType.EPISODE, maxRating)
        val index = episodes.indexOfFirst { it.id == item.id }
        if (index < 0) emptyList() else episodes.subList(index + 1, episodes.size)
    }

    /** The first episode of the show [seriesItemId], or null. */
    suspend fun firstEpisode(seriesItemId: Long, maxRating: Int?): Long? = withContext(io) {
        val series = itemDao.byId(seriesItemId) ?: return@withContext null
        itemDao.episodes(series.serverId, series.remoteId, MediaType.EPISODE, maxRating).firstOrNull()?.id
    }

    /**
     * Where the signed-in user stopped watching [itemId] in Jellyfin, or 0 when there is no
     * resume point or the server can't be reached.
     */
    suspend fun resumePositionMs(itemId: Long): Long = withContext(io) {
        try {
            val item = itemDao.byId(itemId) ?: return@withContext 0L
            val server = serverDao.byId(item.serverId) ?: return@withContext 0L
            val userId = server.userId ?: return@withContext 0L
            val token = tokens.get(server.jellyfinServerId) ?: return@withContext 0L
            playstates.create(server.baseUrl, token, userId).resumePositionMs(item.remoteId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.w(TAG, "No resume point for item $itemId: ${e.message}")
            0L
        }
    }

    private suspend fun baseUrls(): Map<Long, String> = serverDao.all().associate { it.id to it.baseUrl }

    private fun OnDemandRow.poster(bases: Map<Long, String>): String? =
        bases[serverId]?.let { imageUrl(it, remoteId, primaryImageTag, POSTER_HEIGHT) }

    companion object {
        private const val TAG = "OnDemand"

        /** Poster height asked of the server, in pixels: enough for a tile on a 1080p TV. */
        private const val POSTER_HEIGHT = 480

        /** The item's primary image from Jellyfin, or null when it has none. The tag makes the URL change with the image. */
        fun imageUrl(baseUrl: String, remoteId: String, tag: String?, maxHeight: Int): String? {
            if (tag == null) return null
            return "${baseUrl.trimEnd('/')}/Items/$remoteId/Images/Primary?tag=$tag&maxHeight=$maxHeight&quality=90"
        }
    }
}
