package com.theveloper.pixelplay.data.p2p

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import java.net.Inet4Address
import java.net.NetworkInterface
import timber.log.Timber

/**
 * mDNS advertiser + browser for `_musicshare._tcp` (docs/PROTOCOL.md section 2), built on
 * NsdManager. Needs no Android permission; local-network enforcement on newer images is
 * caught and reported as a failed announce instead of crashing.
 *
 * Discovery churn defenses carried from the reference MVP (measured on API 37, REPORT.md
 * defect 6): every removal hint passes through [DiscoveryChurnGate] — a 2.5 s debounce with
 * heal-on-refound inside the current browse generation, a 30 s quiet period after each browse
 * start, and stale-generation suppression; a 45 s re-browse cycle keeps lastSeen fresh so the
 * 60 s expiry backstop only fires for genuinely silent peers.
 */
class NsdPeerDiscovery(
    private val localId: String,
    context: Context,
    private val listener: Listener,
) : PeerDiscoveryHandle {

    interface Listener {
        fun onPeerFound(peerId: String, name: String, host: String, port: Int, elapsedDiscoveryMs: Long)
        fun onPeerRemoved(peerId: String, reason: String)
        fun onAnnounceResult(ok: Boolean, detail: String)
    }

    private val appContext = context.applicationContext
    private val nsd = appContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val wifi = appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val multicastLock = wifi.createMulticastLock("pixelplay-p2p").apply { setReferenceCounted(false) }
    private val handler = Handler(Looper.getMainLooper())
    private val churnGate = DiscoveryChurnGate()

    private var regListener: NsdManager.RegistrationListener? = null
    private var registerRetries = 0
    private var discListener: NsdManager.DiscoveryListener? = null
    private var browseGeneration = 0
    private var discoveryStartMs = 0L
    private val instanceToId = HashMap<String, String>()
    private val rebrowseRunnable = Runnable { rebrowse() }
    private var registerRetryRunnable: Runnable? = null

    override fun register(localName: String, port: Int) {
        acquireLock("register")
        val info = NsdServiceInfo().apply {
            serviceName = "musicshare-$localId"
            serviceType = P2pProtocolSpec.SERVICE_TYPE
            this.port = port
            setAttribute(P2pProtocolSpec.TXT_ID, localId.toByteArray(Charsets.UTF_8))
            setAttribute(P2pProtocolSpec.TXT_NAME, localName.toByteArray(Charsets.UTF_8).copyOfSeries())
            setAttribute(P2pProtocolSpec.TXT_V, P2pProtocolSpec.PROTOCOL_VERSION.toByteArray(Charsets.UTF_8))
        }
        val rl = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                registerRetries = 0
                Timber.tag("P2P").i("nsd registered: %s port=%d", serviceInfo.serviceName, port)
                listener.onAnnounceResult(true, "port=$port")
            }

            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Timber.tag("P2P").w("nsd register failed code=%d", errorCode)
                listener.onAnnounceResult(false, "register_failed code=$errorCode")
            }

            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
        }
        unregisterQuietly()
        regListener = rl
        try {
            nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, rl)
        } catch (e: SecurityException) {
            // API 37 images gate NSD behind a runtime local-network consent dialog that can be
            // granted while the engine is already running; retry a bounded number of times so a
            // post-consent register succeeds without a manual toggle.
            Timber.tag("P2P").w(e, "nsd register denied (local network permission)")
            releaseLock("register_denied")
            listener.onAnnounceResult(false, "SecurityException: ${e.message}")
            if (registerRetries < MAX_REGISTER_RETRIES) {
                registerRetries += 1
                val retry = Runnable { register(localName, port) }
                registerRetryRunnable = retry
                handler.postDelayed(retry, REGISTER_RETRY_MS)
                Timber.tag("P2P").i("nsd register retry %d/%d in %dms", registerRetries, MAX_REGISTER_RETRIES, REGISTER_RETRY_MS)
            }
        } catch (e: Exception) {
            Timber.tag("P2P").w(e, "nsd register exception")
            listener.onAnnounceResult(false, "exception: ${e.message}")
        }
    }

    override fun startBrowsing() {
        stopBrowse()
        acquireLock("discover")
        discoveryStartMs = System.currentTimeMillis()
        runBrowse()
    }

    override fun stop() {
        handler.removeCallbacks(rebrowseRunnable)
        registerRetryRunnable?.let { handler.removeCallbacks(it) }
        registerRetryRunnable = null
        churnGate.clear()
        unregisterQuietly()
        stopBrowse()
        releaseLock("shutdown")
    }

    // ------------------------------------------------------------------ internals

    private fun runBrowse() {
        churnGate.onBrowseStarted(System.currentTimeMillis())
        val gen = ++browseGeneration
        val dl = makeDiscoveryListener(gen)
        discListener = dl
        try {
            nsd.discoverServices(P2pProtocolSpec.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, dl)
        } catch (e: SecurityException) {
            Timber.tag("P2P").w(e, "nsd discover denied (local network permission)")
        } catch (e: Exception) {
            Timber.tag("P2P").w(e, "nsd discover exception")
        }
        handler.removeCallbacks(rebrowseRunnable)
        handler.postDelayed(rebrowseRunnable, P2pProtocolSpec.REBROWSE_INTERVAL_MS)
    }

    private fun rebrowse() {
        stopBrowse()
        handler.postDelayed(
            { acquireLock("rediscover"); runBrowse() },
            P2pProtocolSpec.REBROWSE_RESTART_DELAY_MS,
        )
    }

    private fun stopBrowse() {
        browseGeneration++
        discListener?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        discListener = null
        churnGate.clear()
    }

    private fun makeDiscoveryListener(gen: Int) = object : NsdManager.DiscoveryListener {
        override fun onStartDiscoveryFailed(type: String, errorCode: Int) {
            Timber.tag("P2P").w("nsd discovery start failed code=%d", errorCode)
        }

        override fun onStopDiscoveryFailed(type: String, errorCode: Int) = Unit
        override fun onDiscoveryStarted(type: String) = Unit
        override fun onDiscoveryStopped(type: String) = Unit

        override fun onServiceFound(serviceInfo: NsdServiceInfo) {
            if (serviceInfo.serviceType != P2pProtocolSpec.SERVICE_TYPE) return
            // A found right after a transient lost is the browse-start snapshot heal.
            churnGate.heal(serviceInfo.serviceName)
            runCatching { nsd.resolveService(serviceInfo, makeResolveListener(serviceInfo.serviceName)) }
                .onFailure { Timber.tag("P2P").d("resolve not started: %s", it.message) }
        }

        override fun onServiceLost(serviceInfo: NsdServiceInfo) {
            // Only the active generation may report removals; teardown noise from a replaced
            // browse is suppressed (PROTOCOL.md section 2 "OS reports service removed").
            if (gen != browseGeneration) return
            val instance = serviceInfo.serviceName
            if (churnGate.inBrowseQuietPeriod(System.currentTimeMillis())) {
                Timber.tag("P2P").d("service_removed during browse quiet period ignored instance=%s", instance)
                return
            }
            val confirm = churnGate.scheduleRemoval(
                instance = instance,
                peerId = instanceToId[instance],
                observedAtMs = System.currentTimeMillis(),
            ) { peerId ->
                instanceToId.remove(instance)
                listener.onPeerRemoved(peerId, "service_removed")
            } ?: return
            handler.postDelayed(confirm, P2pProtocolSpec.GONE_CONFIRM_MS)
        }
    }

    private fun makeResolveListener(instance: String) = object : NsdManager.ResolveListener {
        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
            Timber.tag("P2P").w("resolve failed code=%d name=%s", errorCode, serviceInfo.serviceName)
        }

        override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
            churnGate.heal(instance)
            val attrs = serviceInfo.attributes ?: return
            val id = attrs[P2pProtocolSpec.TXT_ID]?.let { String(it) } ?: return
            if (id == localId) return
            val name = attrs[P2pProtocolSpec.TXT_NAME]?.let { String(it) } ?: ""
            val host = serviceInfo.host?.hostAddress ?: return
            instanceToId[instance] = id
            val elapsed = System.currentTimeMillis() - discoveryStartMs
            listener.onPeerFound(id, name, host, serviceInfo.port, elapsed)
        }
    }

    private fun unregisterQuietly() {
        regListener?.let { runCatching { nsd.unregisterService(it) } }
        regListener = null
    }

    private fun acquireLock(reason: String) {
        if (!multicastLock.isHeld) runCatching { multicastLock.acquire() }
        Timber.tag("P2P").d("multicast lock acquired: %s", reason)
    }

    private fun releaseLock(reason: String) {
        if (multicastLock.isHeld) runCatching { multicastLock.release() }
        Timber.tag("P2P").d("multicast lock released: %s", reason)
    }

    /** TXT values are UTF-8; NsdManager caps a TXT record at 255 bytes total, so trim the name. */
    private fun ByteArray.copyOfSeries(): ByteArray =
        if (size <= TXT_VALUE_MAX_BYTES) this else copyOf(TXT_VALUE_MAX_BYTES)

    private companion object {
        const val TXT_VALUE_MAX_BYTES = 63
        const val MAX_REGISTER_RETRIES = 5
        const val REGISTER_RETRY_MS = 10_000L
    }
}

/** Best-effort list of this device's LAN IPv4 addresses, for logs and the debug UI. */
fun localIpv4Addresses(): List<String> = runCatching {
    NetworkInterface.getNetworkInterfaces().asSequence()
        .filter { it.isUp && !it.isLoopback }
        .flatMap { it.inetAddresses.asSequence() }
        .filterIsInstance<Inet4Address>()
        .map { it.hostAddress.orEmpty() }
        .filter { it.isNotEmpty() }
        .toList()
}.getOrDefault(emptyList())
