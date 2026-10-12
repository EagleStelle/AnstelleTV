package tv.anstelle.core.data.backup

import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import tv.anstelle.core.model.ChannelKind
import tv.anstelle.core.model.MediaType
import tv.anstelle.core.model.PlaybackOrder
import tv.anstelle.core.model.ProfileRole
import tv.anstelle.core.model.RuleField
import tv.anstelle.core.model.RuleOp
import tv.anstelle.core.model.SeasonMode
import tv.anstelle.core.model.ThemeSpec
import tv.anstelle.core.model.WatchSource
import tv.anstelle.core.model.codeOf

/** A file that isn't a backup this app can restore, with what to tell the user. */
class BackupException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Everything a device holds that the library can't rebuild: servers without their sign-ins,
 * profiles, themes, channels with their groups, settings, and the dashboards' history. Ids are
 * the device's own, so references inside the file hold; a restore keeps them. Enums are their
 * stable codes, never names, like the database stores them.
 */
@Serializable
data class Backup(
    val format: String = FORMAT,
    val version: Int = VERSION,
    val createdAtUtcMs: Long,
    /** The app version that made it. */
    val app: String,
    /** The device it came from, as it names itself to browsers. */
    val device: String,
    val servers: List<BackupServer> = emptyList(),
    val profiles: List<BackupProfile> = emptyList(),
    val themes: List<BackupTheme> = emptyList(),
    val groups: List<BackupGroup> = emptyList(),
    val channels: List<BackupChannel> = emptyList(),
    val settings: Map<String, String> = emptyMap(),
    val history: List<BackupView> = emptyList(),
) {
    companion object {
        const val FORMAT = "anstelletv-backup"

        /** Raised whenever the file changes in a way an older app can't read. */
        const val VERSION = 1
    }
}

/** A Jellyfin server and the libraries it syncs; never its token. */
@Serializable
data class BackupServer(
    val id: Long,
    val jellyfinServerId: String,
    val name: String,
    val baseUrl: String,
    val userId: String? = null,
    val userName: String? = null,
    val serverVersion: String? = null,
    val createdAtUtcMs: Long,
    val libraries: List<BackupLibrary> = emptyList(),
)

/** A library and whether it is synced; its items come back with the next sync. */
@Serializable
data class BackupLibrary(
    val remoteId: String,
    val name: String,
    val collectionType: String? = null,
    val enabled: Boolean,
)

/** A profile with its role, PIN hash, rating limit and theme ([builtInTheme] by key, or [customTheme] by id). */
@Serializable
data class BackupProfile(
    val id: Long,
    val name: String,
    val role: Int,
    val maxRatingScore: Int? = null,
    val pinHash: String? = null,
    val pictureUrl: String? = null,
    val builtInTheme: String? = null,
    val customTheme: Long? = null,
    val sortOrder: Int,
    val createdAtUtcMs: Long,
    val accounts: List<BackupAccount> = emptyList(),
)

/** A profile's own Jellyfin user on a server, without its token. */
@Serializable
data class BackupAccount(
    val serverId: Long,
    val userId: String,
    val userName: String,
    val device: String,
)

/** A theme someone made or imported; the built-in ones ship with the app. */
@Serializable
data class BackupTheme(
    val id: Long,
    val name: String,
    val json: String,
    val updatedAtUtcMs: Long,
)

@Serializable
data class BackupGroup(
    val id: Long,
    val name: String,
    val sortOrder: Int,
    val numberStart: Int,
    val libraryKey: String? = null,
)

/**
 * A channel with its lineup, seasons and dayparts. Library channels come along too, so their
 * numbers, favorites and dashboard history keep their place until the library sync takes them
 * over again. [logo] is the stored PNG in Base64.
 */
@Serializable
data class BackupChannel(
    val id: Long,
    val number: Int,
    val name: String,
    val logo: String? = null,
    val kind: Int,
    val seed: Long,
    val seriesOrder: Int,
    val seriesBlock: Int,
    val movieOrder: Int,
    val movieBlock: Int,
    val moviesOncePerDay: Boolean,
    val hidden: Boolean,
    val favorite: Boolean,
    val groupId: Long,
    val autoKey: String? = null,
    val createdAtUtcMs: Long,
    val updatedAtUtcMs: Long,
    val rules: List<BackupRule> = emptyList(),
    val seasons: List<BackupSeason> = emptyList(),
    val dayparts: List<BackupDaypart> = emptyList(),
)

/** A lineup rule; [seasonId] ties it to one of its channel's seasons. */
@Serializable
data class BackupRule(
    val seasonId: Long? = null,
    val logicGroup: Int,
    val field: Int,
    val op: Int,
    val value: String,
    val weight: Float,
)

@Serializable
data class BackupSeason(
    val id: Long,
    val name: String,
    val mode: Int,
    val startMonth: Int,
    val startDay: Int,
    val endMonth: Int,
    val endDay: Int,
)

