package com.meracast.ui

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import com.meracast.common.AppConstants
import com.meracast.common.MeracastPorts

/**
 * Network Service Discovery (mDNS / Bonjour) helper.
 *
 * Uses Android's built‑in [NsdManager] to:
 * - **Register** a local service so other devices on the same Wi‑Fi
 *   network can discover this device (SINK mode).
 * - **Discover** remote services on the network and report them via
 *   a callback (SOURCE mode).
 *
 * This replaces the Wi‑Fi Direct (P2P) discovery path for network‑based
 * streaming, avoiding the need for Wi‑Fi Direct hardware support or
 * `NEARBY_WIFI_DEVICES` / location permissions.
 *
 * Service type:  _meracast._tcp
 * TXT records:
 *   model  – device model name (e.g. "moto g71 5G")
 *   mode   – "sink" | "source"
 *   name   – display name
 */
class NsdDiscoveryHelper(private val context: Context) {

    companion object {
        private const val TAG = "${AppConstants.TAG}.NsdDisc"
        const val SERVICE_TYPE = "_meracast._tcp"
        const val SERVICE_NAME = "MeracastHub"
    }

    private val nsdManager: NsdManager =
        context.getSystemService(Context.NSD_SERVICE) as NsdManager

    private var isRegistered = false

    // ── Registration (SINK mode) ───────────────────────────────────────

