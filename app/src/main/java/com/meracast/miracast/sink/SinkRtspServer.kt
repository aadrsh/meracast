package com.meracast.miracast.sink

import android.util.Log
import com.meracast.common.AppConstants
import com.meracast.common.WfdHeaders
import com.meracast.common.WfdPorts
import com.meracast.miracast.wfd.WfdCapabilityNegotiator
import com.meracast.ui.DebugLogStore
import kotlinx.coroutines.*
import java.io.*
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException

/**
 * RTSP server for Miracast SINK mode.
 *
 * Listens on port 7236 for incoming RTSP connections from a Miracast source.
 * Handles the WFD M1-M6 message exchange to negotiate capabilities and
 * establish the media streaming session.
 *
 * Listens for the standard WFD RTSP message flow:
 * C -> S: M1 OPTIONS
 * S -> C: M2 200 OK
 * C -> S: M3 SETUP (with wfd_video_formats, wfd_audio_codecs)
 * S -> C: M4 200 OK (with selected formats)
 * C -> S: M5 PLAY
 * S -> C: M6 200 OK
 * C -> S: TEARDOWN
 */
class SinkRtspServer {

    companion object {
        private const val TAG = "${AppConstants.TAG}.RtspServer"
        private const val RTSP_PORT = WfdPorts.RTSP_PORT
        private const val SERVER_HEADER = "MiracastHub/1.0 (Android Sink)"
    }

    private var serverSocket: ServerSocket? = null
    private var clientSocket: Socket? = null
    private var reader: BufferedReader? = null
    private var writer: PrintWriter? = null
    @Volatile
    private var isRunning = false
    private var cseq = 1
    private var sessionId = "00000000000000000001"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Negotiated state
    private var localIp: String = ""
    private var remoteVideoFormats: String = ""
    private var remoteAudioCodecs: String = ""
    private var remoteRtpPorts: String = ""
    private var remoteIpAddress: String = ""
    var negotiatedPort: Int = 19000

    // Callbacks
    var onSourceConnected: ((sourceIp: String) -> Unit)? = null
    var onSessionNegotiated: ((port: Int) -> Unit)? = null
    var onSessionFailed: ((String) -> Unit)? = null
    var onStreamStarted: (() -> Unit)? = null
    var onStreamStopped: (() -> Unit)? = null
    var onTeardownRequested: (() -> Unit)? = null

