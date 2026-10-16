package tv.anstelle.core.data

import android.content.Context
import androidx.room.withTransaction
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import tv.anstelle.core.common.AnstelleDispatchers
import tv.anstelle.core.common.ApplicationScope
import tv.anstelle.core.common.AppLog
import tv.anstelle.core.common.Dispatcher
import tv.anstelle.core.common.TimeSource
import tv.anstelle.core.data.sync.SyncProgress
import tv.anstelle.core.data.sync.SyncStatusTracker
import tv.anstelle.core.data.work.LibrarySyncWorker
import tv.anstelle.core.database.dao.LibraryDao
import tv.anstelle.core.database.AnstelleDatabase
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
import tv.anstelle.core.model.ProfileRights
import tv.anstelle.core.model.ServerRights
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

    /** [profileId] is the profile the user now belongs to, when the sign-in was for one. */
    data class SignedIn(val serverId: Long, val profileId: Long? = null) : QuickConnectState
}

/** Who a sign-in is for. */
sealed interface SignInFor {
    /** The server's own sign-in: it syncs the library. */
    data object Server : SignInFor

    /** A profile's own Jellyfin user on the server: the profile plays as it and keeps its watch history there. */
    data class Profile(val profileId: Long) : SignInFor

    /** A new profile made from the user. */
    data object NewProfile : SignInFor
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
    private val syncStatus: SyncStatusTracker,
    private val profiles: ProfileRepository,
    private val removal: ServerRemovalService,
    private val time: TimeSource,
    private val database: AnstelleDatabase,
    @ApplicationScope private val scope: CoroutineScope,
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

    /** The known server [serverId] at its saved address, for signing in to it again. */
    suspend fun candidateFor(serverId: Long): ServerCandidate {
        val server = serverDao.byId(serverId) ?: throw ServerConnectException(ServerProblem.INVALID_URL, "That server is gone")
        return findServer(server.baseUrl)
    }

    /**
     * Signs in with a password. For a profile, the user is that profile's own on the server
     * instead of the server's, signed in as a device of its own: the profile plays as it there.
     */
    suspend fun signInWithPassword(
        candidate: ServerCandidate,
        username: String,
        password: String,
        target: SignInFor = SignInFor.Server,
        actorId: Long? = null,
    ): QuickConnectState.SignedIn {
        val actor = if (target == SignInFor.Server) checkServerAccess(candidate, actorId) else actorId
        val url = candidate.info.url
        val device = deviceFor(target)
        val session = auth.signInWithPassword(url, username.trim(), password, device)
        return save(candidate, session, target, device, actor) { ownerDevice -> auth.signInWithPassword(url, username.trim(), password, ownerDevice) }
    }

    /** Starts Quick Connect, waits for approval, then signs in like [signInWithPassword]. Cancel the flow to give up. */
    fun quickConnect(candidate: ServerCandidate, target: SignInFor = SignInFor.Server, actorId: Long? = null): Flow<QuickConnectState> = flow {
        val actor = if (target == SignInFor.Server) checkServerAccess(candidate, actorId) else actorId
        val url = candidate.info.url
        val device = deviceFor(target)
        val code = auth.startQuickConnect(url, device)
        emit(QuickConnectState.WaitingForApproval(code.code))
        while (!auth.isQuickConnectApproved(url, code.secret, device)) delay(QUICK_CONNECT_POLL_MS)
        val session = auth.finishQuickConnect(url, code.secret, device)
        emit(save(candidate, session, target, device, actor) { ownerDevice -> approvedSession(url, session.accessToken, ownerDevice) })
    }

    /** Another session for the user signed in with [accessToken], as [device]: a Quick Connect code that user approves. */
    private suspend fun approvedSession(url: String, accessToken: String, device: String): AuthSession {
        val code = auth.startQuickConnect(url, device)
        auth.authorizeQuickConnect(url, accessToken, code.code)
        return auth.finishQuickConnect(url, code.secret, device)
    }

    private fun deviceFor(target: SignInFor): String? = if (target == SignInFor.Server) null else ProfileRepository.newDevice()

    private suspend fun checkServerAccess(candidate: ServerCandidate, actorId: Long?): Long? = withContext(io) {
        val all = profiles.observeProfiles().first()
        if (all.isEmpty()) {
            if (actorId != null) throw ServerConnectException(ServerProblem.FORBIDDEN, "The profile that started this sign-in was removed.")
            return@withContext null
        }
        val viewerId = actorId ?: profiles.activeProfile().id
        val actor = all.firstOrNull { it.id == viewerId }
        val server = serverDao.byJellyfinId(candidate.info.id)
        val owner = all.firstOrNull { it.role == tv.anstelle.core.model.ProfileRole.OWNER }
        if (actor == null || !ProfileRights.canManageApp(actor) ||
            (server != null && !ServerRights.canRemove(actor.id, owner?.id, server.ownerProfileId))) {
            throw ServerConnectException(ServerProblem.FORBIDDEN, "Only the owner or this server's owning profile can change its connection sign-in.")
        }
        actor.id
    }

