package tv.anstelle.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import tv.anstelle.core.database.entity.LibraryEntity
import tv.anstelle.core.database.entity.MediaItemEntity
import tv.anstelle.core.database.entity.MediaServerEntity
import tv.anstelle.core.model.MediaType

/** Local row id for a Jellyfin item id. */
data class ItemIdRow(val id: Long, val remoteId: String)

data class TypeCount(val type: MediaType, val count: Int)

/** Metadata episodes inherit from their series. */
data class SeriesMetadataRow(
    val remoteId: String,
    val genres: String,
    val studios: String,
    val tags: String,
    val collections: String,
    val officialRating: String?,
    val ratingScore: Int?,
)

/** What sync keeps from a stored item when the server sends it again. */
data class StoredItemRow(val id: Long, val remoteId: String, val collections: String)

/** An item's box-set membership, for reconciliation. */
data class ItemCollectionsRow(
    val id: Long,
    val remoteId: String,
    val seriesRemoteId: String?,
    val collections: String,
)

/** The metadata channel rules test, for one item. Loaded for the whole library at once. */
data class CatalogRow(
    val id: Long,
    val remoteId: String,
    val libraryRemoteId: String?,
    val type: MediaType,
    val title: String,
    val seriesRemoteId: String?,
    val showTitle: String?,
    val season: Int?,
    val episode: Int?,
    val durationMs: Long,
    val genres: String,
    val studios: String,
    val tags: String,
    val collections: String,
    val year: Int?,
    val officialRating: String?,
    val ratingScore: Int?,
)

/** A show or movie as the on-demand pages list it. */
data class OnDemandRow(
    val id: Long,
    val serverId: Long,
    val remoteId: String,
    val title: String,
    val year: Int?,
    val primaryImageTag: String?,
    /** The show's episodes; 1 for a movie. */
    val itemCount: Int,
)

@Dao
interface MediaServerDao {
    @Query("SELECT * FROM media_server ORDER BY name")
    fun observeAll(): Flow<List<MediaServerEntity>>

    @Query("SELECT * FROM media_server WHERE id = :id")
    fun observe(id: Long): Flow<MediaServerEntity?>

    @Query("SELECT * FROM media_server ORDER BY name")
    suspend fun all(): List<MediaServerEntity>

    @Query("SELECT * FROM media_server WHERE id = :id")
    suspend fun byId(id: Long): MediaServerEntity?

    @Query("SELECT * FROM media_server WHERE jellyfinServerId = :jellyfinServerId")
    suspend fun byJellyfinId(jellyfinServerId: String): MediaServerEntity?

    @Upsert
    suspend fun upsert(server: MediaServerEntity): Long

    @Query("UPDATE media_server SET name = :name, serverVersion = :version WHERE id = :id")
    suspend fun setNameAndVersion(id: Long, name: String, version: String?)

    @Query("UPDATE media_server SET lastSyncUtcMs = :atUtcMs, lastSyncError = :error WHERE id = :id")
    suspend fun setSyncResult(id: Long, atUtcMs: Long, error: String?)

    @Query("UPDATE media_server SET lastReconcileUtcMs = :atUtcMs WHERE id = :id")
    suspend fun setReconciled(id: Long, atUtcMs: Long)

