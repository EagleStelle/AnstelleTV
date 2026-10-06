package tv.anstelle.core.jellyfin

import org.jellyfin.sdk.api.client.extensions.sessionApi
import org.jellyfin.sdk.api.client.extensions.userDataApi
import org.jellyfin.sdk.model.api.PlayMethod
import org.jellyfin.sdk.model.api.PlaybackOrder
import org.jellyfin.sdk.model.api.PlaybackProgressInfo
import org.jellyfin.sdk.model.api.PlaybackStartInfo
import org.jellyfin.sdk.model.api.PlaybackStopInfo
import org.jellyfin.sdk.model.api.RepeatMode
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

/** Playback reports and played state for one signed-in user on one server. */
interface Playstate {
    suspend fun started(item: PlaystateItem, positionMs: Long)
    suspend fun progress(item: PlaystateItem, positionMs: Long, paused: Boolean)
    suspend fun stopped(item: PlaystateItem, positionMs: Long)

    /** Whether the server has the item marked played for this user. */
    suspend fun isPlayed(itemId: String): Boolean
    suspend fun markUnplayed(itemId: String)
}

@Singleton
class PlaystateFactory @Inject constructor(
    private val clients: JellyfinClients,
) {
    fun create(baseUrl: String, accessToken: String, userId: String): Playstate =
        JellyfinPlaystate(clients.api(baseUrl, accessToken), userId)
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

    override suspend fun markUnplayed(itemId: String) = jellyfinCall("Marking unplayed") {
        api.userDataApi.markUnplayedItem(itemId = itemId.toUUID(), userId = user)
        Unit
    }

    private fun method(item: PlaystateItem) = if (item.transcoded) PlayMethod.TRANSCODE else PlayMethod.DIRECT_PLAY

    private companion object {
        const val TICKS_PER_MS = 10_000L
    }
}
