package tv.anstelle.webserver.api

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

internal val ApiJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
}

@Serializable
data class HealthDto(
    val app: String,
    val version: String,
    val deviceName: String,
    val isTv: Boolean,
)

@Serializable
data class ErrorDto(val error: String, val message: String)

/** [device] is true when another AnstelleTV pairs to copy the backup, not a browser. */
@Serializable
data class PairStartRequest(val clientName: String, val device: Boolean = false)

@Serializable
data class PairStartResponse(val requestId: String, val expiresInSeconds: Long, val deviceName: String)

@Serializable
data class PairConfirmRequest(val requestId: String, val pin: String)

@Serializable
data class PairConfirmResponse(val clientId: Long, val clientName: String)

/** The profile a pairing speaks for. [role] is `owner` or `admin`. */
@Serializable
data class SessionProfileDto(val id: Long, val name: String, val role: String)

@Serializable
data class SessionDto(
    val clientId: Long,
    val clientName: String,
    val deviceName: String,
    val version: String,
    val isTv: Boolean,
    val profile: SessionProfileDto,
)

/** What the home's rows and readouts say about each area, like the app's settings home. */
@Serializable
data class OverviewDto(
    val servers: ServersOverviewDto,
    val channels: ChannelStatsDto,
    val onDemand: OnDemandOverviewDto,
)

/** Every server as one: how their syncing stands and what their libraries hold together. */
@Serializable
data class ServersOverviewDto(
    val count: Int,
    /** The only server's name, when there is one. */
    val name: String?,
    /** Servers signed out or whose last sync failed. */
    val needAttention: Int,
    /** Servers syncing now. */
    val syncing: Int,
    /** The newest sync of any server. */
    val lastSyncUtcMs: Long?,
    val movies: Int,
    val series: Int,
    val episodes: Int,
)

@Serializable
data class ChannelStatsDto(
    val total: Int,
    val custom: Int,
    val library: Int,
    val visible: Int,
    val favorites: Int,
    val hidden: Int,
    val groups: Int,
)

/** [summary] is the home row's line, "10 per banner". */
@Serializable
data class OnDemandOverviewDto(val summary: String, val homeBanner: Int, val kinds: List<KindOverviewDto>)

/** A catalog tab: its banner's size and the row it opens with, by name. [key] is `movies` or `series`. */
@Serializable
data class KindOverviewDto(val key: String, val label: String, val banner: Int, val firstRow: String?)

// Web control

@Serializable
data class ClientDto(
    val id: Long,
    val name: String,
    val address: String?,
    val userAgent: String?,
    val lastSeenUtcMs: Long,
    /** The profile that paired it. */
    val profileName: String?,
    /** The browser asking. */
    val you: Boolean,
)

@Serializable
data class WebDto(val running: Boolean, val urls: List<String>, val clients: List<ClientDto>)

// Servers

@Serializable
data class ServerDto(
    val id: Long,
    val name: String,
    val address: String,
    val version: String?,
    val userName: String?,
    val isSignedIn: Boolean,
    val lastSyncUtcMs: Long?,
    val lastSyncError: String?,
    /** The running sync's progress line, or null while none runs. */
    val progress: String?,
)

@Serializable
data class CountDto(val type: String, val count: Int)

@Serializable
data class LibraryDto(val id: Long, val name: String, val kind: String, val enabled: Boolean, val canAir: Boolean)

@Serializable
data class ServerDetailDto(val server: ServerDto, val counts: List<CountDto>, val libraries: List<LibraryDto>)

@Serializable
data class SyncRequest(val full: Boolean = false)

@Serializable
data class EnabledRequest(val enabled: Boolean)

@Serializable
data class FindServerRequest(val address: String)

@Serializable
data class FoundServerDto(val signInId: String, val name: String, val address: String, val version: String, val quickConnect: Boolean)

@Serializable
data class PasswordRequest(val username: String, val password: String = "")

