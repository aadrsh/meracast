package com.meracast.miracast.source

import android.app.Application
import android.media.projection.MediaProjection
import android.net.nsd.NsdServiceInfo
import android.util.Log
import com.meracast.common.AppConstants
import com.meracast.common.WfdCapabilities
import com.meracast.common.WfdPorts
import com.meracast.miracast.rtp.TsMuxer
import com.meracast.miracast.wfd.WfdCompatibilityProfiles
import com.meracast.miracast.wfd.WfdDevice
import com.meracast.miracast.wfd.WifiP2pManagerWrapper
import com.meracast.ui.DebugLogStore
import com.meracast.ui.DebugOverlayView
import com.meracast.ui.NsdDiscoveryHelper
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * Miracast SOURCE engine.
 *
 * Coordinates screen casting: discover sinks, connect, capture
 * internal audio + screen, mux to MPEG2-TS, stream over UDP.
 */
class MiracastSourceEngine(private val application: Application) {

    companion object {
        private const val TAG = "${AppConstants.TAG}.SrcEngine"
        private const val TS_PACKET_SIZE = 188
    }

    // ── Sub‑components ─────────────────────────────────────────────────
    private val p2pWrapper = WifiP2pManagerWrapper(application)
    private val screenCapturer = ScreenCapturer(application)
    private val audioCapturer = AudioCapturer()
    private val rtspClient = SourceRtspClient()
    private val tsMuxer = TsMuxer()
    private val nsdHelper = NsdDiscoveryHelper(application)

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var udpSocket: DatagramSocket? = null
    private var sinkAddress: InetAddress? = null
    private var isStreaming = false
    private var startedAt = 0L

    private var mediaProjection: MediaProjection? = null
    private var captureWidth = AppConstants.DEFAULT_WIDTH
    private var captureHeight = AppConstants.DEFAULT_HEIGHT

    // Compatibility profile for the connected sink
    private var compatProfile = WfdCompatibilityProfiles.DEFAULT

    // ── mDNS-discovered network devices (separate from P2P) ────────────
    private val _networkDevices = MutableStateFlow<List<WfdDevice>>(emptyList())

    // ── Debug stats ────────────────────────────────────────────────────
    private val _stats = MutableStateFlow(DebugOverlayView.Snapshot())
    val stats: StateFlow<DebugOverlayView.Snapshot> = _stats
    private var videoFrames = 0L
    private var lastFpsCheck = 0L
    private var lastFrameCount = 0L

