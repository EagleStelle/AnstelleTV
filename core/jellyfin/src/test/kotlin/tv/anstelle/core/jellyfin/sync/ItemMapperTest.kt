package tv.anstelle.core.jellyfin.sync

import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemDtoQueryResult
import org.jellyfin.sdk.model.api.ParentalRating
import tv.anstelle.core.database.dao.SeriesMetadataRow
import tv.anstelle.core.database.entity.MediaItemEntity
import tv.anstelle.core.jellyfin.Fixtures
import tv.anstelle.core.model.AudioStream
import tv.anstelle.core.model.FontAttachment
import tv.anstelle.core.model.HdrType
import tv.anstelle.core.model.MediaTracks
import tv.anstelle.core.model.MediaType
import tv.anstelle.core.model.SubtitleStream
import java.time.Instant
import java.util.TimeZone
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ItemMapperTest {
    // First, so the fixtures below are decoded in the test zone.
    private val originalZone = Fixtures.useTestZone()

    @AfterTest
    fun restoreZone() = TimeZone.setDefault(originalZone)

    private val tvPage = Fixtures.decode<BaseItemDtoQueryResult>("items_tv_page.json").items
    private val moviePage = Fixtures.decode<BaseItemDtoQueryResult>("items_movie_page_derived.json").items
    private val ratings = RatingScores.from(Fixtures.decode<List<ParentalRating>>("parental_ratings.json"))

    private fun item(page: List<BaseItemDto>, name: String) = page.single { it.name == name }

    private fun context(series: Map<String, SeriesMetadataRow> = emptyMap()) =
        SyncContext(serverId = 7, libraryId = 3, ratings = ratings, series = series, nowUtcMs = 1_000L)

    /** Maps the series in the page first, like the syncer does, and returns their inheritable metadata. */
    private fun seriesRows(): Map<String, SeriesMetadataRow> = tvPage
        .mapNotNull { ItemMapper.map(it, context()) }
        .filter { it.type == MediaType.SERIES }
        .associate { it.remoteId to SeriesMetadataRow(it.remoteId, it.genres, it.studios, it.tags, "|Example Saga|", it.officialRating, it.ratingScore) }

    private fun map(dto: BaseItemDto): MediaItemEntity? = ItemMapper.map(dto, context(seriesRows()))

    @Test
    fun `series keeps its own metadata and rating`() {
        val series = map(item(tvPage, "Example Comedy"))!!
        assertEquals(MediaType.SERIES, series.type)
        assertEquals("fa11000000000000000000000000001a", series.remoteId)
        assertEquals("|Comedy|Animation|Cartoons|", series.genres)
        assertEquals("|Example TV|Example Studio|", series.studios)
        assertTrue(series.tags.startsWith("|school|slice of life|"))
        assertEquals("TV-PG", series.officialRating)
        assertEquals(10, series.ratingScore)
        assertEquals(2002, series.year)
        assertEquals(8f, series.communityRating)
        assertNull(series.mediaSourceId)
        assertNull(series.season)
        assertFalse(series.playedOnServer)
    }

    @Test
    fun `foreign ratings get an age score`() {
        assertEquals(16, map(item(tvPage, "Example Fantasy"))!!.ratingScore)
    }

    @Test
    fun `episode maps playback details and inherits series metadata`() {
        val episode = ItemMapper.map(item(tvPage, "Pilot"), context(series = seriesRows()), existingId = 42)!!
        assertEquals(42, episode.id)
        assertEquals(7, episode.serverId)
        assertEquals(3, episode.libraryId)
        assertEquals(MediaType.EPISODE, episode.type)
        assertEquals("fa110000000000000000000000000026", episode.remoteId)
        assertEquals("fa110000000000000000000000000013", episode.seriesRemoteId)
        assertEquals("Example Drama", episode.showTitle)
        assertEquals(1, episode.season)
        assertEquals(1, episode.episode)
        assertEquals(2_215_721L, episode.durationMs)
        assertEquals(Instant.parse("2023-12-03T16:00:00Z").toEpochMilli(), episode.premiereDateUtcMs)
        // The episode has no genres or studios of its own; the series does.
        assertEquals("|Drama|Suspense|Thriller|Mystery|", episode.genres)
        assertEquals("|Example Network|", episode.studios)
        // A new episode starts in its series' box sets; a known one keeps what reconciliation stored.
        assertEquals("|Example Saga|", episode.collections)
        assertEquals("", ItemMapper.map(item(tvPage, "Pilot"), context(seriesRows()), 42, existingCollections = "")!!.collections)
        assertEquals("mkv", episode.container)
        assertEquals("h264", episode.videoCodec)
        assertEquals(1920, episode.width)
        assertEquals(1080, episode.height)
        assertEquals(8, episode.videoBitDepth)
        assertEquals(HdrType.SDR, episode.hdrType)
        assertEquals("opus", episode.audioCodec)
        assertEquals("Opus", episode.audioFormat)
        assertEquals(2, episode.audioChannels)
        assertEquals("fa110000000000000000000000000026", episode.mediaSourceId)
        assertEquals("fa110000000000000000000000000029", episode.primaryImageTag)
        assertTrue(episode.playedOnServer)
        assertEquals(1_000L, episode.updatedAtUtcMs)
    }

    @Test
    fun `movie maps HDR, lossless audio and rating`() {
        val movie = map(item(moviePage, "Derived Test Movie"))!!
        assertEquals(MediaType.MOVIE, movie.type)
        assertNull(movie.season)
        assertNull(movie.episode)
        assertNull(movie.seriesRemoteId)
        assertEquals(HdrType.HDR10, movie.hdrType)
        assertEquals("hevc", movie.videoCodec)
        assertEquals(3840, movie.width)
        assertEquals(10, movie.videoBitDepth)
        assertEquals("truehd", movie.audioCodec)
        assertEquals("Atmos", movie.audioFormat)
        assertEquals(8, movie.audioChannels)
        assertEquals("PG-13", movie.officialRating)
        assertEquals(13, movie.ratingScore)
        assertEquals("|Example Pictures|", movie.studios)
    }

    @Test
    fun `tracks keep audio, subtitles and fonts`() {
        val dto = Fixtures.decode<BaseItemDtoQueryResult>("items_subtitles_derived.json").items.single()
        val item = map(dto)!!
        val tracks = item.tracks
        assertEquals(listOf(AudioStream(1, "eng", isDefault = false), AudioStream(2, "jpn", isDefault = true)), tracks.audio)
        // The default track's language comes first.
        assertEquals("|jpn|eng|", item.audioLanguages)
        assertEquals(
            listOf(
                SubtitleStream(3, "eng", "Full", "ass", isDefault = true),
                SubtitleStream(4, "eng", "Signs", "ass", isForced = true),
                SubtitleStream(5, "spa", null, "pgssub"),
                SubtitleStream(6, "eng", null, "subrip", isExternal = true),
            ),
            tracks.subtitles,
        )
        assertEquals(listOf(FontAttachment(0, "ExampleSans.ttf"), FontAttachment(2, "ExampleSerif.otf")), tracks.fonts)
        assertTrue(tracks.hasAss)
        // Stored and read back unchanged.
        assertEquals(tracks, MediaTracks.decode(tracks.encode()))
    }

    @Test
    fun `pipe lists are clean and round-trip`() {
        assertEquals("", ItemMapper.pipeJoin(emptyList()))
        assertEquals("|A|B/C|", ItemMapper.pipeJoin(listOf(" A ", "B|C", "", "A")))
        assertEquals(listOf("A", "B/C"), ItemMapper.pipeSplit("|A|B/C|"))
        assertEquals(emptyList(), ItemMapper.pipeSplit(null))
    }

    @Test
    fun `audio badges`() {
        assertEquals("DTS:X", ItemMapper.audioFormat("dts", "DTS:X"))
        assertEquals("DTS-HD", ItemMapper.audioFormat("dts", "DTS-HD MA"))
        assertEquals("DTS", ItemMapper.audioFormat("DTS", null))
        assertEquals("Atmos", ItemMapper.audioFormat("eac3", "Dolby Digital Plus + Dolby Atmos"))
        assertEquals("DD+", ItemMapper.audioFormat("eac3", null))
        assertEquals("DD", ItemMapper.audioFormat("ac3", null))
        assertNull(ItemMapper.audioFormat(null, null))
    }
}
