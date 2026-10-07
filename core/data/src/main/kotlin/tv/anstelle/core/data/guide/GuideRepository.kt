package tv.anstelle.core.data.guide

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import tv.anstelle.core.common.AnstelleDispatchers
import tv.anstelle.core.common.Dispatcher
import tv.anstelle.core.data.Artwork
import tv.anstelle.core.data.playback.StreamFacts
import tv.anstelle.core.data.programDetail
import tv.anstelle.core.data.programTitle
import tv.anstelle.core.database.dao.MediaItemDao
import tv.anstelle.core.database.dao.MediaServerDao
import tv.anstelle.core.database.dao.ScheduleDao
import tv.anstelle.core.database.entity.MediaItemEntity
import tv.anstelle.core.jellyfin.sync.ItemMapper
import javax.inject.Inject
import javax.inject.Singleton

/** What the guide's details panel says about a program. */
data class ProgramDetails(
    val mediaItemId: Long,
    val title: String,
    /** "S1E4 - Pilot", a movie's year, or null. */
    val detail: String?,
    val overview: String?,
    /** True for an episode, false for a movie. */
    val isEpisode: Boolean,
    /** Rating and year for an episode ("TV-PG · 1994"); a movie's rating alone, since its year is already on its line. */
    val facts: String,
    /** The file's quality, video and audio, as the player's banner shows them: "1080p", "HEVC 10-bit", "DD+ 5.1". */
    val badges: List<String>,
    /** The show's or movie's backdrop and logo. */
    val artwork: Artwork,
)

/** Reads the guide from SQLite: cells per time window, and the details of one program. */
@Singleton
class GuideRepository @Inject constructor(
    private val scheduleDao: ScheduleDao,
    private val itemDao: MediaItemDao,
    private val serverDao: MediaServerDao,
    @Dispatcher(AnstelleDispatchers.IO) private val io: CoroutineDispatcher,
) {
    /** Every channel's cells overlapping `[fromUtcMs, toUtcMs)`, keyed by channel id. */
    suspend fun window(channelIds: List<Long>, fromUtcMs: Long, toUtcMs: Long): Map<Long, ProgramStrip> = withContext(io) {
        if (channelIds.isEmpty()) return@withContext emptyMap()
        val maxSlot = scheduleDao.maxSlotMs()
        // SQLite on older Android allows 999 bound values per statement.
        val rows = channelIds.chunked(MAX_IDS_PER_QUERY).flatMap { scheduleDao.guideWindow(it, fromUtcMs, toUtcMs, maxSlot) }
        ProgramStrip.fromRows(rows, OFF_AIR_TITLE)
    }

    suspend fun details(mediaItemId: Long): ProgramDetails? = withContext(io) {
        val item = itemDao.byId(mediaItemId) ?: return@withContext null
        val series = item.seriesRemoteId?.let { itemDao.byRemoteId(item.serverId, it) }
        details(item, Artwork.of(serverDao.byId(item.serverId)?.baseUrl, item, series))
    }

    companion object {
        const val OFF_AIR_TITLE = "Off air"
        private const val MAX_IDS_PER_QUERY = 500

        fun details(item: MediaItemEntity, artwork: Artwork) = ProgramDetails(
            mediaItemId = item.id,
            title = programTitle(item.title, item.showTitle),
            detail = programDetail(item.title, item.showTitle, item.season, item.episode, item.year),
            overview = item.overview?.takeIf { it.isNotBlank() },
            isEpisode = item.showTitle != null,
            facts = listOfNotNull(
                item.officialRating?.takeIf { it.isNotBlank() },
                item.year?.takeIf { item.showTitle != null }?.toString(),
            ).joinToString(" · "),
            badges = listOfNotNull(
                StreamFacts.quality(item.width, item.height, item.hdrType),
                StreamFacts.video(item.videoCodec, item.videoBitDepth),
                StreamFacts.audio(item.audioCodec, item.audioFormat, item.audioChannels),
            ),
            artwork = artwork,
        )

        fun facts(item: MediaItemEntity): String = listOfNotNull(
            item.officialRating?.takeIf { it.isNotBlank() },
            item.year?.toString(),
            ItemMapper.pipeSplit(item.genres).take(3).joinToString(", ").ifEmpty { null },
            durationLabel(item.durationMs),
        ).joinToString(" · ")

        /** "22 min" or "1 h 54 min". */
        fun durationLabel(durationMs: Long): String? {
            val minutes = ((durationMs + 30_000) / 60_000).toInt()
            return when {
                minutes <= 0 -> null
                minutes < 60 -> "$minutes min"
                minutes % 60 == 0 -> "${minutes / 60} h"
                else -> "${minutes / 60} h ${minutes % 60} min"
            }
        }
    }
}
