package tv.anstelle.app

import android.util.Log
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.jellyfin.sdk.api.client.extensions.libraryApi
import org.jellyfin.sdk.api.client.extensions.sessionApi
import org.jellyfin.sdk.api.client.extensions.userDataApi
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ItemFilter
import org.jellyfin.sdk.model.api.UpdateUserItemDataDto
import org.jellyfin.sdk.model.api.request.GetItemsRequest
import org.jellyfin.sdk.model.serializer.toUUID
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import tv.anstelle.core.jellyfin.PlaybackPresence
import tv.anstelle.core.jellyfin.PlaystateItem
import java.util.UUID

/** Uses a previously watched item and restores its resume point/date in finally. */
@RunWith(AndroidJUnit4::class)
class ProgressPresenceTest {
    @Test fun progressAndNowPlayingNeverChangeWatchedStatus() = runBlocking<Unit> { verifyPresence(false) }

    @Test fun actualServerIdleCleanupNeverChangesWatchedStatus() = runBlocking<Unit> {
        assumeTrue(InstrumentationRegistry.getArguments().getString("verifyServerTimeout") == "true")
        verifyPresence(true)
    }

    private suspend fun verifyPresence(waitForTimeout: Boolean) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val graph = EntryPointAccessors.fromApplication(context.applicationContext, CatalogTestEntryPoint::class.java)
        assumeTrue(graph.profiles().hasProfiles())
        val profile = graph.profiles().activeProfile()
        val server = graph.servers().all().first()
        val token = requireNotNull(graph.tokens().get(server.jellyfinServerId))
        val account = requireNotNull(graph.profiles().watchAccount(profile.id, server, token))
        assumeTrue(account.keepsHistory)
        val api = graph.clients().api(server.baseUrl, account.accessToken, account.device)
        val user = account.userId.toUUID()
        val item = api.libraryApi.getItems(GetItemsRequest(userId = user, recursive = true, limit = 1,
            includeItemTypes = setOf(BaseItemKind.EPISODE, BaseItemKind.MOVIE),
            filters = listOf(ItemFilter.IS_PLAYED), enableUserData = true)).content.items.firstOrNull()
        assumeTrue("Requires an existing watched item so its last-played date can be restored", item != null)
        val id = requireNotNull(item).id
        val before = api.userDataApi.getItemUserData(id, user).content
        assumeTrue(before.lastPlayedDate != null)
        val playstate = graph.playstates().create(server.baseUrl, account.accessToken, account.userId, account.device)
        val presence = PlaybackPresence(UUID.randomUUID().toString(), item.name ?: "Playback test", item.seriesName,
            (item.runTimeTicks ?: 10_000_000_000L) / 10_000)
        val stream = PlaystateItem(id.toString(), null, UUID.randomUUID().toString(), false, presence)
        try {
            playstate.started(stream, 10_000)
            for (position in listOf(15_000L, presence.durationMs)) {
                playstate.saveProgress(id.toString(), position, System.currentTimeMillis())
                playstate.progress(stream, position, paused = position == presence.durationMs)
                val saved = api.userDataApi.getItemUserData(id, user).content
                assertEquals(before.played, saved.played)
                assertEquals(before.playCount, saved.playCount)
                assertEquals(position * 10_000, saved.playbackPositionTicks)
                val session = api.sessionApi.getSessions().content.firstOrNull { it.nowPlayingItem?.id == presence.id.toUUID() }
                assertNotNull("Metadata-only Now Playing missing", session)
                assertEquals(position * 10_000, session?.playState?.positionTicks)
                assertEquals(presence.title, session?.nowPlayingItem?.name)
            }
            playstate.stopped(stream, presence.durationMs)
            assertEquals(before.played, api.userDataApi.getItemUserData(id, user).content.played)
            // Jellyfin's idle-session cleanup uses the embedded metadata ID. It must also
            // remain unrelated to the library item if the client dies without its stop.
            playstate.started(stream, 20_000)
            if (waitForTimeout) {
                val began = SystemClock.elapsedRealtime()
                withTimeout(11 * 60_000L) {
                    while (api.sessionApi.getSessions().content.any { it.nowPlayingItem?.id == presence.id.toUUID() }) delay(5_000)
                }
                Log.i("Anstelle/ProgressPresenceTest", "Actual server idle cleanup after ${SystemClock.elapsedRealtime() - began} ms")
            } else {
                playstate.stopped(stream.copy(itemId = presence.id, presence = null), 20_000)
            }
            val after = api.userDataApi.getItemUserData(id, user).content
            assertEquals(before.played, after.played)
            assertEquals(before.playCount, after.playCount)
            Log.i("Anstelle/ProgressPresenceTest", "Now Playing, live resume, completion and timeout cleanup preserved Played/PlayCount")
        } finally {
            runCatching { playstate.stopped(stream, before.playbackPositionTicks / 10_000) }
            api.userDataApi.updateItemUserData(id, user, UpdateUserItemDataDto(
                playbackPositionTicks = before.playbackPositionTicks, lastPlayedDate = before.lastPlayedDate))
        }
    }
}
