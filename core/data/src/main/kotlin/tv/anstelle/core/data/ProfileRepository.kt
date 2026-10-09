package tv.anstelle.core.data

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import tv.anstelle.core.common.AnstelleDispatchers
import tv.anstelle.core.common.AppLog
import tv.anstelle.core.common.Dispatcher
import tv.anstelle.core.common.TimeSource
import tv.anstelle.core.data.channels.ChannelSummary
import tv.anstelle.core.data.profiles.PinHash
import tv.anstelle.core.database.dao.MediaItemDao
import tv.anstelle.core.database.dao.MediaServerDao
import tv.anstelle.core.database.dao.ProfileDao
import tv.anstelle.core.database.dao.ScheduleDao
import tv.anstelle.core.database.dao.SettingDao
import tv.anstelle.core.database.entity.MediaServerEntity
import tv.anstelle.core.database.entity.ProfileAccountEntity
import tv.anstelle.core.database.entity.ProfileEntity
import tv.anstelle.core.jellyfin.auth.AuthSession
import tv.anstelle.core.jellyfin.auth.JellyfinAuth
import tv.anstelle.core.jellyfin.auth.JellyfinUser
import tv.anstelle.core.jellyfin.auth.TokenStore
import tv.anstelle.core.model.CarriedRating
import tv.anstelle.core.model.Channel
import tv.anstelle.core.model.Profile
import tv.anstelle.core.model.ProfileAccess
import tv.anstelle.core.model.ProfileAccount
import tv.anstelle.core.model.ProfileRights
import tv.anstelle.core.model.ProfileRole
import tv.anstelle.core.model.RatingLevel
import tv.anstelle.core.model.RatingLimits
import tv.anstelle.core.model.ServerConnectException
import javax.inject.Inject
import javax.inject.Singleton

/** What entering a PIN did. */
sealed interface PinCheck {
    data object Right : PinCheck

    data class Wrong(val triesLeft: Int) : PinCheck

    /** Too many wrong PINs in a row: no PIN is checked until [untilUtcMs]. */
    data class LockedOut(val untilUtcMs: Long) : PinCheck
}

/**
 * Who a profile is on a server: the Jellyfin user it plays as, the device it speaks as, and
 * whether its watched marks and resume points go to that user ([keepsHistory]).
 */
data class WatchAccount(val userId: String, val accessToken: String, val device: String?, val keepsHistory: Boolean)

/** A profile's own sign-in on a server, for calls made as that user. */
data class ProfileSession(val profileId: Long, val userId: String, val accessToken: String, val device: String)

/**
 * Profiles: who is watching, as Jellyfin users. Each has a role (owner, admin or member), a
 * PIN, parental controls, a theme and personal settings; channels and schedules are shared.
 */