    /** Signs out on the server, forgets the token and deletes everything synced from it. */
    suspend fun remove(serverId: Long, transferOwnerTo: Long? = null, actorId: Long? = null) =
        removal.remove(serverId, transferOwnerTo, actorId)

    fun observeRemoval(serverId: Long): Flow<ServerRemovalPlan> = removal.observe(serverId)

    /**
     * Turns syncing a library on or off. Nothing changes until the next sync: it fetches a
     * library turned on in full and removes the items of one turned off.
     */
    suspend fun setLibraryEnabled(libraryId: Long, enabled: Boolean) = libraryDao.setEnabled(libraryId, enabled)

    fun syncNow(serverId: Long, fullRefresh: Boolean = false, forceReconcile: Boolean = false) {
        syncStatus.update(serverId, "Waiting for network…")
        LibrarySyncWorker.syncNow(WorkManager.getInstance(context), serverId, fullRefresh, forceReconcile)
    }

    /**
     * Stores the server and its token. The same server added again (by any address) updates
     * the existing entry, so its libraries and channels stay. The first server's user is also
     * the first profile, the owner, signed in as a device of its own by [ownerSession].
     */
    private suspend fun save(
        candidate: ServerCandidate,
        session: AuthSession,
        target: SignInFor,
        device: String?,
        actorId: Long?,
        ownerSession: suspend (device: String) -> AuthSession,
    ): QuickConnectState.SignedIn = scope.async(io) {
        val info = candidate.info
        if (session.serverId != null && session.serverId != info.id) {
            throw ServerConnectException(ServerProblem.ID_MISMATCH, "Signed in to a different server than expected")
        }
        val existing = serverDao.byJellyfinId(info.id)
        if (target != SignInFor.Server) {
            val server = existing ?: throw ServerConnectException(ServerProblem.ID_MISMATCH, "That server isn't connected")
            val profileId = when (target) {
                is SignInFor.Profile -> target.profileId.also { profiles.linkAccount(it, server.id, session, requireNotNull(device)) }
                else -> profiles.createLinked(server.id, session, requireNotNull(device))
            }
            // The sync finds what the profile's user can't see.
            syncNow(server.id)
            return@async QuickConnectState.SignedIn(server.id, profileId)
        }
        checkServerAccess(candidate, actorId)
        val connectionProfile = existing?.ownerProfileId?.let { profiles.observeAccounts(it).first() }
            ?.firstOrNull { it.serverId == existing.id && it.userId == session.userId }
        val profileSession = if (connectionProfile?.signedIn == true) null else ProfileRepository.newDevice().let { ownerSession(it) to it }
        database.withTransaction {
            checkServerAccess(candidate, actorId)
            val current = serverDao.byJellyfinId(info.id)
            if (current?.id != existing?.id || current?.ownerProfileId != existing?.ownerProfileId || current?.userId != existing?.userId) {
                throw ServerConnectException(ServerProblem.FORBIDDEN, "The server changed during sign-in. Open its connection settings again.")
            }
            val userChanged = current != null && current.userId != session.userId
            val id = serverDao.upsert(
                MediaServerEntity(
                    id = existing?.id ?: 0,
                    jellyfinServerId = info.id,
                    name = info.name,
                    baseUrl = info.url,
                    userId = session.userId,
                    userName = session.userName,
                    serverVersion = info.version,
                    createdAtUtcMs = current?.createdAtUtcMs ?: time.nowUtcMs(),
                    lastSyncUtcMs = current?.lastSyncUtcMs,
                    lastSyncError = null,
                    lastReconcileUtcMs = current?.lastReconcileUtcMs,
                    ownerProfileId = existing?.ownerProfileId,
                ),
            )
            // @Upsert returns -1 when it updated an existing row.
            val serverId = existing?.id ?: id
            tokens.put(info.id, session.accessToken)
            AppLog.i(TAG, "Signed in to ${info.name} ${info.version} as ${session.userName}")
            profileSession?.let { (user, userDevice) ->
                profiles.connectServerProfile(serverId, user, userDevice)
            }
            // Another user sees other libraries and items: fetch everything again.
            syncNow(serverId, fullRefresh = userChanged, forceReconcile = true)
            QuickConnectState.SignedIn(serverId)
        }
    }.await()

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
        ownerProfileId = ownerProfileId,
    )

    private companion object {
        const val TAG = "Servers"
        const val QUICK_CONNECT_POLL_MS = 3_000L
    }
}
