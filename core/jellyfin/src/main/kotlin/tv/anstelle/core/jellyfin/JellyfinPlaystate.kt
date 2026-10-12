package tv.anstelle.core.jellyfin

import org.jellyfin.sdk.api.client.extensions.sessionApi
import org.jellyfin.sdk.api.client.extensions.showApi
import org.jellyfin.sdk.api.client.extensions.userDataApi
import org.jellyfin.sdk.model.api.PlayMethod
import org.jellyfin.sdk.model.api.PlaybackOrder
import org.jellyfin.sdk.model.api.PlaybackProgressInfo
import org.jellyfin.sdk.model.api.PlaybackStartInfo
import org.jellyfin.sdk.model.api.PlaybackStopInfo
import org.jellyfin.sdk.model.api.RepeatMode
import org.jellyfin.sdk.model.api.UpdateUserItemDataDto
import org.jellyfin.sdk.model.serializer.toUUID
import javax.inject.Inject
import javax.inject.Singleton

/** One item's playback as Jellyfin sees it. */
data class PlaystateItem(
    val itemId: String,
    val mediaSourceId: String?,
    val playSessionId: String,
    val transcoded: Boolean,
)

/** Where the user stands with one item: where they stopped (0 for no resume point), and whether it is marked played. */
data class ItemWatch(val positionMs: Long, val played: Boolean)

/** Playback reports, played state and resume points for one signed-in user on one server. */
interface Playstate {
    suspend fun started(item: PlaystateItem, positionMs: Long)
    suspend fun progress(item: PlaystateItem, positionMs: Long, paused: Boolean)
    suspend fun stopped(item: PlaystateItem, positionMs: Long)

    /** Whether the server has the item marked played for this user. */
    suspend fun isPlayed(itemId: String): Boolean

    /** Marks the item watched at [atUtcMs], as Jellyfin's own apps do: one more play, resume point cleared. */
    suspend fun markWatched(itemId: String, atUtcMs: Long)

    /** Sets only the played flag, leaving the resume point, play count and last played date as they are. */
    suspend fun setPlayed(itemId: String, played: Boolean)

    /** Where the user stopped watching the item, or 0 when Jellyfin keeps no resume point. */
    suspend fun resumePositionMs(itemId: String): Long

    /** The resume points and played flags of a show's episodes that have either, by item id, in one request. */
    suspend fun episodeWatch(seriesId: String): Map<String, ItemWatch>
}

@Singleton
class PlaystateFactory @Inject constructor(
    private val clients: JellyfinClients,
) {
    /** [device] is the profile's own device when it signed in as a user of its own (see [JellyfinClients.api]). */
    fun create(baseUrl: String, accessToken: String, userId: String, device: String? = null): Playstate =
        JellyfinPlaystate(clients.api(baseUrl, accessToken, device), userId)
}

private class JellyfinPlaystate(
    private val api: org.jellyfin.sdk.api.client.ApiClient,
    userId: String,
) : Playstate {
    private val user = userId.toUUID()

    override suspend fun started(item: PlaystateItem, positionMs: Long) = jellyfinCall("Reporting playback start") {
        api.sessionApi.reportPlaybackStart(
            PlaybackStartInfo(
                canSeek = true,
                itemId = item.itemId.toUUID(),
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
                itemId = item.itemId.toUUID(),
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
                itemId = item.itemId.toUUID(),
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

    private fun method(item: PlaystateItem) = if (item.transcoded) PlayMethod.TRANSCODE else PlayMethod.DIRECT_PLAY

    private companion object {
        const val TICKS_PER_MS = 10_000L
    }
}
