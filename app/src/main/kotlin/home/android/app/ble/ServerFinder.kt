package home.android.app.ble

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

data class FoundServer(val host: String, val port: Int, val name: String)

/** Finds the Home server in the phone's Wi-Fi network via mDNS (_home._tcp). */
class ServerFinder(private val context: Context) {
    @Suppress("DEPRECATION")
    fun find(): Flow<FoundServer> = callbackFlow {
        val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val lock = wifi.createMulticastLock("home-mdns").apply { setReferenceCounted(false); acquire() }
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                close(IllegalStateException("mDNS: ошибка $errorCode"))
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onServiceLost(info: NsdServiceInfo) {}
            override fun onServiceFound(info: NsdServiceInfo) {
                nsd.resolveService(info, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(i: NsdServiceInfo, errorCode: Int) {}
                    override fun onServiceResolved(i: NsdServiceInfo) {
                        val host = i.host?.hostAddress ?: return
                        trySend(FoundServer(host, i.port, i.serviceName))
                    }
                })
            }
        }
        nsd.discoverServices("_home._tcp.", NsdManager.PROTOCOL_DNS_SD, listener)
        awaitClose {
            runCatching { nsd.stopServiceDiscovery(listener) }
            lock.release()
        }
    }
}
