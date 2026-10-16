package tv.anstelle.core.data.backup

import androidx.room.withTransaction
import tv.anstelle.core.common.AppLog
import tv.anstelle.core.data.Settings
import tv.anstelle.core.data.channels.ChannelLogos
import tv.anstelle.core.database.AnstelleDatabase
import tv.anstelle.core.database.dao.BackupDao
import tv.anstelle.core.database.entity.ChannelDaypartEntity
import tv.anstelle.core.database.entity.ChannelEntity
import tv.anstelle.core.database.entity.ChannelGroupEntity
import tv.anstelle.core.database.entity.ChannelRuleEntity
import tv.anstelle.core.database.entity.ChannelSeasonEntity
import tv.anstelle.core.database.entity.LibraryEntity
import tv.anstelle.core.database.entity.MediaServerEntity
import tv.anstelle.core.database.entity.ProfileAccountEntity
import tv.anstelle.core.database.entity.ProfileEntity
import tv.anstelle.core.database.entity.RestoredItemEntity
import tv.anstelle.core.database.entity.SettingEntity
import tv.anstelle.core.database.entity.ThemeEntity
import tv.anstelle.core.database.entity.WatchEventEntity
import tv.anstelle.core.model.ProfileRole
import tv.anstelle.core.model.codeOf
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Turns the database into a [Backup] and back. Reading takes what the library can't rebuild;
 * writing replaces all of it in one transaction, keeping the ids, so channels, profiles, settings
 * and history still point at each other. Callers keep syncs and schedule updates out meanwhile.
 */
