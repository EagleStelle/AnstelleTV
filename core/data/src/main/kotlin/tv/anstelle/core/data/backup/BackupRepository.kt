package tv.anstelle.core.data.backup

import android.content.Context
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import tv.anstelle.core.common.AnstelleDispatchers
import tv.anstelle.core.common.AppInfo
import tv.anstelle.core.common.AppLog
import tv.anstelle.core.common.ApplicationScope
import tv.anstelle.core.common.Dispatcher
import tv.anstelle.core.common.TimeSource
import tv.anstelle.core.data.ProfileRepository
import tv.anstelle.core.data.Settings
import tv.anstelle.core.data.SettingsRepository
import tv.anstelle.core.data.channels.ChannelRepository
import tv.anstelle.core.data.channels.ScheduleUpdater
import tv.anstelle.core.data.sync.LibrarySyncer
import tv.anstelle.core.data.work.LibrarySyncWorker
import tv.anstelle.core.data.work.ScheduleWorker
import tv.anstelle.core.database.dao.BackupDao
import tv.anstelle.core.database.dao.MediaServerDao
import tv.anstelle.core.jellyfin.auth.JellyfinAuth
import tv.anstelle.core.jellyfin.auth.TokenStore
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/** A backup as a file to save: its name and its JSON. */
data class BackupFile(val name: String, val json: String)

/**
 * Backups: everything on this device the library can't rebuild, as one versioned JSON file
 * ([Backup]), saved to a file, served to another device over web control, and restored.
 *
 * A restore replaces everything. Sign-ins never travel: a server this device is already signed in
 * to stays signed in, and every other one waits for its sign-in. Library channels come back with
 * their numbers, favorites and history and wait for the library sync, which fetches every library
 * in full and takes them over again.
 */
@Singleton
class BackupRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val store: BackupStore,
    private val dao: BackupDao,
    private val serverDao: MediaServerDao,
    private val tokens: TokenStore,
    private val auth: JellyfinAuth,
    private val syncer: LibrarySyncer,
    private val channels: ChannelRepository,
    private val updater: ScheduleUpdater,
    private val appInfo: AppInfo,
    private val settings: SettingsRepository,
    private val signal: RestoreSignal,
    private val time: TimeSource,
    @ApplicationScope private val scope: CoroutineScope,
    @Dispatcher(AnstelleDispatchers.IO) private val io: CoroutineDispatcher,
) {
    /** What a backup of this device would hold, and when one was last taken. */
    fun observeSummary(): Flow<BackupSummary> =
        combine(dao.observeCounts(), dao.observeHistoryDays(), settings.observe(Settings.BackupSaved)) { counts, days, saved ->
            BackupSummary(
                profiles = counts.profiles,
                channels = counts.channels,
                customChannels = counts.customChannels,
                groups = counts.channelGroups,
                themes = counts.themes,
                servers = counts.servers,
                historyDays = days,
                savedUtcMs = saved.takeIf { it > 0 },
            )
        }

    /** This device's backup, named by [fileName]. Every copy taken counts as the last backup. */
    suspend fun export(): BackupFile = withContext(io) {
        val now = time.nowUtcMs()
        val file = BackupFile(fileName(now), BackupFormat.encode(store.read(now, appInfo.versionName, appInfo.deviceName)))
        settings.set(Settings.BackupSaved, now)
        file
    }

    /** A backup's file name: the app, this device and the day (`anstelletv-living-room-tv-2026-10-12.json`). */
    fun fileName(atUtcMs: Long = time.nowUtcMs()): String {
        val day = Instant.ofEpochMilli(atUtcMs).atZone(ZoneId.systemDefault()).toLocalDate()
        val device = appInfo.deviceName.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
        return listOf("anstelletv", device, day.toString()).filter { it.isNotEmpty() }.joinToString("-") + ".json"
    }

    /** Reads a backup file; throws [BackupException] saying why it can't be restored. */
    fun parse(text: String): Backup = BackupFormat.decode(text)

    /**
     * Replaces everything on this device with [backup]: no sync or schedule update runs meanwhile,
     * sign-ins the backup has no place for are signed out, and every server syncs afterwards. It
     * runs to its end even when the screen that asked goes away, since the profiles it replaces
     * rebuild the screens.
     */
    suspend fun restore(backup: Backup) = scope.async(io) {
        backup.problem()?.let { throw BackupException(it) }
        val work = WorkManager.getInstance(context)
        // A sync running now would write into the servers being replaced; it stops, and later ones wait.
        LibrarySyncWorker.cancelRequested(work)
        val signOuts = syncer.paused { channels.paused { updater.paused { replace(backup) } } }
        AppLog.i(TAG, "Restored ${backup.summary()} from ${backup.device}")
        signOut(signOuts)
        LibrarySyncWorker.syncNow(work)
        ScheduleWorker.runNow(work)
        signal.restored()
    }.await()

    /** Swaps the data and forgets the sign-ins with no place left. Returns them, to sign out. */
    private suspend fun replace(backup: Backup): List<SignOut> {
        val servers = serverDao.all()
        val byId = servers.associateBy { it.id }
        val backupServers = backup.servers.associate { it.id to it.jellyfinServerId }
        val plan = SignIns.plan(
            servers = servers.map { SignIns.Server(it.jellyfinServerId, it.baseUrl, it.userId, it.userName, tokens.get(it.jellyfinServerId) != null) },
            accounts = dao.accounts().mapNotNull { account ->
                val server = byId[account.serverId] ?: return@mapNotNull null
                SignIns.Account(server.jellyfinServerId, account.device, server.baseUrl, tokens.get(tokenKey(server.jellyfinServerId, account.device)) != null)
            },
            backupServers = backupServers.values.toSet(),
            backupAccounts = backup.profiles.flatMap { profile -> profile.accounts.mapNotNull { account -> backupServers[account.serverId]?.let { it to account.device } } }.toSet(),
        )
        store.write(backup, plan.kept)
        // Only once the restore holds: the tokens are read, then forgotten.
        val dropped = plan.dropServers.map { SignOut(it.jellyfinServerId, it.baseUrl, null) } +
            plan.dropAccounts.map { SignOut(tokenKey(it.jellyfinServerId, it.device), it.baseUrl, it.device) }
        return dropped.mapNotNull { signOut ->
            val token = tokens.get(signOut.tokenKey)
            tokens.remove(signOut.tokenKey)
            token?.let { signOut.copy(token = it) }
        }
    }

    /** Ends the dropped sessions on their servers, all at once and briefly: a server out of reach keeps a stale session. */
    private suspend fun signOut(signOuts: List<SignOut>) = coroutineScope {
        signOuts.map { signOut ->
            async { withTimeoutOrNull(SIGN_OUT_TIMEOUT_MS) { auth.signOut(signOut.baseUrl, signOut.token, signOut.device) } }
        }.awaitAll()
    }

    private fun tokenKey(jellyfinServerId: String, device: String) = ProfileRepository.tokenKey(jellyfinServerId, device)

    private data class SignOut(val tokenKey: String, val baseUrl: String, val device: String?, val token: String = "")

    private companion object {
        const val TAG = "Backup"
        const val SIGN_OUT_TIMEOUT_MS = 3_000L
    }
}
