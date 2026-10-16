package tv.anstelle.core.data.sync

import androidx.room.withTransaction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import tv.anstelle.core.common.AppLog
import tv.anstelle.core.common.TimeSource
import tv.anstelle.core.data.ProfileRepository
import tv.anstelle.core.data.playback.MediaDurations
import tv.anstelle.core.database.AnstelleDatabase
import tv.anstelle.core.database.dao.BackupDao
import tv.anstelle.core.database.dao.LibraryDao
import tv.anstelle.core.database.dao.MediaItemDao
import tv.anstelle.core.database.dao.MediaServerDao
import tv.anstelle.core.database.dao.ScheduleDao
import tv.anstelle.core.database.dao.SeriesMetadataRow
import tv.anstelle.core.database.entity.LibraryEntity
import tv.anstelle.core.database.entity.MediaServerEntity
import tv.anstelle.core.jellyfin.ServerProbe
import tv.anstelle.core.jellyfin.auth.TokenStore
import tv.anstelle.core.jellyfin.sync.ItemMapper
import tv.anstelle.core.jellyfin.sync.LibraryRules
import tv.anstelle.core.jellyfin.sync.LibrarySource
import tv.anstelle.core.jellyfin.sync.LibrarySourceFactory
import tv.anstelle.core.jellyfin.sync.RatingScores
import tv.anstelle.core.jellyfin.sync.RemoteLibrary
import tv.anstelle.core.jellyfin.sync.SyncContext
import tv.anstelle.core.jellyfin.toJellyfinId
import tv.anstelle.core.model.MediaType
import tv.anstelle.core.model.ServerConnectException
import tv.anstelle.core.model.ServerProblem
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

data class SyncOptions(
    /** Ignore the stored watermarks and fetch every item again. */
    val fullRefresh: Boolean = false,
    /** Run the ID-only reconciliation even if it ran recently. */
    val forceReconcile: Boolean = false,
)

/**
 * Pulls a server's libraries into Room.
 *
 * - **Incremental:** each library remembers the server time its last sync started. The
 *   next sync asks only for items with a newer `DateLastSaved` (minus a small overlap).
 * - **Series first:** episodes usually have no genres, studios, tags or rating of their
 *   own, so series are stored before episodes and episodes inherit from them. When a
 *   series changes, its episodes are fetched again.
 * - **Reconciliation:** deletions don't touch `DateLastSaved`, so
 *   an ID-only listing (cheap: no metadata) runs every few hours. Deleted
 *   items are removed and the schedules that air them are marked for regeneration.
 * - **Profiles:** every sync lists, ID-only, what each profile's own Jellyfin user can see; the
 *   rest is hidden from that profile.
 */
