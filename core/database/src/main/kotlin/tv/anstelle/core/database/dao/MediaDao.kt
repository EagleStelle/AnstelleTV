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

/** An official rating as items carry it, its score, and how many items carry it. */
data class RatingCount(val name: String, val score: Int, val count: Int)

/** Metadata episodes inherit from their series. */
data class SeriesMetadataRow(
    val remoteId: String,
    val genres: String,
    val studios: String,
    val tags: String,
    val officialRating: String?,
    val ratingScore: Int?,
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
    val year: Int?,
    val officialRating: String?,
    val ratingScore: Int?,
    /** `|jpn|eng|`, the default audio track's language first. */
    val audioLanguages: String,
    val premiereDateUtcMs: Long? = null,
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

    @Upsert
    suspend fun upsert(library: LibraryEntity): Long

    @Query("UPDATE library SET lastSyncUtcMs = :watermarkUtcMs WHERE id = :id")
    suspend fun setWatermark(id: Long, watermarkUtcMs: Long?)

    @Query("UPDATE library SET lastSyncUtcMs = NULL WHERE serverId = :serverId")
    suspend fun clearWatermarks(serverId: Long)

    @Query("UPDATE library SET isEnabled = :enabled, lastSyncUtcMs = NULL WHERE id = :id")
    suspend fun setEnabled(id: Long, enabled: Boolean)

    /** Libraries turned on that haven't finished a full fetch yet: new, turned on again, or just restored. */
    @Query("SELECT * FROM library WHERE isEnabled = 1 AND lastSyncUtcMs IS NULL")
    suspend fun unsynced(): List<LibraryEntity>

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

    @Query("SELECT id, remoteId FROM media_item WHERE serverId = :serverId AND remoteId IN (:remoteIds)")
    suspend fun storedFor(serverId: Long, remoteIds: List<String>): List<ItemIdRow>

    @Query("SELECT id, remoteId FROM media_item WHERE serverId = :serverId")
    suspend fun allIds(serverId: Long): List<ItemIdRow>

    @Query("SELECT COUNT(*) FROM media_item WHERE serverId = :serverId AND type = :type")
    suspend fun count(serverId: Long, type: MediaType): Int

    @Query("SELECT type, COUNT(*) AS count FROM media_item WHERE serverId = :serverId GROUP BY type ORDER BY type")
    fun observeTypeCounts(serverId: Long): Flow<List<TypeCount>>

    @Query("SELECT type, COUNT(*) AS count FROM media_item WHERE serverId = :serverId GROUP BY type ORDER BY type")
    suspend fun typeCounts(serverId: Long): List<TypeCount>

    @Query(
        """
        SELECT officialRating AS name, ratingScore AS score, COUNT(*) AS count FROM media_item
        WHERE officialRating IS NOT NULL AND ratingScore IS NOT NULL
        GROUP BY officialRating, ratingScore
        """,
    )
    suspend fun ratingCounts(): List<RatingCount>

    @Query("SELECT COUNT(*) FROM media_item")
    fun observeTotalCount(): Flow<Int>

    @Query(
        """
        SELECT remoteId, genres, studios, tags, officialRating, ratingScore
        FROM media_item WHERE serverId = :serverId AND type = :seriesType
        """,
    )
    suspend fun seriesMetadata(serverId: Long, seriesType: MediaType): List<SeriesMetadataRow>

    /** Every item of every server with the fields channel rules test. */
    @Query(
        """
        SELECT m.id, m.remoteId, l.remoteId AS libraryRemoteId, m.type, m.title, m.seriesRemoteId,
               m.showTitle, m.season, m.episode, m.durationMs, m.genres, m.studios, m.tags,
               m.year, m.officialRating, m.ratingScore, m.audioLanguages, m.premiereDateUtcMs
        FROM media_item m LEFT JOIN library l ON l.id = m.libraryId
        ORDER BY m.id
        """,
    )
    suspend fun catalog(): List<CatalogRow>

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

    /** Inserts new items (id 0) and updates existing ones in place, so local ids stay stable. */
    @Upsert
    suspend fun upsertAll(items: List<MediaItemEntity>)

    @Query("DELETE FROM media_item WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    @Query("SELECT id FROM media_item WHERE libraryId = :libraryId")
    suspend fun idsInLibrary(libraryId: Long): List<Long>
}
