package synchronice.android

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import synchronice.protocolclient.InstanceResolver
import java.net.Inet4Address
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class NsdInstanceResolver(context: Context) : InstanceResolver {
    private val manager = context.getSystemService(NsdManager::class.java)
    private var cachedName: String? = null
    private var cachedAddress: String? = null
    private var cachedAt = 0L

    @Synchronized
    override fun resolve(instanceName: String): String? {
        val now = System.nanoTime()
        if (cachedName == instanceName && now - cachedAt < TimeUnit.SECONDS.toNanos(3)) {
            return cachedAddress
        }

        val address = AtomicReference<String?>()
        val resolved = CountDownLatch(1)
        val resolving = AtomicBoolean()
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit

            override fun onServiceFound(service: NsdServiceInfo) {
                if (service.serviceName != instanceName || !resolving.compareAndSet(false, true)) return
                manager.resolveService(service, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                        resolved.countDown()
                    }

                    override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                        address.set((serviceInfo.host as? Inet4Address)?.hostAddress)
                        resolved.countDown()
                    }
                })
            }

            override fun onServiceLost(service: NsdServiceInfo) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                resolved.countDown()
                runCatching { manager.stopServiceDiscovery(this) }
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        }

        try {
            manager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
            resolved.await(2, TimeUnit.SECONDS)
        } catch (_: Exception) {
            // mDNS failure is expected when the PC is off the network; the client uses its last IP.
        } finally {
            runCatching { manager.stopServiceDiscovery(listener) }
        }

        cachedName = instanceName
        cachedAddress = address.get()
        cachedAt = System.nanoTime()
        return cachedAddress
    }

    private companion object {
        const val SERVICE_TYPE = "_synchronice._tcp."
    }
}
