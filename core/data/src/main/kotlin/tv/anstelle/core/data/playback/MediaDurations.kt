package tv.anstelle.core.data.playback

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import tv.anstelle.core.common.AppLog
import tv.anstelle.core.data.Settings
import tv.anstelle.core.data.SettingsRepository
import tv.anstelle.core.database.dao.MediaItemDao
import tv.anstelle.core.database.dao.MediaRuntimeRow
import tv.anstelle.core.database.dao.MediaServerDao
import tv.anstelle.core.database.entity.MediaItemEntity
import tv.anstelle.core.jellyfin.auth.TokenStore
import tv.anstelle.core.jellyfin.sync.FileDurationProbe
import javax.inject.Inject
import javax.inject.Singleton

/** Cached against the file identity and the bad runtime, so a changed source is checked again. */
@Serializable
internal data class DurationCorrection(val remoteId: String, val sourceId: String?, val reportedMs: Long, val measuredMs: Long) {
    fun matches(source: String?, durationMs: Long): Boolean =
        measuredMs > 0 && reportedMs > 0 && source == sourceId && (durationMs == reportedMs || durationMs == measuredMs)
}

/** Outliers trigger measurement; the surrounding episodes never supply a guessed replacement. */
internal object DurationOutliers {
    fun ids(rows: List<MediaRuntimeRow>): Set<Long> {
        val episodes = rows.filter { it.seriesRemoteId != null && (it.season ?: 0) > 0 && (it.episode ?: 0) > 0 && it.durationMs > 0 }
        val series = episodes.groupBy { it.serverId to it.seriesRemoteId }
        return episodes.groupBy { Triple(it.serverId, it.seriesRemoteId, it.season) }.values.flatMap { season ->
            val cohort = if (season.size >= 3) season else series.getValue(season.first().serverId to season.first().seriesRemoteId)
            if (cohort.size < 3) return@flatMap emptyList()
            val lengths = cohort.map { it.durationMs }.sorted()
            val middle = lengths.size / 2
            val median = if (lengths.size % 2 == 0) lengths[middle - 1] + (lengths[middle] - lengths[middle - 1]) / 2 else lengths[middle]
            season.filter { it.durationMs < median / 2 || it.durationMs / 2 > median }.map { it.id }
        }.toSet()
    }
}

/** Corrects broken runtimes before the scheduler loads its catalog and preserves measured lengths through sync. */
@Singleton
class MediaDurations @Inject constructor(
    private val items: MediaItemDao,
    private val servers: MediaServerDao,
    private val tokens: TokenStore,
    private val settings: SettingsRepository,
    private val files: FileDurationProbe,
) {
    private val gate = Mutex()

    private suspend fun saved(serverId: Long): MutableMap<String, DurationCorrection> {
        val raw = settings.get(Settings.fileDurations(serverId))
        return runCatching { Json.decodeFromString<List<DurationCorrection>>(raw) }
            .getOrDefault(emptyList()).associateByTo(LinkedHashMap()) { it.remoteId }
    }

    private suspend fun save(serverId: Long, corrections: Map<String, DurationCorrection>) {
        settings.set(Settings.fileDurations(serverId), Json.encodeToString(corrections.values.toList()))
    }

    /** Runs on existing libraries too: an app update does not require re-syncing every item. */
    suspend fun refresh() = gate.withLock {
        for ((serverId, rows) in items.runtimes().groupBy { it.serverId }) {
            val corrections = saved(serverId)
            val original = corrections.toMap()
            val live = rows.associateBy { it.remoteId }
            corrections.entries.removeAll { (id, correction) ->
                live[id]?.let { !correction.matches(it.mediaSourceId, it.durationMs) } ?: true
            }
            val effective = rows.map { row ->
                val correction = corrections[row.remoteId]
                if (correction != null && row.durationMs != correction.measuredMs) items.setDuration(row.id, correction.measuredMs)
                if (correction == null) row else row.copy(durationMs = correction.measuredMs)
            }
            val suspicious = DurationOutliers.ids(effective)
            val pending = effective.filter { it.id in suspicious && it.remoteId !in corrections }
            if (pending.isNotEmpty()) {
                val server = servers.byId(serverId)
                val token = server?.let { tokens.get(it.jellyfinServerId) }
                if (server != null && token != null) {
                    for (row in pending) {
                        val measured = files.durationMs(server.baseUrl, token, row.remoteId, row.mediaSourceId)?.takeIf { it > 0 } ?: continue
                        val duration = if (ProgramLength.differs(measured, row.durationMs)) measured else row.durationMs
                        corrections[row.remoteId] = DurationCorrection(row.remoteId, row.mediaSourceId, row.durationMs, duration)
                        // Persist each result before moving to another file or changing its catalog row.
                        save(serverId, corrections)
                        if (duration != row.durationMs) {
                            items.setDuration(row.id, duration)
                            AppLog.i("Duration", "Item ${row.id}: file samples corrected ${row.durationMs} ms to $duration ms")
                        }
                    }
                }
            }
            if (corrections != original) save(serverId, corrections)
        }
    }

    /** Sync may repeat a bad header runtime; retain the measurement until that runtime or file changes. */
    internal suspend fun preserve(incoming: List<MediaItemEntity>): List<MediaItemEntity> = gate.withLock { preserved(incoming) }

    /** Keep sync's metadata write under the same gate as measurement, including its file identity check. */
    suspend fun store(incoming: List<MediaItemEntity>) = gate.withLock {
        items.upsertAll(preserved(incoming))
    }

    private suspend fun preserved(incoming: List<MediaItemEntity>): List<MediaItemEntity> {
        val byServer = incoming.map { it.serverId }.distinct().associateWith { saved(it) }
        return incoming.map { item ->
            val correction = byServer[item.serverId]?.get(item.remoteId)
            if (correction?.matches(item.mediaSourceId, item.durationMs) == true) item.copy(durationMs = correction.measuredMs) else item
        }
    }

    /** A natural playback end supplies another trusted measurement, including for movies. */
    suspend fun remember(item: MediaItemEntity, durationMs: Long) = gate.withLock {
        if (durationMs <= 0) return@withLock
        val corrections = saved(item.serverId)
        val previous = corrections[item.remoteId]?.takeIf { it.matches(item.mediaSourceId, item.durationMs) }
        corrections[item.remoteId] = DurationCorrection(item.remoteId, item.mediaSourceId, previous?.reportedMs ?: item.durationMs, durationMs)
        save(item.serverId, corrections)
        items.setDuration(item.id, durationMs)
    }
}