@Serializable
data class QuickConnectDto(val code: String)

/** [state] is `idle`, `waiting`, `signedIn` or `failed`. */
@Serializable
data class SignInStateDto(val state: String, val code: String? = null, val serverId: Long? = null, val message: String? = null)

// Channels

@Serializable
data class GroupDto(
    val id: Long,
    val name: String,
    val start: Int,
    val fromLibrary: Boolean,
    val channelCount: Int,
)

/** [lastGroupId] is the group the last channel built went into, which a new one starts with. */
@Serializable
data class GroupsDto(val groups: List<GroupDto>, val nextStart: Int, val lastGroupId: Long? = null)

@Serializable
data class GroupRequest(val name: String? = null, val start: Int? = null)

@Serializable
data class MoveRequest(val delta: Int)

@Serializable
data class ChannelDto(
    val id: Long,
    val number: Int,
    val name: String,
    /** Where the browser loads the logo from, or null when the channel has none. */
    val logoUrl: String?,
    val isHidden: Boolean,
    val isFavorite: Boolean,
    val groupId: Long,
    val groupName: String,
    val fromLibrary: Boolean,
    /** "Dec 1 – Dec 31" for a seasonal channel. */
    val seasonLabel: String? = null,
    val inSeason: Boolean = true,
)

@Serializable
data class ChannelsDto(val channels: List<ChannelDto>)

@Serializable
data class PlaybackDto(val order: String, val blockSize: Int = 1)

@Serializable
data class ChoiceDto(val playback: PlaybackDto, val label: String, val hint: String)

@Serializable
data class AiringDto(val startUtcMs: Long, val endUtcMs: Long, val title: String?)

@Serializable
data class ChannelDetailDto(
    val channel: ChannelDto,
    val series: PlaybackDto,
    val movies: PlaybackDto,
    val oncePerDay: Boolean,
    val airings: List<AiringDto>,
    val summary: String,
    val airsSeries: Boolean,
    val airsMovies: Boolean,
    val fitsOncePerDay: Boolean,
    val seriesChoices: List<ChoiceDto>,
    val movieChoices: List<ChoiceDto>,
)

@Serializable
data class ChannelPatch(
    val name: String? = null,
    val groupId: Long? = null,
    val favorite: Boolean? = null,
    val visible: Boolean? = null,
    val series: PlaybackDto? = null,
    val movies: PlaybackDto? = null,
    val oncePerDay: Boolean? = null,
)

@Serializable
data class SettingRequest(val value: Int)

// On Demand

/** On Demand's settings, shared by every profile: Home's banner, then each catalog tab's banner and rows. */
@Serializable
data class OnDemandDto(
    val homeBanner: Int,
    /** "10 channels": the sizes Home's banner can take. */
    val homeChoices: List<LabelledInt>,
    val kinds: List<CatalogKindDto>,
)

/** A catalog tab. [key] is `movies` or `series`; [bannerChoices] say "10 movies". */
@Serializable
data class CatalogKindDto(
    val key: String,
    val label: String,
    val banner: Int,
    val bannerChoices: List<LabelledInt>,
    /** Its rows in the order the tab shows them. */
    val rows: List<ShelfDto>,
)

/**
 * One row of a catalog tab. [key] is what an order is stored by; [type] is `continue`,
 * `nextup`, `recent` or `channel`, and a channel's row carries its [channelNumber].
 */
@Serializable
data class ShelfDto(val key: String, val type: String, val label: String, val channelNumber: Int? = null)

@Serializable
data class RowsRequest(val keys: List<String>)

// Lineups

@Serializable
data class RuleDto(val field: String, val op: String, val values: List<String>, val label: String? = null)

@Serializable
data class RuleGroupDto(val rules: List<RuleDto>, val weight: Float = 1f)

/** [kind] is `series` or `movie`. */
@Serializable
data class PickDto(val kind: String, val remoteId: String, val weight: Float = 1f, val title: String? = null)