    /**
     * Register this device as a Meracast sink on the local network.
     * Other devices (Windows, Android sources) can discover it via mDNS.
     *
     * @param port  The RTSP server port (default 7236)
     * @param onRegistered  Called when the service is registered, with the
     *                      actual service name (may differ if there's a conflict)
     */
    fun registerSink(
        port: Int = 7236,
        onRegistered: (String) -> Unit = {}
    ) {
        val serviceInfo = NsdServiceInfo().apply {
            serviceType = SERVICE_TYPE
            serviceName = SERVICE_NAME
            setPort(port)

            // TXT records for metadata (use setAttribute, not attributes map)
            setAttribute("model", android.os.Build.MODEL)
            setAttribute("manufacturer", android.os.Build.MANUFACTURER)
            setAttribute("mode", "sink")
            setAttribute("protocol", "rtsp")
        }

        nsdManager.registerService(
            serviceInfo,
            NsdManager.PROTOCOL_DNS_SD,
            object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(info: NsdServiceInfo) {
                    isRegistered = true
                    DebugLogStore.log("NET: Registered as \"${info.serviceName}\" on port ${info.port}")
                    Log.d(TAG, "Service registered: ${info.serviceName}")
                    onRegistered(info.serviceName)
                }

                override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                    DebugLogStore.log("NET: Service registration failed (code=$errorCode)")
                    Log.w(TAG, "Registration failed: $errorCode for ${info.serviceName}")
                }

                override fun onServiceUnregistered(info: NsdServiceInfo) {
                    Log.d(TAG, "Service unregistered: ${info.serviceName}")
                }

                override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                    Log.w(TAG, "Unregistration failed: $errorCode")
                }
            }
        )
    }

    /**
     * Register this device as a Meracast **source** on the local network.
     * Other devices (sinks) discover it via mDNS and connect the control
     * protocol to browse and play files.
     *
     * Service type: _meracast._tcp
     * TXT records: mode=source, model, manufacturer, version
     *
     * @param port  Control protocol port (default 7237)
     * @param onRegistered  Called when registered, with the actual service name
     */
    fun registerSource(
        port: Int = MeracastPorts.CONTROL,
        onRegistered: (String) -> Unit = {}
    ) {
        val serviceInfo = NsdServiceInfo().apply {
            serviceType = SERVICE_TYPE
            serviceName = "Meracast-${android.os.Build.MODEL}"
            setPort(port)

            setAttribute("mode", "source")
            setAttribute("model", android.os.Build.MODEL)
            setAttribute("manufacturer", android.os.Build.MANUFACTURER)
            setAttribute("version", "1")
        }

        nsdManager.registerService(
            serviceInfo,
            NsdManager.PROTOCOL_DNS_SD,
            object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(info: NsdServiceInfo) {
                    isRegistered = true
                    DebugLogStore.log("NET: Source registered as \"${info.serviceName}\" on port ${info.port}")
                    Log.d(TAG, "Source service registered: ${info.serviceName}")
                    onRegistered(info.serviceName)
                }

                override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                    DebugLogStore.log("NET: Source registration failed (code=$errorCode)")
                    Log.w(TAG, "Source registration failed: $errorCode for ${info.serviceName}")
                }

                override fun onServiceUnregistered(info: NsdServiceInfo) {
                    Log.d(TAG, "Source service unregistered: ${info.serviceName}")
                }

                override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                    Log.w(TAG, "Source unregistration failed: $errorCode")
                }
            }
        )
    }

    /**
     * Unregister the previously registered service(s).
     */
    fun unregister() {
        if (!isRegistered) return
        try {
            nsdManager.unregisterService(noopListener)
            isRegistered = false
        } catch (_: Exception) {
            // Already unregistered
            isRegistered = false
        }
    }

    /** No‑op listener for unregister calls when we don't care about the result. */
    private val noopListener = object : NsdManager.RegistrationListener {
        override fun onServiceRegistered(info: NsdServiceInfo) {}
        override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {}
        override fun onServiceUnregistered(info: NsdServiceInfo) {}
        override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {}
    }

    // ── Discovery (SOURCE mode) ────────────────────────────────────────

    /**
     * Start discovering Meracast sinks on the local network.
     * Found services are reported via [onServiceFound].
     */
    fun startDiscovery(
        onServiceFound: (NsdServiceInfo) -> Unit,
        onDiscoveryStopped: () -> Unit = {}
    ) {
        discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {
                DebugLogStore.log("NET: mDNS discovery started ($regType)")
                Log.d(TAG, "Discovery started: $regType")
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                Log.d(TAG, "Service found: ${serviceInfo.serviceName} type=${serviceInfo.serviceType}")
                // Only report our service type
                if (serviceInfo.serviceType == SERVICE_TYPE) {
                    DebugLogStore.log("NET: Found sink \"${serviceInfo.serviceName}\"")
                    onServiceFound(serviceInfo)
                }
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                Log.d(TAG, "Service lost: ${serviceInfo.serviceName}")
            }

            override fun onDiscoveryStopped(serviceType: String) {
                Log.d(TAG, "Discovery stopped: $serviceType")
                onDiscoveryStopped()
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                DebugLogStore.log("NET: mDNS discovery start failed (code=$errorCode)")
                Log.w(TAG, "Start discovery failed: $errorCode for $serviceType")
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "Stop discovery failed: $errorCode for $serviceType")
            }
        }

        nsdManager.discoverServices(
            SERVICE_TYPE,
            NsdManager.PROTOCOL_DNS_SD,
            discoveryListener!!
        )
    }

    /**
     * Resolve a discovered service to get its host address and port.
     */
    fun resolveService(
        serviceInfo: NsdServiceInfo,
        onResolved: (NsdServiceInfo) -> Unit
    ) {
        nsdManager.resolveService(
            serviceInfo,
            object : NsdManager.ResolveListener {
                override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                    DebugLogStore.log("NET: Failed to resolve ${info.serviceName} (code=$errorCode)")
                    Log.w(TAG, "Resolve failed: $errorCode for ${info.serviceName}")
                }

                override fun onServiceResolved(info: NsdServiceInfo) {
                    Log.d(TAG, "Resolved ${info.serviceName} → ${info.host}:${info.port}")
                    DebugLogStore.log("NET: Resolved \"${info.serviceName}\" at ${info.host.hostAddress}:${info.port}")
                    onResolved(info)
                }
            }
        )
    }

    fun stopDiscovery() {
        discoveryListener?.let {
            try {
                nsdManager.stopServiceDiscovery(it)
            } catch (_: Exception) {}
            discoveryListener = null
        }
    }

    private var discoveryListener: NsdManager.DiscoveryListener? = null
}
