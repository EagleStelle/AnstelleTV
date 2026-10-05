package tv.anstelle.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import tv.anstelle.core.database.entity.LibraryEntity
import tv.anstelle.core.database.entity.MediaItemEntity
import tv.anstelle.core.database.entity.MediaSegmentEntity
import tv.anstelle.core.database.entity.MediaServerEntity
import tv.anstelle.core.model.MediaType

@Dao
interface MediaServerDao {
    @Query("SELECT * FROM media_server ORDER BY name")
    fun observeAll(): Flow<List<MediaServerEntity>>

    @Query("SELECT * FROM media_server ORDER BY name")
    suspend fun all(): List<MediaServerEntity>

    @Query("SELECT * FROM media_server WHERE id = :id")
    suspend fun byId(id: Long): MediaServerEntity?

    @Query("SELECT * FROM media_server WHERE jellyfinServerId = :jellyfinServerId")
    suspend fun byJellyfinId(jellyfinServerId: String): MediaServerEntity?

    @Upsert
    suspend fun upsert(server: MediaServerEntity): Long

    @Query("DELETE FROM media_server WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface LibraryDao {
    @Query("SELECT * FROM library WHERE serverId = :serverId ORDER BY name")
    fun observeForServer(serverId: Long): Flow<List<LibraryEntity>>

    @Query("SELECT * FROM library WHERE serverId = :serverId")
    suspend fun forServer(serverId: Long): List<LibraryEntity>

    @Upsert
    suspend fun upsert(library: LibraryEntity): Long
}

@Dao
interface MediaItemDao {
    @Query("SELECT * FROM media_item WHERE id = :id")
    suspend fun byId(id: Long): MediaItemEntity?

    @Query("SELECT * FROM media_item WHERE id IN (:ids)")
    suspend fun byIds(ids: List<Long>): List<MediaItemEntity>

    @Query("SELECT * FROM media_item WHERE serverId = :serverId AND remoteId = :remoteId")
    suspend fun byRemoteId(serverId: Long, remoteId: String): MediaItemEntity?

    @Query("SELECT COUNT(*) FROM media_item WHERE serverId = :serverId AND type = :type")
    suspend fun count(serverId: Long, type: MediaType): Int

    @Query("SELECT COUNT(*) FROM media_item")
    fun observeTotalCount(): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<MediaItemEntity>)

    @Query("DELETE FROM media_item WHERE serverId = :serverId AND remoteId IN (:remoteIds)")
    suspend fun deleteByRemoteIds(serverId: Long, remoteIds: List<String>)

    @Query("SELECT * FROM media_segment WHERE mediaItemId = :mediaItemId ORDER BY startMs")
    suspend fun segments(mediaItemId: Long): List<MediaSegmentEntity>

    @Query("DELETE FROM media_segment WHERE mediaItemId = :mediaItemId")
    suspend fun deleteSegments(mediaItemId: Long)

    @Insert
    suspend fun insertSegments(segments: List<MediaSegmentEntity>)
}
