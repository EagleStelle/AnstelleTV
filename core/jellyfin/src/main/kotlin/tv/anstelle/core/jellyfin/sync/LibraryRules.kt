package tv.anstelle.core.jellyfin.sync

import org.jellyfin.sdk.model.api.BaseItemKind
import tv.anstelle.core.model.MediaType

/** Which libraries and items AnstelleTV syncs, and what each item is used for. */
object LibraryRules {
    /** Item kinds that can air: anything with a video stream. */
    private val Playable = setOf(BaseItemKind.MOVIE, BaseItemKind.EPISODE, BaseItemKind.VIDEO, BaseItemKind.MUSIC_VIDEO)

    /** Libraries that hold nothing to air. Music is on hold until music channels are built. */
    private val NotSynced = setOf("music", "books", "photos", "boxsets", "playlists", "livetv", "trailers")

    /** Item kinds to fetch from a library of [collectionType]; empty when the library is not synced. */
    fun itemKinds(collectionType: String?): Set<BaseItemKind> = when (collectionType) {
        "movies" -> setOf(BaseItemKind.MOVIE)
        "tvshows" -> setOf(BaseItemKind.SERIES, BaseItemKind.EPISODE)
        "musicvideos" -> setOf(BaseItemKind.MUSIC_VIDEO)
        "homevideos" -> setOf(BaseItemKind.VIDEO, BaseItemKind.MOVIE)
        in NotSynced -> emptySet()
        // Mixed-content libraries ("folders", or no type at all) can hold anything.
        else -> Playable + BaseItemKind.SERIES
    }

    /** What a synced item is used for, or null when it should not be stored. */
    fun mediaType(kind: BaseItemKind): MediaType? = when (kind) {
        BaseItemKind.MOVIE, BaseItemKind.VIDEO -> MediaType.MOVIE
        BaseItemKind.EPISODE -> MediaType.EPISODE
        BaseItemKind.SERIES -> MediaType.SERIES
        BaseItemKind.MUSIC_VIDEO -> MediaType.MUSIC_VIDEO
        else -> null
    }
}