    /**
     * Start the RTSP server on the standard WFD port 7236.
     * The sink must already be the P2P Group Owner.
     *
     * @param localIp The IP address of the sink in the P2P group
     */
    suspend fun start(localIp: String) {
        this.localIp = localIp
        isRunning = true

        try {
            // Bind to 0.0.0.0 (all interfaces) because the P2P group IP may not
            // be assigned to the interface yet at this point — group formation is
            // asynchronous.  The localIp is still stored for use in the Transport
            // header of the RTSP SETUP response.
            serverSocket = ServerSocket(RTSP_PORT, 1, InetAddress.getByName("0.0.0.0"))
            Log.d(TAG, "RTSP server listening on 0.0.0.0:$RTSP_PORT (P2P IP: $localIp)")
            DebugLogStore.log("RTSP: Listening on 0.0.0.0:$RTSP_PORT (P2P IP: $localIp)")

            onSourceConnected?.invoke("ready")

            // Accept exactly one source connection
            withContext(Dispatchers.IO) {
                try {
                    Log.d(TAG, "Waiting for source connection...")
                    clientSocket = serverSocket?.accept()
                    remoteIpAddress = clientSocket?.inetAddress?.hostAddress ?: ""

                    if (clientSocket != null) {
                        Log.d(TAG, "Source connected from $remoteIpAddress")
                        DebugLogStore.log("RTSP: Source connected from $remoteIpAddress")
                        onSourceConnected?.invoke(remoteIpAddress)

                        clientSocket?.soTimeout = 15000
                        reader = BufferedReader(
                            InputStreamReader(clientSocket?.getInputStream())
                        )
                        writer = PrintWriter(
                            OutputStreamWriter(clientSocket?.getOutputStream()), true
                        )

                        // Handle RTSP message loop
                        handleRtspMessages()
                    }
                } catch (e: Exception) {
                    // Suppress error callback during controlled shutdown —
                    // isRunning is set to false by stop() before closing sockets.
                    // Without this check, the SocketException from closing the
                    // socket while accept() / handleRtspMessages() is blocked
                    // would be reported as a user-facing error.
                    if (isRunning) {
                        Log.e(TAG, "Error accepting source connection", e)
                        onSessionFailed?.invoke("Connection error: ${e.message}")
                    } else {
                        Log.d(TAG, "RTSP server shut down (${e.message})")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start RTSP server", e)
            onSessionFailed?.invoke("Server start failed: ${e.message}")
        }
    }

    /**
     * Main RTSP message handling loop.
     * Reads incoming RTSP requests and responds appropriately.
     */
    private suspend fun handleRtspMessages() {
        try {
            var keepReading = true

            while (keepReading && isRunning) {
                val request = readRequest()

                if (request == null) {
                    Log.d(TAG, "Connection closed by source")
                    keepReading = false
                    break
                }

                val method = parseMethod(request)
                Log.d(TAG, "Received RTSP request: $method")

                when {
                    method == null -> {
                        DebugLogStore.log("RTSP: Bad request from source")
                        sendErrorResponse(400, "Bad Request")
                    }
                    method.equals("OPTIONS", ignoreCase = true) -> {
                        DebugLogStore.log("RTSP: M1 OPTIONS received")
                        handleOptions(request)
                    }
                    method.equals("SETUP", ignoreCase = true) -> {
                        DebugLogStore.log("RTSP: M3 SETUP received")
                        handleSetup(request)
                    }
                    method.equals("PLAY", ignoreCase = true) -> {
                        DebugLogStore.log("RTSP: M5 PLAY received → starting stream")
                        handlePlay(request)
                        keepReading = false  // Stream starts, no more RTSP
                    }
                    method.equals("TEARDOWN", ignoreCase = true) -> {
                        DebugLogStore.log("RTSP: TEARDOWN received")
                        handleTeardown()
                        keepReading = false
                    }
                    method.equals("SET_PARAMETER", ignoreCase = true) -> {
                        DebugLogStore.log("RTSP: SET_PARAMETER received")
                        handleSetParameter(request)
                    }
                    else -> {
                        DebugLogStore.log("RTSP: Unknown method $method → 501")
                        sendErrorResponse(501, "Not Implemented")
                    }
                }
            }
        } catch (e: SocketException) {
            DebugLogStore.log("RTSP: Source disconnected (${e.message})")
            Log.d(TAG, "Source disconnected")
        } catch (e: Exception) {
            if (isRunning) {
                Log.e(TAG, "Error in RTSP message loop", e)
                onSessionFailed?.invoke("RTSP error: ${e.message}")
            } else {
                Log.d(TAG, "RTSP message loop ended (${e.message})")
            }
        } finally {
            if (isRunning) {
                onStreamStopped?.invoke()
            }
        }
    }

    /**
     * Handle OPTIONS request (M1).
     * Respond with supported WFD methods.
     */
    private fun handleOptions(request: String) {
        val response = buildString {
            append("RTSP/1.0 200 OK\r\n")
            append("${WfdHeaders.CSEQ}: ${getHeaderValue(request, WfdHeaders.CSEQ)}\r\n")
            append("Public: org.wfa.wfd1.0, SETUP, PLAY, TEARDOWN, SET_PARAMETER, GET_PARAMETER, PAUSE\r\n")
            append("Server: $SERVER_HEADER\r\n")
            append("\r\n")
        }

        writer?.print(response)
        writer?.flush()
        Log.d(TAG, "OPTIONS response sent")
        DebugLogStore.log("RTSP: M2 200 OK sent (OPTIONS)")
    }

    /**
     * Handle SETUP request (M3).
     * Parse capabilities, store them, and respond with selected formats.
     */
    private fun handleSetup(request: String) {
        // Extract capabilities from SETUP request
        remoteVideoFormats = getHeaderValue(request, WfdHeaders.WFD_VIDEO_FORMATS)
        remoteAudioCodecs = getHeaderValue(request, WfdHeaders.WFD_AUDIO_CODECS)
        remoteRtpPorts = getHeaderValue(request, WfdHeaders.WFD_CLIENT_RTP_PORTS)

        Log.d(TAG, "Source video formats: $remoteVideoFormats")
        Log.d(TAG, "Source audio codecs: $remoteAudioCodecs")

        // Generate our selection response
        val audioCodecs = remoteAudioCodecs.ifEmpty {
            WfdCapabilityNegotiator.createAudioCodecsRtspHeader()
        }
        val videoFormats = remoteVideoFormats.ifEmpty {
            WfdCapabilityNegotiator.createVideoFormatsRtspHeader()
        }

        // Negotiate port (use what source requested or our default)
        val sourcePorts = getHeaderValue(request, WfdHeaders.WFD_CLIENT_RTP_PORTS)
        negotiatedPort = if (sourcePorts.isNotEmpty()) {
            sourcePorts.split(" ").firstOrNull()?.toIntOrNull() ?: 19000
        } else {
            19000
        }

        Log.d(TAG, "Negotiated port: $negotiatedPort")

        val response = buildString {
            append("RTSP/1.0 200 OK\r\n")
            append("${WfdHeaders.CSEQ}: ${getHeaderValue(request, WfdHeaders.CSEQ)}\r\n")
            append("Session: $sessionId\r\n")
            append("Transport: RTP/AVP/UDP;unicast;client_port=$negotiatedPort-$negotiatedPort;server_port=$negotiatedPort-$negotiatedPort;mode=play\r\n")
            append("Server: $SERVER_HEADER\r\n")
            append("\r\n")
        }

        writer?.print(response)
        writer?.flush()
        Log.d(TAG, "SETUP response sent")
        DebugLogStore.log("RTSP: M4 200 OK sent — port $negotiatedPort")

        onSessionNegotiated?.invoke(negotiatedPort)
    }

    /**
     * Handle PLAY request (M5).
     * Start the streaming session.
     */
    private fun handlePlay(request: String) {
        val requestCseq = getHeaderValue(request, WfdHeaders.CSEQ)
        val response = buildString {
            append("RTSP/1.0 200 OK\r\n")
            append("${WfdHeaders.CSEQ}: $requestCseq\r\n")
            append("Session: $sessionId\r\n")
            append("Server: $SERVER_HEADER\r\n")
            append("\r\n")
        }

        writer?.print(response)
        writer?.flush()
        Log.d(TAG, "PLAY response sent - streaming starting")
        DebugLogStore.log("RTSP: M6 200 OK sent (PLAY)")
        onStreamStarted?.invoke()
    }

    /**
     * Handle TEARDOWN request.
     */
    private fun handleTeardown() {
        val response = buildString {
            append("RTSP/1.0 200 OK\r\n")
            append("${WfdHeaders.CSEQ}: ${cseq++}\r\n")
            append("Session: $sessionId\r\n")
            append("Server: $SERVER_HEADER\r\n")
            append("\r\n")
        }

        writer?.print(response)
        writer?.flush()
        Log.d(TAG, "TEARDOWN acknowledged")
        DebugLogStore.log("RTSP: TEARDOWN acknowledged")
        onTeardownRequested?.invoke()
    }

    /**
     * Handle SET_PARAMETER (e.g., IDR request).
     */
    private fun handleSetParameter(request: String) {
        // Check for IDR request
        val idrRequest = getHeaderValue(request, WfdHeaders.WFD_IDR_REQUEST)
        if (idrRequest == "1") {
            Log.d(TAG, "IDR request received")
        }

        val response = buildString {
            append("RTSP/1.0 200 OK\r\n")
            append("${WfdHeaders.CSEQ}: ${getHeaderValue(request, WfdHeaders.CSEQ)}\r\n")
            append("Session: $sessionId\r\n")
            append("Server: $SERVER_HEADER\r\n")
            append("\r\n")
        }

        writer?.print(response)
        writer?.flush()
    }

    /**
     * Send an error response.
     */
    private fun sendErrorResponse(code: Int, message: String) {
        val response = buildString {
            append("RTSP/1.0 $code $message\r\n")
            append("Server: $SERVER_HEADER\r\n")
            append("\r\n")
        }

        try {
            writer?.print(response)
            writer?.flush()
        } catch (_: Exception) {}
    }

    /**
     * Read a complete RTSP request from the client.
     */
    private fun readRequest(): String? {
        return try {
            val request = StringBuilder()
            var line: String = ""
            var numHeaders = 0
            var headersComplete = false
            var contentLength = 0

            // Read request line + headers
            while (reader?.readLine()?.also { line = it } != null) {
                val currentLine = line
                numHeaders++

                if (!headersComplete) {
                    request.append(currentLine).append("\r\n")

                    if (currentLine.startsWith("Content-Length:", ignoreCase = true)) {
                        contentLength = currentLine.substringAfter(":").trim().toIntOrNull() ?: 0
                    }

                    if (currentLine.isEmpty()) {
                        headersComplete = true
                        // Read body if present
                        if (contentLength > 0) {
                            val body = CharArray(contentLength)
                            reader?.read(body, 0, contentLength)
                            request.append(body)
                        }
                        break
                    }
                }
            }

            if (numHeaders <= 1) null else request.toString()
        } catch (e: Exception) {
            Log.w(TAG, "Error reading request", e)
            null
        }
    }

    /**
     * Parse the RTSP method from the request line.
     */
    private fun parseMethod(request: String): String? {
        return try {
            val firstLine = request.substringBefore("\r\n")
            firstLine.substringBefore(" ").trim()
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Extract a header value from the RTSP request.
     */
    private fun getHeaderValue(request: String, headerName: String): String {
        return try {
            val lines = request.split("\r\n")
            for (line in lines) {
                if (line.startsWith(headerName, ignoreCase = true)) {
                    return line.substringAfter(":").trim()
                }
            }
            ""
        } catch (e: Exception) {
            ""
        }
    }

    /**
     * Stop the RTSP server and clean up.
     */
    fun stop() {
        isRunning = false
        DebugLogStore.log("RTSP: Server stopping")
        try {
            clientSocket?.close()
        } catch (_: Exception) {}
        try {
            serverSocket?.close()
        } catch (_: Exception) {}
        try {
            reader?.close()
        } catch (_: Exception) {}
        try {
            writer?.close()
        } catch (_: Exception) {}
        clientSocket = null
        serverSocket = null
        reader = null
        writer = null
    }

    /**
     * Release all resources.
     */
    fun release() {
        stop()
        scope.cancel()
    }
}
