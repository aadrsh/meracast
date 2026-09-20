package com.meracast.miracast.source

import android.util.Log
import com.meracast.common.AppConstants
import com.meracast.common.WfdHeaders
import com.meracast.common.WfdPorts
import com.meracast.miracast.wfd.WfdCapabilityNegotiator
import com.meracast.miracast.wfd.WfdCompatibilityProfiles
import kotlinx.coroutines.*
import java.io.*
import java.net.Socket
import java.net.SocketException

/**
 * RTSP client for Miracast SOURCE mode.
 *
 * Connects to the sink's RTSP server on port 7236 and runs
 * the WFD M1-M6 message exchange to negotiate capabilities
 * and establish the media streaming session.
 *
 * Message flow:
 * M1: OPTIONS (client -> server)
 * M2: OPTIONS response (server -> client)
 * M3: SETUP with wfd_video_formats, wfd_audio_codecs (client -> server)
 * M4: SETUP response with selected formats (server -> client)
 * M5: PLAY (client -> server)
 * M6: PLAY response (server -> client)
 * TEARDOWN (client -> server)
 */
class SourceRtspClient {

    companion object {
        private const val TAG = "${AppConstants.TAG}.RtspClient"
        private const val USER_AGENT = "MiracastHub/1.0 Android"
        private const val RTSP_PORT = WfdPorts.RTSP_PORT
    }

    // Session state
    private var rtspSocket: Socket? = null
    private var reader: BufferedReader? = null
    private var writer: PrintWriter? = null
    private var cseq: Int = 1
    private var sessionId: String? = null
    @Volatile
    private var isActive = false
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Negotiated parameters
    var negotiatedPort: Int = 19000
    var sinkIpAddress: String = ""

    // Compatibility profile (set before connect())
    var compatProfile: WfdCompatibilityProfiles.Profile = WfdCompatibilityProfiles.DEFAULT

    // Callbacks
    var onSessionEstablished: ((sessionId: String, port: Int) -> Unit)? = null
    var onSessionFailed: ((String) -> Unit)? = null
    var onSessionTerminated: (() -> Unit)? = null

    /**
     * Connect to the sink's RTSP server and run the WFD negotiation.
     *
     * @param host Sink IP address (from Wi-Fi P2P connection)
     * @param port RTSP port (default 7236)
     */
    suspend fun connect(host: String, port: Int = RTSP_PORT) {
        sinkIpAddress = host
        isActive = true

        try {
            Log.d(TAG, "Connecting to sink RTSP at $host:$port")
            rtspSocket = Socket(host, port)
            rtspSocket?.soTimeout = 10000
            reader = BufferedReader(InputStreamReader(rtspSocket?.getInputStream()))
            writer = PrintWriter(OutputStreamWriter(rtspSocket?.getOutputStream()), true)

            // Run WFD negotiation
            if (negotiateWfdSession()) {
                Log.d(TAG, "WFD session established! Session: $sessionId, port: $negotiatedPort")
                onSessionEstablished?.invoke(sessionId ?: "", negotiatedPort)
            } else {
                onSessionFailed?.invoke("WFD negotiation failed")
            }
        } catch (e: Exception) {
            Log.e(TAG, "RTSP connection failed", e)
            onSessionFailed?.invoke("Connection failed: ${e.message}")
            cleanup()
        }
    }

    /**
     * Run the full M1-M6 WFD RTSP negotiation.
     */
    private suspend fun negotiateWfdSession(): Boolean {
        // M1/M2: OPTIONS exchange
        if (!sendOptions()) return false
        Log.d(TAG, "M1/M2: OPTIONS exchange complete")

        // M3/M4: SETUP with capabilities
        if (!sendSetup()) return false
        Log.d(TAG, "M3/M4: SETUP exchange complete")

        // M5/M6: PLAY to start streaming
        if (!sendPlay()) return false
        Log.d(TAG, "M5/M6: PLAY exchange complete")

        return true
    }

    /**
     * Send OPTIONS request and parse response.
     * M1: OPTIONS * RTSP/1.0
     * M2: RTSP/1.0 200 OK
     */
    private suspend fun sendOptions(): Boolean {
        val request = buildString {
            append("OPTIONS * RTSP/1.0\r\n")
            append("${WfdHeaders.CSEQ}: ${cseq++}\r\n")
            append("Require: org.wfa.wfd1.0\r\n")
            append("\r\n")
        }

        return sendRequestAndCheckResponse(request, "OPTIONS")
    }

