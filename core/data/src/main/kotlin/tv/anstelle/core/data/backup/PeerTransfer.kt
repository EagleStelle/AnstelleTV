package tv.anstelle.core.data.backup

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import tv.anstelle.core.common.AnstelleDispatchers
import tv.anstelle.core.common.AppInfo
import tv.anstelle.core.common.AppLog
import tv.anstelle.core.common.Dispatcher
import tv.anstelle.core.data.Settings
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** Copying from another device didn't work, in words for the user; [wrongPin] means the PIN can be tried again. */
class PeerException(message: String, val wrongPin: Boolean = false) : Exception(message)

/** A pairing the other device shows a PIN for, and the name it goes by. */
data class Pairing(val requestId: String, val deviceName: String)

/**
 * Copies a backup from another AnstelleTV over the network, the way a browser pairs with its web
 * control: the other device shows a PIN, this one sends it back and gets a token, downloads the
 * backup with it (`GET /api/backup/export`), then unpairs.
 */
@Singleton
class PeerTransfer @Inject constructor(
    private val http: OkHttpClient,
    private val appInfo: AppInfo,
    @Dispatcher(AnstelleDispatchers.IO) private val io: CoroutineDispatcher,
) {
    /** Asks the device at [address] to show a pairing PIN. Returns the pairing and the device's own name. */
    suspend fun ask(address: String): Pairing = withContext(io) {
        val url = urlOf(address)
        val body = json.encodeToString(PairStart.serializer(), PairStart(appInfo.deviceName, device = true))
        call(url, Request.Builder().url(url.resolve("/api/pair/start")!!).post(body.toRequestBody(JSON_TYPE)).build()) { response ->
            val started = json.decodeFromString(PairStarted.serializer(), response.body.string())
            Pairing(started.requestId, started.deviceName ?: url.host)
        }
    }

    /** Pairs with the [pin] the device at [address] shows, downloads its backup and unpairs. */
    suspend fun fetch(address: String, requestId: String, pin: String): Backup = withContext(io) {
        val url = urlOf(address)
        val body = json.encodeToString(PairConfirm.serializer(), PairConfirm(requestId, pin))
        val token = call(url, Request.Builder().url(url.resolve("/api/pair/confirm")!!).post(body.toRequestBody(JSON_TYPE)).build()) { response ->
            response.headers("Set-Cookie").firstNotNullOfOrNull { cookie ->
                cookie.substringBefore(';').takeIf { it.startsWith("$TOKEN_COOKIE=") }?.substringAfter('=')
            } ?: throw PeerException("${url.host} didn't pair. Try again.")
        }
        try {
            val text = call(url, Request.Builder().url(url.resolve("/api/backup/export")!!).header("Authorization", "Bearer $token").build()) { it.body.string() }
            BackupFormat.decode(text)
        } finally {
            // The pairing was only for this copy.
            runCatching { http.newCall(Request.Builder().url(url.resolve("/api/session")!!).header("Authorization", "Bearer $token").delete().build()).execute().close() }
        }
    }

    /** Runs [request]; a refusal or an unreachable device becomes a [PeerException] saying why. */
    private fun <T> call(url: HttpUrl, request: Request, read: (Response) -> T): T {
        val response = try {
            http.newCall(request).execute()
        } catch (e: IOException) {
            AppLog.w(TAG, "${url.host} unreachable: ${e.message}")
            throw PeerException("Couldn't reach ${url.host}. Check that it's on, on this network, with Web control on.")
        }
        return response.use {
            if (it.isSuccessful) return@use read(it)
            val error = runCatching { json.decodeFromString(ErrorBody.serializer(), it.body.string()) }.getOrNull()
            throw PeerException(error?.message ?: "${url.host} refused (${it.code}).", wrongPin = error?.error == "wrong_pin")
        }
    }

    private fun urlOf(address: String): HttpUrl = normalize(address)?.toHttpUrlOrNull() ?: throw PeerException("That doesn't look like an address.")

    @Serializable private class PairStart(val clientName: String, val device: Boolean)
    @Serializable private class PairStarted(val requestId: String, val deviceName: String? = null)
    @Serializable private class PairConfirm(val requestId: String, val pin: String)
    @Serializable private class ErrorBody(val error: String = "", val message: String? = null)

    companion object {
        private const val TAG = "Peer"
        private const val TOKEN_COOKIE = "anstelle_token"
        private val JSON_TYPE = "application/json".toMediaType()
        private val json = Json { ignoreUnknownKeys = true }

        /**
         * A device address as typed ("192.168.1.20", "192.168.1.20:8765", "http://tv.local:8765/")
         * as the web control's base URL; the port defaults to web control's own. Null when it isn't one.
         */
        fun normalize(input: String): String? {
            val bare = input.trim().removePrefix("http://").trimEnd('/')
            if (bare.isEmpty() || bare.startsWith("https://") || bare.any { it.isWhitespace() || it == '/' }) return null
            val hasPort = if (bare.startsWith("[")) "]:" in bare else ':' in bare
            val withPort = if (hasPort) bare else "$bare:${Settings.WebServerPort.default}"
            return "http://$withPort".toHttpUrlOrNull()?.let { "http://$withPort" }
        }
    }
}
