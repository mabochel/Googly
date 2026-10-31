package com.mabochel.bluey.link

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.UUID

/**
 * Finds the Mac app on the local network (Android's DNS-SD, the same Bonjour service the iPhone uses)
 * and keeps one authenticated connection open. Everything here runs on the main thread.
 */
class MacLink(private val context: Context) {
    var connected by mutableStateOf(false)
        private set
    var macName by mutableStateOf<String?>(null)
        private set
    /** Every Mac running Bluey on this Wi-Fi, by its Bonjour name. */
    var macs by mutableStateOf<List<String>>(emptyList())
        private set
    /** The Mac the phone is linked to (or trying to link to). */
    var currentMac by mutableStateOf<String?>(null)
        private set
    private val prefs = context.getSharedPreferences("link", Context.MODE_PRIVATE)
    /** The Mac picked in the speaker panel. Remembered, so the phone goes back to it on launch. */
    var preferredMac by mutableStateOf(prefs.getString("preferredMac", null))
        private set
    val pairings = PairingStore(context)
    var isPaired by mutableStateOf(pairings.load(preferredMac) != null)
        private set

    var onCommand: ((String, String?) -> Unit)? = null
    var onFocusState: ((String) -> Unit)? = null
    var onFace: ((FaceState) -> Unit)? = null
    var onConnectedChanged: ((Boolean) -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())
    private val nsd = context.getSystemService(NsdManager::class.java)
    private var discovery: NsdManager.DiscoveryListener? = null

    private class Found(var info: NsdServiceInfo) {
        var host: InetAddress? = null
        var port = 0
        var id: String? = null
        val resolved get() = host != null && port > 0
    }
    private val services = LinkedHashMap<String, Found>()
    private val resolveQueue = ArrayDeque<String>()
    private var resolving: String? = null
    private var link: LineConnection? = null
    private val waiting = HashMap<String, (Packet?) -> Unit>()
    private val retry = Runnable { if (discovery == null) start() else { requeueUnresolved(); connectIfNeeded() } }

