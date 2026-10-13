package tv.anstelle.core.data

import kotlinx.coroutines.flow.StateFlow

data class WebServerStatus(
    val running: Boolean,
    val port: Int,
    /** Addresses browsers on the LAN can open, e.g. `http://192.168.1.20:8765`. */
    val urls: List<String>,
    val error: String?,
) {
    companion object {
        val Stopped = WebServerStatus(running = false, port = 0, urls = emptyList(), error = null)
    }
}

/**
 * Starts and stops the embedded LAN web server. Implemented by the `webserver` module so
 * feature screens can show the server state without depending on Ktor.
 */
interface WebServerController {
    val status: StateFlow<WebServerStatus>

    /** Starts the server if it is enabled in settings. Must be called while the app is in the foreground. */
    fun startIfEnabled()

    fun stop()

    /** Unpairs a browser or device at once, whatever it is doing. */
    suspend fun unpair(clientId: Long)
}
