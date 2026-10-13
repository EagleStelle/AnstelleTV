package tv.anstelle.webserver

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import tv.anstelle.core.common.AppLog
import tv.anstelle.core.common.ApplicationScope
import tv.anstelle.core.data.Settings
import tv.anstelle.core.data.SettingsRepository
import tv.anstelle.core.data.WebServerController
import tv.anstelle.core.data.WebServerStatus
import tv.anstelle.webserver.pairing.PairingManager
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
internal class WebServerControllerImpl @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
    @param:ApplicationScope private val scope: CoroutineScope,
    private val pairing: PairingManager,
) : WebServerController {
    private val _status = MutableStateFlow(WebServerStatus.Stopped)
    override val status: StateFlow<WebServerStatus> = _status.asStateFlow()

    override fun startIfEnabled() {
        scope.launch {
            if (!settings.get(Settings.WebServerEnabled)) return@launch
            runCatching {
                ContextCompat.startForegroundService(context, Intent(context, WebServerService::class.java))
            }.onFailure { AppLog.w(TAG, "Could not start the web server service", it) }
        }
    }

    override fun stop() {
        context.stopService(Intent(context, WebServerService::class.java))
    }

    override suspend fun unpair(clientId: Long) = pairing.revoke(clientId)

    /** Called by [WebServerService] whenever the server state changes. */
    fun publish(status: WebServerStatus) {
        _status.value = status
    }

    private companion object {
        const val TAG = "WebServer"
    }
}