@Serializable
data class LineupDto(
    val groups: List<RuleGroupDto> = emptyList(),
    val includes: List<PickDto> = emptyList(),
    val excludes: List<PickDto> = emptyList(),
)

@Serializable
data class NewChannelRequest(val name: String, val groupId: Long, val lineup: LineupDto)

@Serializable
data class CreatedDto(val id: Long)

@Serializable
data class ReachDto(
    val episodes: Int,
    val movies: Int,
    val videos: Int,
    val series: Int,
    val hours: Long,
    val episodeShare: Float,
    val movieShare: Float,
    val videoShare: Float,
)

/** A lineup as the builder shows it: rules described, picks titled, and what it airs. */
@Serializable
data class LineupPreviewDto(val lineup: LineupDto, val reach: ReachDto)

@Serializable
data class OpDto(val op: String, val label: String, val typed: Boolean, val single: Boolean)

@Serializable
data class FieldDto(val field: String, val label: String, val hint: String, val ops: List<OpDto>)

@Serializable
data class WeightDto(val weight: Float, val label: String, val hint: String)

@Serializable
data class BuilderDto(val fields: List<FieldDto>, val weights: List<WeightDto>)

@Serializable
data class OptionDto(val value: String, val label: String, val count: Int)

// Themes

@Serializable
data class ThemeDto(
    val id: Long,
    val name: String,
    val isBuiltIn: Boolean,
    /** Ground, rows, text and the accent, for the list's swatches. */
    val swatches: List<String> = emptyList(),
    /** The theme the paired profile wears. */
    val inUse: Boolean = false,
    /** The theme's JSON, for the list's preview of the theme pointed at. */
    val spec: JsonObject? = null,
)

/** The theme the paired profile wears, which the web UI wears too. [spec] is its JSON. */
@Serializable
data class WornThemeDto(val id: Long, val spec: JsonObject)

@Serializable
data class UseThemeRequest(val themeId: Long)

@Serializable
data class ColorRoleDto(val key: String, val label: String, val about: String, val hex: String)

@Serializable
data class LabelledFloat(val value: Float, val label: String)

@Serializable
data class LabelledInt(val value: Int, val label: String)

@Serializable
data class LabelledName(val value: String, val label: String)

/** What the designer offers for the type, guide and effect choices. */
@Serializable
data class DesignChoicesDto(
    val fonts: List<LabelledName>,
    val textSizes: List<LabelledFloat>,
    val densities: List<LabelledName>,
    val radii: List<LabelledInt>,
    val scanlines: List<LabelledFloat>,
)

/** Whether two colors read well against each other, in words for each way of fixing it. */
@Serializable
data class ReadabilityDto(
    val foreground: String,
    val background: String,
    val ok: Boolean,
    /** Said on the designer's main page. */
    val text: String,
    /** Said in the foreground color's own pane. */
    val inForeground: String,
    /** Said in the background color's own pane. */
    val inBackground: String,
)

/** A theme opened in the designer. [spec] is the theme's JSON, as its file holds it. */
@Serializable
data class ThemeDesignDto(
    val id: Long,
    val isBuiltIn: Boolean,
    val spec: JsonObject,
    val roles: List<ColorRoleDto>,
    val readability: List<ReadabilityDto>,
    val choices: DesignChoicesDto,
)

@Serializable
data class ThemeSaveRequest(val spec: JsonObject)

// Backup

@Serializable
data class BackupDto(
    val profiles: Int,
    val channels: Int,
    val groups: Int,
    val themes: Int,
    val servers: Int,
    val historyDays: Int,
    val savedUtcMs: Long?,
    val canRestore: Boolean,
)

@Serializable
data class BackupCheckDto(val question: String, val explanation: String)

/** Sent over `/ws`. */
@Serializable
data class SocketMessage(
    val type: String,
    val tables: List<String> = emptyList(),
)
