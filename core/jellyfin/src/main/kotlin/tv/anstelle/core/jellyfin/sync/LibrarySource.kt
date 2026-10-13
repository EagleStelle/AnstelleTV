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

    /** Ids of every item below [parentId]. Cheap: no metadata. */
    suspend fun itemIds(parentId: String, kinds: Set<BaseItemKind>): List<String>
}

/** Creates a [LibrarySource] for a signed-in user on one server. */
@Singleton
class LibrarySourceFactory @Inject constructor(
    private val clients: JellyfinClients,
) {
    /** [device] is a profile's own device when the user is a profile's (see [JellyfinClients.api]). */
    fun create(baseUrl: String, accessToken: String, userId: String, device: String? = null): LibrarySource =
        JellyfinLibrarySource(clients.api(baseUrl, accessToken, device), userId)
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
            val type = view.collectionType?.serialName
            if (!LibraryRules.isLibrary(type)) continue
            val id = view.id.toJellyfinId()
            libraries[id] = RemoteLibrary(id, view.name.orEmpty(), type)
        }
        val grouped = views.any { it.type == BaseItemKind.USER_VIEW && it.collectionType != null }
        if (grouped) {
            for (option in api.userViewApi.getGroupingOptions(userId = user).content) {
                val id = option.id?.let(::normalizeJellyfinId) ?: continue
                if (id in libraries) continue
                val folder = api.libraryApi.getItem(itemId = id.toUUID(), userId = user).content
                val type = folder.collectionType?.serialName
                if (!LibraryRules.isLibrary(type)) continue
                libraries[id] = RemoteLibrary(id, folder.name ?: option.name.orEmpty(), type)
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
                enableUserData = false,
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

    override suspend fun itemIds(parentId: String, kinds: Set<BaseItemKind>): List<String> = jellyfinCall("Listing item ids") {
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
                    enableUserData = false,
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

    private companion object {
        const val ID_PAGE_SIZE = 2_000

        val ItemDetailFields = listOf(
            ItemFields.GENRES,
            ItemFields.STUDIOS,
            ItemFields.TAGS,
            ItemFields.OVERVIEW,
            ItemFields.SORT_NAME,
            // When it joined the library, for On Demand's Recently added.
            ItemFields.DATE_CREATED,
            // The source id, container and streams. Streams are most of each
            // item's size (~8 KB), so pages stay small.
            ItemFields.MEDIA_SOURCES,
        )
    }
}
