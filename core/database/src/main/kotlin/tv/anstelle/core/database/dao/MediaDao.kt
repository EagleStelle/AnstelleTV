package tv.anstelle.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import tv.anstelle.core.database.entity.LibraryEntity
import tv.anstelle.core.database.entity.MediaItemEntity
import tv.anstelle.core.database.entity.MediaSegmentEntity
import tv.anstelle.core.database.entity.MediaServerEntity
import tv.anstelle.core.model.LibraryRole
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
    val officialRating: String?,
    val ratingScore: Int?,
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

    @Query("UPDATE library SET role = :role, lastSyncUtcMs = NULL WHERE id = :id")
    suspend fun setRole(id: Long, role: LibraryRole)

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
    suspend fun idsFor(serverId: Long, remoteIds: List<String>): List<ItemIdRow>

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
        SELECT remoteId, genres, studios, tags, officialRating, ratingScore
        FROM media_item WHERE serverId = :serverId AND type = :seriesType
        """,
    )
    suspend fun seriesMetadata(serverId: Long, seriesType: MediaType): List<SeriesMetadataRow>

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

    @Query("SELECT id, remoteId FROM media_item WHERE serverId = :serverId AND segmentsFetched = 0 ORDER BY id")
    suspend fun withoutSegments(serverId: Long): List<ItemIdRow>

    @Query("UPDATE media_item SET segmentsFetched = 1 WHERE id IN (:ids)")
    suspend fun markSegmentsFetched(ids: List<Long>)

    @Query("SELECT * FROM media_segment WHERE mediaItemId = :mediaItemId ORDER BY startMs")
    suspend fun segments(mediaItemId: Long): List<MediaSegmentEntity>

    @Query("DELETE FROM media_segment WHERE mediaItemId IN (:mediaItemIds)")
    suspend fun deleteSegments(mediaItemIds: List<Long>)

    @Insert
    suspend fun insertSegments(segments: List<MediaSegmentEntity>)

    /** Replaces the cached segments of [mediaItemIds] and marks them fetched. */
    @Transaction
    suspend fun replaceSegments(mediaItemIds: List<Long>, segments: List<MediaSegmentEntity>) {
        deleteSegments(mediaItemIds)
        insertSegments(segments)
        markSegmentsFetched(mediaItemIds)
    }
}
