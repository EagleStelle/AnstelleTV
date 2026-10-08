package tv.anstelle.core.data

import tv.anstelle.core.database.entity.MediaItemEntity
import tv.anstelle.core.model.HdrType
import tv.anstelle.core.model.MediaTracks
import tv.anstelle.core.model.MediaType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ArtworkTest {
    private val base = "http://jellyfin.local:8096/"

    private fun entity(remoteId: String, type: MediaType, backdrop: String? = null, logo: String? = null) = MediaItemEntity(
        serverId = 1, libraryId = 1, remoteId = remoteId, type = type, title = remoteId, sortTitle = null,
        seriesRemoteId = null, showTitle = null, season = null, episode = null, durationMs = 0, genres = "", studios = "",
        tags = "", collections = "", year = null, premiereDateUtcMs = null, officialRating = null, ratingScore = null,
        overview = null, communityRating = null, hdrType = HdrType.SDR, audioFormat = null, videoCodec = null,
        audioCodec = null, container = null, width = null, height = null, videoBitDepth = null, audioChannels = null,
        mediaSourceId = null, tracks = MediaTracks(emptyList(), emptyList(), emptyList()), audioLanguages = "",
        primaryImageTag = null, backdropImageTag = backdrop, logoImageTag = logo, updatedAtUtcMs = 0,
    )

    @Test
    fun `an episode uses its series' backdrop and logo`() {
        val series = entity("show", MediaType.SERIES, backdrop = "b1", logo = "l1")
        val art = Artwork.of(base, entity("ep", MediaType.EPISODE), series)
        assertEquals("http://jellyfin.local:8096/Items/show/Images/Backdrop/0?tag=b1&maxWidth=1280&quality=90", art.backdropUrl)
        assertEquals("http://jellyfin.local:8096/Items/show/Images/Logo?tag=l1&maxWidth=640&quality=90", art.logoUrl)
    }

    @Test
    fun `an item's own artwork wins, one kind at a time`() {
        val series = entity("show", MediaType.SERIES, backdrop = "b1", logo = "l1")
        val art = Artwork.of(base, entity("ep", MediaType.EPISODE, backdrop = "b2"), series)
        assertEquals("http://jellyfin.local:8096/Items/ep/Images/Backdrop/0?tag=b2&maxWidth=1280&quality=90", art.backdropUrl)
        assertEquals("http://jellyfin.local:8096/Items/show/Images/Logo?tag=l1&maxWidth=640&quality=90", art.logoUrl)
    }

    @Test
    fun `missing artwork or server gives none`() {
        assertEquals(Artwork.None, Artwork.of(base, entity("movie", MediaType.MOVIE), null))
        assertEquals(Artwork.None, Artwork.of(null, entity("movie", MediaType.MOVIE, backdrop = "b", logo = "l"), null))
        assertNull(jellyfinImageUrl(base, "movie", "Logo", tag = null))
    }
}
