package tv.anstelle.core.jellyfin.sync

import org.jellyfin.sdk.model.api.BaseItemKind
import tv.anstelle.core.model.MediaType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LibraryRulesTest {
    @Test
    fun `item kinds per library type`() {
        assertEquals(setOf(BaseItemKind.MOVIE), LibraryRules.itemKinds("movies"))
        assertEquals(setOf(BaseItemKind.SERIES, BaseItemKind.EPISODE), LibraryRules.itemKinds("tvshows"))
        assertEquals(setOf(BaseItemKind.MUSIC_VIDEO), LibraryRules.itemKinds("musicvideos"))
        assertTrue(BaseItemKind.SERIES in LibraryRules.itemKinds(null))
        assertTrue(BaseItemKind.VIDEO in LibraryRules.itemKinds("homevideos"))
        // Music is on hold, and books, photos and collections have nothing to air.
        assertTrue(LibraryRules.itemKinds("music").isEmpty())
        assertTrue(LibraryRules.itemKinds("boxsets").isEmpty())
    }

    @Test
    fun `media types`() {
        assertEquals(MediaType.MOVIE, LibraryRules.mediaType(BaseItemKind.VIDEO))
        assertEquals(MediaType.MOVIE, LibraryRules.mediaType(BaseItemKind.MOVIE))
        assertEquals(MediaType.EPISODE, LibraryRules.mediaType(BaseItemKind.EPISODE))
        assertEquals(MediaType.SERIES, LibraryRules.mediaType(BaseItemKind.SERIES))
        assertEquals(MediaType.MUSIC_VIDEO, LibraryRules.mediaType(BaseItemKind.MUSIC_VIDEO))
        assertNull(LibraryRules.mediaType(BaseItemKind.AUDIO))
    }
}