    // ── Callbacks ──────────────────────────────────────────────────────
    var onStateChanged: ((String) -> Unit)? = null
    var onDeviceFound: ((List<WfdDevice>) -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    // ── Public API ─────────────────────────────────────────────────────

    fun initialize() {
        Log.d(TAG, "Initialising source engine")
        DebugLogStore.log("SRC: Initializing — dual discovery enabled (P2P _wfd._tcp + mDNS _meracast._tcp)")
        p2pWrapper.initialize()
        screenCapturer.initialize(captureWidth, captureHeight)
    }

    fun startDiscovery() {
        DebugLogStore.log("SRC: ======== SOURCE DISCOVERY START ========")
        DebugLogStore.log("SRC: Starting P2P discovery via _wfd._tcp DNS-SD")
        p2pWrapper.startDiscovery()

        DebugLogStore.log("SRC: Starting network discovery via mDNS _meracast._tcp")
        // Start mDNS discovery for sinks on the same network
        nsdHelper.startDiscovery(
            onServiceFound = { serviceInfo ->
                DebugLogStore.log("SRC: mDNS found sink \"${serviceInfo.serviceName}\" — resolving...")
                nsdHelper.resolveService(serviceInfo) { resolved ->
                    val host = resolved.host?.hostAddress ?: return@resolveService
                    DebugLogStore.log("SRC: mDNS resolved sink \"${resolved.serviceName}\" → $host:${resolved.port}")
                    val device = WfdDevice(
                        deviceAddress = host,
                        deviceName = resolved.serviceName,
                        primaryDeviceType = WfdCapabilities.DEVICE_TYPE_PRIMARY_SINK,
                        isAvailable = true,
                        groupOwner = true,
                        groupOwnerAddress = host,
                        wifiP2pDevice = null  // no P2P — direct RTSP
                    )
                    _networkDevices.value = listOf(device) + _networkDevices.value.filter { it.deviceAddress != host }
                }
            }
        )

        // Combine P2P-discovered + mDNS-discovered devices into one list
        scope.launch {
            combine(p2pWrapper.discoveredDevices, _networkDevices) { p2p, net ->
                p2p + net
            }.collect { all ->
                DebugLogStore.log("SRC: Device list updated — ${all.size} device(s) found (${p2pWrapper.discoveredDevices.value.size} P2P + ${_networkDevices.value.size} network)")
                onDeviceFound?.invoke(all)
            }
        }

        scope.launch {
            p2pWrapper.connectionState.collect { state ->
                onStateChanged?.invoke(state.name)
                _stats.update { copy(state = state.name) }
                if (state == com.meracast.miracast.wfd.P2pConnectionState.GROUP_FORMED) {
                    val addr = p2pWrapper.groupOwnerAddress.value
                    val device = p2pWrapper.connectedDevice.value
                    val target = addr ?: device?.deviceAddress
                    if (target != null) {
                        if (device != null) {
                            compatProfile = WfdCompatibilityProfiles.detect(device)
                            _stats.update { copy(compatProfile = this@MiracastSourceEngine.compatProfile.label) }
                        }
                        connectRtsp(target)
                    }
                }
            }
        }
        // Start stats updater
        startStatsUpdater()
    }

    fun selectDevice(device: WfdDevice) {
        compatProfile = WfdCompatibilityProfiles.detect(device)
        _stats.update { copy(compatProfile = this@MiracastSourceEngine.compatProfile.label, peerName = device.deviceName) }

        if (device.wifiP2pDevice != null) {
            // P2P-discovered device: connect via Wi-Fi Direct
            DebugLogStore.log("SRC: Connecting to P2P sink \"${device.deviceName}\" (${device.deviceAddress})")
            p2pWrapper.connectToSink(device)
            onStateChanged?.invoke("CONNECTING")
        } else {
            // mDNS-discovered device on same network: connect RTSP directly (skip P2P)
            val host = device.groupOwnerAddress ?: device.deviceAddress
            DebugLogStore.log("SRC: Connecting to network sink \"${device.deviceName}\" at $host (no P2P)")
            DebugLogStore.log("SRC: RTSP negotiation will start directly to $host")
            onStateChanged?.invoke("CONNECTING")
            connectRtsp(host)
        }
    }

    fun setMediaProjection(projection: MediaProjection) {
        this.mediaProjection = projection
        // Audio capture needs the MediaProjection for internal audio
        audioCapturer.initialize(projection)
    }

    // ── Internal ───────────────────────────────────────────────────────

    private fun connectRtsp(host: String) {
        scope.launch {
            rtspClient.compatProfile = compatProfile  // pass profile for codec negotiation
            rtspClient.onSessionEstablished = { sessionId, port ->
                startMediaStreaming(host, port)
            }
            rtspClient.onSessionFailed = { error ->
                onError?.invoke("RTSP: $error")
            }
            // Apply compatibility profile: use alt port if specified
            val port = WfdCompatibilityProfiles.rtspPort(compatProfile)
            rtspClient.connect(host, port)
        }
    }

    private fun startMediaStreaming(sinkIp: String, port: Int) {
        try {
            sinkAddress = InetAddress.getByName(sinkIp)
            udpSocket = DatagramSocket(WfdPorts.UDP_PORT_START)
            val projection = mediaProjection ?: run { onError?.invoke("No MediaProjection"); return }
            isStreaming = true
            startedAt = System.currentTimeMillis()
            onStateChanged?.invoke("STREAMING")

            audioCapturer.startCapture()
            screenCapturer.startEncoding(projection, captureWidth, captureHeight)

            val timestampIncrement = 1_000_000L / AppConstants.DEFAULT_FPS
            var pts = 0L

            screenCapturer.onEncodedNalUnit = { nalData, isKeyFrame ->
                if (isStreaming) {
                    pts += timestampIncrement
                    videoFrames++
                    val packets = tsMuxer.wrapH264(nalData, isKeyFrame, pts, pts)
                    sendTsPackets(packets)
                }
            }

            audioCapturer.onAacFrame = { aacFrame, ptsUs ->
                if (isStreaming) {
                    val packets = tsMuxer.wrapAac(aacFrame, ptsUs)
                    sendTsPackets(packets)
                }
            }

            screenCapturer.onError = { onError?.invoke(it) }
            audioCapturer.onError = { Log.w(TAG, "Audio: $it") }
        } catch (e: Exception) {
            onError?.invoke("Stream start: ${e.message}")
        }
    }

    private fun sendTsPackets(packets: List<ByteArray>) {
        try {
            val addr = sinkAddress ?: return
            val socket = udpSocket ?: return
            val hasPrefix = WfdCompatibilityProfiles.hasQuirk(compatProfile,
                WfdCompatibilityProfiles.QUIRK_UDP_LENGTH_PREFIX)

            for (pkt in packets) {
                if (hasPrefix) {
                    // Some dongles expect a 4‑byte length prefix before each TS packet
                    val prefixed = ByteArray(4 + pkt.size)
                    prefixed[0] = (pkt.size shr 24).toByte()
                    prefixed[1] = (pkt.size shr 16).toByte()
                    prefixed[2] = (pkt.size shr 8).toByte()
                    prefixed[3] = pkt.size.toByte()
                    System.arraycopy(pkt, 0, prefixed, 4, pkt.size)
                    val dp = DatagramPacket(prefixed, prefixed.size, addr, rtspClient.negotiatedPort)
                    socket.send(dp)
                } else {
                    val dp = DatagramPacket(pkt, pkt.size, addr, rtspClient.negotiatedPort)
                    socket.send(dp)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "UDP send error", e)
        }
    }

    private fun startStatsUpdater() {
        scope.launch {
            while (true) {
                val now = System.currentTimeMillis()
                val elapsed = if (startedAt > 0) (now - startedAt) / 1000 else 0L
                val fps = if (lastFpsCheck > 0 && now - lastFpsCheck > 0)
                    (videoFrames - lastFrameCount) * 1000f / (now - lastFpsCheck) else 0f
                lastFpsCheck = now
                lastFrameCount = videoFrames

                val res = "${captureWidth}x${captureHeight}"

                _stats.update {
                    copy(
                        state = if (isStreaming) "STREAMING" else "IDLE",
                        mode = "SOURCE",
                        videoFps = fps,
                        videoBitrateKbps = (AppConstants.DEFAULT_BITRATE / 1000).toLong(),
                        audioBitrateKbps = (audioCapturer.captureBitrate / 1000),
                        audioFrames = audioCapturer.framesCaptured,
                        uptimeSec = elapsed,
                        resolution = res
                    )
                }
                delay(1000)
            }
        }
    }

    fun stopCasting() {
        isStreaming = false
        screenCapturer.stopEncoding()
        audioCapturer.stopCapture()
        rtspClient.sendTeardown()
        rtspClient.cleanup()
        p2pWrapper.disconnect()
        p2pWrapper.stopDiscovery()
        nsdHelper.stopDiscovery()
        nsdHelper.unregister()
        _networkDevices.value = emptyList()
        try { udpSocket?.close() } catch (_: Exception) {}
        udpSocket = null
        tsMuxer.reset()
        mediaProjection = null
        onStateChanged?.invoke("IDLE")
    }

    fun release() {
        stopCasting()
        screenCapturer.release()
        audioCapturer.release()
        rtspClient.release()
        p2pWrapper.release()
        scope.cancel()
    }
}

/** Tiny helper to update a StateFlow value immutably. */
private fun <T> MutableStateFlow<T>.update(transform: T.() -> T) {
    value = value.transform()
}
