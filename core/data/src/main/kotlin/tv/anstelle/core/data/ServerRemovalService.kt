package tv.anstelle.core.data

import android.content.Context
import androidx.room.withTransaction
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import tv.anstelle.core.common.AnstelleDispatchers
import tv.anstelle.core.common.ApplicationScope
import tv.anstelle.core.common.Dispatcher
import tv.anstelle.core.data.channels.ChannelLogos
import tv.anstelle.core.data.channels.ChannelRepository
import tv.anstelle.core.data.channels.ScheduleUpdater
import tv.anstelle.core.data.sync.LibrarySyncer
import tv.anstelle.core.data.work.LibrarySyncWorker
import tv.anstelle.core.database.AnstelleDatabase
import tv.anstelle.core.database.entity.MediaServerEntity
import tv.anstelle.core.database.entity.ProfileAccountEntity
import tv.anstelle.core.database.entity.ProfileEntity
import tv.anstelle.core.jellyfin.auth.JellyfinAuth
import tv.anstelle.core.jellyfin.auth.TokenStore
import tv.anstelle.core.model.ProfileRole
import tv.anstelle.core.model.ServerConnectException
import tv.anstelle.core.model.ServerProblem
import javax.inject.Inject
import javax.inject.Singleton

/** Validates and commits server, account and ownership changes together for both clients. */
@Singleton
class ServerRemovalService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: AnstelleDatabase,
    private val settings: SettingsRepository,
    private val tokens: TokenStore,
    private val auth: JellyfinAuth,
    private val syncer: LibrarySyncer,
    private val channels: ChannelRepository,
    private val updater: ScheduleUpdater,
    private val logos: ChannelLogos,
    private val web: WebServerController,
    @ApplicationScope private val scope: CoroutineScope,
    @Dispatcher(AnstelleDispatchers.IO) private val io: CoroutineDispatcher,
) {
    private val lock = Mutex()
    private val sessionChanges = MutableStateFlow(0L)

    fun observe(serverId: Long) = combine(
        database.mediaServerDao().observeAll(),
        database.profileDao().observeAll(),
        database.profileDao().observeAllAccounts(),
        sessionChanges,
    ) { servers, profiles, accounts, _ -> plan(serverId, servers, profiles, accounts) }
        .onStart { forgetExpiredSessions(serverId) }
        .flowOn(io)

    /** App scope finishes cleanup even when deleting the watching profile closes its screen. */
    suspend fun remove(serverId: Long, transferOwnerTo: Long?, actorId: Long?) = scope.async(io) {
        lock.withLock {
            val removal = syncer.paused { channels.paused { updater.paused {
                forgetExpiredSessions(serverId)
                val dropped = database.withTransaction {
                    val servers = database.mediaServerDao().all()
                    if (servers.none { it.id == serverId }) return@withTransaction Removal(emptyList(), false)
                    val profiles = database.profileDao().all()
                    val accounts = database.backupDao().accounts()
                    val plan = plan(serverId, servers, profiles, accounts)
                    plan.validate(actorId ?: settings.get(Settings.ActiveProfileId), transferOwnerTo)
                    val removedServers = if (plan.resetsApp) servers else servers.filter { it.id == serverId }
                    val removedAccounts = accounts.filter { it.serverId == serverId || it.profileId in plan.deletedProfileIds }
                    val byId = servers.associateBy { it.id }
                    val signOuts = removedServers.map { SignOut(it.jellyfinServerId, it.baseUrl, null, tokens.get(it.jellyfinServerId)) } +
                        removedAccounts.mapNotNull { account ->
                            byId[account.serverId]?.let { server ->
                                val key = ProfileRepository.tokenKey(server.jellyfinServerId, account.device)
                                SignOut(key, server.baseUrl, account.device, tokens.get(key))
                            }
                        }
                    if (plan.resetsApp) {
                        // Defaults and the original first-server pipeline, including stale signed-out entries.
                        database.backupDao().clear(keepSettings = listOf(Settings.ThemesSeeded.name))
                    } else {
                        transferOwnerTo?.let { id ->
                            val target = profiles.first { it.id == id }
                            database.profileDao().upsert(target.copy(role = ProfileRole.OWNER, maxRatingScore = null))
                        }
                        for (id in plan.deletedProfileIds) {
                            database.profileDao().delete(id)
                            database.settingDao().removeForProfile(id)
                        }
                        if (settings.get(Settings.ActiveProfileId) in plan.deletedProfileIds) {
                            settings.set(Settings.ActiveProfileId, transferOwnerTo ?: profiles.first { it.id !in plan.deletedProfileIds }.id)
                        }
                        syncer.removeItems(database.mediaItemDao().allIds(serverId).map { it.id })
                        database.mediaServerDao().delete(serverId)
                    }
                    val work = WorkManager.getInstance(context)
                    removedServers.forEach { LibrarySyncWorker.cancel(work, it.id) }
                    Removal(signOuts, plan.resetsApp)
                }
                // Only forget credentials after the transaction succeeds, before another sync can run.
                dropped.sessions.forEach { tokens.remove(it.key) }
                if (dropped.resetsApp) logos.keepOnly(emptyList())
                dropped
            } } }
            // Remote cleanup continues after the response; resetting also stops the now-disabled web service.
            scope.launch(io) {
                try {
                    removal.sessions.map { signOut -> async {
                        signOut.token?.let { withTimeoutOrNull(3_000L) { auth.signOut(signOut.url, it, signOut.device) } }
                    } }.awaitAll()
                } finally {
                    if (removal.resetsApp && !settings.get(Settings.WebServerEnabled)) web.stop()
                }
            }
            Unit
        }
    }.await()

    /** A stored token may already have expired. Offline sessions stay until Jellyfin rejects them. */
    private suspend fun forgetExpiredSessions(serverId: Long) = coroutineScope {
        val servers = database.mediaServerDao().all().filter { it.id != serverId }
        val byId = servers.associateBy { it.id }
        val sessions = servers.map { SignOut(it.jellyfinServerId, it.baseUrl, null, tokens.get(it.jellyfinServerId)) } +
            database.backupDao().accounts().mapNotNull { account ->
                byId[account.serverId]?.let { server ->
                    val key = ProfileRepository.tokenKey(server.jellyfinServerId, account.device)
                    SignOut(key, server.baseUrl, account.device, tokens.get(key))
                }
            }
        val expired = sessions.filter { it.token != null }.map { session -> async {
            try {
                withTimeoutOrNull(3_000L) { auth.currentUser(session.url, requireNotNull(session.token), session.device) }
                false
            } catch (e: ServerConnectException) {
                if (e.problem == ServerProblem.SIGNED_OUT && tokens.get(session.key) == session.token) {
                    tokens.remove(session.key)
                    true
                } else false
            }
        } }.awaitAll().any { it }
        if (expired) sessionChanges.update { it + 1 }
    }

    private fun plan(serverId: Long, servers: List<MediaServerEntity>, profiles: List<ProfileEntity>, accounts: List<ProfileAccountEntity>): ServerRemovalPlan {
        val signedIn = servers.filter { tokens.get(it.jellyfinServerId) != null }.mapTo(HashSet()) { it.id }
        val byId = servers.associateBy { it.id }
        val byProfile = accounts.groupBy { it.profileId }
        return serverRemovalPlan(serverId, signedIn, profiles.map { profile ->
            val own = byProfile[profile.id].orEmpty()
            RemovalProfile(profile.id, profile.name, profile.role, own.mapTo(HashSet()) { it.serverId }, own.filter { account ->
                val server = byId[account.serverId]
                server != null && tokens.get(ProfileRepository.tokenKey(server.jellyfinServerId, account.device)) != null
            }.mapTo(HashSet()) { it.serverId })
        })
    }

    private data class SignOut(val key: String, val url: String, val device: String?, val token: String?)
    private data class Removal(val sessions: List<SignOut>, val resetsApp: Boolean)
}
