package tv.anstelle.webserver.api

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

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

@Serializable
data class SessionDto(val clientId: Long, val clientName: String, val deviceName: String, val version: String)

/** [role] is `owner`, `admin` or `member`. */
@Serializable
data class ProfileDto(val id: Long, val name: String, val role: String, val hasPin: Boolean)

@Serializable
data class ThemeDto(val id: Long, val name: String, val isBuiltIn: Boolean)

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
)

/** Sent over `/ws`. */
@Serializable
data class SocketMessage(
    val type: String,
    val tables: List<String> = emptyList(),
)