    /**
     * Send SETUP request with WFD capabilities and parse response.
     * M3: SETUP wfd://0.0.0.0/streamid=0 RTSP/1.0
     * M4: RTSP/1.0 200 OK
     */
    private suspend fun sendSetup(): Boolean {
        // Use compatibility-profile-aware codec strings
        val audioCodecs = if (WfdCompatibilityProfiles.hasQuirk(compatProfile,
                WfdCompatibilityProfiles.QUIRK_LEGACY_AUDIO_CODECS)) {
            WfdCompatibilityProfiles.effectiveAudioCodecs(compatProfile)
        } else {
            WfdCapabilityNegotiator.createAudioCodecsRtspHeader()
        }

        val hasRes = WfdCompatibilityProfiles.preferredResolution(compatProfile)
        val vw = if (hasRes.first > 0) hasRes.first else 1920
        val vh = if (hasRes.second > 0) hasRes.second else 1080
        val videoFormats = WfdCapabilityNegotiator.createVideoFormatsRtspHeader(
            nativeProfiles = WfdCompatibilityProfiles.effectiveH264Profile(compatProfile),
            nativeLevels = WfdCompatibilityProfiles.effectiveH264Level(compatProfile),
            maxWidth = vw, maxHeight = vh
        )
        val rtpPorts = WfdCapabilityNegotiator.createRtpPortsHeader(negotiatedPort)

        val request = buildString {
            append("SETUP wfd://0.0.0.0/streamid=0 RTSP/1.0\r\n")
            append("${WfdHeaders.CSEQ}: ${cseq++}\r\n")
            append("${WfdHeaders.WFD_AUDIO_CODECS}: $audioCodecs\r\n")
            append("${WfdHeaders.WFD_VIDEO_FORMATS}: $videoFormats\r\n")
            append("${WfdHeaders.WFD_CLIENT_RTP_PORTS}: $rtpPorts\r\n")
            append("User-Agent: $USER_AGENT\r\n")
            append("\r\n")
        }

        // Send SETUP and parse response
        try {
            writer?.print(request)
            writer?.flush()
            Log.d(TAG, "SETUP request sent")

            // Parse response
            val response = readResponse()
            if (response == null) {
                Log.w(TAG, "No SETUP response received")
                return false
            }

            Log.d(TAG, "SETUP response: $response")

            // Check for 200 OK
            if (!response.startsWith("RTSP/1.0 200")) {
                Log.w(TAG, "SETUP failed: $response")
                return false
            }

            // Parse Session header
            val sessionLine = response.split("\r\n").find { it.startsWith("Session:", ignoreCase = true) }
            if (sessionLine != null) {
                sessionId = sessionLine.substringAfter("Session:").trim()
                Log.d(TAG, "Session ID: $sessionId")
            }

            // Parse server RTP ports
            val transportLine = response.split("\r\n").find { it.startsWith("Transport:", ignoreCase = true) }
            if (transportLine != null) {
                // Format: RTP/AVP/UDP;unicast;client_port=19000-19001;server_port=19000-19001
                val serverPortStr = transportLine.substringAfter("server_port=").substringBefore(";")
                if (serverPortStr.isNotEmpty()) {
                    val serverPort = serverPortStr.split("-").firstOrNull()?.toIntOrNull()
                    if (serverPort != null) {
                        negotiatedPort = serverPort
                        Log.d(TAG, "Negotiated server port: $negotiatedPort")
                    }
                }
            }

            return true
        } catch (e: Exception) {
            Log.e(TAG, "SETUP failed", e)
            return false
        }
    }

    /**
     * Send PLAY request and parse response.
     * M5: PLAY wfd://0.0.0.0/streamid=0 RTSP/1.0
     * M6: RTSP/1.0 200 OK
     */
    private suspend fun sendPlay(): Boolean {
        val request = buildString {
            append("PLAY wfd://0.0.0.0/streamid=0 RTSP/1.0\r\n")
            append("${WfdHeaders.CSEQ}: ${cseq++}\r\n")
            append("Session: $sessionId\r\n")
            append("User-Agent: $USER_AGENT\r\n")
            append("\r\n")
        }

        return sendRequestAndCheckResponse(request, "PLAY")
    }

