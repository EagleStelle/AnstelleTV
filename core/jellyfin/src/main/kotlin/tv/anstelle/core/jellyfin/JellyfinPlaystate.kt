package tv.anstelle.core.jellyfin

import org.jellyfin.sdk.api.client.extensions.libraryApi
import org.jellyfin.sdk.api.client.extensions.sessionApi
import org.jellyfin.sdk.api.client.extensions.showApi
import org.jellyfin.sdk.api.client.extensions.userDataApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ItemFilter
import org.jellyfin.sdk.model.api.LocationType
import org.jellyfin.sdk.model.api.MediaType
import org.jellyfin.sdk.model.api.PlayMethod
import org.jellyfin.sdk.model.api.PlaybackOrder
import org.jellyfin.sdk.model.api.PlaybackProgressInfo
import org.jellyfin.sdk.model.api.PlaybackStartInfo
import org.jellyfin.sdk.model.api.PlaybackStopInfo
import org.jellyfin.sdk.model.api.RepeatMode
import org.jellyfin.sdk.model.api.UpdateUserItemDataDto
import org.jellyfin.sdk.model.api.request.GetItemsRequest
import org.jellyfin.sdk.model.api.request.GetNextUpRequest
import org.jellyfin.sdk.model.api.request.GetResumeItemsRequest
import org.jellyfin.sdk.model.serializer.toUUID
import javax.inject.Inject
import javax.inject.Singleton
import java.util.UUID

/** One item's playback as Jellyfin sees it. */
data class PlaystateItem(
    val itemId: String,
    val mediaSourceId: String?,
    val playSessionId: String,
    val transcoded: Boolean,
    /** Non-library presence keeps server session side effects away from saved user data. */
    val presence: PlaybackPresence? = null,
)

data class PlaybackPresence(val id: String, val title: String, val series: String?, val durationMs: Long)

/** Where the user stands with one item: where they stopped (0 for no resume point), and whether it is marked played. */
data class ItemWatch(val positionMs: Long, val played: Boolean)

/**
 * Where the user stands with a movie or a series: for a movie where they stopped and whether it
 * is played; for a series whether every episode is played, and how many are not.
 */
data class TitleWatch(val positionMs: Long, val played: Boolean, val unplayedCount: Int?)

/** An item the user stopped part-way through, and when they last played it. */
data class ResumePoint(val itemId: String, val positionMs: Long, val lastPlayedUtcMs: Long)

/** Playback reports, played state and resume points for one signed-in user on one server. */
interface Playstate {
    suspend fun started(item: PlaystateItem, positionMs: Long)
    suspend fun progress(item: PlaystateItem, positionMs: Long, paused: Boolean)
    suspend fun stopped(item: PlaystateItem, positionMs: Long)

    /** Saves a resume point without session reports changing Played or PlayCount. */
    suspend fun saveProgress(itemId: String, positionMs: Long, atUtcMs: Long)

    /** Whether the server has the item marked played for this user. */
    suspend fun isPlayed(itemId: String): Boolean

    /** Marks the item watched at [atUtcMs], as Jellyfin's own apps do: one more play, resume point cleared. */
    suspend fun markWatched(itemId: String, atUtcMs: Long)

    /** Sets only the played flag, leaving the resume point, play count and last played date as they are. */
    suspend fun setPlayed(itemId: String, played: Boolean)

    /** Where the user stopped watching the item, or 0 when Jellyfin keeps no resume point. */
    suspend fun resumePositionMs(itemId: String): Long

    /** Where the user stopped watching the item and whether it is marked played, in one request. */
    suspend fun itemWatch(itemId: String): ItemWatch

    /** The resume points and played flags of a show's episodes that have either, by item id, in one request. */
    suspend fun episodeWatch(seriesId: String): Map<String, ItemWatch>

    /**
     * Every movie the user has started or played, and every series with its unplayed count, by
     * item id: what the catalog's posters say about each.
     */
    suspend fun titleWatch(episodic: Boolean? = null): Map<String, TitleWatch>

    /** Movies, or episodes when [episodes], the user stopped part-way through, last played first. */
    suspend fun resumeItems(episodes: Boolean, limit: Int): List<ResumePoint>

    /** The next episode of each series the user is watching, as Jellyfin's Next Up lists them. */
    suspend fun nextUp(limit: Int): List<String>
}