@Serializable
data class BackupDaypart(
    val name: String,
    val startMinuteOfDay: Int,
    val endMinuteOfDay: Int,
    val maxRatingScore: Int? = null,
    val minRatingScore: Int? = null,
    val ruleLogicGroup: Int? = null,
)

/**
 * One dashboard row. The item is named by its server and Jellyfin id ([item]), since the library
 * on the device restoring it gets ids of its own.
 */
@Serializable
data class BackupView(
    val profileId: Long,
    val source: Int,
    val channelId: Long? = null,
    val serverId: Long? = null,
    val item: String? = null,
    val title: String,
    val showTitle: String? = null,
    val mediaType: Int,
    val startUtcMs: Long,
    val durationMs: Long,
    val localDay: Long,
    val completed: Boolean,
)

/** What a backup holds, counted, so a restore can say what it brings. */
data class BackupSummary(
    val profiles: Int = 0,
    val channels: Int = 0,
    val customChannels: Int = 0,
    val groups: Int = 0,
    val themes: Int = 0,
    val servers: Int = 0,
    /** Days the dashboards hold, across every profile. */
    val historyDays: Int = 0,
)

fun Backup.summary() = BackupSummary(
    profiles = profiles.size,
    channels = channels.size,
    customChannels = channels.count { it.autoKey == null },
    groups = groups.size,
    themes = themes.size,
    servers = servers.size,
    historyDays = history.mapTo(HashSet()) { it.localDay }.size,
)

/** Reads and writes backup files. */
object BackupFormat {
    private val json = Json {
        ignoreUnknownKeys = true
        // The format and version are defaults, and every file must name them.
        encodeDefaults = true
        explicitNulls = false
    }

    fun encode(backup: Backup): String = json.encodeToString(Backup.serializer(), backup)

    /** Parses and checks a backup file; throws [BackupException] with what's wrong. */
    fun decode(text: String): Backup {
        val backup = try {
            json.decodeFromString(Backup.serializer(), text)
        } catch (e: SerializationException) {
            throw BackupException(NOT_A_BACKUP, e)
        } catch (e: IllegalArgumentException) {
            throw BackupException(NOT_A_BACKUP, e)
        }
        backup.problem()?.let { throw BackupException(it) }
        return backup
    }

    const val NOT_A_BACKUP = "That file isn't an AnstelleTV backup."
    const val NEWER = "That backup comes from a newer AnstelleTV. Update this one first."
    const val DAMAGED = "That backup is damaged, so nothing was changed."
}

/** Why the backup can't be restored, or null when it can: wrong file, a newer version, or references that don't hold. */
fun Backup.problem(): String? {
    if (format != Backup.FORMAT || version < 1) return BackupFormat.NOT_A_BACKUP
    if (version > Backup.VERSION) return BackupFormat.NEWER
    if (profiles.count { it.role == ProfileRole.OWNER.code } != 1) return BackupFormat.DAMAGED
    val damaged = try {
        !holdsTogether()
    } catch (e: IllegalArgumentException) {
        // An enum code this version doesn't know, or a theme that doesn't parse.
        true
    }
    return if (damaged) BackupFormat.DAMAGED else null
}

/** Every reference points at something in the file, every id is unique, every code is known. */
private fun Backup.holdsTogether(): Boolean {
    val serverIds = servers.map { it.id }
    val profileIds = profiles.map { it.id }
    val themeIds = themes.map { it.id }
    val groupIds = groups.map { it.id }
    val channelIds = channels.map { it.id }
    val seasonIds = channels.flatMap { channel -> channel.seasons.map { it.id } }
    val unique = listOf(serverIds, profileIds, themeIds, groupIds, channelIds, seasonIds).all { it.size == it.toSet().size } &&
        servers.map { it.jellyfinServerId }.let { it.size == it.toSet().size } &&
        groups.mapNotNull { it.libraryKey }.let { it.size == it.toSet().size }
    if (!unique) return false

    profiles.forEach { profile ->
        codeOf<ProfileRole>(profile.role)
        if (profile.customTheme != null && profile.customTheme !in themeIds) return false
        if (profile.accounts.any { it.serverId !in serverIds }) return false
    }
    themes.forEach { ThemeSpec.parse(it.json).validate() }
    if (groups.any { it.numberStart !in 0..9999 }) return false
    channels.forEach { channel ->
        if (channel.groupId !in groupIds) return false
        codeOf<ChannelKind>(channel.kind)
        codeOf<PlaybackOrder>(channel.seriesOrder)
        codeOf<PlaybackOrder>(channel.movieOrder)
        val seasons = channel.seasons.map { it.id }.toSet()
        channel.seasons.forEach { codeOf<SeasonMode>(it.mode) }
        channel.rules.forEach { rule ->
            codeOf<RuleField>(rule.field)
            codeOf<RuleOp>(rule.op)
            if (rule.seasonId != null && rule.seasonId !in seasons) return false
        }
    }
    history.forEach { view ->
        if (view.profileId !in profileIds) return false
        if (view.serverId != null && view.serverId !in serverIds) return false
        codeOf<WatchSource>(view.source)
        codeOf<MediaType>(view.mediaType)
    }
    return true
}
