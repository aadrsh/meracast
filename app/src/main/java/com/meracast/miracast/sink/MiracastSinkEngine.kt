package com.meracast.miracast.sink

import android.app.Application
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import android.view.Surface
import com.meracast.common.AppConstants
import com.meracast.common.WfdPorts
import com.meracast.miracast.rtp.TsDemuxer
import com.meracast.ui.DebugLogStore
import com.meracast.ui.NsdDiscoveryHelper
import com.meracast.miracast.wfd.WfdCompatibilityProfiles
import com.meracast.miracast.wfd.WifiP2pManagerWrapper
import com.meracast.ui.DebugOverlayView
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.NetworkInterface

class MiracastSinkEngine(private val application: Application) {

    companion object {
        private const val TAG = "${AppConstants.TAG}.SinkEngine"
        private const val TS_PACKET_SIZE = 188
        private const val UDP_BUFFER_SIZE = 65536
    }

    // ── Sub‑components ─────────────────────────────────────────────────
    private val p2pWrapper = WifiP2pManagerWrapper(application)
    private val rtspServer = SinkRtspServer()
    private val tsDemuxer = TsDemuxer()
    private val videoRenderer = VideoRenderer()
    private val audioPlayer = AudioPlayer()
    private val nsdHelper = NsdDiscoveryHelper(application)

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var udpSocket: DatagramSocket? = null
    @Volatile
    private var isReceiving = false
    private var receiveJob: Job? = null
    private var startedAt = 0L

    // Compatibility profile (detected from connecting source)
    private var compatProfile = WfdCompatibilityProfiles.DEFAULT

    // ── Debug stats ────────────────────────────────────────────────────
    private val _stats = MutableStateFlow(DebugOverlayView.Snapshot())
    val stats: StateFlow<DebugOverlayView.Snapshot> = _stats
    private var videoFrames = 0L
    private var lastFpsCheck = 0L
    private var lastFrameCount = 0L

