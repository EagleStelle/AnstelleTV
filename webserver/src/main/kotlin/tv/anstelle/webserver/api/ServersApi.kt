package tv.anstelle.webserver.api

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import tv.anstelle.core.common.ApplicationScope
import tv.anstelle.core.common.TimeSource
import tv.anstelle.core.data.ServerRepository
import tv.anstelle.core.data.SignInFor
import tv.anstelle.core.model.MediaServer
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The Jellyfin servers, for the web UI: their state, syncing, libraries, removing one, and
 * adding one (see [ServerSignIns]). Missing servers come back as null or false; a server that
 * can't be reached or signed in to throws [tv.anstelle.core.model.ServerConnectException].
 */
interface ServersApi {
    suspend fun servers(): List<ServerDto>
    suspend fun server(serverId: Long, actorId: Long): ServerDetailDto?
    suspend fun sync(serverId: Long, full: Boolean): Boolean
    suspend fun setLibrary(serverId: Long, libraryId: Long, enabled: Boolean): Boolean
    suspend fun remove(serverId: Long, transferOwnerTo: Long?, actorId: Long): Boolean

    suspend fun find(address: String): FoundServerDto

    /** The server's id once signed in, or null when [signInId] is unknown or expired. */
    suspend fun signIn(signInId: String, username: String, password: String, actorId: Long): Long?

    /** The Quick Connect code, or null when [signInId] is unknown or expired. */
    suspend fun quickConnect(signInId: String, actorId: Long): String?
    fun signInState(signInId: String): SignInStateDto?
    fun cancelSignIn(signInId: String)
}

@Singleton
internal class RoomServersApi @Inject constructor(
    private val repository: ServerRepository,
    time: TimeSource,
    @param:ApplicationScope scope: CoroutineScope,
) : ServersApi {
    private val signIns = ServerSignIns(
        find = repository::findServer,
        password = { candidate, username, password, actorId -> repository.signInWithPassword(candidate, username, password, SignInFor.Server, actorId) },
        quickConnect = { candidate, actorId -> repository.quickConnect(candidate, SignInFor.Server, actorId) },
        time = time,
        scope = scope,
    )

    override suspend fun servers(): List<ServerDto> {
        val progress = repository.syncProgress.first()
        return repository.observeServers().first().map { it.toDto(progress[it.id]?.message) }
    }

    override suspend fun server(serverId: Long, actorId: Long): ServerDetailDto? {
        val server = repository.observeServer(serverId).first() ?: return null
        return ServerDetailDto(
            server = server.toDto(repository.syncProgress.first()[serverId]?.message),
            counts = repository.observeItemCounts(serverId).first().filter { it.count > 0 }.map { CountDto(it.type.name, it.count) },
            libraries = repository.observeLibraries(serverId).first().map {
                LibraryDto(it.id, it.name, it.collectionType ?: "mixed", it.isEnabled, repository.canAir(it.collectionType))
            },
            removal = repository.observeRemoval(serverId).first().let { plan ->
                ServerRemovalDto(plan.resetsApp, plan.requiresTransfer, plan.admins.map { RemovalAdminDto(it.id, it.name) }, plan.canRemove(actorId), plan.explanation)
            },
        )
    }

    override suspend fun sync(serverId: Long, full: Boolean): Boolean {
        repository.observeServer(serverId).first() ?: return false
        repository.syncNow(serverId, fullRefresh = full, forceReconcile = true)
        return true
    }

    override suspend fun setLibrary(serverId: Long, libraryId: Long, enabled: Boolean): Boolean {
        val library = repository.observeLibraries(serverId).first().firstOrNull { it.id == libraryId } ?: return false
        require(repository.canAir(library.collectionType)) { "AnstelleTV can't air this kind of library yet." }
        repository.setLibraryEnabled(libraryId, enabled)
        return true
    }

    override suspend fun remove(serverId: Long, transferOwnerTo: Long?, actorId: Long): Boolean {
        repository.observeServer(serverId).first() ?: return false
        repository.remove(serverId, transferOwnerTo, actorId)
        return true
    }

    override suspend fun find(address: String) = signIns.find(address)

    override suspend fun signIn(signInId: String, username: String, password: String, actorId: Long) = signIns.signIn(signInId, username, password, actorId)

    override suspend fun quickConnect(signInId: String, actorId: Long) = signIns.startQuickConnect(signInId, actorId)

    override fun signInState(signInId: String) = signIns.state(signInId)

    override fun cancelSignIn(signInId: String) = signIns.cancel(signInId)

    private fun MediaServer.toDto(progress: String?) = ServerDto(
        id = id,
        name = name,
        address = baseUrl,
        version = serverVersion,
        userName = userName,
        isSignedIn = isSignedIn,
        lastSyncUtcMs = lastSyncUtcMs,
        lastSyncError = lastSyncError,
        progress = progress,
    )
}
