package tv.anstelle.webserver

import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import tv.anstelle.core.common.AppLog
import tv.anstelle.webserver.net.BindAddresses
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/** Owns the Ktor CIO engine. Not thread-safe; the service calls it from one coroutine. */
@Singleton
class AnstelleServer @Inject constructor(
    private val dependencies: Provider<ServerDependencies>,
) {
    private var engine: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null
    private var boundTo: BindAddresses? = null
    private var boundPort: Int = 0

    val isRunning: Boolean get() = engine != null

    /** Starts listening on [addresses] and [port], restarting if either changed. */
    fun start(addresses: BindAddresses, port: Int) {
        if (engine != null && addresses == boundTo && port == boundPort) return
        stop()
        val deps = dependencies.get()
        val server = embeddedServer(
            factory = CIO,
            configure = {
                for (host in addresses.hosts) {
                    connector {
                        this.host = host
                        this.port = port
                    }
                }
                connectionIdleTimeoutSeconds = IDLE_TIMEOUT_SECONDS
            },
            module = { anstelleModule(deps) },
        )
        server.start(wait = false)
        engine = server
        boundTo = addresses
        boundPort = port
        AppLog.i(TAG, "Web server listening on ${addresses.hosts.joinToString()} port $port")
    }

    fun stop() {
        val current = engine ?: return
        engine = null
        boundTo = null
        current.stop(gracePeriodMillis = 200, timeoutMillis = 1_000)
        AppLog.i(TAG, "Web server stopped")
    }

    private companion object {
        const val TAG = "WebServer"
        const val IDLE_TIMEOUT_SECONDS = 45
    }
}