    // ── Callbacks ──────────────────────────────────────────────────────
    var onStateChanged: ((String) -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    // ── Public API ─────────────────────────────────────────────────────

    fun initialize() {
        p2pWrapper.initialize()
        audioPlayer.initialize()
    }

    /**
     * Log all available network interfaces — useful for P2P IP detection debugging.
     */
    private fun logNetworkInterfaces() {
        try {
            DebugLogStore.log("SINK: === Scanning network interfaces ===")
            val interfaces = NetworkInterface.getNetworkInterfaces()
            var count = 0
            while (interfaces.hasMoreElements()) {
                val netif = interfaces.nextElement()
                if (!netif.isUp) continue
                val addrs = netif.inetAddresses
                val ips = mutableListOf<String>()
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        ips.add(addr.hostAddress ?: "?")
                    }
                }
                if (ips.isNotEmpty()) {
                    DebugLogStore.log("SINK:   ${netif.name} (mtu=${netif.mtu}): ${ips.joinToString(", ")}")
                    count++
                }
            }
            DebugLogStore.log("SINK: === $count P2P-capable interface(s) found ===")
        } catch (e: Exception) {
            DebugLogStore.log("SINK: Network interface scan failed: ${e.message}")
        }
    }

    fun startReceiving(renderSurface: Surface) {
        startedAt = System.currentTimeMillis()
        DebugLogStore.log("SINK: ======== MIRACAST SINK START (P2P MODE) ========")
        DebugLogStore.log("SINK: [Phase 0/3] Initializing renderers and decoders")
        onStateChanged?.invoke("STARTING")

        // Dump available network interfaces so we can verify P2P interface appears
        logNetworkInterfaces()

        videoRenderer.initialize(renderSurface)
        videoRenderer.onError = { onError?.invoke("Video: $it") }
        videoRenderer.onFrameRendered = { videoFrames++ }

        tsDemuxer.onVideoNal = { nal, keyFrame, pts ->
            videoRenderer.feedNalUnit(nal, keyFrame, pts)
        }
        tsDemuxer.onAudioFrame = { frame, pts ->
            audioPlayer.feedAacFrame(frame, pts)
        }
        tsDemuxer.onError = { Log.w(TAG, "Demux: $it") }

        // 1. Create P2P group as Group Owner FIRST so the P2P interface is up
        DebugLogStore.log("SINK: [Phase 1/3] Creating P2P group as Group Owner...")
        p2pWrapper.createGroupAsOwner()

        // 2. Then register the _wfd._tcp service (needs P2P interface ready)
        DebugLogStore.log("SINK: [Phase 2/3] Registering _wfd._tcp DNS-SD service...")
        p2pWrapper.registerLocalWfdService()

        // Re-scan interfaces after group creation — the P2P interface should now exist
        DebugLogStore.log("SINK: Scanning interfaces AFTER P2P group creation:")
        logNetworkInterfaces()

        // 3. Start WFD service discovery so Windows+K can find the device
        //    via DNS-SD service queries over Wi‑Fi Direct
        DebugLogStore.log("SINK: [Phase 3/3] Starting WFD service discovery...")
        p2pWrapper.startWfdServiceDiscovery()

        val deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}"
        val p2pIp = getP2pGroupAddress()
        DebugLogStore.log("SINK: Device = $deviceModel")
        DebugLogStore.log("SINK: P2P group IP = $p2pIp")
        DebugLogStore.log("SINK: RTSP port = ${WfdPorts.RTSP_PORT}, UDP port = ${WfdPorts.UDP_PORT_START}")

        // Self-check: assess whether Windows has a plausible discovery path
        val hasLiveIp = p2pWrapper.groupOwnerAddress.value != null
        DebugLogStore.log("SINK: === Discovery health check ===")
        DebugLogStore.log("SINK:   RTSP server will listen on 0.0.0.0:${WfdPorts.RTSP_PORT}")
        DebugLogStore.log("SINK:   ${if (hasLiveIp) "✓" else "⚠"} P2P GO IP = $p2pIp (${if (hasLiveIp) "from live WifiP2pManager" else "DEFAULT FALLBACK — may not be accurate"})")
        DebugLogStore.log("SINK:   ${if (hasLiveIp) "✓" else "⚠"} Windows will try to reach GO at $p2pIp:${WfdPorts.RTSP_PORT}")
        DebugLogStore.log("SINK: ======== SINK STARTED — waiting for source ========")

        onStateChanged?.invoke("WAITING_FOR_SOURCE")

        scope.launch {
            rtspServer.onSourceConnected = { ip ->
                DebugLogStore.log("SINK: ✓ Source connected from $ip")
                onStateChanged?.invoke("SOURCE_CONNECTED")
                _stats.update { copy(peerName = ip) }
            }
            rtspServer.onSessionNegotiated = { port ->
                DebugLogStore.log("SINK: ✓ RTSP session negotiated, UDP port = $port")
                onStateChanged?.invoke("SESSION_NEGOTIATED")
                startUdpReceiver(port)
            }
            rtspServer.onStreamStarted = {
                DebugLogStore.log("SINK: ✓ Stream started")
                audioPlayer.startPlayback()
                isReceiving = true
                onStateChanged?.invoke("STREAMING")
                startStatsUpdater()
            }
            rtspServer.onStreamStopped = { stopReceiving() }
            rtspServer.onSessionFailed = { onError?.invoke("Session: $it"); onStateChanged?.invoke("ERROR") }
            rtspServer.start(getP2pGroupAddress())
        }
    }

    /**
     * Get the device's current Wi‑Fi IP address (IPv4).
     * Returns null if Wi‑Fi is not connected.
     */
    private fun getWifiIpAddress(): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                // Look for the Wi‑Fi interface (wlan0) or any interface
                // that has a Wi‑Fi gateway
                if (!networkInterface.isUp || networkInterface.isLoopback) continue
                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val host = addr.hostAddress ?: continue
                        // Filter for typical Wi‑Fi IP ranges
                        if (host.startsWith("192.168.") ||
                            host.startsWith("10.") ||
                            host.startsWith("172.")) {
                            return host
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get Wi-Fi IP", e)
        }
        return null
    }

    /**
     * Start receiving in NETWORK mode — no Wi‑Fi Direct.
     *
     * Uses mDNS (NsdManager) for discovery instead of P2P, and streams
     * over the regular Wi‑Fi network via the device's Wi‑Fi IP address.
     *
     * Both the source and sink must be on the same Wi‑Fi network.
     */
    fun startNetworkReceiving(renderSurface: Surface) {
        startedAt = System.currentTimeMillis()
        val wifiIp = getWifiIpAddress()
        if (wifiIp == null) {
            DebugLogStore.log("SINK: No Wi‑Fi IP — cannot start network mode")
            onError?.invoke("Not connected to Wi‑Fi")
            return
        }

        DebugLogStore.log("SINK: Starting receiver (NETWORK mode)")
        DebugLogStore.log("SINK: Wi‑Fi IP = $wifiIp")
        onStateChanged?.invoke("STARTING")

        videoRenderer.initialize(renderSurface)
        videoRenderer.onError = { onError?.invoke("Video: $it") }
        videoRenderer.onFrameRendered = { videoFrames++ }

        tsDemuxer.onVideoNal = { nal, keyFrame, pts ->
            videoRenderer.feedNalUnit(nal, keyFrame, pts)
        }
        tsDemuxer.onAudioFrame = { frame, pts ->
            audioPlayer.feedAacFrame(frame, pts)
        }
        tsDemuxer.onError = { Log.w(TAG, "Demux: $it") }

        // Register via mDNS on the local network
        nsdHelper.registerSink(WfdPorts.RTSP_PORT) { serviceName ->
            DebugLogStore.log("SINK: mDNS registered as \"$serviceName\"")
            DebugLogStore.log("SINK: Windows / other devices on this network can now discover this device")
        }

        val deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}"
        DebugLogStore.log("SINK: Device = $deviceModel")
        DebugLogStore.log("SINK: Network = ${getSsid()}")
        DebugLogStore.log("SINK: Listening on $wifiIp:${WfdPorts.RTSP_PORT}")

        onStateChanged?.invoke("WAITING_FOR_SOURCE")

        scope.launch {
            rtspServer.onSourceConnected = { ip ->
                onStateChanged?.invoke("SOURCE_CONNECTED")
                _stats.update { copy(peerName = ip) }
            }
            rtspServer.onSessionNegotiated = { port ->
                onStateChanged?.invoke("SESSION_NEGOTIATED")
                startUdpReceiver(port)
            }
            rtspServer.onStreamStarted = {
                audioPlayer.startPlayback()
                isReceiving = true
                onStateChanged?.invoke("STREAMING")
                startStatsUpdater()
            }
            rtspServer.onStreamStopped = { stopNetworkReceiving() }
            rtspServer.onSessionFailed = {
                onError?.invoke("Session: $it"); onStateChanged?.invoke("ERROR")
            }
            // Start RTSP server on the Wi‑Fi IP instead of P2P IP
            rtspServer.start(wifiIp)
        }
    }

    /**
     * Get the current Wi‑Fi SSID (network name).
     */
    private fun getSsid(): String {
        return try {
            val wifi = application.getSystemService(WifiManager::class.java)
            wifi.connectionInfo?.ssid ?: "Unknown"
        } catch (e: Exception) {
            "Unknown"
        }
    }

    /**
     * Stop the network-mode receiver.
     */
    fun stopNetworkReceiving() {
        DebugLogStore.log("SINK: Stopping receiver (NETWORK mode)")
        isReceiving = false
        receiveJob?.cancel()
        receiveJob = null
        tsDemuxer.reset()
        videoRenderer.stopDecoding()
        audioPlayer.stopPlayback()
        rtspServer.stop()
        nsdHelper.unregister()
        try { udpSocket?.close() } catch (_: Exception) {}
        udpSocket = null
        onStateChanged?.invoke("IDLE")
    }

    // ── UDP receiver ──────────────────────────────────────────────────

    private fun startUdpReceiver(port: Int) {
        DebugLogStore.log("SINK: UDP receiver starting on port $port")
        receiveJob = scope.launch(Dispatchers.IO) {
            try {
                udpSocket = DatagramSocket(port)
                udpSocket?.soTimeout = 30000
                val buf = ByteArray(UDP_BUFFER_SIZE)

                while (isActive && isReceiving) {
                    try {
                        val pkt = DatagramPacket(buf, buf.size)
                        udpSocket?.receive(pkt)
                        val data = pkt.data
                        val len = pkt.length

                        // Check for length prefix (compatibility quirk)
                        val hasPrefix = WfdCompatibilityProfiles.hasQuirk(compatProfile,
                            WfdCompatibilityProfiles.QUIRK_UDP_LENGTH_PREFIX)
                        var offset = 0

                        if (hasPrefix) {
                            // Skip 4‑byte length prefix
                            var consumed = 0
                            while (consumed + 4 < len) {
                                val pktLen = ((data[consumed].toInt() and 0xFF) shl 24) or
                                        ((data[consumed + 1].toInt() and 0xFF) shl 16) or
                                        ((data[consumed + 2].toInt() and 0xFF) shl 8) or
                                        (data[consumed + 3].toInt() and 0xFF)
                                consumed += 4
                                if (pktLen > 0 && consumed + pktLen <= len) {
                                    val ts = data.copyOfRange(consumed, consumed + pktLen)
                                    feedTsPacket(ts)
                                    consumed += pktLen
                                } else break
                            }
                        } else {
                            // Standard: process in 188‑byte blocks
                            offset = 0
                            while (offset + TS_PACKET_SIZE <= len) {
                                val ts = data.copyOfRange(offset, offset + TS_PACKET_SIZE)
                                feedTsPacket(ts)
                                offset += TS_PACKET_SIZE
                            }
                        }
                    } catch (_: java.net.SocketTimeoutException) { }
                }
            } catch (e: Exception) {
                if (isReceiving) {
                    DebugLogStore.log("SINK: UDP error — ${e.message}")
                    onError?.invoke("UDP: ${e.message}")
                }
            }
        }
    }

    private var tsPacketCount = 0L

    private fun feedTsPacket(ts: ByteArray) {
        if (++tsPacketCount % 100 == 1L) {
            DebugLogStore.log("SINK: Receiving TS packets ($tsPacketCount total)")
            if (tsPacketCount == 1L) {
                DebugLogStore.log("SINK: First TS packet received — stream is flowing")
            }
        }
        tsDemuxer.feedPacket(ts)
    }

    // ── Stats updater ─────────────────────────────────────────────────

    private fun startStatsUpdater() {
        scope.launch {
            while (isReceiving) {
                delay(1000)
                val now = System.currentTimeMillis()
                val elapsed = (now - startedAt) / 1000
                val fps = if (lastFpsCheck > 0 && now - lastFpsCheck > 0)
                    (videoFrames - lastFrameCount) * 1000f / (now - lastFpsCheck) else 0f
                lastFpsCheck = now
                lastFrameCount = videoFrames

                _stats.update {
                    copy(
                        state = "STREAMING",
                        mode = "SINK",
                        videoFps = fps,
                        videoBitrateKbps = 0L,
                        audioBitrateKbps = 0L,
                        audioFrames = audioPlayer.framesPlayed,
                        uptimeSec = elapsed,
                        resolution = "${videoRenderer.width}x${videoRenderer.height}",
                        compatProfile = this@MiracastSinkEngine.compatProfile.label
                    )
                }
            }
        }
    }

    // ── Control ────────────────────────────────────────────────────────

    /**
     * Return the P2P Group Owner local address.
     *
     * First tries the live value from the P2P wrapper (populated by
     * [WifiP2pManager.requestGroupInfo] inside [createGroupAsOwner]).
     * Falls back to the standard 192.168.49.1 which is the default
     * for Android's Wi‑Fi Direct implementation.
     */
    private fun getP2pGroupAddress(): String {
        val liveAddr = p2pWrapper.groupOwnerAddress.value
        if (!liveAddr.isNullOrBlank()) return liveAddr
        return "192.168.49.1"
    }

    fun stopReceiving() {
        DebugLogStore.log("SINK: Stopping receiver")
        isReceiving = false
        receiveJob?.cancel()
        receiveJob = null
        tsDemuxer.reset()
        videoRenderer.stopDecoding()
        audioPlayer.stopPlayback()
        rtspServer.stop()
        p2pWrapper.unregisterLocalWfdService()
        p2pWrapper.removeGroup()
        p2pWrapper.stopDiscovery()
        try { udpSocket?.close() } catch (_: Exception) {}
        udpSocket = null
        onStateChanged?.invoke("IDLE")
    }

    fun release() {
        stopReceiving()
        videoRenderer.release()
        audioPlayer.release()
        rtspServer.release()
        p2pWrapper.release()
        scope.cancel()
    }
}

/** Tiny helper to update a StateFlow value immutably. */
private fun <T> kotlinx.coroutines.flow.MutableStateFlow<T>.update(transform: T.() -> T) {
    value = value.transform()
}
