package tv.anstelle.core.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import tv.anstelle.core.model.WatchSource

/** A guide theme stored as JSON. Built-in themes are re-seeded from assets by [builtInKey]. */
@Entity(
    tableName = "theme",
    indices = [Index(value = ["builtInKey"], unique = true)],
)
data class ThemeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val json: String,
    val isBuiltIn: Boolean,
    val builtInKey: String?,
    val updatedAtUtcMs: Long,
)

@Entity(
    tableName = "watch_event",
    foreignKeys = [
        ForeignKey(
            entity = ProfileEntity::class,
            parentColumns = ["id"],
            childColumns = ["profileId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["profileId", "startUtcMs"]),
        Index(value = ["channelId"]),
    ],
)
data class WatchEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val profileId: Long,
    val source: WatchSource,
    val channelId: Long?,
    val liveChannelId: Long?,
    val mediaItemId: Long?,
    /** Denormalized so stats survive library changes. */
    val title: String,
    val showTitle: String?,
    val startUtcMs: Long,
    val durationMs: Long,
)

/** A browser paired with the web UI. Only the SHA-256 of the token is stored. */
@Entity(
    tableName = "paired_client",
    indices = [Index(value = ["tokenHash"], unique = true)],
)
data class PairedClientEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tokenHash: String,
    val name: String,
    val userAgent: String?,
    val lastAddress: String?,
    val createdAtUtcMs: Long,
    val lastSeenUtcMs: Long,
)

@Entity(tableName = "setting")
data class SettingEntity(
    @PrimaryKey val key: String,
    val value: String,
)
