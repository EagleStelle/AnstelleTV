package tv.anstelle.core.jellyfin.sync

import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.libraryApi
import org.jellyfin.sdk.api.client.extensions.localizationApi
import org.jellyfin.sdk.api.client.extensions.systemApi
import org.jellyfin.sdk.api.client.extensions.userViewApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.ItemFields
import org.jellyfin.sdk.model.api.ItemFilter
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.LocationType
import org.jellyfin.sdk.model.api.ParentalRating
import org.jellyfin.sdk.model.api.request.GetItemsRequest
import org.jellyfin.sdk.model.serializer.toUUID
import tv.anstelle.core.jellyfin.JellyfinClients
import tv.anstelle.core.jellyfin.jellyfinCall
import tv.anstelle.core.jellyfin.normalizeJellyfinId
import tv.anstelle.core.jellyfin.sdkDateTime
import tv.anstelle.core.jellyfin.toEpochMs
import tv.anstelle.core.jellyfin.toJellyfinId
import javax.inject.Inject
import javax.inject.Singleton

/** A Jellyfin library (collection folder) the user can see. */
data class RemoteLibrary(val id: String, val name: String, val collectionType: String?)

/** A box set (a Jellyfin "collection" of movies or shows) the user can see. */
data class RemoteBoxSet(val id: String, val name: String)

data class ItemPage(val items: List<BaseItemDto>, val totalCount: Int)

/** The server calls library sync needs. An interface so the sync logic can be tested offline. */
interface LibrarySource {
    /** The server's clock, so incremental sync never depends on this device's clock. */
    suspend fun serverTimeUtcMs(): Long

    suspend fun libraries(): List<RemoteLibrary>

    suspend fun parentalRatings(): List<ParentalRating>

    /**
     * One page of items below [parentId] (a library or a series), with the metadata the
     * guide and the scheduler use. [changedSinceUtcMs] limits it to items whose
     * `DateLastSaved` is at or after that server time.
     */
    suspend fun items(
        parentId: String,
        kinds: Set<BaseItemKind>,
        changedSinceUtcMs: Long?,
        startIndex: Int,
        limit: Int,
    ): ItemPage

    /** Ids of every item below [parentId], or of the played ones only. Cheap: no metadata. */
    suspend fun itemIds(parentId: String, kinds: Set<BaseItemKind>, playedOnly: Boolean): List<String>

    suspend fun boxSets(): List<RemoteBoxSet>

    /** Ids of the movies, shows and episodes in a box set. */
    suspend fun boxSetItemIds(boxSetId: String): List<String>
}

/** Creates a [LibrarySource] for a signed-in user on one server. */
@Singleton
class LibrarySourceFactory @Inject constructor(
    private val clients: JellyfinClients,
) {
    fun create(baseUrl: String, accessToken: String, userId: String): LibrarySource =
        JellyfinLibrarySource(clients.api(baseUrl, accessToken), userId)
}