@OptIn(ExperimentalEncodingApi::class)
@Singleton
class BackupStore @Inject constructor(
    private val database: AnstelleDatabase,
    private val dao: BackupDao,
    private val logos: ChannelLogos,
) {
    suspend fun read(createdAtUtcMs: Long, app: String, device: String): Backup {
        val settings = dao.settings().filter { it.key !in Settings.DeviceOnly }
        val values = settings.associate { it.key to it.value }
        val profiles = dao.profiles()
        // History goes along for the profiles that keep a dashboard.
        val keeping = profiles.map { it.id }.filter { values[Settings.dashboard(it).name]?.toBooleanStrictOrNull() != false }
        val items = dao.watchedItems().associateBy { it.id }
        val restored = dao.restoredItems().associateBy { it.eventId }
        val themes = dao.themes()
        val builtInKeys = themes.filter { it.isBuiltIn }.associate { it.id to it.builtInKey }
        val customIds = themes.filter { !it.isBuiltIn }.mapTo(HashSet()) { it.id }
        val accounts = dao.accounts().groupBy { it.profileId }
        val libraries = dao.libraries().groupBy { it.serverId }
        val rules = dao.rules().groupBy { it.channelId }
        val seasons = dao.seasons().groupBy { it.channelId }
        val dayparts = dao.dayparts().groupBy { it.channelId }
        return Backup(
            createdAtUtcMs = createdAtUtcMs,
            app = app,
            device = device,
            servers = dao.servers().map { server ->
                BackupServer(
                    id = server.id,
                    jellyfinServerId = server.jellyfinServerId,
                    name = server.name,
                    baseUrl = server.baseUrl,
                    userId = server.userId,
                    userName = server.userName,
                    serverVersion = server.serverVersion,
                    createdAtUtcMs = server.createdAtUtcMs,
                    libraries = libraries[server.id].orEmpty().map { BackupLibrary(it.remoteId, it.name, it.collectionType, it.isEnabled) },
                    ownerProfileId = server.ownerProfileId,
                )
            },
            profiles = profiles.map { profile ->
                BackupProfile(
                    id = profile.id,
                    name = profile.name,
                    role = profile.role.code,
                    maxRatingScore = profile.maxRatingScore,
                    pinHash = profile.pinHash,
                    pictureUrl = profile.pictureUrl,
                    builtInTheme = profile.themeId?.let(builtInKeys::get),
                    customTheme = profile.themeId?.takeIf { it in customIds },
                    sortOrder = profile.sortOrder,
                    createdAtUtcMs = profile.createdAtUtcMs,
                    accounts = accounts[profile.id].orEmpty().map { BackupAccount(it.serverId, it.userId, it.userName, it.device) },
                )
            },
            themes = themes.filter { !it.isBuiltIn }.map { BackupTheme(it.id, it.name, it.json, it.updatedAtUtcMs) },
            groups = dao.groups().map { BackupGroup(it.id, it.name, it.sortOrder, it.numberStart, it.libraryKey) },
            channels = dao.channels().map { channel ->
                BackupChannel(
                    id = channel.id,
                    number = channel.number,
                    name = channel.name,
                    logo = logos.file(channel.logoUri)?.let { file -> runCatching { Base64.encode(file.readBytes()) }.getOrNull() },
                    kind = channel.kind.code,
                    seed = channel.seed,
                    seriesOrder = channel.seriesOrder.code,
                    seriesBlock = channel.seriesBlock,
                    movieOrder = channel.movieOrder.code,
                    movieBlock = channel.movieBlock,
                    moviesOncePerDay = channel.moviesOncePerDay,
                    hidden = channel.isHidden,
                    favorite = channel.isFavorite,
                    groupId = channel.groupId,
                    autoKey = channel.autoKey,
                    createdAtUtcMs = channel.createdAtUtcMs,
                    updatedAtUtcMs = channel.updatedAtUtcMs,
                    rules = rules[channel.id].orEmpty().map { BackupRule(it.seasonId, it.logicGroup, it.field.code, it.op.code, it.value, it.weight) },
                    seasons = seasons[channel.id].orEmpty().map {
                        BackupSeason(it.id, it.name, it.mode.code, it.startMonth, it.startDay, it.endMonth, it.endDay)
                    },
                    dayparts = dayparts[channel.id].orEmpty().map {
                        BackupDaypart(it.name, it.startMinuteOfDay, it.endMinuteOfDay, it.maxRatingScore, it.minRatingScore, it.ruleLogicGroup)
                    },
                )
            },
            settings = settings.associate { it.key to it.value },
            history = dao.history(keeping).map { view ->
                // The item by its Jellyfin id; a row restored before that hasn't met its item again keeps the id it came with.
                val item = view.mediaItemId?.let(items::get)
                val pending = restored[view.id]
                BackupView(
                    profileId = view.profileId,
                    source = view.source.code,
                    channelId = view.channelId,
                    serverId = item?.serverId ?: pending?.serverId,
                    item = item?.remoteId ?: pending?.remoteId,
                    title = view.title,
                    showTitle = view.showTitle,
                    mediaType = view.mediaType.code,
                    startUtcMs = view.startUtcMs,
                    durationMs = view.durationMs,
                    localDay = view.localDay,
                    completed = view.completed,
                )
            },
        )
    }

    /**
     * Replaces everything with [backup], which [Backup.problem] passed. Servers in [kept] stay
     * signed in as this device's user at this device's address; the rest wait for a sign-in.
     * Libraries come back unsynced, so the next sync fetches them in full, and history rows find
     * their items again after it ([BackupDao.settleRestoredItems]). Returns the owner's profile id.
     */
    suspend fun write(backup: Backup, kept: Map<String, SignIns.Server>): Long {
        // Logos first, named for the ids their channels keep, so the transaction only links them.
        val logoUris = HashMap<Long, String>()
        for (channel in backup.channels) {
            val logo = channel.logo ?: continue
            try {
                logoUris[channel.id] = logos.store(channel.id, Base64.decode(logo))
            } catch (e: Exception) {
                AppLog.w(TAG, "Channel ${channel.number}'s logo is unreadable; restored without it", e)
            }
        }
        val owner = try {
            database.withTransaction { replace(backup, kept, logoUris) }
        } catch (e: Exception) {
            logoUris.values.forEach(logos::delete)
            throw e
        }
        // The logos of the channels that were here before go.
        logos.keepOnly(logoUris.values)
        return owner
    }

    private suspend fun replace(backup: Backup, kept: Map<String, SignIns.Server>, logoUris: Map<Long, String>): Long {
        dao.clear(Settings.DeviceOnly)
        dao.insertServers(
            backup.servers.map { server ->
                val signIn = kept[server.jellyfinServerId]
                MediaServerEntity(
                    id = server.id,
                    jellyfinServerId = server.jellyfinServerId,
                    name = server.name,
                    baseUrl = signIn?.baseUrl ?: server.baseUrl,
                    userId = if (signIn != null) signIn.userId else server.userId,
                    userName = if (signIn != null) signIn.userName else server.userName,
                    serverVersion = server.serverVersion,
                    createdAtUtcMs = server.createdAtUtcMs,
                    ownerProfileId = server.ownerProfileId,
                )
            },
        )
        dao.insertLibraries(
            backup.servers.flatMap { server ->
                server.libraries.map { LibraryEntity(serverId = server.id, remoteId = it.remoteId, name = it.name, collectionType = it.collectionType, isEnabled = it.enabled, lastSyncUtcMs = null) }
            },
        )
        // Themes someone made get new ids; built-in ones are this app's own.
        val themeIds = backup.themes.associate { theme ->
            theme.id to dao.insertTheme(ThemeEntity(name = theme.name, json = theme.json, isBuiltIn = false, builtInKey = null, updatedAtUtcMs = theme.updatedAtUtcMs))
        }
        val builtIns = dao.themes().filter { it.isBuiltIn }.associate { it.builtInKey to it.id }
        dao.insertProfiles(
            backup.profiles.map { profile ->
                ProfileEntity(
                    id = profile.id,
                    name = profile.name,
                    role = codeOf(profile.role),
                    maxRatingScore = profile.maxRatingScore,
                    pinHash = profile.pinHash,
                    pictureUrl = profile.pictureUrl,
                    themeId = profile.customTheme?.let(themeIds::get) ?: profile.builtInTheme?.let(builtIns::get),
                    sortOrder = profile.sortOrder,
                    createdAtUtcMs = profile.createdAtUtcMs,
                )
            },
        )
        dao.insertAccounts(backup.profiles.flatMap { profile -> profile.accounts.map { ProfileAccountEntity(profile.id, it.serverId, it.userId, it.userName, it.device) } })
        AnstelleDatabase.assignServerOwners(database.openHelper.writableDatabase)
        dao.insertGroups(backup.groups.map { ChannelGroupEntity(it.id, it.name, it.sortOrder, it.numberStart, it.libraryKey) })
        dao.insertChannels(
            backup.channels.map { channel ->
                ChannelEntity(
                    id = channel.id,
                    number = channel.number,
                    name = channel.name,
                    logoUri = logoUris[channel.id],
                    kind = codeOf(channel.kind),
                    seed = channel.seed,
                    seriesOrder = codeOf(channel.seriesOrder),
                    seriesBlock = channel.seriesBlock,
                    movieOrder = codeOf(channel.movieOrder),
                    movieBlock = channel.movieBlock,
                    moviesOncePerDay = channel.moviesOncePerDay,
                    isHidden = channel.hidden,
                    isFavorite = channel.favorite,
                    groupId = channel.groupId,
                    autoKey = channel.autoKey,
                    createdAtUtcMs = channel.createdAtUtcMs,
                    updatedAtUtcMs = channel.updatedAtUtcMs,
                )
            },
        )
        dao.insertSeasons(
            backup.channels.flatMap { channel ->
                channel.seasons.map { ChannelSeasonEntity(it.id, channel.id, it.name, codeOf(it.mode), it.startMonth, it.startDay, it.endMonth, it.endDay) }
            },
        )
        dao.insertRules(
            backup.channels.flatMap { channel ->
                channel.rules.map { ChannelRuleEntity(channelId = channel.id, seasonId = it.seasonId, logicGroup = it.logicGroup, field = codeOf(it.field), op = codeOf(it.op), value = it.value, weight = it.weight) }
            },
        )
        dao.insertDayparts(
            backup.channels.flatMap { channel ->
                channel.dayparts.map {
                    ChannelDaypartEntity(
                        channelId = channel.id,
                        name = it.name,
                        startMinuteOfDay = it.startMinuteOfDay,
                        endMinuteOfDay = it.endMinuteOfDay,
                        maxRatingScore = it.maxRatingScore,
                        minRatingScore = it.minRatingScore,
                        ruleLogicGroup = it.ruleLogicGroup,
                    )
                }
            },
        )
        val owner = backup.profiles.first { it.role == ProfileRole.OWNER.code }.id
        dao.putSettings(backup.settings.filterKeys { it !in Settings.DeviceOnly }.map { (key, value) -> SettingEntity(key, value) })
        dao.putSettings(listOf(SettingEntity(Settings.ActiveProfileId.name, Settings.ActiveProfileId.encode(owner))))

        // A channel the backup no longer has drops out of the channel ranks; its time still counts.
        val channelIds = backup.channels.mapTo(HashSet()) { it.id }
        val ids = dao.insertHistory(
            backup.history.map { view ->
                WatchEventEntity(
                    profileId = view.profileId,
                    source = codeOf(view.source),
                    channelId = view.channelId?.takeIf { it in channelIds },
                    liveChannelId = null,
                    mediaItemId = null,
                    title = view.title,
                    showTitle = view.showTitle,
                    mediaType = codeOf(view.mediaType),
                    startUtcMs = view.startUtcMs,
                    durationMs = view.durationMs,
                    localDay = view.localDay,
                    completed = view.completed,
                )
            },
        )
        dao.insertRestoredItems(
            backup.history.zip(ids).mapNotNull { (view, id) ->
                val serverId = view.serverId ?: return@mapNotNull null
                view.item?.let { RestoredItemEntity(id, serverId, it) }
            },
        )
        return owner
    }

    private companion object {
        const val TAG = "Backup"
    }
}
