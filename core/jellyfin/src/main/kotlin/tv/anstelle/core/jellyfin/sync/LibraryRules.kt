package tv.anstelle.core.jellyfin.sync

import org.jellyfin.sdk.model.api.BaseItemKind
import tv.anstelle.core.model.LibraryRole
import tv.anstelle.core.model.MediaType

/** Which libraries and items AnstelleTV syncs, and what each item is used for. */
object LibraryRules {
    /** Item kinds that can air: anything with a video stream. */
    private val Playable = setOf(BaseItemKind.MOVIE, BaseItemKind.EPISODE, BaseItemKind.VIDEO, BaseItemKind.MUSIC_VIDEO)

    /** Libraries that hold nothing to air. Music is on hold until music channels are built. */
    private val NotSynced = setOf("music", "books", "photos", "boxsets", "playlists", "livetv", "trailers")

    /**
     * Role for a newly found library. Libraries named like filler ("Commercials",
     * "Bumpers", "Station IDs") become filler sources; everything else with video is content.
     */
    fun defaultRole(name: String, collectionType: String?): LibraryRole {
        if (collectionType in NotSynced) return LibraryRole.IGNORED
        val lower = name.lowercase()
        val words = lower.split(NonWord).filter { it.isNotEmpty() }
        return when {
            words.any { it.startsWith("commercial") || it.startsWith("advert") || it == "ads" } -> LibraryRole.COMMERCIALS
            words.any { it.startsWith("bumper") } -> LibraryRole.BUMPERS
            "station id" in lower || words.any { it == "ident" || it == "idents" } -> LibraryRole.STATION_IDS
            else -> LibraryRole.CONTENT
        }
    }

    /** Item kinds to fetch from a library; empty when the library is not synced. */
    fun itemKinds(role: LibraryRole, collectionType: String?): Set<BaseItemKind> = when (role) {
        LibraryRole.IGNORED -> emptySet()
        LibraryRole.COMMERCIALS, LibraryRole.BUMPERS, LibraryRole.STATION_IDS ->
            if (collectionType in NotSynced) emptySet() else Playable
        LibraryRole.CONTENT -> when (collectionType) {
            "movies" -> setOf(BaseItemKind.MOVIE)
            "tvshows" -> setOf(BaseItemKind.SERIES, BaseItemKind.EPISODE)
            "musicvideos" -> setOf(BaseItemKind.MUSIC_VIDEO)
            "homevideos" -> setOf(BaseItemKind.VIDEO, BaseItemKind.MOVIE)
            in NotSynced -> emptySet()
            // Mixed-content libraries ("folders", or no type at all) can hold anything.
            else -> Playable + BaseItemKind.SERIES
        }
    }

    /**
     * What a synced item is used for, or null when it should not be stored. Items in a
     * filler library are filler; in a content library, an item tagged as filler
     * ([fillerKindForTags]) is filler too.
     */
    fun mediaType(kind: BaseItemKind, role: LibraryRole, tags: Collection<String>): MediaType? {
        val filler = when (role) {
            LibraryRole.IGNORED -> return null
            LibraryRole.COMMERCIALS -> MediaType.COMMERCIAL
            LibraryRole.BUMPERS -> MediaType.BUMPER
            LibraryRole.STATION_IDS -> MediaType.STATION_ID
            LibraryRole.CONTENT -> if (kind in Playable) fillerKindForTags(tags) else null
        }
        if (filler != null) return if (kind in Playable) filler else null
        return when (kind) {
            BaseItemKind.MOVIE, BaseItemKind.VIDEO -> MediaType.MOVIE
            BaseItemKind.EPISODE -> MediaType.EPISODE
            BaseItemKind.SERIES -> MediaType.SERIES
            BaseItemKind.MUSIC_VIDEO -> MediaType.MUSIC_VIDEO
            else -> null
        }
    }

    /** Tags (case-insensitive, whole tag) that mark an item as filler. */
    fun fillerKindForTags(tags: Collection<String>): MediaType? {
        for (tag in tags) {
            FillerTags[tag.trim().lowercase().removePrefix(TAG_PREFIX)]?.let { return it }
        }
        return null
    }

    private const val TAG_PREFIX = "anstelle:"
    private val NonWord = Regex("[^a-z0-9]+")
    private val FillerTags = mapOf(
        "commercial" to MediaType.COMMERCIAL,
        "commercials" to MediaType.COMMERCIAL,
        "advert" to MediaType.COMMERCIAL,
        "advertisement" to MediaType.COMMERCIAL,
        "bumper" to MediaType.BUMPER,
        "bumpers" to MediaType.BUMPER,
        "station id" to MediaType.STATION_ID,
        "station ids" to MediaType.STATION_ID,
        "station_id" to MediaType.STATION_ID,
        "ident" to MediaType.STATION_ID,
        "card" to MediaType.CARD,
        "interstitial" to MediaType.CARD,
    )
}
