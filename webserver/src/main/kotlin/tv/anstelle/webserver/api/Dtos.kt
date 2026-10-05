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

@Serializable
data class PairStartRequest(val clientName: String)

@Serializable
data class PairStartResponse(val requestId: String, val expiresInSeconds: Long, val deviceName: String)

@Serializable
data class PairConfirmRequest(val requestId: String, val pin: String)

@Serializable
data class PairConfirmResponse(val clientId: Long, val clientName: String)

@Serializable
data class SessionDto(val clientId: Long, val clientName: String, val deviceName: String, val version: String)

@Serializable
data class ProfileDto(val id: Long, val name: String, val isKid: Boolean, val hasPin: Boolean)

@Serializable
data class ChannelDto(
    val id: Long,
    val number: Int,
    val name: String,
    val logoUri: String?,
    val isHidden: Boolean,
    val isFavorite: Boolean,
    val groupId: Long?,
)

/** Sent over `/ws`. */
@Serializable
data class SocketMessage(
    val type: String,
    val tables: List<String> = emptyList(),
)
