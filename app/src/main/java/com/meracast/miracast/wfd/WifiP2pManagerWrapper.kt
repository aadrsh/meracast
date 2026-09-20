package com.meracast.miracast.wfd

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.MacAddress
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pManager
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceInfo
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceRequest
import android.net.wifi.p2p.nsd.WifiP2pServiceInfo
import android.net.wifi.p2p.nsd.WifiP2pServiceRequest
import android.os.Build
import android.util.Log
import com.meracast.common.AppConstants
import com.meracast.common.WfdCapabilities
import com.meracast.miracast.wfd.WfdCapabilityNegotiator
import com.meracast.ui.DebugLogStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/**
 * Data class representing a discovered Miracast device.
 */
data class WfdDevice(
    val deviceAddress: String,
    val deviceName: String,
    val primaryDeviceType: Int = WfdCapabilities.DEVICE_TYPE_DUAL_ROLE,
    val isAvailable: Boolean = true,
    val groupOwner: Boolean = false,
    val groupOwnerAddress: String? = null,
    val wfdCapabilities: ByteArray? = null,
    val wifiP2pDevice: WifiP2pDevice? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is WfdDevice) return false
        return deviceAddress == other.deviceAddress
    }

    override fun hashCode() = deviceAddress.hashCode()
}

/**
 * Connection state for the Wi-Fi P2P link.
 */
enum class P2pConnectionState {
    DISCONNECTED,
    DISCOVERING,
    CONNECTING,
    CONNECTED,
    GROUP_FORMED
}

/**
 * Wrapper around Android's WifiP2pManager.
 *
 * Handles peer discovery, service discovery (WFD via DNS-SD),
 * group owner negotiation, and connection lifecycle.
 *
 * ── Critical: BroadcastReceiver ──
 * The Android Wi‑Fi Direct framework pushes state changes via
 * broadcast intents.  This wrapper registers its own receiver
 * in [initialize] and dispatches to [requestPeers] /
 * [requestConnectionInfo] whenever the corresponding intent
 * arrives.  Without this the listeners are never populated.
 */
class WifiP2pManagerWrapper(private val context: Context) {

    companion object {
        private const val TAG = "${AppConstants.TAG}.P2P"
        private const val SERVICE_TYPE_WFD = "_wfd._tcp"
        private const val SERVICE_NAME_WFD = "MeracastHub"

        /** Decode P2P framework state numbers to human-readable labels. */
        private fun p2pStateLabel(state: Int): String = when (state) {
            WifiP2pManager.WIFI_P2P_STATE_ENABLED -> "ENABLED"
            WifiP2pManager.WIFI_P2P_STATE_DISABLED -> "DISABLED"
            else -> "UNKNOWN($state)"
        }
    }

    private val manager: WifiP2pManager by lazy {
        context.getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager
    }

