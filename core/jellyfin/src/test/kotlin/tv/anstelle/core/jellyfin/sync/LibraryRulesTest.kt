package tv.anstelle.core.jellyfin.sync

import org.jellyfin.sdk.model.api.BaseItemKind
import tv.anstelle.core.model.LibraryRole
import tv.anstelle.core.model.MediaType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LibraryRulesTest {
    @Test
    fun `default roles`() {
        assertEquals(LibraryRole.CONTENT, LibraryRules.defaultRole("Anime", "tvshows"))
        assertEquals(LibraryRole.CONTENT, LibraryRules.defaultRole("Movies", "movies"))
        assertEquals(LibraryRole.CONTENT, LibraryRules.defaultRole("Stuff", null))
        assertEquals(LibraryRole.COMMERCIALS, LibraryRules.defaultRole("80s Commercials", "homevideos"))
        assertEquals(LibraryRole.COMMERCIALS, LibraryRules.defaultRole("TV Ads", null))
        assertEquals(LibraryRole.BUMPERS, LibraryRules.defaultRole("Toonami Bumpers", "musicvideos"))
        assertEquals(LibraryRole.STATION_IDS, LibraryRules.defaultRole("Station IDs", null))
        // Music is on hold, and books, photos and collections have nothing to air.
        assertEquals(LibraryRole.IGNORED, LibraryRules.defaultRole("Music", "music"))
        assertEquals(LibraryRole.IGNORED, LibraryRules.defaultRole("Collections", "boxsets"))
        assertEquals(LibraryRole.IGNORED, LibraryRules.defaultRole("Commercial Jingles", "music"))
        // "Broadcast" contains "ad" but is not a filler name.
        assertEquals(LibraryRole.CONTENT, LibraryRules.defaultRole("Broadcast Classics", "tvshows"))
    }

    @Test
    fun `item kinds per library type`() {
        assertEquals(setOf(BaseItemKind.MOVIE), LibraryRules.itemKinds(LibraryRole.CONTENT, "movies"))
        assertEquals(setOf(BaseItemKind.SERIES, BaseItemKind.EPISODE), LibraryRules.itemKinds(LibraryRole.CONTENT, "tvshows"))
        assertEquals(setOf(BaseItemKind.MUSIC_VIDEO), LibraryRules.itemKinds(LibraryRole.CONTENT, "musicvideos"))
        assertTrue(BaseItemKind.SERIES in LibraryRules.itemKinds(LibraryRole.CONTENT, null))
        assertTrue(BaseItemKind.VIDEO in LibraryRules.itemKinds(LibraryRole.CONTENT, "homevideos"))
        assertTrue(LibraryRules.itemKinds(LibraryRole.CONTENT, "music").isEmpty())
        assertTrue(LibraryRules.itemKinds(LibraryRole.IGNORED, "movies").isEmpty())
        assertTrue(BaseItemKind.SERIES !in LibraryRules.itemKinds(LibraryRole.COMMERCIALS, "tvshows"))
    }

    @Test
    fun `media types`() {
        assertEquals(MediaType.MOVIE, LibraryRules.mediaType(BaseItemKind.VIDEO, LibraryRole.CONTENT, emptyList()))
        assertEquals(MediaType.SERIES, LibraryRules.mediaType(BaseItemKind.SERIES, LibraryRole.CONTENT, listOf("commercial")))
        assertEquals(MediaType.STATION_ID, LibraryRules.mediaType(BaseItemKind.MOVIE, LibraryRole.STATION_IDS, emptyList()))
        assertEquals(MediaType.CARD, LibraryRules.mediaType(BaseItemKind.VIDEO, LibraryRole.CONTENT, listOf("Anstelle:Card")))
        assertNull(LibraryRules.mediaType(BaseItemKind.AUDIO, LibraryRole.CONTENT, emptyList()))
    }

    @Test
    fun `filler tags match whole tags only`() {
        assertEquals(MediaType.COMMERCIAL, LibraryRules.fillerKindForTags(listOf("anime", " Commercial ")))
        assertEquals(MediaType.BUMPER, LibraryRules.fillerKindForTags(listOf("Bumpers")))
        assertEquals(MediaType.STATION_ID, LibraryRules.fillerKindForTags(listOf("station id")))
        assertNull(LibraryRules.fillerKindForTags(listOf("card game", "commercial break drama")))
    }
}
