package com.mutsho.localuno.network

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

class NsdHelper(private val context: Context) {

    companion object {
        private const val TAG = "NsdHelper"
        private const val SERVICE_TYPE = "_localuno._tcp."
        private const val SERVICE_NAME = "LocalUno"

        /** If Android's resolve callback never fires (real mDNS flakiness), release the lock
         *  instead of blocking every future resolve until the app restarts. */
        private const val RESOLVE_TIMEOUT_MS = 5000L
    }

    private val nsdManager: NsdManager by lazy {
        context.getSystemService(Context.NSD_SERVICE) as NsdManager
    }

    private val wifiManager: WifiManager? by lazy {
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    }
    private var multicastLock: WifiManager.MulticastLock? = null

    /**
     * mDNS is entirely multicast traffic (224.0.0.251:5353), and Wi-Fi chipsets drop multicast
     * frames that aren't addressed to this device unless something holds a multicast lock - it's a
     * battery optimisation, and it is silent: discovery simply never finds anything.
     *
     * The CHANGE_WIFI_MULTICAST_STATE permission has been in the manifest since the beginning; the
     * lock it exists for was never actually taken.
     *
     * This is insurance rather than the fix for hosts not being discoverable - that was an ordering
     * bug in LobbyViewModel.createLobby which meant no service was ever registered. NsdManager
     * usually runs mDNS in a system daemon that holds its own lock, so on many devices this changes
     * nothing; on the ones where it matters it is the difference between working and not, and it
     * costs a few mA while a lobby list is open.
     */
    private fun acquireMulticastLock() {
        if (multicastLock?.isHeld == true) return
        try {
            multicastLock = wifiManager?.createMulticastLock("localuno-mdns")?.apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Could not acquire multicast lock", e)
        }
    }