@Singleton
class ProfileRepository @Inject constructor(
    private val dao: ProfileDao,
    private val itemDao: MediaItemDao,
    private val settingDao: SettingDao,
    private val serverDao: MediaServerDao,
    private val scheduleDao: ScheduleDao,
    private val settings: SettingsRepository,
    private val tokens: TokenStore,
    private val auth: JellyfinAuth,
    private val time: TimeSource,
    @Dispatcher(AnstelleDispatchers.IO) private val io: CoroutineDispatcher,
) {
    private val pinLock = Any()
    private var wrongPins = 0
    private var lockedUntilUtcMs = 0L

    fun observeProfiles(): Flow<List<Profile>> = dao.observeAll().map { list -> list.map { it.toModel() } }

    fun observeProfile(profileId: Long): Flow<Profile?> = dao.observe(profileId).map { it?.toModel() }

    /** Whether the profile watching may change app-wide settings: the owner and admins. */
    fun observeCanManageApp(): Flow<Boolean> = observeActiveProfile().map { ProfileRights.canManageApp(it) }.distinctUntilChanged()

    /** The profile watching now. Waits for [ensureActiveProfile] on first launch. */
    fun observeActiveProfile(): Flow<Profile> =
        combine(settings.observe(Settings.ActiveProfileId), dao.observeAll()) { activeId, all ->
            (all.firstOrNull { it.id == activeId } ?: all.firstOrNull())?.toModel()
        }.mapNotNull { it }.distinctUntilChanged()

    /**
     * Makes sure at least one profile exists and returns the active one. On first launch
     * this creates the default "Home" profile, the owner, and makes it active. There is always
     * one owner: when none is left, the first profile becomes it.
     */
    suspend fun ensureActiveProfile(): Profile {
        val profiles = dao.all()
        val existing = when {
            profiles.isEmpty() -> listOf(requireNotNull(dao.byId(insert(DEFAULT_PROFILE_NAME, ProfileRole.OWNER))))
            profiles.none { it.role == ProfileRole.OWNER } -> {
                dao.upsert(profiles.first().copy(role = ProfileRole.OWNER))
                dao.all()
            }
            else -> profiles
        }
        val activeId = settings.get(Settings.ActiveProfileId)
        val active = existing.firstOrNull { it.id == activeId } ?: existing.first()
        if (active.id != activeId) settings.set(Settings.ActiveProfileId, active.id)
        return active.toModel()
    }

    suspend fun setActive(profileId: Long) = settings.set(Settings.ActiveProfileId, profileId)

    /** What [profileId] may watch: its parental rating limit and what its Jellyfin users can't see. */
    fun observeAccess(profileId: Long): Flow<ProfileAccess> =
        combine(dao.observe(profileId), dao.observeHidden(profileId)) { profile, hidden ->
            ProfileAccess(profileId, profile?.maxRatingScore, hidden)
        }.distinctUntilChanged()

    suspend fun access(profileId: Long): ProfileAccess = observeAccess(profileId).first()

    /**
     * The channels a profile tunes through, in [channels]' order: shown and in season, without
     * those whose every upcoming program the profile's Jellyfin users can't see.
     */
    suspend fun tunable(channels: List<ChannelSummary>, access: ProfileAccess): List<Channel> {
        val hidden = if (access.hidesItems) {
            withContext(io) { scheduleDao.hiddenChannels(access.profileId, time.nowUtcMs()) }.toHashSet()
        } else {
            emptySet()
        }
        return channels.filter { !it.channel.isHidden && it.inSeason && it.channel.id !in hidden }.map { it.channel }
    }

    /**
     * The parental rating levels a limit can be set to: the servers' own rating tables, kept
     * to the levels the library's items reach ([RatingLimits.levels]).
     */
    suspend fun ratingLevels(): List<RatingLevel> = withContext(io) {
        val table = serverDao.all().flatMap { server -> decodeRatingTable(settings.get(Settings.ratingTable(server.id))) }
        val carried = itemDao.ratingCounts().map { CarriedRating(it.name, it.score, it.count) }
        RatingLimits.levels(table, carried)
    }

    /** Keeps the server's rating table (`/Localization/ParentalRatings`), read at every sync. */
    suspend fun saveRatingTable(serverId: Long, table: Map<String, Int>) {
        settings.set(Settings.ratingTable(serverId), table.entries.joinToString("\n") { (name, score) -> "$score\t$name" })
    }

    /** Sets the parental rating limit; what airs over it is blacked out for the profile. */
    suspend fun setRatingLimit(profileId: Long, limit: Int?) = edit(profileId) { it.copy(maxRatingScore = limit) }

    suspend fun setTheme(profileId: Long, themeId: Long?) = edit(profileId) { it.copy(themeId = themeId) }

    /** Makes a member an admin or back. An admin has no parental controls. The owner stays the owner. */
    suspend fun setAdmin(profileId: Long, admin: Boolean) = edit(profileId) { profile ->
        when {
            profile.role == ProfileRole.OWNER -> profile
            admin -> profile.copy(role = ProfileRole.ADMIN, maxRatingScore = null)
            else -> profile.copy(role = ProfileRole.MEMBER)
        }
    }

    /** Hands the owner role to [profileId]; the owner before stays an admin. */
    suspend fun transferOwnership(profileId: Long) = withContext(io) {
        val target = dao.byId(profileId) ?: return@withContext
        if (target.role == ProfileRole.OWNER) return@withContext
        dao.all().filter { it.role == ProfileRole.OWNER }.forEach { dao.upsert(it.copy(role = ProfileRole.ADMIN)) }
        dao.upsert(target.copy(role = ProfileRole.OWNER, maxRatingScore = null))
        AppLog.i(TAG, "Profile $profileId is now the owner")
    }

    /** Sets the profile's PIN, or removes it with null. */
    suspend fun setPin(profileId: Long, pin: String?) {
        val hash = pin?.let { withContext(Dispatchers.Default) { PinHash.hash(it) } }
        edit(profileId) { it.copy(pinHash = hash) }
    }

    /** Checks [pin] against the profile's. After five wrong PINs in a row, nothing is checked for 30 seconds. */
    suspend fun checkPin(profileId: Long, pin: String): PinCheck {
        val stored = dao.byId(profileId)?.pinHash ?: return PinCheck.Right
        return checkAgainst(pin, listOf(stored))
    }

    private suspend fun checkAgainst(pin: String, hashes: List<String>): PinCheck {
        val now = time.nowUtcMs()
        synchronized(pinLock) { if (now < lockedUntilUtcMs) return PinCheck.LockedOut(lockedUntilUtcMs) }
        val right = withContext(Dispatchers.Default) { hashes.any { PinHash.verify(pin, it) } }
        synchronized(pinLock) {
            if (right) {
                wrongPins = 0
                return PinCheck.Right
            }
            wrongPins++
            if (wrongPins >= MAX_WRONG_PINS) {
                wrongPins = 0
                lockedUntilUtcMs = now + LOCKOUT_MS
                return PinCheck.LockedOut(lockedUntilUtcMs)
            }
            return PinCheck.Wrong(MAX_WRONG_PINS - wrongPins)
        }
    }

    /**
     * Deletes a profile with its watch history and personal settings. The owner can't go;
     * deleting the active profile makes the first remaining one active.
     */
    suspend fun delete(profileId: Long): Boolean = withContext(io) {
        val all = dao.all()
        val profile = all.firstOrNull { it.id == profileId } ?: return@withContext false
        if (profile.role == ProfileRole.OWNER) return@withContext false
        for (account in dao.accounts(profileId)) serverDao.byId(account.serverId)?.let { forget(account, it) }
        dao.delete(profileId)
        settingDao.removeForProfile(profileId)
        if (settings.get(Settings.ActiveProfileId) == profileId) settings.set(Settings.ActiveProfileId, all.first { it.id != profileId }.id)
        AppLog.i(TAG, "Deleted profile $profileId")
        true
    }

    /** The profile's own Jellyfin users, one per server it signed in to, first server first. */
    fun observeAccounts(profileId: Long): Flow<List<ProfileAccount>> =
        dao.observeAccounts(profileId).map { list ->
            list.mapNotNull { account ->
                val server = serverDao.byId(account.serverId) ?: return@mapNotNull null
                val signedIn = tokens.get(tokenKey(server.jellyfinServerId, account.device)) != null
                ProfileAccount(account.serverId, account.userId, account.userName, signedIn)
            }
        }.flowOn(io)

    /**
     * Makes [session]'s user, signed in as [device], the profile's own on [serverId]: the profile
     * plays as that user there and its watch history goes to it. The profile mirrors the user's
     * name and picture when it is its user on the first server it has one on.
     */
    suspend fun linkAccount(profileId: Long, serverId: Long, session: AuthSession, device: String) = withContext(io) {
        val server = serverDao.byId(serverId) ?: return@withContext
        dao.account(profileId, serverId)?.let { forget(it, server) }
        tokens.put(tokenKey(server.jellyfinServerId, device), session.accessToken)
        dao.upsertAccount(ProfileAccountEntity(profileId, serverId, session.userId, session.userName, device))
        // What the last user couldn't see says nothing about this one; the next sync looks again.
        dao.replaceHidden(profileId, serverId, emptyList())
        if (isFirstAccount(profileId, serverId)) mirror(profileId, server, JellyfinUser(session.userId, session.userName, session.imageTag))
        AppLog.i(TAG, "Profile $profileId is now ${session.userName} on ${server.name}")
    }

    /** A new member profile made from [session]'s user, as [linkAccount] links one; returns its id. */
    suspend fun createLinked(serverId: Long, session: AuthSession, device: String): Long {
        val id = insert(session.userName.trim().ifEmpty { "Profile" }.take(MAX_NAME), ProfileRole.MEMBER)
        linkAccount(id, serverId, session, device)
        return id
    }

    /**
     * The server's own sign-in is the user of every profile without one of its own anywhere
     * (the first profile, made at launch): those take that user's name and picture. Only the
     * first server connected names them.
     */
    suspend fun adoptServerUser(serverId: Long, user: JellyfinUser) = withContext(io) {
        val server = serverDao.byId(serverId) ?: return@withContext
        if (serverDao.all().minOf { it.id } != serverId) return@withContext
        dao.withoutAccounts().forEach { mirror(it.id, server, user) }
    }

    /**
     * Reads every profile's Jellyfin user on [server] again (the server's own sign-in with
     * [serverToken], and each profile's own), so names and pictures changed in Jellyfin follow.
     * A user that can't be read keeps what the profile had.
     */
    suspend fun refreshFromJellyfin(server: MediaServerEntity, serverToken: String) = withContext(io) {
        try {
            adoptServerUser(server.id, auth.currentUser(server.baseUrl, serverToken))
        } catch (e: ServerConnectException) {
            AppLog.w(TAG, "${server.name}: couldn't read the signed-in user: ${e.message}")
        }
        for (account in dao.accountsOn(server.id)) {
            val token = tokens.get(tokenKey(server.jellyfinServerId, account.device)) ?: continue
            try {
                val user = auth.currentUser(server.baseUrl, token, account.device)
                if (user.name.isNotBlank() && user.name != account.userName) dao.setAccountName(account.profileId, server.id, user.name)
                if (isFirstAccount(account.profileId, server.id)) mirror(account.profileId, server, user)
            } catch (e: ServerConnectException) {
                AppLog.w(TAG, "${server.name}: couldn't read profile ${account.profileId}'s user: ${e.message}")
            }
        }
    }

    /** A server is going away: its profile sign-ins go with it. */
    suspend fun forgetAccountsOn(serverId: Long) = withContext(io) {
        val server = serverDao.byId(serverId) ?: return@withContext
        for (account in dao.accountsOn(serverId)) {
            forget(account, server)
            dao.deleteAccount(account.profileId, serverId)
        }
    }

    /** The profiles signed in on [server] as users of their own, with their tokens. */
    suspend fun sessionsOn(server: MediaServerEntity): List<ProfileSession> = withContext(io) {
        dao.accountsOn(server.id).mapNotNull { account ->
            tokens.get(tokenKey(server.jellyfinServerId, account.device))?.let { ProfileSession(account.profileId, account.userId, it, account.device) }
        }
    }

    /** What the profile's own user on [serverId] can't see, found by a library sync. */
    suspend fun setHidden(profileId: Long, serverId: Long, itemIds: List<Long>) = withContext(io) {
        dao.replaceHidden(profileId, serverId, itemIds)
    }

    /**
     * Who [profileId] is on [server]. With a Jellyfin user of its own there, the profile plays
     * as that user and its watch history goes to it; null when that user's token is gone, so
     * nothing plays or is reported as someone else. A profile with no user of its own anywhere
     * (the first profile) is the server's sign-in ([serverToken]), watch history and all; one
     * with users on other servers only plays as the server's sign-in here, and keeps no history.
     */
    suspend fun watchAccount(profileId: Long, server: MediaServerEntity, serverToken: String): WatchAccount? = withContext(io) {
        val accounts = dao.accounts(profileId)
        val own = accounts.firstOrNull { it.serverId == server.id }
        if (own == null) {
            return@withContext server.userId?.let { WatchAccount(it, serverToken, device = null, keepsHistory = accounts.isEmpty()) }
        }
        val token = tokens.get(tokenKey(server.jellyfinServerId, own.device))
        if (token == null) AppLog.w(TAG, "Profile $profileId's sign-in on ${server.name} is gone; nothing plays as it")
        token?.let { WatchAccount(own.userId, it, own.device, keepsHistory = true) }
    }

    /** Whether [serverId] is the first server the profile has a user of its own on: that user names the profile. */
    private suspend fun isFirstAccount(profileId: Long, serverId: Long) = dao.accounts(profileId).firstOrNull()?.serverId == serverId

    /** Copies [user]'s name and picture onto the profile. */
    private suspend fun mirror(profileId: Long, server: MediaServerEntity, user: JellyfinUser) {
        val picture = user.imageTag?.let { tag ->
            "${server.baseUrl.trimEnd('/')}/Users/${user.id}/Images/Primary?tag=$tag&fillWidth=$PICTURE_PX&fillHeight=$PICTURE_PX&quality=90"
        }
        edit(profileId) { profile ->
            profile.copy(
                name = user.name.trim().takeIf { it.isNotEmpty() }?.take(MAX_NAME) ?: profile.name,
                pictureUrl = picture,
            )
        }
    }

    /** Signs [account] out on [server] and forgets its token. */
    private suspend fun forget(account: ProfileAccountEntity, server: MediaServerEntity) {
        val key = tokenKey(server.jellyfinServerId, account.device)
        tokens.get(key)?.let { token -> withTimeoutOrNull(SIGN_OUT_TIMEOUT_MS) { auth.signOut(server.baseUrl, token, account.device) } }
        tokens.remove(key)
    }

    private suspend fun insert(name: String, role: ProfileRole): Long = withContext(io) {
        dao.upsert(
            ProfileEntity(
                name = name,
                role = role,
                maxRatingScore = null,
                pinHash = null,
                pictureUrl = null,
                themeId = null,
                sortOrder = dao.maxSortOrder() + 1,
                createdAtUtcMs = time.nowUtcMs(),
            ),
        )
    }

    private suspend fun edit(profileId: Long, change: (ProfileEntity) -> ProfileEntity) = withContext(io) {
        val profile = dao.byId(profileId) ?: return@withContext
        val changed = change(profile)
        if (changed != profile) dao.upsert(changed)
    }

    companion object {
        private const val TAG = "Profiles"
        private const val DEFAULT_PROFILE_NAME = "Home"
        private const val MAX_WRONG_PINS = 5
        private const val LOCKOUT_MS = 30_000L
        private const val SIGN_OUT_TIMEOUT_MS = 3_000L
        private const val PICTURE_PX = 320
        private const val MAX_NAME = 24

        /** A device for a profile's own Jellyfin sign-in, apart from the server's and every other profile's. */
        fun newDevice() = "profile-" + java.util.UUID.randomUUID().toString().take(8)

        /**
         * Profile sign-ins sit in the token store beside the servers', keyed by server and the
         * sign-in's own device, so two profiles signed in as one user never share a token.
         */
        fun tokenKey(jellyfinServerId: String, device: String) = "$jellyfinServerId/$device"
    }
}

internal fun ProfileEntity.toModel() = Profile(
    id = id,
    name = name,
    pictureUrl = pictureUrl,
    role = role,
    maxRatingScore = maxRatingScore,
    hasPin = pinHash != null,
    themeId = themeId,
)

/** A rating table as [ProfileRepository.saveRatingTable] stores it: one "score<TAB>name" per line. */
private fun decodeRatingTable(raw: String): List<RatingLevel> = raw.lineSequence().mapNotNull { line ->
    val tab = line.indexOf('\t')
    val score = line.take(tab.coerceAtLeast(0)).toIntOrNull() ?: return@mapNotNull null
    RatingLevel(score, line.substring(tab + 1))
}.toList()