    fun start() {
        if (discovery != null) return
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                main.post { if (discovery === this) { discovery = null; scheduleRetry() } }
            }
            override fun onServiceFound(info: NsdServiceInfo) { main.post { if (discovery === this) found(info) } }
            override fun onServiceLost(info: NsdServiceInfo) { main.post { if (discovery === this) lost(info) } }
        }
        discovery = listener
        try {
            nsd.discoverServices(GooglyService.TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            Log.w(TAG, "Discovery failed to start", e)
            discovery = null
            scheduleRetry()
        }
    }

    fun stop() {
        main.removeCallbacks(retry)
        discovery?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        discovery = null
        services.clear()
        resolveQueue.clear()
        resolving = null
        val old = link
        link = null
        old?.cancel()
        updateConnected(false)
        macName = null
        failWaiting()
    }

    /** Switches to another Mac and remembers the choice. */
    fun choose(name: String) {
        setPreferred(name)
        if (currentMac != name) {
            val old = link
            link = null
            old?.cancel()
            updateConnected(false)
            failWaiting()
        }
        connectIfNeeded()
    }

    fun pair(record: PairingRecord): Boolean {
        if (!pairings.save(record)) return false
        stop()
        setPreferred(record.service)
        start()
        return true
    }

    fun send(packet: Packet) {
        link?.send(packet)
    }

    /** Sends a request and calls back with its reply (null if the Mac went away or took over 12 s). */
    fun request(packet: Packet, done: (Packet?) -> Unit) {
        val link = link
        if (link == null || !connected) { done(null); return }
        val id = UUID.randomUUID().toString().uppercase()
        waiting[id] = done
        main.postDelayed({ waiting.remove(id)?.invoke(null) }, 12_000)
        link.send(packet.copy(callID = id))
    }

    // MARK: Discovery

    private fun found(info: NsdServiceInfo) {
        val name = info.serviceName ?: return
        val existing = services[name]
        if (existing == null) services[name] = Found(info) else existing.info = info
        publishMacs()
        enqueueResolve(name)
    }

    private fun lost(info: NsdServiceInfo) {
        services.remove(info.serviceName ?: return)
        publishMacs()
    }

    private fun publishMacs() {
        macs = services.keys.sorted()
    }

    private fun requeueUnresolved() {
        services.filterValues { !it.resolved }.keys.forEach(::enqueueResolve)
    }

    private fun enqueueResolve(name: String) {
        if (name != resolving && name !in resolveQueue) resolveQueue.addLast(name)
        resolveNext()
    }

    @Suppress("DEPRECATION")
    private fun resolveNext() {
        if (resolving != null) return
        val name = resolveQueue.removeFirstOrNull() ?: return
        val found = services[name] ?: return resolveNext()
        resolving = name
        val listener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                main.post {
                    if (resolving == name) resolving = null
                    // Only one resolve can run at a time on older Androids; try this one again shortly.
                    main.postDelayed({ if (services.containsKey(name)) enqueueResolve(name) }, 1500)
                    resolveNext()
                }
            }
            override fun onServiceResolved(info: NsdServiceInfo) {
                main.post {
                    if (resolving == name) resolving = null
                    services[name]?.apply {
                        host = bestHost(info)
                        port = info.port
                        id = info.attributes["bluey-id"]?.toString(Charsets.UTF_8)
                    }
                    resolveNext()
                    connectIfNeeded()
                }
            }
        }
        try {
            nsd.resolveService(found.info, listener)
        } catch (e: Exception) {
            resolving = null
            main.postDelayed({ enqueueResolve(name) }, 1500)
        }
    }

    @Suppress("DEPRECATION")
    private fun bestHost(info: NsdServiceInfo): InetAddress? {
        if (Build.VERSION.SDK_INT >= 34) {
            val all = info.hostAddresses
            return all.firstOrNull { it is Inet4Address } ?: all.firstOrNull() ?: info.host
        }
        return info.host
    }

    // MARK: Connection

    private fun connectIfNeeded() {
        if (link != null) return
        val record = pairings.load(preferredMac) ?: return
        val secret = record.key ?: return
        // The Mac advertises a fingerprint of the shared secret; fall back to its name if TXT data is missing.
        val match = services.entries.firstOrNull { (name, found) ->
            found.resolved && (found.id == record.identifier || (found.id.isNullOrEmpty() && name == record.service))
        } ?: return
        val name = match.key
        currentMac = name
        val connection = LineConnection(
            InetSocketAddress(match.value.host, match.value.port), secret,
            deliver = { block -> main.post(block) },
            listener = object : LineConnection.Listener {
                override fun onAuthenticated(link: LineConnection) {
                    if (link !== this@MacLink.link) return
                    updateConnected(true)
                    link.send(Packet(hello = deviceName()))
                    link.send(Packet(command = "focusSync"))
                }

                override fun onPacket(link: LineConnection, packet: Packet) {
                    if (link !== this@MacLink.link) return
                    packet.hello?.let { macName = it }
                    packet.face?.let { onFace?.invoke(it) }
                    val command = packet.command ?: return
                    if (command == "focusState" && packet.text != null) { onFocusState?.invoke(packet.text); return }
                    val done = packet.callID?.let { waiting.remove(it) }
                    if (done != null) done(packet) else onCommand?.invoke(command, packet.text)
                }

                override fun onClosed(link: LineConnection, reason: String) {
                    if (link !== this@MacLink.link) return
                    Log.i(TAG, "Link closed: $reason")
                    this@MacLink.link = null
                    updateConnected(false)
                    macName = null
                    failWaiting()
                    // The Mac may have restarted on a new port: look it up again.
                    services[name]?.let { it.host = null; it.port = 0 }
                    scheduleRetry()
                }
            },
        )
        link = connection
        connection.start()
    }

    private fun scheduleRetry() {
        main.removeCallbacks(retry)
        main.postDelayed(retry, 1500)
    }

    private fun failWaiting() {
        val pending = waiting.values.toList()
        waiting.clear()
        pending.forEach { it(null) }
    }

    private fun updateConnected(value: Boolean) {
        if (connected == value) return
        connected = value
        onConnectedChanged?.invoke(value)
    }

    private fun setPreferred(name: String) {
        preferredMac = name
        prefs.edit().putString("preferredMac", name).apply()
        isPaired = pairings.load(name) != null
    }

    private fun deviceName(): String =
        Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)?.takeIf { it.isNotBlank() }
            ?: Build.MODEL ?: "Android"

    private companion object {
        const val TAG = "BlueyLink"
    }
}