    /** Only safe once BOTH roles are done - a host that is also scanning still needs it. */
    private fun releaseMulticastLockIfIdle() {
        if (isDiscovering || isRegistered) return
        try {
            multicastLock?.takeIf { it.isHeld }?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Could not release multicast lock", e)
        }
        multicastLock = null
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var isDiscovering = false
    private var isRegistered = false
    private val isResolving = AtomicBoolean(false)
    private var resolveTimeoutRunnable: Runnable? = null

    // Android only allows one NsdManager.resolveService() in flight at a time - queueing (rather
    // than dropping) services found while a resolve is already running matters because several
    // hosts can already be broadcasting when a scan starts, so onServiceFound can fire for more
    // than one in quick succession.
    private val pendingResolves = ArrayDeque<NsdServiceInfo>()

    data class LobbyInfo(
        val name: String,
        val serviceName: String,
        val host: String,
        val port: Int,
        val gameMode: String,
        val playerCount: Int,
        val maxPlayers: Int,
        val hasPin: Boolean
    )

    fun registerService(
        lobbyName: String,
        port: Int,
        gameMode: String,
        maxPlayers: Int,
        hasPin: Boolean,
        onRegistered: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        val serviceInfo = NsdServiceInfo().apply {
            serviceName = "$SERVICE_NAME-$lobbyName"
            serviceType = SERVICE_TYPE
            setPort(port)
            setAttribute("lobby", lobbyName)
            setAttribute("mode", gameMode)
            setAttribute("max", maxPlayers.toString())
            setAttribute("pin", if (hasPin) "1" else "0")
            setAttribute("count", "1")
        }

        registrationListener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                Log.d(TAG, "Service registered: ${info.serviceName}")
                isRegistered = true
                onRegistered(info.serviceName)
            }

            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "Registration failed: $errorCode")
                onError("Registration failed with error code: $errorCode")
            }

            override fun onServiceUnregistered(info: NsdServiceInfo) {
                Log.d(TAG, "Service unregistered")
            }

            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "Unregistration failed: $errorCode")
            }
        }

        acquireMulticastLock()
        nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)
    }

    /**
     * Re-issues the current discovery with the same callbacks.
     *
     * A discovery listener can stop delivering without ever calling onDiscoveryStopped - the mDNS
     * daemon restarts, Wi-Fi reassociates, the system reclaims it during doze - leaving a scan that
     * looks healthy and is deaf. Nothing observable distinguishes that from "no tables here", which
     * is precisely the failure a player cannot diagnose, so the scan re-arms on a timer instead.
     */
    fun restartDiscovery() {
        val callbacks = lastDiscovery ?: return
        Log.d(TAG, "Re-arming discovery")
        discoverServices(callbacks.onLobbyFound, callbacks.onLobbyLost, callbacks.onError)
    }

    private class DiscoveryCallbacks(
        val onLobbyFound: (LobbyInfo) -> Unit,
        val onLobbyLost: (String) -> Unit,
        val onError: (String) -> Unit
    )

    private var lastDiscovery: DiscoveryCallbacks? = null

    fun discoverServices(
        onLobbyFound: (LobbyInfo) -> Unit,
        onLobbyLost: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        if (isDiscovering) {
            stopDiscovery()
        }
        lastDiscovery = DiscoveryCallbacks(onLobbyFound, onLobbyLost, onError)

        discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                Log.d(TAG, "Discovery started")
                isDiscovering = true
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                Log.d(TAG, "Service found: ${serviceInfo.serviceName}")
                if (serviceInfo.serviceType.contains("_localuno._tcp") ||
                    serviceInfo.serviceName.startsWith(SERVICE_NAME)
                ) {
                    resolveService(serviceInfo, onLobbyFound, onError)
                }
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                Log.d(TAG, "Service lost: ${serviceInfo.serviceName}")
                onLobbyLost(serviceInfo.serviceName)
            }

            override fun onDiscoveryStopped(serviceType: String) {
                Log.d(TAG, "Discovery stopped")
                isDiscovering = false
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "Discovery start failed: $errorCode")
                isDiscovering = false
                onError("Discovery failed with error code: $errorCode")
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "Discovery stop failed: $errorCode")
            }
        }

        acquireMulticastLock()
        nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
    }

    private fun resolveService(
        serviceInfo: NsdServiceInfo,
        onLobbyFound: (LobbyInfo) -> Unit,
        onError: (String) -> Unit
    ) {
        if (!isResolving.compareAndSet(false, true)) {
            Log.d(TAG, "Queueing resolve for ${serviceInfo.serviceName}; already resolving another service")
            pendingResolves.addLast(serviceInfo)
            return
        }
        startResolve(serviceInfo, onLobbyFound, onError)
    }

    private fun startResolve(
        serviceInfo: NsdServiceInfo,
        onLobbyFound: (LobbyInfo) -> Unit,
        onError: (String) -> Unit
    ) {
        // Guards against onResolveFailed/onServiceResolved and the timeout runnable both trying
        // to finish this same attempt (e.g. a timeout racing a resolve that succeeds right after) -
        // only the first to flip this actually advances to the next queued resolve.
        val handled = AtomicBoolean(false)
        fun finish() {
            if (!handled.compareAndSet(false, true)) return
            val next = pendingResolves.removeFirstOrNull()
            if (next != null) {
                startResolve(next, onLobbyFound, onError)
            } else {
                isResolving.set(false)
            }
        }

        val timeoutRunnable = Runnable {
            Log.e(TAG, "Resolve timed out for ${serviceInfo.serviceName}; moving on")
            finish()
        }
        resolveTimeoutRunnable = timeoutRunnable
        mainHandler.postDelayed(timeoutRunnable, RESOLVE_TIMEOUT_MS)

        nsdManager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
            override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                mainHandler.removeCallbacks(timeoutRunnable)
                Log.e(TAG, "Resolve failed: $errorCode")
                onError("Failed to resolve service")
                finish()
            }

            override fun onServiceResolved(info: NsdServiceInfo) {
                mainHandler.removeCallbacks(timeoutRunnable)
                Log.d(TAG, "Service resolved: ${info.host}:${info.port}")
                val lobbyInfo = LobbyInfo(
                    name = info.attributes["lobby"]?.let { String(it) } ?: info.serviceName,
                    serviceName = info.serviceName,
                    host = info.host.hostAddress ?: "",
                    port = info.port,
                    gameMode = info.attributes["mode"]?.let { String(it) } ?: "CLASSIC",
                    playerCount = info.attributes["count"]?.let { String(it).toIntOrNull() } ?: 1,
                    maxPlayers = info.attributes["max"]?.let { String(it).toIntOrNull() } ?: 6,
                    hasPin = info.attributes["pin"]?.let { String(it) == "1" } ?: false
                )
                onLobbyFound(lobbyInfo)
                finish()
            }
        })
    }

    /**
     * Intentionally not implemented, and callers should not expect a live count.
     *
     * mDNS TXT records can't be edited in place - the only way to change the advertised "count" is
     * to unregister and re-register the service. Android may then resolve a name collision by
     * silently renaming it ("LocalUno-Kitchen (2)"), so a lobby that re-registers on every join
     * would show up in other people's scan lists renamed, duplicated, or briefly missing. A stale
     * count is a much smaller problem than a lobby that flickers or clones itself mid-scan.
     *
     * The count therefore stays at its registration value, and the Join screen deliberately shows
     * capacity rather than an occupancy figure it cannot keep true. The real roster arrives over
     * TCP via LobbyUpdate immediately after connecting.
     */
    @Suppress("UNUSED_PARAMETER")
    fun updateServicePlayerCount(count: Int) = Unit

    fun stopDiscovery() {
        if (isDiscovering) {
            try {
                discoveryListener?.let { nsdManager.stopServiceDiscovery(it) }
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping discovery", e)
            }
            isDiscovering = false
            releaseMulticastLockIfIdle()
        }
    }

    fun unregisterService() {
        if (!isRegistered) return
        try {
            registrationListener?.let { nsdManager.unregisterService(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering service", e)
        }
        isRegistered = false
        releaseMulticastLockIfIdle()
    }

    fun cleanup() {
        stopDiscovery()
        unregisterService()
        resolveTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        pendingResolves.clear()
        isResolving.set(false)
    }
}