internal class JellyfinLibrarySource(
    private val api: ApiClient,
    userId: String,
) : LibrarySource {
    private val user = userId.toUUID()

    override suspend fun serverTimeUtcMs(): Long = jellyfinCall("Reading the server clock") {
        api.systemApi.getUtcTime().content.requestReceptionTime.toEpochMs()
    }

    /**
     * The user's libraries. A grouped view (one "TV Shows" view over several libraries)
     * is not a library itself, so the underlying libraries come from the grouping options.
     */
    override suspend fun libraries(): List<RemoteLibrary> = jellyfinCall("Listing libraries") {
        val views = api.userViewApi.getUserViews(userId = user).content.items
        val libraries = linkedMapOf<String, RemoteLibrary>()
        for (view in views) {
            if (view.type != BaseItemKind.COLLECTION_FOLDER) continue
            val id = view.id.toJellyfinId()
            libraries[id] = RemoteLibrary(id, view.name.orEmpty(), view.collectionType?.serialName)
        }
        val grouped = views.any { it.type == BaseItemKind.USER_VIEW && it.collectionType != null }
        if (grouped) {
            for (option in api.userViewApi.getGroupingOptions(userId = user).content) {
                val id = option.id?.let(::normalizeJellyfinId) ?: continue
                if (id in libraries) continue
                val folder = api.libraryApi.getItem(itemId = id.toUUID(), userId = user).content
                libraries[id] = RemoteLibrary(id, folder.name ?: option.name.orEmpty(), folder.collectionType?.serialName)
            }
        }
        libraries.values.toList()
    }

    override suspend fun parentalRatings(): List<ParentalRating> = jellyfinCall("Reading parental ratings") {
        api.localizationApi.getParentalRatings().content
    }

    override suspend fun items(
        parentId: String,
        kinds: Set<BaseItemKind>,
        changedSinceUtcMs: Long?,
        startIndex: Int,
        limit: Int,
    ): ItemPage = jellyfinCall("Fetching items") {
        val result = api.libraryApi.getItems(
            GetItemsRequest(
                userId = user,
                parentId = parentId.toUUID(),
                recursive = true,
                includeItemTypes = kinds,
                excludeLocationTypes = listOf(LocationType.VIRTUAL),
                minDateLastSaved = changedSinceUtcMs?.let(::sdkDateTime),
                fields = ItemDetailFields,
                enableUserData = true,
                enableImages = true,
                // One of each: the poster, the first backdrop and the title logo.
                imageTypeLimit = 1,
                enableImageTypes = listOf(ImageType.PRIMARY, ImageType.BACKDROP, ImageType.LOGO),
                // A stable order keeps paging consistent while the library changes.
                sortBy = listOf(ItemSortBy.SORT_NAME),
                startIndex = startIndex,
                limit = limit,
                enableTotalRecordCount = true,
            ),
        ).content
        ItemPage(result.items, result.totalRecordCount)
    }

    override suspend fun itemIds(
        parentId: String,
        kinds: Set<BaseItemKind>,
        playedOnly: Boolean,
    ): List<String> = jellyfinCall("Listing item ids") {
        val ids = ArrayList<String>()
        var start = 0
        while (true) {
            val page = api.libraryApi.getItems(
                GetItemsRequest(
                    userId = user,
                    parentId = parentId.toUUID(),
                    recursive = true,
                    includeItemTypes = kinds,
                    excludeLocationTypes = listOf(LocationType.VIRTUAL),
                    filters = if (playedOnly) listOf(ItemFilter.IS_PLAYED) else null,
                    enableUserData = playedOnly,
                    enableImages = false,
                    sortBy = listOf(ItemSortBy.SORT_NAME),
                    startIndex = start,
                    limit = ID_PAGE_SIZE,
                    enableTotalRecordCount = false,
                ),
            ).content.items
            page.mapTo(ids) { it.id.toJellyfinId() }
            if (page.size < ID_PAGE_SIZE) break
            start += page.size
        }
        ids
    }

    override suspend fun boxSets(): List<RemoteBoxSet> = jellyfinCall("Listing collections") {
        api.libraryApi.getItems(
            GetItemsRequest(
                userId = user,
                recursive = true,
                includeItemTypes = listOf(BaseItemKind.BOX_SET),
                enableImages = false,
                sortBy = listOf(ItemSortBy.SORT_NAME),
                enableTotalRecordCount = false,
            ),
        ).content.items.map { RemoteBoxSet(it.id.toJellyfinId(), it.name.orEmpty()) }
    }

    override suspend fun boxSetItemIds(boxSetId: String): List<String> = jellyfinCall("Listing a collection") {
        // A box set's children are its linked items; no paging, box sets are small.
        api.libraryApi.getItems(
            GetItemsRequest(
                userId = user,
                parentId = boxSetId.toUUID(),
                enableImages = false,
                enableTotalRecordCount = false,
            ),
        ).content.items.map { it.id.toJellyfinId() }
    }

    private companion object {
        const val ID_PAGE_SIZE = 2_000

        val ItemDetailFields = listOf(
            ItemFields.GENRES,
            ItemFields.STUDIOS,
            ItemFields.TAGS,
            ItemFields.OVERVIEW,
            ItemFields.SORT_NAME,
            // The source id, container and streams. Streams are most of each
            // item's size (~8 KB), so pages stay small.
            ItemFields.MEDIA_SOURCES,
        )
    }
}