    @Query("DELETE FROM media_server WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface LibraryDao {
    @Query("SELECT * FROM library WHERE serverId = :serverId ORDER BY name")
    fun observeForServer(serverId: Long): Flow<List<LibraryEntity>>

    @Query("SELECT * FROM library WHERE serverId = :serverId ORDER BY name")
    suspend fun forServer(serverId: Long): List<LibraryEntity>

    @Query("SELECT * FROM library WHERE id = :id")
    suspend fun byId(id: Long): LibraryEntity?

    @Upsert
    suspend fun upsert(library: LibraryEntity): Long

    @Query("UPDATE library SET lastSyncUtcMs = :watermarkUtcMs WHERE id = :id")
    suspend fun setWatermark(id: Long, watermarkUtcMs: Long?)

    @Query("UPDATE library SET lastSyncUtcMs = NULL WHERE serverId = :serverId")
    suspend fun clearWatermarks(serverId: Long)

    @Query("UPDATE library SET isEnabled = :enabled, lastSyncUtcMs = NULL WHERE id = :id")
    suspend fun setEnabled(id: Long, enabled: Boolean)

    @Query("DELETE FROM library WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface MediaItemDao {
    @Query("SELECT * FROM media_item WHERE id = :id")
    suspend fun byId(id: Long): MediaItemEntity?

    @Query("SELECT * FROM media_item WHERE id IN (:ids)")
    suspend fun byIds(ids: List<Long>): List<MediaItemEntity>

    @Query("SELECT * FROM media_item WHERE serverId = :serverId AND remoteId = :remoteId")
    suspend fun byRemoteId(serverId: Long, remoteId: String): MediaItemEntity?

    @Query("SELECT id, remoteId, collections FROM media_item WHERE serverId = :serverId AND remoteId IN (:remoteIds)")
    suspend fun storedFor(serverId: Long, remoteIds: List<String>): List<StoredItemRow>

    @Query("SELECT id, remoteId FROM media_item WHERE serverId = :serverId")
    suspend fun allIds(serverId: Long): List<ItemIdRow>

    @Query("SELECT COUNT(*) FROM media_item WHERE serverId = :serverId AND type = :type")
    suspend fun count(serverId: Long, type: MediaType): Int

    @Query("SELECT type, COUNT(*) AS count FROM media_item WHERE serverId = :serverId GROUP BY type ORDER BY type")
    fun observeTypeCounts(serverId: Long): Flow<List<TypeCount>>

    @Query("SELECT type, COUNT(*) AS count FROM media_item WHERE serverId = :serverId GROUP BY type ORDER BY type")
    suspend fun typeCounts(serverId: Long): List<TypeCount>

    @Query("SELECT COUNT(*) FROM media_item")
    fun observeTotalCount(): Flow<Int>

    @Query(
        """
        SELECT remoteId, genres, studios, tags, collections, officialRating, ratingScore
        FROM media_item WHERE serverId = :serverId AND type = :seriesType
        """,
    )
    suspend fun seriesMetadata(serverId: Long, seriesType: MediaType): List<SeriesMetadataRow>

    /** Every item of every server with the fields channel rules test. */
    @Query(
        """
        SELECT m.id, m.remoteId, l.remoteId AS libraryRemoteId, m.type, m.title, m.seriesRemoteId,
               m.showTitle, m.season, m.episode, m.durationMs, m.genres, m.studios, m.tags,
               m.collections, m.year, m.officialRating, m.ratingScore
        FROM media_item m LEFT JOIN library l ON l.id = m.libraryId
        ORDER BY m.id
        """,
    )
    suspend fun catalog(): List<CatalogRow>

    @Query("SELECT id, remoteId, seriesRemoteId, collections FROM media_item WHERE serverId = :serverId")
    suspend fun collectionRows(serverId: Long): List<ItemCollectionsRow>

    /**
     * Shows with at least one episode within [maxRating] (null: no limit; unrated episodes
     * never pass a limit), by sort title.
     */
    @Query(
        """
        SELECT s.id, s.serverId, s.remoteId, s.title, s.year, s.primaryImageTag, COUNT(e.id) AS itemCount
        FROM media_item s
        JOIN media_item e ON e.serverId = s.serverId AND e.seriesRemoteId = s.remoteId AND e.type = :episodeType
        WHERE s.type = :seriesType
          AND (:maxRating IS NULL OR (e.ratingScore IS NOT NULL AND e.ratingScore <= :maxRating))
        GROUP BY s.id
        ORDER BY COALESCE(s.sortTitle, s.title) COLLATE NOCASE
        """,
    )
    fun observeShows(maxRating: Int?, seriesType: MediaType, episodeType: MediaType): Flow<List<OnDemandRow>>

    /** Items of [type] within [maxRating], by sort title. */
    @Query(
        """
        SELECT id, serverId, remoteId, title, year, primaryImageTag, 1 AS itemCount
        FROM media_item
        WHERE type = :type AND (:maxRating IS NULL OR (ratingScore IS NOT NULL AND ratingScore <= :maxRating))
        ORDER BY COALESCE(sortTitle, title) COLLATE NOCASE
        """,
    )
    fun observeOfType(type: MediaType, maxRating: Int?): Flow<List<OnDemandRow>>

    /** A show's episodes within [maxRating], in airing order: seasons, then specials, then unnumbered ones. */
    @Query(
        """
        SELECT * FROM media_item
        WHERE serverId = :serverId AND seriesRemoteId = :seriesRemoteId AND type = :episodeType
          AND (:maxRating IS NULL OR (ratingScore IS NOT NULL AND ratingScore <= :maxRating))
        ORDER BY season IS NULL, season = 0, season, episode IS NULL, episode, COALESCE(sortTitle, title)
        """,
    )
    suspend fun episodes(serverId: Long, seriesRemoteId: String, episodeType: MediaType, maxRating: Int?): List<MediaItemEntity>

    @Query("UPDATE media_item SET collections = :collections WHERE id = :id")
    suspend fun setCollections(id: Long, collections: String)

    /** Inserts new items (id 0) and updates existing ones in place, so local ids stay stable. */
    @Upsert
    suspend fun upsertAll(items: List<MediaItemEntity>)

    @Query("DELETE FROM media_item WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    @Query("SELECT id FROM media_item WHERE libraryId = :libraryId")
    suspend fun idsInLibrary(libraryId: Long): List<Long>

    @Query("UPDATE media_item SET playedOnServer = 0 WHERE serverId = :serverId")
    suspend fun clearPlayed(serverId: Long)

    @Query("UPDATE media_item SET playedOnServer = 1 WHERE serverId = :serverId AND remoteId IN (:remoteIds)")
    suspend fun markPlayed(serverId: Long, remoteIds: List<String>)

    @Query("UPDATE media_item SET playedOnServer = :played WHERE id = :id")
    suspend fun setPlayedOnServer(id: Long, played: Boolean)
}