@Singleton
class PlaystateFactory @Inject constructor(
    private val clients: JellyfinClients,
) {
    /** [device] is the profile's own device when it signed in as a user of its own (see [JellyfinClients.api]). */
    fun create(baseUrl: String, accessToken: String, userId: String, device: String? = null): Playstate =
        JellyfinPlaystate(clients.api(baseUrl, accessToken, device), userId)
}

internal class JellyfinPlaystate(
    private val api: org.jellyfin.sdk.api.client.ApiClient,
    userId: String,
) : Playstate {
    private val user = userId.toUUID()

    private fun PlaystateItem.reportedId(): UUID = if (presence == null) itemId.toUUID() else UUID(0, 0)
    private fun PlaystateItem.metadata(): BaseItemDto? = presence?.let {
        BaseItemDto(id = it.id.toUUID(), name = it.title, seriesName = it.series,
            runTimeTicks = it.durationMs * TICKS_PER_MS, mediaType = MediaType.VIDEO,
            type = if (it.series == null) BaseItemKind.MOVIE else BaseItemKind.EPISODE)
    }

    override suspend fun started(item: PlaystateItem, positionMs: Long) = jellyfinCall("Reporting playback start") {
        api.sessionApi.reportPlaybackStart(
            PlaybackStartInfo(
                canSeek = true,
                itemId = item.reportedId(),
                item = item.metadata(),
                mediaSourceId = item.mediaSourceId,
                isPaused = false,
                isMuted = false,
                positionTicks = positionMs * TICKS_PER_MS,
                playMethod = method(item),
                playSessionId = item.playSessionId,
                repeatMode = RepeatMode.REPEAT_NONE,
                playbackOrder = PlaybackOrder.DEFAULT,
            ),
        )
        Unit
    }

    override suspend fun progress(item: PlaystateItem, positionMs: Long, paused: Boolean) = jellyfinCall("Reporting playback progress") {
        api.sessionApi.reportPlaybackProgress(
            PlaybackProgressInfo(
                canSeek = true,
                itemId = item.reportedId(),
                item = item.metadata(),
                mediaSourceId = item.mediaSourceId,
                isPaused = paused,
                isMuted = false,
                positionTicks = positionMs * TICKS_PER_MS,
                playMethod = method(item),
                playSessionId = item.playSessionId,
                repeatMode = RepeatMode.REPEAT_NONE,
                playbackOrder = PlaybackOrder.DEFAULT,
            ),
        )
        Unit
    }

    override suspend fun stopped(item: PlaystateItem, positionMs: Long) = jellyfinCall("Reporting playback stop") {
        api.sessionApi.reportPlaybackStopped(
            PlaybackStopInfo(
                itemId = item.reportedId(),
                item = item.metadata(),
                mediaSourceId = item.mediaSourceId,
                positionTicks = positionMs * TICKS_PER_MS,
                playSessionId = item.playSessionId,
                failed = false,
            ),
        )
        Unit
    }

    override suspend fun isPlayed(itemId: String): Boolean = jellyfinCall("Reading played state") {
        api.userDataApi.getItemUserData(itemId = itemId.toUUID(), userId = user).content.played
    }

    override suspend fun saveProgress(itemId: String, positionMs: Long, atUtcMs: Long) = jellyfinCall("Saving progress") {
        api.userDataApi.updateItemUserData(
            itemId = itemId.toUUID(), userId = user,
            data = UpdateUserItemDataDto(
                playbackPositionTicks = positionMs.coerceAtLeast(0) * TICKS_PER_MS,
                lastPlayedDate = sdkDateTime(atUtcMs),
            ),
        )
        Unit
    }

    override suspend fun markWatched(itemId: String, atUtcMs: Long) = jellyfinCall("Marking watched") {
        api.userDataApi.markPlayedItem(itemId = itemId.toUUID(), userId = user, datePlayed = sdkDateTime(atUtcMs))
        Unit
    }

    override suspend fun setPlayed(itemId: String, played: Boolean) = jellyfinCall("Setting played state") {
        api.userDataApi.updateItemUserData(itemId = itemId.toUUID(), userId = user, data = UpdateUserItemDataDto(played = played))
        Unit
    }

    override suspend fun resumePositionMs(itemId: String): Long = jellyfinCall("Reading the resume point") {
        api.userDataApi.getItemUserData(itemId = itemId.toUUID(), userId = user).content.playbackPositionTicks / TICKS_PER_MS
    }

    override suspend fun itemWatch(itemId: String): ItemWatch = jellyfinCall("Reading what was watched") {
        val data = api.userDataApi.getItemUserData(itemId = itemId.toUUID(), userId = user).content
        ItemWatch(data.playbackPositionTicks / TICKS_PER_MS, data.played)
    }

    override suspend fun episodeWatch(seriesId: String): Map<String, ItemWatch> = jellyfinCall("Reading what was watched") {
        api.showApi.getEpisodes(seriesId = seriesId.toUUID(), userId = user, enableImages = false, enableUserData = true)
            .content.items
            .mapNotNull { episode ->
                val ticks = episode.userData?.playbackPositionTicks ?: 0
                val played = episode.userData?.played == true
                if (ticks > 0 || played) episode.id.toJellyfinId() to ItemWatch(ticks / TICKS_PER_MS, played) else null
            }
            .toMap()
    }

    override suspend fun titleWatch(episodic: Boolean?): Map<String, TitleWatch> = jellyfinCall("Reading what was watched") {
        val watch = HashMap<String, TitleWatch>()
        // Movies only matter once started or played; every series carries its unplayed count.
        for (filter in if (episodic != true) listOf(ItemFilter.IS_RESUMABLE, ItemFilter.IS_PLAYED) else emptyList()) {
            for (movie in pagedItems(setOf(BaseItemKind.MOVIE), listOf(filter))) {
                val data = movie.userData ?: continue
                watch[movie.id.toJellyfinId()] = TitleWatch(data.playbackPositionTicks / TICKS_PER_MS, data.played, null)
            }
        }
        for (series in if (episodic != false) pagedItems(setOf(BaseItemKind.SERIES), emptyList()) else emptyList()) {
            val data = series.userData ?: continue
            watch[series.id.toJellyfinId()] = TitleWatch(0, data.played, data.unplayedItemCount)
        }
        watch
    }

    override suspend fun resumeItems(episodes: Boolean, limit: Int): List<ResumePoint> = jellyfinCall("Reading Continue Watching") {
        api.libraryApi.getResumeItems(
            GetResumeItemsRequest(
                userId = user,
                limit = limit,
                mediaTypes = listOf(MediaType.VIDEO),
                includeItemTypes = listOf(if (episodes) BaseItemKind.EPISODE else BaseItemKind.MOVIE),
                enableUserData = true,
                enableImages = false,
                enableTotalRecordCount = false,
            ),
        ).content.items.mapNotNull { item ->
            val data = item.userData ?: return@mapNotNull null
            ResumePoint(item.id.toJellyfinId(), data.playbackPositionTicks / TICKS_PER_MS, data.lastPlayedDate?.toEpochMs() ?: 0L)
        }
    }

    override suspend fun nextUp(limit: Int): List<String> = jellyfinCall("Reading Next Up") {
        api.showApi.getNextUp(
            GetNextUpRequest(
                userId = user,
                limit = limit,
                enableImages = false,
                enableUserData = false,
                enableTotalRecordCount = false,
                // Episodes left part-way are Continue Watching's; Next Up is what comes after one watched through.
                enableResumable = false,
                enableRewatching = false,
            ),
        ).content.items.map { it.id.toJellyfinId() }
    }

    /** Every item of [kinds] the user can see that passes [filters], with its user data and nothing else. */
    private suspend fun pagedItems(kinds: Set<BaseItemKind>, filters: List<ItemFilter>): List<BaseItemDto> {
        val items = ArrayList<BaseItemDto>()
        var start = 0
        while (true) {
            val page = api.libraryApi.getItems(
                GetItemsRequest(
                    userId = user,
                    recursive = true,
                    includeItemTypes = kinds,
                    filters = filters,
                    excludeLocationTypes = listOf(LocationType.VIRTUAL),
                    enableUserData = true,
                    enableImages = false,
                    fields = emptyList(),
                    startIndex = start,
                    limit = PAGE_SIZE,
                    enableTotalRecordCount = false,
                ),
            ).content.items
            items += page
            if (page.size < PAGE_SIZE) break
            start += page.size
        }
        return items
    }

    private fun method(item: PlaystateItem) = if (item.transcoded) PlayMethod.TRANSCODE else PlayMethod.DIRECT_PLAY

    private companion object {
        const val TICKS_PER_MS = 10_000L
        const val PAGE_SIZE = 2_000
    }
}