@Singleton
class LibrarySyncer @Inject constructor(
    private val database: AnstelleDatabase,
    private val serverDao: MediaServerDao,
    private val libraryDao: LibraryDao,
    private val itemDao: MediaItemDao,
    private val scheduleDao: ScheduleDao,
    private val tokens: TokenStore,
    private val probe: ServerProbe,
    private val sources: LibrarySourceFactory,
    private val status: SyncStatusTracker,
    private val profiles: ProfileRepository,
    private val backupDao: BackupDao,
    private val time: TimeSource,
    private val durations: MediaDurations,
) {
    /** One sync at a time, so a restore can hold them all off ([paused]). */
    private val gate = Mutex()

    /** Runs [block] while no sync runs; none starts until it ends. */
    suspend fun <T> paused(block: suspend () -> T): T = gate.withLock { block() }

    /** Syncs every server. One failing server does not stop the others. */
    suspend fun syncAll(options: SyncOptions = SyncOptions()): Boolean {
        var ok = true
        for (server in serverDao.all()) ok = sync(server.id, options) && ok
        return ok
    }

    /** Syncs one server. Returns false when it failed; the reason is stored on the server row. */
    suspend fun sync(serverId: Long, options: SyncOptions = SyncOptions()): Boolean =
        gate.withLock {
            val server = serverDao.byId(serverId) ?: return@withLock true
            val started = time.nowUtcMs()
            status.update(serverId, "Connecting…")
            try {
                runSync(server, options)
                serverDao.setSyncResult(serverId, time.nowUtcMs(), null)
                AppLog.i(TAG, "${server.name}: sync finished in ${(time.nowUtcMs() - started) / 1000.0} s")
                true
            } catch (e: ServerConnectException) {
                AppLog.w(TAG, "${server.name}: sync failed: ${e.message}")
                if (e.problem == ServerProblem.SIGNED_OUT) tokens.remove(server.jellyfinServerId)
                serverDao.setSyncResult(serverId, time.nowUtcMs(), e.userMessage())
                false
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A bug or bad data must not leave the server looking healthy.
                AppLog.e(TAG, "${server.name}: sync crashed", e)
                serverDao.setSyncResult(serverId, time.nowUtcMs(), "Sync failed: ${e.message ?: e.javaClass.simpleName}")
                false
            } finally {
                status.finish(serverId)
            }
        }

    private suspend fun runSync(server: MediaServerEntity, options: SyncOptions) {
        val token = tokens.get(server.jellyfinServerId)
        val userId = server.userId
        if (token == null || userId == null) {
            throw ServerConnectException(ServerProblem.SIGNED_OUT, "Not signed in")
        }
        // A cheap unauthenticated check first: is the server up, and is it still the same one?
        val info = probe.probe(server.baseUrl)
        if (info.id != server.jellyfinServerId) {
            throw ServerConnectException(ServerProblem.ID_MISMATCH, "${server.baseUrl} is now ${info.name}, a different server")
        }
        serverDao.setNameAndVersion(server.id, info.name, info.version)
        AppLog.i(TAG, "${info.name}: syncing from ${server.baseUrl}")

        val source = sources.create(server.baseUrl, token, userId)
        if (options.fullRefresh) libraryDao.clearWatermarks(server.id)
        val serverNow = source.serverTimeUtcMs()
        val libraries = refreshLibraries(server, source.libraries())
        val ratings = RatingScores.from(source.parentalRatings())
        profiles.saveRatingTable(server.id, ratings.table)

        for (library in libraries) syncLibrary(server, library, source, ratings, serverNow)
        // Dashboard rows restored from a backup find their items again, now the library is whole.
        val linked = backupDao.settleRestoredItems(server.id)
        if (linked > 0) AppLog.i(TAG, "${server.name}: $linked restored dashboard rows found their items")

        val reconcileDue = server.lastReconcileUtcMs.let { it == null || time.nowUtcMs() - it >= RECONCILE_INTERVAL_MS }
        if (options.forceReconcile || options.fullRefresh || reconcileDue) reconcile(server, libraries, source)
        findHidden(server, libraries)
        // Profiles are Jellyfin users: names and pictures changed there follow.
        profiles.refreshFromJellyfin(server)
    }

    /**
     * The library is synced as the server's own sign-in, but a profile signed in as a user of
     * its own sees only what Jellyfin shows that user: its libraries, within its parental
     * controls. An ID-only listing as each such user finds the synced items it can't see,
     * which the profile then never plays. A user that can't be read keeps what was found before.
     */
    private suspend fun findHidden(server: MediaServerEntity, libraries: List<LibraryEntity>) {
        val sessions = profiles.sessionsOn(server)
        if (sessions.isEmpty()) return
        val synced = libraries.filter { it.syncedKinds().isNotEmpty() }
        val items = itemDao.allIds(server.id)
        for (session in sessions) {
            status.update(server.id, "Checking what each profile can see…")
            try {
                val source = sources.create(server.baseUrl, session.accessToken, session.userId, session.device)
                val seen = source.libraries().mapTo(HashSet()) { it.id }
                val visible = HashSet<String>()
                for (library in synced) {
                    if (library.remoteId in seen) visible += source.itemIds(library.remoteId, library.syncedKinds())
                }
                val hidden = items.filter { it.remoteId !in visible }.map { it.id }
                profiles.setHidden(session.profileId, server.id, hidden)
                AppLog.i(TAG, "${server.name}: profile ${session.profileId} can't see ${hidden.size} of ${items.size} items")
            } catch (e: ServerConnectException) {
                AppLog.w(TAG, "${server.name}: couldn't read profile ${session.profileId}'s libraries: ${e.message}")
            }
        }
    }

    /** Stores newly found libraries (on when their type can air) and drops the ones that are gone. */
    private suspend fun refreshLibraries(server: MediaServerEntity, remote: List<RemoteLibrary>): List<LibraryEntity> {
        val local = libraryDao.forServer(server.id).associateBy { it.remoteId }
        for (library in remote) {
            val existing = local[library.id]
            when {
                existing == null -> {
                    val enabled = LibraryRules.itemKinds(library.collectionType).isNotEmpty()
                    libraryDao.upsert(
                        LibraryEntity(
                            serverId = server.id,
                            remoteId = library.id,
                            name = library.name,
                            collectionType = library.collectionType,
                            isEnabled = enabled,
                            lastSyncUtcMs = null,
                        ),
                    )
                    val state = if (enabled) "synced" else "not synced"
                    AppLog.i(TAG, "${server.name}: new library ${library.name} (${library.collectionType ?: "mixed"}), $state")
                }
                existing.name != library.name || existing.collectionType != library.collectionType ->
                    libraryDao.upsert(existing.copy(name = library.name, collectionType = library.collectionType))
            }
        }
        val remoteIds = remote.mapTo(HashSet()) { it.id }
        for (gone in local.values.filter { it.remoteId !in remoteIds }) {
            removeItems(itemDao.idsInLibrary(gone.id))
            libraryDao.delete(gone.id)
            AppLog.i(TAG, "${server.name}: library ${gone.name} is no longer visible; removed")
        }
        return libraryDao.forServer(server.id)
    }

    private suspend fun syncLibrary(
        server: MediaServerEntity,
        library: LibraryEntity,
        source: LibrarySource,
        ratings: RatingScores,
        serverNow: Long,
    ) {
        val kinds = library.syncedKinds()
        if (kinds.isEmpty()) {
            // Not synced (or just turned off): nothing of it may stay in the database.
            val stale = itemDao.idsInLibrary(library.id)
            if (stale.isNotEmpty()) {
                removeItems(stale)
                AppLog.i(TAG, "${server.name}/${library.name}: not synced; removed ${stale.size} items")
            }
            return
        }
        val since = library.lastSyncUtcMs?.let { it - WATERMARK_OVERLAP_MS }
        val label = "${server.name}/${library.name}"
        val context = { series: Map<String, SeriesMetadataRow> ->
            SyncContext(server.id, library.id, ratings, series, time.nowUtcMs())
        }

        var fetched = 0
        var changedSeries = emptyList<String>()
        if (BaseItemKind.SERIES in kinds) {
            changedSeries = fetchAll(source, label, library.remoteId, setOf(BaseItemKind.SERIES), since, context(emptyMap()))
            fetched += changedSeries.size
        }
        val series = itemDao.seriesMetadata(server.id, MediaType.SERIES).associateBy { it.remoteId }
        val otherKinds = kinds - BaseItemKind.SERIES
        fetched += fetchAll(source, label, library.remoteId, otherKinds, since, context(series)).size

        // Episodes inherit series metadata, so a changed series means re-reading its episodes.
        if (since != null && changedSeries.isNotEmpty() && BaseItemKind.EPISODE in otherKinds) {
            if (changedSeries.size > MAX_SERIES_REFRESH) {
                fetched += fetchAll(source, label, library.remoteId, otherKinds, null, context(series)).size
            } else {
                for (seriesId in changedSeries) {
                    fetched += fetchAll(source, label, seriesId, setOf(BaseItemKind.EPISODE), null, context(series)).size
                }
            }
        }
        libraryDao.setWatermark(library.id, serverNow)
        val mode = if (since == null) "full fetch" else "incremental since ${Instant.ofEpochMilli(since)}"
        AppLog.i(TAG, "$label: $mode, $fetched items fetched")
    }

    /** Fetches and stores every page below [parentId]. Returns the Jellyfin ids of the fetched items. */
    private suspend fun fetchAll(
        source: LibrarySource,
        label: String,
        parentId: String,
        kinds: Set<BaseItemKind>,
        since: Long?,
        context: SyncContext,
    ): List<String> {
        val fetched = ArrayList<String>()
        var start = 0
        while (true) {
            status.update(context.serverId, if (start == 0) "$label: reading…" else "$label: $start items…")
            val page = source.items(parentId, kinds, since, start, PAGE_SIZE).items
            store(context.serverId, page, context)
            page.mapTo(fetched) { it.id.toJellyfinId() }
            start += page.size
            if (page.size < PAGE_SIZE) break
        }
        return fetched
    }

    private suspend fun store(serverId: Long, dtos: List<BaseItemDto>, context: SyncContext) {
        if (dtos.isEmpty()) return
        val existing = itemDao.storedFor(serverId, dtos.map { it.id.toJellyfinId() }).associateBy { it.remoteId }
        val entities = dtos.mapNotNull { dto -> ItemMapper.map(dto, context, existing[dto.id.toJellyfinId()]?.id ?: 0L) }
        if (entities.isNotEmpty()) durations.store(entities)
    }

    private suspend fun reconcile(server: MediaServerEntity, libraries: List<LibraryEntity>, source: LibrarySource) {
        status.update(server.id, "Checking for removed items…")
        val remoteIds = HashSet<String>()
        for (library in libraries) {
            val kinds = library.syncedKinds()
            if (kinds.isEmpty()) continue
            remoteIds += source.itemIds(library.remoteId, kinds)
        }
        val gone = itemDao.allIds(server.id).filter { it.remoteId !in remoteIds }.map { it.id }
        removeItems(gone)
        serverDao.setReconciled(server.id, time.nowUtcMs())
        AppLog.i(TAG, "${server.name}: reconciled ${remoteIds.size} items, removed ${gone.size}")
    }

    /**
     * Deletes items and marks the schedules that air them in the future as stale, so the
     * schedule worker regenerates those channels after their current and next airings.
     */
    suspend fun removeItems(ids: List<Long>) {
        if (ids.isEmpty()) return
        val now = time.nowUtcMs()
        database.withTransaction {
            for (chunk in ids.chunked(SQL_CHUNK)) {
                scheduleDao.invalidateForItems(chunk, now)
                itemDao.deleteByIds(chunk)
            }
        }
    }

    private companion object {
        const val TAG = "LibrarySync"
        const val PAGE_SIZE = 150
        const val SQL_CHUNK = 500
        const val MAX_SERIES_REFRESH = 25

        /** Re-reads a few minutes before the watermark in case the server saved items while the last sync ran. */
        const val WATERMARK_OVERLAP_MS = 5 * 60 * 1000L
        const val RECONCILE_INTERVAL_MS = 6 * 60 * 60 * 1000L
    }
}

/** Item kinds to sync from this library; empty when it is turned off or holds nothing to air. */
private fun LibraryEntity.syncedKinds() =
    if (isEnabled) LibraryRules.itemKinds(collectionType) else emptySet()

/** A short explanation for the settings screen. */
fun ServerConnectException.userMessage(): String = when (problem) {
    ServerProblem.INVALID_URL -> "That doesn't look like a server address."
    ServerProblem.UNREACHABLE -> "Server not reachable."
    ServerProblem.NOT_JELLYFIN -> "That address is not a Jellyfin server."
    ServerProblem.UNSUPPORTED_VERSION -> "This Jellyfin version is too old."
    ServerProblem.ID_MISMATCH -> "This address now points to a different server. Add it again."
    ServerProblem.BAD_CREDENTIALS -> "Wrong username or password."
    ServerProblem.SIGNED_OUT -> "Signed out. Sign in again."
    ServerProblem.FORBIDDEN -> message.orEmpty()
    ServerProblem.QUICK_CONNECT_DISABLED -> "Quick Connect is turned off on this server."
    ServerProblem.SERVER_ERROR -> "The server reported an error."
}
