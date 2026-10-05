package tv.anstelle.webserver

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.getSystemService
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tv.anstelle.core.common.AppLog
import tv.anstelle.core.data.Settings
import tv.anstelle.core.data.SettingsRepository
import tv.anstelle.core.data.WebServerStatus
import tv.anstelle.webserver.net.BindAddresses
import tv.anstelle.webserver.net.NetworkAddresses
import tv.anstelle.webserver.pairing.PairingManager
import javax.inject.Inject

/**
 * Foreground service that keeps the LAN web server alive while the app is in use or in the
 * background. It rebinds when the network changes and shows the pairing PIN in its
 * notification when the TV app is not on screen.
 */
@AndroidEntryPoint
class WebServerService : LifecycleService() {
    @Inject lateinit var server: AnstelleServer
    @Inject lateinit var settings: SettingsRepository
    @Inject lateinit var pairing: PairingManager
    @Inject internal lateinit var controller: WebServerControllerImpl

    private val networkTick = MutableStateFlow(0)
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startInForeground(buildNotification(text = getString(R.string.web_server_starting)))
        registerNetworkCallback()

        lifecycleScope.launch {
            combine(
                settings.observe(Settings.WebServerPort),
                networkTick.debounce(NETWORK_DEBOUNCE_MS),
            ) { port, _ -> port }
                .collect { port -> rebind(port) }
        }
        lifecycleScope.launch {
            combine(pairing.activeRequest, controller.status) { request, status -> request to status }
                .distinctUntilChanged()
                .collect { (request, status) -> updateNotification(request?.pin, status) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    override fun onDestroy() {
        networkCallback?.let { runCatching { getSystemService<ConnectivityManager>()?.unregisterNetworkCallback(it) } }
        server.stop()
        controller.publish(WebServerStatus.Stopped)
        super.onDestroy()
    }

    private suspend fun rebind(port: Int) {
        val addresses = withContext(Dispatchers.IO) { NetworkAddresses.current() }
        val status = withContext(Dispatchers.IO) {
            try {
                server.start(addresses, port)
                WebServerStatus(running = true, port = port, urls = urls(addresses, port), error = null)
            } catch (e: Exception) {
                AppLog.e(TAG, "Web server failed to start on port $port", e)
                server.stop()
                WebServerStatus(running = false, port = port, urls = emptyList(), error = e.message ?: e.javaClass.simpleName)
            }
        }
        controller.publish(status)
    }

    private fun urls(addresses: BindAddresses, port: Int) = addresses.lanHosts.map { "http://$it:$port" }

    private fun registerNetworkCallback() {
        val connectivity = getSystemService<ConnectivityManager>() ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = bump()
            override fun onLost(network: Network) = bump()
            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) = bump()
            private fun bump() {
                networkTick.value = networkTick.value + 1
            }
        }
        runCatching { connectivity.registerDefaultNetworkCallback(callback) }
            .onSuccess { networkCallback = callback }
            .onFailure { AppLog.w(TAG, "Network callback unavailable", it) }
    }

    private fun startInForeground(notification: Notification) {
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
    }

    private fun updateNotification(pin: String?, status: WebServerStatus) {
        val text = when {
            pin != null -> getString(R.string.web_server_pin, pin)
            status.running && status.urls.isNotEmpty() -> getString(R.string.web_server_running, status.urls.first())
            status.error != null -> getString(R.string.web_server_error, status.error)
            else -> getString(R.string.web_server_starting)
        }
        getSystemService<NotificationManager>()?.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_web_server)
            .setContentTitle(getString(R.string.web_server_title))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(packageManager.getLaunchIntentForPackage(packageName)?.let { launch ->
                android.app.PendingIntent.getActivity(
                    this, 0, launch,
                    android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT,
                )
            })
            .build()

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val channel = NotificationChannel(CHANNEL_ID, getString(R.string.web_server_channel), NotificationManager.IMPORTANCE_LOW)
        getSystemService<NotificationManager>()?.createNotificationChannel(channel)
    }

    private companion object {
        const val TAG = "WebServer"
        const val CHANNEL_ID = "web_server"
        const val NOTIFICATION_ID = 8765
        const val NETWORK_DEBOUNCE_MS = 1_000L
    }
}