    /**
     * Send a generic RTSP request and verify the response.
     */
    private suspend fun sendRequestAndCheckResponse(
        request: String,
        requestType: String
    ): Boolean {
        return withTimeout(5000L) {
            try {
                writer?.print(request)
                writer?.flush()
                Log.d(TAG, "$requestType request sent")

                val response = readResponse()
                if (response == null) {
                    Log.w(TAG, "No response for $requestType")
                    return@withTimeout false
                }

                Log.d(TAG, "$requestType response: ${response.take(100)}...")

                if (!response.startsWith("RTSP/1.0 200")) {
                    Log.w(TAG, "$requestType failed: $response")
                    return@withTimeout false
                }

                // Parse Session header if present
                val sessionLine = response.split("\r\n").find {
                    it.startsWith("Session:", ignoreCase = true)
                }
                if (sessionLine != null) {
                    sessionId = sessionLine.substringAfter("Session:").trim()
                }

                true
            } catch (e: Exception) {
                Log.e(TAG, "$requestType failed", e)
                false
            }
        }
    }

    /**
     * Read one complete RTSP response (status line + headers + optional body).
     *
     * The loop breaks as soon as the empty-line terminator is reached,
     * so it does NOT block waiting for the socket to close.
     */
    private fun readResponse(): String? {
        return try {
            val response = StringBuilder()
            var line: String = ""
            var contentLength = 0

            // Read headers until empty line
            while (reader?.readLine()?.also { line = it } != null) {
                if (line.isEmpty()) {
                    // End of headers — read body if Content-Length is set
                    if (contentLength > 0) {
                        val body = CharArray(contentLength)
                        val read = reader?.read(body, 0, contentLength) ?: 0
                        if (read > 0) response.append(body, 0, read)
                    }
                    break  // ← critical: stop after one response
                }
                response.append(line).append("\r\n")
                if (line.startsWith("Content-Length:", ignoreCase = true)) {
                    contentLength = line.substringAfter(":").trim().toIntOrNull() ?: 0
                }
            }

            response.toString()
        } catch (e: SocketException) {
            Log.w(TAG, "Socket closed during read", e)
            null
        } catch (e: Exception) {
            Log.w(TAG, "Error reading RTSP response", e)
            null
        }
    }

    /**
     * Send TEARDOWN to end the WFD session.
     */
    fun sendTeardown() {
        if (!isActive) return

        try {
            val request = buildString {
                append("TEARDOWN wfd://0.0.0.0/streamid=0 RTSP/1.0\r\n")
                append("${WfdHeaders.CSEQ}: ${cseq++}\r\n")
                if (sessionId != null) {
                    append("Session: $sessionId\r\n")
                }
                append("User-Agent: $USER_AGENT\r\n")
                append("\r\n")
            }
            writer?.print(request)
            writer?.flush()
            Log.d(TAG, "TEARDOWN sent")
        } catch (e: Exception) {
            Log.w(TAG, "Error sending TEARDOWN", e)
        }
    }

    /**
     * Send an IDR request (force key frame) during streaming.
     */
    fun sendIdrRequest() {
        if (!isActive || sessionId == null) return

        try {
            val request = buildString {
                append("SET_PARAMETER wfd://0.0.0.0/streamid=0 RTSP/1.0\r\n")
                append("${WfdHeaders.CSEQ}: ${cseq++}\r\n")
                append("Session: $sessionId\r\n")
                append("${WfdHeaders.WFD_IDR_REQUEST}: 1\r\n")
                append("\r\n")
            }
            writer?.print(request)
            writer?.flush()
        } catch (e: Exception) {
            Log.w(TAG, "Error sending IDR request", e)
        }
    }

    /**
     * Clean up and close the RTSP connection.
     */
    fun cleanup() {
        isActive = false
        try {
            rtspSocket?.close()
        } catch (_: Exception) {}
        try {
            reader?.close()
        } catch (_: Exception) {}
        try {
            writer?.close()
        } catch (_: Exception) {}
        rtspSocket = null
        reader = null
        writer = null
    }

    /**
     * Release all resources.
     */
    fun release() {
        if (isActive) sendTeardown()
        cleanup()
        scope.cancel()
    }
}