    private val channel: WifiP2pManager.Channel by lazy {
        manager.initialize(context, context.mainLooper, null)
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // ── State flows ────────────────────────────────────────────────────

    private val _discoveredDevices = MutableStateFlow<List<WfdDevice>>(emptyList())
    val discoveredDevices: StateFlow<List<WfdDevice>> = _discoveredDevices.asStateFlow()

    private val _connectionState = MutableStateFlow(P2pConnectionState.DISCONNECTED)
    val connectionState: StateFlow<P2pConnectionState> = _connectionState.asStateFlow()

    private val _connectedDevice = MutableStateFlow<WfdDevice?>(null)
    val connectedDevice: StateFlow<WfdDevice?> = _connectedDevice.asStateFlow()

    private val _groupOwnerAddress = MutableStateFlow<String?>(null)
    val groupOwnerAddress: StateFlow<String?> = _groupOwnerAddress.asStateFlow()

    // ── Internal tracking ──────────────────────────────────────────────

    private val knownDevices = mutableMapOf<String, WfdDevice>()
    private var isDiscovering = false
    private var receiverRegistered = false

    // Listeners
    private var peerListListener: WifiP2pManager.PeerListListener? = null
    private var connectionInfoListener: WifiP2pManager.ConnectionInfoListener? = null
    private var serviceResponseListener: WifiP2pManager.DnsSdServiceResponseListener? = null
    private var txtRecordListener: WifiP2pManager.DnsSdTxtRecordListener? = null

    // ── BroadcastReceiver ──────────────────────────────────────────────

    /**
     * Receives Wi‑Fi P2P framework broadcasts and dispatches them
     * to the corresponding WifiP2pManager request methods.
     */
    private val p2pReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                    val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                    val label = p2pStateLabel(state)
                    Log.d(TAG, "P2P state changed: $label")
                    DebugLogStore.log("P2P: Wi‑Fi Direct state → $label")
                    if (state != WifiP2pManager.WIFI_P2P_STATE_ENABLED) {
                        DebugLogStore.log("P2P: ⚠ Wi‑Fi Direct is DISABLED — Windows+K will NOT see this device")
                    }
                }

                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                    Log.d(TAG, "Peers changed broadcast — requesting peer list")
                    peerListListener?.let {
                        manager.requestPeers(channel, it)
                    }
                }

                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                    Log.d(TAG, "Connection changed broadcast — requesting connection info")
                    connectionInfoListener?.let {
                        manager.requestConnectionInfo(channel, it)
                    }
                }

                WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> {
                    // Our own device info changed — useful for logging
                }
            }
        }
    }

    /**
     * Intent filter for all Wi-Fi P2P broadcast events.
     */
    fun getP2pIntentFilter(): IntentFilter {
        val filter = IntentFilter()
        filter.addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
        filter.addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
        filter.addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
        filter.addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
        if (Build.VERSION.SDK_INT >= 33) {
            filter.addAction(WifiP2pManager.WIFI_P2P_DISCOVERY_CHANGED_ACTION)
        }
        return filter
    }

    /**
     * Initialize listeners AND register the BroadcastReceiver.
     * Call once during setup.
     */
    fun initialize() {
        Log.d(TAG, "Initializing Wi-Fi P2P wrapper")

        // ── Peer list listener ─────────────────────────────────────────
        peerListListener = WifiP2pManager.PeerListListener { peerList ->
            val devices = peerList.deviceList
            Log.d(TAG, "Peers changed: ${devices.size} device(s)")
            DebugLogStore.log("P2P: Peer list updated — ${devices.size} device(s) seen on P2P link")
            if (devices.isNotEmpty()) {
                for (d in devices) {
                    val statusLabel = when (d.status) {
                        WifiP2pDevice.AVAILABLE -> "available"
                        WifiP2pDevice.INVITED -> "invited"
                        WifiP2pDevice.CONNECTED -> "connected"
                        WifiP2pDevice.FAILED -> "failed"
                        WifiP2pDevice.UNAVAILABLE -> "unavailable"
                        else -> "unknown(${d.status})"
                    }
                    DebugLogStore.log(
                        "P2P:   peer ${d.deviceName} " +
                        "(${d.deviceAddress.takeLast(8)}) " +
                        "type=${d.primaryDeviceType} status=$statusLabel"
                    )
                }
            }
        }

        // ── Connection info listener ───────────────────────────────────
        connectionInfoListener = WifiP2pManager.ConnectionInfoListener { info ->
            if (info != null) {
                val isGO = info.isGroupOwner
                val goAddr = info.groupOwnerAddress?.hostAddress
                _groupOwnerAddress.value = goAddr
                Log.d(TAG, "Connection: groupFormed=${info.groupFormed}, isGO=$isGO, goAddr=$goAddr")

                if (info.groupFormed) {
                    DebugLogStore.log("P2P: Connection event — group formed, isGO=$isGO")
                    DebugLogStore.log("P2P: P2P group owner address (IP) = ${goAddr ?: "N/A"}")
                    _connectionState.value = P2pConnectionState.GROUP_FORMED
                } else {
                    DebugLogStore.log("P2P: Connection event — group NOT formed (still waiting or disconnected)")
                    _connectionState.value = P2pConnectionState.DISCONNECTED
                }
            } else {
                Log.d(TAG, "Connection info is null — no P2P connection")
            }
        }

        // ── DNS-SD service response listener ───────────────────────────
        serviceResponseListener =
            WifiP2pManager.DnsSdServiceResponseListener { instanceName, registrationType, srcDevice ->
                if (registrationType.contains(SERVICE_TYPE_WFD, ignoreCase = true)) {
                    val existing = knownDevices[srcDevice.deviceAddress]
                    if (existing != null) {
                        knownDevices[srcDevice.deviceAddress] = existing.copy(
                            isAvailable = true, wifiP2pDevice = srcDevice
                        )
                    } else {
                        knownDevices[srcDevice.deviceAddress] = WfdDevice(
                            deviceAddress = srcDevice.deviceAddress,
                            deviceName = srcDevice.deviceName ?: instanceName,
                            isAvailable = true, wifiP2pDevice = srcDevice
                        )
                    }
                    _discoveredDevices.value = knownDevices.values.toList()
                }
            }

        // ── TXT record listener ────────────────────────────────────────
        txtRecordListener = WifiP2pManager.DnsSdTxtRecordListener { fullDomain, txtRecordMap, srcDevice ->
            val wfdInfoPresent = txtRecordMap?.containsKey("wfd_group_owner") == true ||
                    txtRecordMap?.containsKey("wfd_device_type") == true ||
                    txtRecordMap?.containsKey("wfd_capabilities") == true

            if (wfdInfoPresent) {
                val deviceTypeStr = txtRecordMap?.get("wfd_device_type") ?: "3"
                val deviceType = deviceTypeStr.toIntOrNull() ?: WfdCapabilities.DEVICE_TYPE_DUAL_ROLE
                val isGO = txtRecordMap?.get("wfd_group_owner") == "true"

                val existing = knownDevices[srcDevice.deviceAddress]
                knownDevices[srcDevice.deviceAddress] = WfdDevice(
                    deviceAddress = srcDevice.deviceAddress,
                    deviceName = srcDevice.deviceName ?: fullDomain,
                    primaryDeviceType = deviceType,
                    isAvailable = true, groupOwner = isGO,
                    wifiP2pDevice = srcDevice
                )
                _discoveredDevices.value = knownDevices.values.toList()
            }
        }

        // Register DNS-SD listeners
        manager.setDnsSdResponseListeners(channel, serviceResponseListener, txtRecordListener)

        // ── Register BroadcastReceiver ─────────────────────────────────
        if (!receiverRegistered) {
            context.registerReceiver(p2pReceiver, getP2pIntentFilter())
            receiverRegistered = true
            Log.d(TAG, "BroadcastReceiver registered")
        }
    }

    // ── Service advertisement (SINK mode) ──────────────────────────────

    /**
     * Build a proper WFD DNS-SD TXT record map for a primary sink.
     *
     * Windows+K discovers Miracast receivers through a combination of:
     *   1. WFD Information Elements in P2P probe responses (firmware-level)
     *   2. DNS-SD service queries over P2P for _wfd._tcp
     *
     * The TXT records below follow the WFD specification for DNS-SD.
     * `wfd_capabilities` is the hex-encoded WFD Device Information element:
     *   0x44 = session_available(1b) | P2P(00b) | primary_sink(01b) | no_sink(0b) | no_session(0b)
     */
    private fun buildWfdTxtMap(
        deviceType: Int = WfdCapabilities.DEVICE_TYPE_PRIMARY_SINK
    ): Map<String, String> = mapOf(
        "wfd_group_owner" to "true",
        "wfd_device_type" to deviceType.toString(),
        "wfd_capabilities" to "0x0044",
        "wfd_session_availability" to "1"
    )

    /**
     * Register the local _wfd._tcp (Wi‑Fi Display) service.
     * Call this AFTER the P2P group is formed so the P2P interface is up.
     */
    fun registerLocalWfdService(deviceType: Int = WfdCapabilities.DEVICE_TYPE_PRIMARY_SINK) {
        val deviceModel = Build.MODEL
        val serviceName = SERVICE_NAME_WFD
        val serviceType = SERVICE_TYPE_WFD  // "_wfd._tcp"

        val txtMap = buildWfdTxtMap(deviceType)

        // Log the full DNS-SD advertisement payload for debugging
        val deviceInfoHex = txtMap["wfd_capabilities"] ?: "N/A"
        DebugLogStore.log("P2P: === WFD DNS-SD advertisement ===")
        DebugLogStore.log("P2P: Service name  = \"$serviceName\"")
        DebugLogStore.log("P2P: Service type  = $serviceType")
        DebugLogStore.log("P2P: Device model  = $deviceModel")
        DebugLogStore.log("P2P: TXT records:")
        for ((key, value) in txtMap) {
            DebugLogStore.log("P2P:   $key = $value")
        }
        DebugLogStore.log("P2P: WFD Device Info hex = $deviceInfoHex")
        DebugLogStore.log("P2P:   Bit decode: session_avail=${if (deviceInfoHex.contains("4", ignoreCase=true)) "probably yes" else "check"}, device_type=primary_sink")
        DebugLogStore.log("P2P: === end advertisement ===")
        DebugLogStore.log("P2P: ⚠ Windows+K discovers via WFD IE in P2P probe responses (firmware) + DNS-SD _wfd._tcp (software)")
        DebugLogStore.log("P2P:   DNS-SD registration alone may be INSUFFICIENT for Windows if the Wi-Fi chipset doesn't")
        DebugLogStore.log("P2P:   also include the WFD IE in its beacon/probe response frames.")

        val serviceInfo = WifiP2pDnsSdServiceInfo.newInstance(
            serviceName, serviceType, txtMap
        )

        manager.addLocalService(channel, serviceInfo, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.d(TAG, "Local WFD service registered")
                DebugLogStore.log("P2P: ✓ WFD DNS-SD service registered")
                DebugLogStore.log("P2P:   Check Windows+K now — if still invisible, the Wi-Fi chipset may lack WFD IE in probe responses")
            }
            override fun onFailure(reason: Int) {
                val hint = when (reason) {
                    0 -> "Check Wi‑Fi is ON and P2P permissions are granted"
                    1 -> "P2P framework is busy — try again"
                    2 -> "This device does not support Wi‑Fi Direct"
                    else -> "Unknown error"
                }
                Log.w(TAG, "Failed to register local service: $reason ($hint)")
                DebugLogStore.log("P2P: ✗ WFD service registration FAILED (reason=$reason) — $hint")
                DebugLogStore.log("P2P:   CRITICAL: Without _wfd._tcp registration, Windows+K cannot discover via DNS-SD")
                DebugLogStore.log("P2P:   The device may still appear if the Wi-Fi chipset adds WFD IE at firmware level")
            }
        })
    }

    /**
     * Start WFD service discovery so this device appears in
     * Windows+K / Miracast device lists.
     *
     * When another P2P device (e.g. Windows laptop) queries for
     * _wfd._tcp services, this ensures our registered WFD service
     * is included in the response.
     */
    fun startWfdServiceDiscovery() {
        if (isDiscovering) return
        isDiscovering = true
        _connectionState.value = P2pConnectionState.DISCOVERING

        DebugLogStore.log("P2P: === Starting WFD service discovery ===")
        DebugLogStore.log("P2P: Adding DNS-SD service request for $SERVICE_TYPE_WFD")

        // Register a service request for WFD so the P2P framework
        // knows to respond to _wfd._tcp queries from peers.
        val serviceRequest = WifiP2pDnsSdServiceRequest.newInstance(SERVICE_TYPE_WFD)
        manager.addServiceRequest(channel, serviceRequest, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.d(TAG, "WFD service request added")
                DebugLogStore.log("P2P: ✓ DNS-SD service request added for $SERVICE_TYPE_WFD")
                DebugLogStore.log("P2P:   Device is now discoverable via DNS-SD _wfd._tcp queries")
            }
            override fun onFailure(reason: Int) {
                Log.w(TAG, "Failed to add WFD service request: $reason")
                DebugLogStore.log("P2P: ✗ Failed to add DNS-SD service request (reason=$reason)")
            }
        })

        DebugLogStore.log("P2P: Calling discoverServices() to announce presence on P2P link")

        // Also discover peers so other P2P devices see us
        manager.discoverServices(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.d(TAG, "P2P service discovery started (sink mode)")
                DebugLogStore.log("P2P: ✓ discoverServices() succeeded — P2P service discovery is active")
            }
            override fun onFailure(reason: Int) {
                Log.w(TAG, "P2P service discovery failed: $reason")
                DebugLogStore.log("P2P: ✗ discoverServices() FAILED (reason=$reason)")
                DebugLogStore.log("P2P:   Windows+K will NOT find this device if discovery is not running")
                isDiscovering = false
                _connectionState.value = P2pConnectionState.DISCONNECTED
            }
        })
    }

    fun unregisterLocalWfdService() {
        manager.clearLocalServices(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() { Log.d(TAG, "Local services cleared") }
            override fun onFailure(reason: Int) { Log.w(TAG, "Failed to clear local services: $reason") }
        })
    }

    // ── Discovery (SOURCE mode) ────────────────────────────────────────

    fun startDiscovery() {
        if (isDiscovering) return
        isDiscovering = true
        _connectionState.value = P2pConnectionState.DISCOVERING

        val serviceRequest = WifiP2pDnsSdServiceRequest.newInstance(SERVICE_TYPE_WFD)
        manager.addServiceRequest(channel, serviceRequest, object : WifiP2pManager.ActionListener {
            override fun onSuccess() { Log.d(TAG, "Service request added") }
            override fun onFailure(reason: Int) { Log.w(TAG, "Failed to add service request: $reason") }
        })

        manager.discoverServices(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() { Log.d(TAG, "Service discovery started") }
            override fun onFailure(reason: Int) {
                Log.w(TAG, "Service discovery failed: $reason")
                isDiscovering = false
                _connectionState.value = P2pConnectionState.DISCONNECTED
            }
        })
    }

    fun stopDiscovery() {
        if (!isDiscovering) return
        isDiscovering = false
        manager.stopPeerDiscovery(channel, null)
        manager.clearServiceRequests(channel, null)
        if (_connectionState.value == P2pConnectionState.DISCOVERING) {
            _connectionState.value = P2pConnectionState.DISCONNECTED
        }
    }

    // ── Connection (SOURCE mode: connect as client to sink's GO) ───────

    /**
     * Connect to a sink device as a P2P client.
     * Source mode: the sink is the Group Owner, we connect as client.
     */
    fun connectToSink(device: WfdDevice) {
        val p2pDevice = device.wifiP2pDevice ?: return
        _connectionState.value = P2pConnectionState.CONNECTING
        _connectedDevice.value = device
        connectAsClient(p2pDevice)
    }

    private fun connectAsClient(device: WifiP2pDevice) {
        val macAddr = MacAddress.fromString(device.deviceAddress)
        val config = WifiP2pConfig.Builder()
            .setDeviceAddress(macAddr)
            .build()

        manager.connect(channel, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.d(TAG, "Connection request sent to ${device.deviceAddress}")
                _connectionState.value = P2pConnectionState.CONNECTED
            }

            override fun onFailure(reason: Int) {
                Log.w(TAG, "Connection failed: reason=$reason")
                _connectionState.value = P2pConnectionState.DISCONNECTED
                _connectedDevice.value = null
            }
        })
    }

    // ── Group Owner mode (SINK mode) ───────────────────────────────────

    /**
     * Create a P2P group as the Group Owner.
     * Sink mode: the smartboard becomes the GO that sources connect to.
     */
    fun createGroupAsOwner() {
        DebugLogStore.log("P2P: === Creating P2P group as Group Owner ===")
        DebugLogStore.log("P2P: Device = ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
        DebugLogStore.log("P2P: Wi‑Fi chipset = ${Build.HARDWARE} / ${Build.BOARD}")
        DebugLogStore.log("P2P: Requesting GO mode — Windows expects a P2P Group Owner for Miracast discovery")

        manager.createGroup(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.d(TAG, "P2P Group created as GO")
                _connectionState.value = P2pConnectionState.GROUP_FORMED
                DebugLogStore.log("P2P: ✓ createGroup() succeeded — device is now a P2P Group Owner")
                DebugLogStore.log("P2P:   The P2P GO creates a virtual Wi‑Fi network on channel same as the connected AP")
                // Query the actual group owner address from the API
                manager.requestGroupInfo(channel) { group ->
                    if (group != null) {
                        val goAddr = group.owner?.deviceAddress
                        val networkName = group.networkName
                        val clientCount = group.clientList.size
                        val frequency = group.frequency
                        val passphrase = group.passphrase
                        Log.d(TAG, "Group: $networkName, owner=$goAddr, clients=$clientCount, freq=$frequency")
                        DebugLogStore.log("P2P: Group SSID = \"$networkName\"")
                        DebugLogStore.log("P2P: GO MAC address = ${goAddr ?: "N/A"}")
                        DebugLogStore.log("P2P: Clients connected = $clientCount")
                        DebugLogStore.log("P2P: P2P frequency = ${frequency ?: "N/A"} MHz")
                        if (passphrase != null) {
                            DebugLogStore.log("P2P: Group passphrase = $passphrase")
                        }
                        if (clientCount == 0) {
                            DebugLogStore.log("P2P: ⏳ No clients yet — waiting for Windows to connect")
                        }
                    }
                }
            }

            override fun onFailure(reason: Int) {
                val hint = when (reason) {
                    0 -> "Check Wi‑Fi is ON and P2P permissions are granted"
                    1 -> "Another P2P operation in progress — try again"
                    2 -> "Wi‑Fi Direct is not supported on this device"
                    else -> "Unknown error"
                }
                val severity = if (reason == 2) "CRITICAL" else "ERROR"
                Log.w(TAG, "Failed to create group: reason=$reason ($hint)")
                DebugLogStore.log("P2P: ✗ [$severity] Group creation FAILED (reason=$reason) — $hint")
                if (reason == 2) {
                    DebugLogStore.log("P2P:   This device's Wi‑Fi chipset does NOT support P2P Group Owner mode.")
                    DebugLogStore.log("P2P:   Windows+K CANNOT discover this device as a Miracast sink.")
                    DebugLogStore.log("P2P:   Try NETWORK mode (both devices on same Wi‑Fi) instead.")
                }
            }
        })
    }

    fun removeGroup() {
        manager.removeGroup(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() { Log.d(TAG, "Group removed") }
            override fun onFailure(reason: Int) { Log.w(TAG, "Failed to remove group: $reason") }
        })
        _connectionState.value = P2pConnectionState.DISCONNECTED
        _connectedDevice.value = null
        _groupOwnerAddress.value = null
    }

    fun disconnect() {
        removeGroup()
    }

    // ── Cleanup ────────────────────────────────────────────────────────

    fun release() {
        stopDiscovery()
        disconnect()
        unregisterLocalWfdService()
        manager.removeGroup(channel, null)

        // Unregister the BroadcastReceiver to avoid leaks
        if (receiverRegistered) {
            try {
                context.unregisterReceiver(p2pReceiver)
            } catch (_: IllegalArgumentException) { }
            receiverRegistered = false
            Log.d(TAG, "BroadcastReceiver unregistered")
        }

        scope.cancel()
    }
}
