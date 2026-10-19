package tv.anstelle.core.data.playback

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith
import tv.anstelle.core.data.WatchAccount
import tv.anstelle.core.database.entity.MediaItemEntity
import tv.anstelle.core.database.entity.MediaServerEntity
import tv.anstelle.core.database.entity.ScheduleSlotEntity
import tv.anstelle.core.model.HdrType
import tv.anstelle.core.model.MediaTracks
import tv.anstelle.core.model.MediaType
import tv.anstelle.core.model.SlotKind
import tv.anstelle.core.player.ProgramStream
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertSame

@RunWith(AndroidJUnit4::class)
class ProgramSeekTest {
    @Test
    fun scheduleShiftsKeepCurrentAndQueuedPlaybackIdentity() {
        val current = program(startUtcMs = 100_000)
        // The same episode can air twice; each occurrence must keep its own identity.
        val next = program(startUtcMs = current.slot.endUtcMs)
        val playlist = listOf(current.mediaItem, next.mediaItem)
        assertNotEquals(playlist[0].mediaId, playlist[1].mediaId)
        var queue = listOf(current, next)
        var totalShiftMs = 0L

        // Forward, backward, and repeated seeks move the schedule, not ExoPlayer's playlist.
        for (deltaMs in listOf(-30_000L, 10_000L, 10_000L)) {
            totalShiftMs += deltaMs
            queue = queue.map { it.shifted(deltaMs) }
            queue.forEachIndexed { index, shifted ->
                assertEquals(playlist[index].mediaId, shifted.mediaItem.mediaId)
                assertSame(playlist[index], shifted.mediaItem)
            }
            assertEquals(current.slot.startUtcMs + totalShiftMs, queue[0].info.startUtcMs)
            assertEquals(current.slot.endUtcMs + totalShiftMs, queue[0].info.endUtcMs)
            assertEquals(next.slot.startUtcMs + totalShiftMs, queue[1].info.startUtcMs)
            assertEquals(next.slot.endUtcMs + totalShiftMs, queue[1].info.endUtcMs)
        }
    }

    private fun program(startUtcMs: Long): Program {
        val item = MediaItemEntity(
            id = 1, serverId = 1, libraryId = null, remoteId = "episode", type = MediaType.EPISODE,
            title = "Pilot", sortTitle = null, seriesRemoteId = "series", showTitle = "Show", season = 1,
            episode = 1, durationMs = 60_000, genres = "", studios = "", tags = "", year = null,
            premiereDateUtcMs = null, officialRating = null, ratingScore = null, overview = null,
            communityRating = null, hdrType = HdrType.SDR, audioFormat = null, videoCodec = null,
            audioCodec = null, container = null, width = null, height = null, videoBitDepth = null,
            audioChannels = null, mediaSourceId = null, tracks = MediaTracks(), audioLanguages = "",
            primaryImageTag = null, backdropImageTag = null, logoImageTag = null, updatedAtUtcMs = 0,
        )
        return Program(
            slot = ScheduleSlotEntity(channelId = 1, kind = SlotKind.PROGRAM, mediaItemId = item.id,
                startUtcMs = startUtcMs, endUtcMs = startUtcMs + item.durationMs),
            item = item,
            server = MediaServerEntity(1, "server", "Server", "http://localhost", null, null, null, 0),
            account = WatchAccount("user", "token", null, false),
            stream = ProgramStream("http://localhost/episode.mp4", null, false, "session"),
            logoUrl = null,
        )
    }
}
