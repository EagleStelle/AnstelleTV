package tv.anstelle.core.data

import android.content.Context
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import tv.anstelle.core.common.AnstelleDispatchers
import tv.anstelle.core.common.AppLog
import tv.anstelle.core.common.Dispatcher
import tv.anstelle.core.common.TimeSource
import tv.anstelle.core.data.sync.LibrarySyncer
import tv.anstelle.core.data.sync.SyncProgress
import tv.anstelle.core.data.sync.SyncStatusTracker
import tv.anstelle.core.data.work.LibrarySyncWorker
import tv.anstelle.core.database.dao.LibraryDao
import tv.anstelle.core.database.dao.MediaItemDao
import tv.anstelle.core.database.dao.MediaServerDao
import tv.anstelle.core.database.dao.TypeCount
import tv.anstelle.core.database.entity.LibraryEntity
import tv.anstelle.core.database.entity.MediaServerEntity
import tv.anstelle.core.jellyfin.ServerProbe
import tv.anstelle.core.jellyfin.auth.AuthSession
import tv.anstelle.core.jellyfin.auth.JellyfinAuth
import tv.anstelle.core.jellyfin.auth.TokenStore
import tv.anstelle.core.jellyfin.sync.LibraryRules
import tv.anstelle.core.model.MediaServer
import tv.anstelle.core.model.PublicServerInfo
import tv.anstelle.core.model.ServerConnectException
import tv.anstelle.core.model.ServerProblem
import javax.inject.Inject
import javax.inject.Singleton

/** A server that answered during setup, before the user signs in. */
data class ServerCandidate(
    /** The server and the address it answered at. */
    val info: PublicServerInfo,
    val quickConnectEnabled: Boolean,
)

sealed interface QuickConnectState {
    /** Show [code]; the user approves it in another Jellyfin client. */
    data class WaitingForApproval(val code: String) : QuickConnectState

    data class SignedIn(val serverId: Long) : QuickConnectState
}

