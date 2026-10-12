package tv.anstelle.core.data.backup

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import androidx.core.content.getSystemService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import tv.anstelle.core.common.AppInfo
import tv.anstelle.core.common.AppLog
import java.io.Closeable
import java.net.Inet6Address
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/** Another AnstelleTV on the network, by the address its web control answers at (`http://192.168.1.20:8765`). */
data class Peer(val name: String, val address: String)

/**
 * AnstelleTV devices find each other on the local network with DNS-SD (`_anstelletv._tcp`): each
 * one serving web control [advertise]s itself, and a device about to copy a backup lists the others
 * ([observe]). Networks that block multicast hide them; the address can still be typed.
 */
@Singleton
class NearbyDevices @Inject constructor(
    @ApplicationContext private val context: Context,
    private val appInfo: AppInfo,
) {
    /** The name this device advertises under, once the network accepted it, so it never lists itself. */
    @Volatile
    private var ownName: String? = null

    /** Advertises web control on [port] until the result is closed. */
    fun advertise(port: Int): Closeable {
        val nsd = context.getSystemService<NsdManager>() ?: return Closeable { }
        val info = NsdServiceInfo().apply {
            serviceName = appInfo.deviceName
            serviceType = SERVICE_TYPE
            setPort(port)
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(registered: NsdServiceInfo) {
                // The network may rename it when another device took the name first.
                ownName = registered.serviceName
                AppLog.i(TAG, "Advertising ${registered.serviceName} on port $port")
            }

            override fun onRegistrationFailed(failed: NsdServiceInfo, errorCode: Int) {
                AppLog.w(TAG, "Couldn't advertise on the network (error $errorCode)")
            }

            override fun onServiceUnregistered(unregistered: NsdServiceInfo) {
                ownName = null
            }

            override fun onUnregistrationFailed(failed: NsdServiceInfo, errorCode: Int) = Unit
        }
        runCatching { nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener) }
            .onFailure { AppLog.w(TAG, "Couldn't advertise on the network", it) }
        return Closeable { runCatching { nsd.unregisterService(listener) } }
    }

    /** The other devices on the network, as they're found and lost. */
    fun observe(): Flow<List<Peer>> = callbackFlow {
        val nsd = context.getSystemService<NsdManager>()
        if (nsd == null) {
            send(emptyList())
            awaitClose { }
            return@callbackFlow
        }
        // Found and lost services queue up and are handled in order, one at a time: older systems
        // resolve one service at a time.
        val events = Channel<Pair<NsdServiceInfo, Boolean>>(Channel.UNLIMITED)
        launch {
            val found = LinkedHashMap<String, Peer>()
            for ((info, isFound) in events) {
                if (isFound) {
                    found[info.serviceName] = resolve(nsd, info) ?: continue
                } else if (found.remove(info.serviceName) == null) {
                    continue
                }
                send(found.values.toList())
            }
        }
        val listener = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(info: NsdServiceInfo) {
                if (info.serviceName != ownName) events.trySend(info to true)
            }

            override fun onServiceLost(info: NsdServiceInfo) {
                events.trySend(info to false)
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                AppLog.w(TAG, "Couldn't look for devices (error $errorCode)")
                trySend(emptyList())
            }

            override fun onDiscoveryStarted(serviceType: String) {
                trySend(emptyList())
            }

            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        }
        runCatching { nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener) }
            .onFailure {
                AppLog.w(TAG, "Couldn't look for devices", it)
                trySend(emptyList())
            }
        awaitClose {
            events.close()
            runCatching { nsd.stopServiceDiscovery(listener) }
        }
    }

    @Suppress("DEPRECATION")
    private suspend fun resolve(nsd: NsdManager, info: NsdServiceInfo): Peer? = suspendCancellableCoroutine { done ->
        val listener = object : NsdManager.ResolveListener {
            override fun onServiceResolved(resolved: NsdServiceInfo) {
                val host = resolved.host
                val address = when {
                    host == null -> null
                    host is Inet6Address -> "http://[${host.hostAddress?.substringBefore('%')}]:${resolved.port}"
                    else -> "http://${host.hostAddress}:${resolved.port}"
                }
                done.resume(address?.let { Peer(resolved.serviceName, it) })
            }

            override fun onResolveFailed(failed: NsdServiceInfo, errorCode: Int) {
                AppLog.w(TAG, "Couldn't reach ${failed.serviceName} (error $errorCode)")
                done.resume(null)
            }
        }
        runCatching { nsd.resolveService(info, listener) }.onFailure { done.resume(null) }
    }

    companion object {
        private const val TAG = "Nearby"
        const val SERVICE_TYPE = "_anstelletv._tcp"
    }
}
