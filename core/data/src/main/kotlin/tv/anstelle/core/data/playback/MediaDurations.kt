package tv.anstelle.core.data.playback

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import kotlinx.coroutines.NonCancellable
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import tv.anstelle.core.common.AppLog
import tv.anstelle.core.common.TimeSource
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

@Serializable
internal data class DurationAttempt(val remoteId: String, val sourceId: String?, val reportedMs: Long, val retryUtcMs: Long)

data class DurationRefresh(val changed: Set<Long>, val hasMore: Boolean)

/** Local corrections never wait on remote probes. Only background maintenance measures files. */
@Singleton
class MediaDurations @Inject constructor(
    private val items: MediaItemDao,
    private val servers: MediaServerDao,
    private val tokens: TokenStore,
    private val settings: SettingsRepository,
    private val files: FileDurationProbe,
    private val time: TimeSource = TimeSource.System,
) {
    private val gate = Mutex()
    private val probes = Mutex()

    private suspend fun saved(serverId: Long): MutableMap<String, DurationCorrection> {
        val raw = settings.get(Settings.fileDurations(serverId))
        return runCatching { Json.decodeFromString<List<DurationCorrection>>(raw) }
            .getOrDefault(emptyList()).associateByTo(LinkedHashMap()) { it.remoteId }
    }

    private suspend fun save(serverId: Long, corrections: Map<String, DurationCorrection>) {
        settings.set(Settings.fileDurations(serverId), Json.encodeToString(corrections.values.toList()))
    }

    /** Reapply cached measurements without contacting any server. */
    suspend fun applySaved() = gate.withLock {
        for ((serverId, rows) in items.runtimes().groupBy { it.serverId }) {
            val corrections = saved(serverId)
            val original = corrections.toMap()
            val live = rows.associateBy { it.remoteId }
            corrections.entries.removeAll { (id, correction) ->
                live[id]?.let { !correction.matches(it.mediaSourceId, it.durationMs) } ?: true
            }
            rows.forEach { row ->
                val correction = corrections[row.remoteId]
                if (correction != null && row.durationMs != correction.measuredMs) items.setDuration(row.id, correction.measuredMs)
            }
            if (corrections != original) save(serverId, corrections)
        }
    }

    /** One bounded pass; no network I/O holds the metadata gate or scheduler mutex. */
    suspend fun refresh(
        budgetMs: Long = 20_000,
        onChanged: suspend (Set<Long>) -> Unit = {},
    ): DurationRefresh = probes.withLock {
        val deadline = System.nanoTime() / 1_000_000 + budgetMs
        applySaved()
        val changed = LinkedHashSet<Long>()
        var hasMore = false
        try {
            for ((serverId, rows) in items.runtimes().groupBy { it.serverId }) {
                val corrections = gate.withLock { saved(serverId) }
                val suspicious = DurationOutliers.ids(rows)
                val live = rows.associateBy { it.remoteId }
                val attempts = runCatching {
                    Json.decodeFromString<List<DurationAttempt>>(settings.get(Settings.fileDurationAttempts(serverId)))
                }.getOrDefault(emptyList()).filter { attempt ->
                    live[attempt.remoteId]?.let { it.mediaSourceId == attempt.sourceId && it.durationMs == attempt.reportedMs } == true
                }.associateByTo(LinkedHashMap()) { it.remoteId }
                val pending = rows.filter { row ->
                    row.id in suspicious && row.remoteId !in corrections &&
                        (attempts[row.remoteId]?.retryUtcMs ?: 0) <= time.nowUtcMs()
                }
                if (pending.isEmpty()) continue
                val server = servers.byId(serverId) ?: continue
                val token = tokens.get(server.jellyfinServerId) ?: continue
                for (row in pending) {
                    currentCoroutineContext().ensureActive()
                    val remaining = deadline - System.nanoTime() / 1_000_000
                    if (remaining <= 0) { hasMore = true; break }
                    // Persist before starting: a killed process must not probe the same bad file every launch.
                    attempts[row.remoteId] = DurationAttempt(row.remoteId, row.mediaSourceId, row.durationMs, time.nowUtcMs() + RETRY_MS)
                    settings.set(Settings.fileDurationAttempts(serverId), Json.encodeToString(attempts.values.toList()))
                    val measured = withTimeoutOrNull(remaining) {
                        files.durationMs(server.baseUrl, token, row.remoteId, row.mediaSourceId, remaining)
                    }?.takeIf { it > 0 } ?: continue
                    withContext(NonCancellable) { gate.withLock commit@{
                        val duration = if (ProgramLength.differs(measured, row.durationMs)) measured else row.durationMs
                        if (items.correctDuration(row.id, row.serverId, row.remoteId, row.mediaSourceId, row.durationMs, duration) == 0) return@commit
                        val latest = saved(serverId)
                        latest[row.remoteId] = DurationCorrection(row.remoteId, row.mediaSourceId, row.durationMs, duration)
                        save(serverId, latest)
                        attempts.remove(row.remoteId)
                        settings.set(Settings.fileDurationAttempts(serverId), Json.encodeToString(attempts.values.toList()))
                        if (duration != row.durationMs) {
                            changed += row.id
                            AppLog.i("Duration", "Item ${row.id}: corrected ${row.durationMs} ms to $duration ms")
                        }
                    } }
                }
            }
            DurationRefresh(changed, hasMore)
        } finally {
            // Cancellation may follow a successful measurement in the same pass.
            // Persist its invalidations even when the remaining probes are cancelled.
            if (changed.isNotEmpty()) withContext(NonCancellable) { onChanged(changed.toSet()) }
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
        if (items.correctDuration(item.id, item.serverId, item.remoteId, item.mediaSourceId, item.durationMs, durationMs) == 0) return@withLock
        val corrections = saved(item.serverId)
        val previous = corrections[item.remoteId]?.takeIf { it.matches(item.mediaSourceId, item.durationMs) }
        corrections[item.remoteId] = DurationCorrection(item.remoteId, item.mediaSourceId, previous?.reportedMs ?: item.durationMs, durationMs)
        save(item.serverId, corrections)
    }

    private companion object { const val RETRY_MS = 24 * 60 * 60 * 1000L }
}