/** Jellyfin servers: setup, sign-in, libraries and sync. */
@Singleton
class ServerRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val serverDao: MediaServerDao,
    private val libraryDao: LibraryDao,
    private val itemDao: MediaItemDao,
    private val tokens: TokenStore,
    private val probe: ServerProbe,
    private val auth: JellyfinAuth,
    private val syncer: LibrarySyncer,
    private val syncStatus: SyncStatusTracker,
    private val time: TimeSource,
    @Dispatcher(AnstelleDispatchers.IO) private val io: CoroutineDispatcher,
) {
    // Token lookups may hit the Keystore, so mapping runs off the main thread.
    fun observeServers(): Flow<List<MediaServer>> =
        serverDao.observeAll().map { list -> list.map { it.toModel() } }.flowOn(io)

    fun observeServer(serverId: Long): Flow<MediaServer?> =
        serverDao.observe(serverId).map { it?.toModel() }.flowOn(io)

    fun observeLibraries(serverId: Long): Flow<List<LibraryEntity>> = libraryDao.observeForServer(serverId)

    fun observeItemCounts(serverId: Long): Flow<List<TypeCount>> = itemDao.observeTypeCounts(serverId)

    val syncProgress: Flow<Map<Long, SyncProgress>> get() = syncStatus.running

    /** False for library types AnstelleTV cannot air yet (music, photos, books, …). */
    fun canAir(collectionType: String?): Boolean =
        LibraryRules.itemKinds(collectionType).isNotEmpty()

    /** Finds the Jellyfin server behind an address the user typed (an IP address or a URL). */
    suspend fun findServer(address: String): ServerCandidate {
        if (address.isBlank()) throw ServerConnectException(ServerProblem.INVALID_URL, "Enter a server address")
        val info = probe.find(address)
        val quickConnect = try {
            auth.isQuickConnectEnabled(info.url)
        } catch (e: ServerConnectException) {
            AppLog.w(TAG, "Quick Connect check failed: ${e.message}")
            false
        }
        return ServerCandidate(info, quickConnect)
    }

    suspend fun signInWithPassword(candidate: ServerCandidate, username: String, password: String): Long {
        val session = auth.signInWithPassword(candidate.info.url, username.trim(), password)
        return save(candidate, session)
    }

    /** Starts Quick Connect, waits for approval, then signs in. Cancel the flow to give up. */
    fun quickConnect(candidate: ServerCandidate): Flow<QuickConnectState> = flow {
        val url = candidate.info.url
        val code = auth.startQuickConnect(url)
        emit(QuickConnectState.WaitingForApproval(code.code))
        while (!auth.isQuickConnectApproved(url, code.secret)) delay(QUICK_CONNECT_POLL_MS)
        val session = auth.finishQuickConnect(url, code.secret)
        emit(QuickConnectState.SignedIn(save(candidate, session)))
    }

    /** Signs out on the server, forgets the token and deletes everything synced from it. */
    suspend fun remove(serverId: Long) {
        val server = serverDao.byId(serverId) ?: return
        LibrarySyncWorker.cancel(WorkManager.getInstance(context), serverId)
        tokens.get(server.jellyfinServerId)?.let { token ->
            withTimeoutOrNull(SIGN_OUT_TIMEOUT_MS) { auth.signOut(server.baseUrl, token) }
        }
        tokens.remove(server.jellyfinServerId)
        syncer.removeItems(itemDao.allIds(serverId).map { it.id })
        serverDao.delete(serverId)
        AppLog.i(TAG, "Removed server ${server.name}")
    }

    /**
     * Turns syncing a library on or off. A library turned on is fetched in full; a library
     * turned off loses its items right away.
     */
    suspend fun setLibraryEnabled(libraryId: Long, enabled: Boolean) {
        val library = libraryDao.byId(libraryId) ?: return
        libraryDao.setEnabled(libraryId, enabled)
        if (!enabled) syncer.removeItems(itemDao.idsInLibrary(libraryId))
        syncNow(library.serverId, forceReconcile = true)
    }

    fun syncNow(serverId: Long, fullRefresh: Boolean = false, forceReconcile: Boolean = false) {
        syncStatus.update(serverId, "Waiting for network…")
        LibrarySyncWorker.syncNow(WorkManager.getInstance(context), serverId, fullRefresh, forceReconcile)
    }

    /**
     * Stores the server and its token. The same server added again (by any address) updates
     * the existing entry, so its libraries and channels stay.
     */
    private suspend fun save(candidate: ServerCandidate, session: AuthSession): Long = withContext(io) {
        val info = candidate.info
        if (session.serverId != null && session.serverId != info.id) {
            throw ServerConnectException(ServerProblem.ID_MISMATCH, "Signed in to a different server than expected")
        }
        val existing = serverDao.byJellyfinId(info.id)
        val userChanged = existing != null && existing.userId != session.userId
        val id = serverDao.upsert(
            MediaServerEntity(
                id = existing?.id ?: 0,
                jellyfinServerId = info.id,
                name = info.name,
                baseUrl = info.url,
                userId = session.userId,
                userName = session.userName,
                serverVersion = info.version,
                createdAtUtcMs = existing?.createdAtUtcMs ?: time.nowUtcMs(),
                lastSyncUtcMs = existing?.lastSyncUtcMs,
                lastSyncError = null,
                lastReconcileUtcMs = existing?.lastReconcileUtcMs,
            ),
        )
        // @Upsert returns -1 when it updated an existing row.
        val serverId = existing?.id ?: id
        tokens.put(info.id, session.accessToken)
        AppLog.i(TAG, "Signed in to ${info.name} ${info.version} as ${session.userName}")
        // Another user sees other libraries and items: fetch everything again.
        syncNow(serverId, fullRefresh = userChanged, forceReconcile = true)
        serverId
    }

    private fun MediaServerEntity.toModel() = MediaServer(
        id = id,
        jellyfinServerId = jellyfinServerId,
        name = name,
        baseUrl = baseUrl,
        userId = userId,
        userName = userName,
        serverVersion = serverVersion,
        lastSyncUtcMs = lastSyncUtcMs,
        lastSyncError = lastSyncError,
        isSignedIn = tokens.get(jellyfinServerId) != null,
    )

    private companion object {
        const val TAG = "Servers"
        const val QUICK_CONNECT_POLL_MS = 3_000L
        const val SIGN_OUT_TIMEOUT_MS = 3_000L
    }
}
