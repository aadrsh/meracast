package com.meracast.common

import android.util.Log
import com.meracast.ui.DebugLogStore
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.*
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException

/**
 * TCP-based transport for length-prefixed JSON control messages.
 *
 * Protocol:
 *   [4 bytes: payload length (big-endian int)] [N bytes: UTF-8 JSON]
 *
 * On connect, source sends [ControlMessage.HelloSource] and sink replies
 * with [ControlMessage.HelloSink]. After that handshake, normal commands flow.
 *
 * Sink runs [ControlServer], source connects via [ControlClient].
 */
object ControlSocket {

    private const val TAG = "${AppConstants.TAG}.Ctrl"
    const val DEFAULT_PORT = 7237
    private const val HANDSHAKE_TIMEOUT_MS = 5000L

    // ── Server side (sink) ───────────────────────────────────────────

    class ControlServer(
        private val port: Int = DEFAULT_PORT,
        private val onMessage: (ControlMessage) -> Unit,
        /**
         * Called when a source connects and the HELLO handshake completes.
         * [peerName] is the device name from HelloSource.
         */
        private val onClientConnected: (peerName: String) -> Unit = {},
        private val onClientDisconnected: () -> Unit = {}
    ) {
        private var serverSocket: ServerSocket? = null
        private var clientSocket: Socket? = null
        private var writer: DataOutputStream? = null
        private var reader: DataInputStream? = null
        private var isRunning = false
        private val sendMutex = Mutex()
        private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        private var readJob: Job? = null

        /** The device name of the connected source, set after handshake. */
        @Volatile
        var peerDeviceName: String = "?"
            private set

        /** The remote address of the connected source. */
        @Volatile
        var remoteAddress: String = "?"
            private set

        fun start() {
            if (isRunning) return
            isRunning = true
            scope.launch {
                try {
                    serverSocket = ServerSocket(port, 1)
                    Log.d(TAG, "Control server listening on port $port")
                    DebugLogStore.log("CTRL: Control server listening on port $port")

                    clientSocket = serverSocket?.accept()
                    val remoteAddr = clientSocket?.inetAddress?.hostAddress ?: "?"
                    remoteAddress = remoteAddr
                    Log.d(TAG, "Control client connected from $remoteAddr")
                    DebugLogStore.log("CTRL: Source connected from $remoteAddr")

                    writer = DataOutputStream(BufferedOutputStream(clientSocket?.getOutputStream()))
                    reader = DataInputStream(BufferedInputStream(clientSocket?.getInputStream()))

                    // ── Handshake: wait for HelloSource, reply HelloSink ──
                    DebugLogStore.log("CTRL: Awaiting HELLO from source...")
                    val helloJson = readOneMessage(reader!!)
                    if (helloJson == null) {
                        Log.w(TAG, "Source disconnected during handshake")
                        DebugLogStore.log("CTRL: Source disconnected during HELLO")
                        cleanUp()
                        return@launch
                    }
                    val helloMsg = parseControlMessage(helloJson)
                    if (helloMsg !is ControlMessage.HelloSource) {
                        Log.w(TAG, "Expected HelloSource, got: $helloJson")
                        DebugLogStore.log("CTRL: Expected HELLO_SOURCE, got something else")
                        cleanUp()
                        return@launch
                    }

                    peerDeviceName = helloMsg.deviceName.ifEmpty { remoteAddr }
                    DebugLogStore.log("CTRL: HELLO from \"${helloMsg.deviceName}\" (${helloMsg.deviceModel}) v${helloMsg.version}")

                    // Reply with HelloSink
                    val reply = ControlMessage.HelloSink(
                        deviceName = android.os.Build.MODEL,
                        deviceModel = android.os.Build.MODEL,
                        version = 1,
                        capabilities = listOf("playback", "cast")
                    )
                    writeOneMessage(writer!!, reply.toJson())

                    DebugLogStore.log("CTRL: HELLO handshake complete with \"$peerDeviceName\"")
                    onClientConnected(peerDeviceName)

                    // ── Normal read loop ──
                    readJob = scope.launch {
                        readLoop()
                    }
                } catch (e: Exception) {
                    if (isRunning) {
                        Log.e(TAG, "Control server error", e)
                        DebugLogStore.log("CTRL: Server error — ${e.message}")
                    }
                }
            }
        }

        private suspend fun readLoop() {
            try {
                val buf = ByteArray(65536)
                while (isRunning && reader != null) {
                    val len = try {
                        reader!!.readInt()
                    } catch (_: EOFException) {
                        break
                    } catch (_: SocketException) {
                        break
                    }
                    if (len <= 0 || len > buf.size) break
                    var offset = 0
                    while (offset < len) {
                        val read = reader!!.read(buf, offset, len - offset)
                        if (read < 0) throw EOFException()
                        offset += read
                    }
                    val json = String(buf, 0, len, Charsets.UTF_8)
                    val msg = parseControlMessage(json)
                    if (msg != null) {
                        onMessage(msg as ControlMessage)
                    } else {
                        Log.w(TAG, "Unparseable control message: $json")
                    }
                }
            } catch (e: Exception) {
                if (isRunning) Log.d(TAG, "Control read loop ended: ${e.message}")
            } finally {
                onClientDisconnected()
            }
        }

        fun send(message: ControlMessage) {
            scope.launch {
                sendMutex.withLock {
                    try {
                        writeOneMessage(writer!!, message.toJson())
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to send control message", e)
                    }
                }
            }
        }

        fun stop() {
            isRunning = false
            readJob?.cancel()
            cleanUp()
            scope.cancel()
        }

        private fun cleanUp() {
            try { clientSocket?.close() } catch (_: Exception) {}
            try { serverSocket?.close() } catch (_: Exception) {}
        }
    }

    // ── Client side (source) ────────────────────────────────────────

    class ControlClient(
        private val host: String,
        private val port: Int = DEFAULT_PORT,
        private val onMessage: (ControlMessage) -> Unit,
        /**
         * Called when connected AND the HELLO handshake completes.
         * [peerName] is the device name from HelloSink.
         */
        private val onConnected: (peerName: String) -> Unit = {},
        private val onDisconnected: () -> Unit = {}
    ) {
        private var socket: Socket? = null
        private var writer: DataOutputStream? = null
        private var reader: DataInputStream? = null
        private var isConnected = false
        private val sendMutex = Mutex()
        private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        private var readJob: Job? = null

        /** The device name of the connected sink, set after handshake. */
        @Volatile
        var peerDeviceName: String = "?"
            private set

        fun connect() {
            if (isConnected) return
            scope.launch {
                try {
                    socket = Socket(host, port)
                    writer = DataOutputStream(BufferedOutputStream(socket?.getOutputStream()))
                    reader = DataInputStream(BufferedInputStream(socket?.getInputStream()))

                    // ── Handshake: send HelloSource, wait for HelloSink ──
                    DebugLogStore.log("CTRL: Sending HELLO to sink at $host:$port...")
                    val hello = ControlMessage.HelloSource(
                        deviceName = android.os.Build.MODEL,
                        deviceModel = android.os.Build.MODEL,
                        version = 1,
                        capabilities = listOf("media", "cast")
                    )
                    writeOneMessage(writer!!, hello.toJson())

                    val replyJson = readOneMessage(reader!!)
                    if (replyJson == null) {
                        Log.w(TAG, "Sink disconnected during handshake")
                        DebugLogStore.log("CTRL: Sink disconnected during HELLO")
                        cleanUp()
                        return@launch
                    }
                    val replyMsg = parseControlMessage(replyJson)
                    if (replyMsg !is ControlMessage.HelloSink) {
                        Log.w(TAG, "Expected HelloSink, got: $replyJson")
                        DebugLogStore.log("CTRL: Expected HELLO_SINK, got something else")
                        cleanUp()
                        return@launch
                    }

                    peerDeviceName = replyMsg.deviceName.ifEmpty { host }
                    isConnected = true
                    Log.d(TAG, "Connected to sink \"$peerDeviceName\" at $host:$port")
                    DebugLogStore.log("CTRL: HELLO handshake complete with \"$peerDeviceName\"")
                    onConnected(peerDeviceName)

                    // ── Normal read loop ──
                    readJob = scope.launch {
                        readLoop()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to connect to control server", e)
                    DebugLogStore.log("CTRL: Connection failed — ${e.message}")
                }
            }
        }

        private suspend fun readLoop() {
            try {
                val buf = ByteArray(65536)
                while (isConnected && reader != null) {
                    val len = try {
                        reader!!.readInt()
                    } catch (_: EOFException) { break }
                      catch (_: SocketException) { break }
                    if (len <= 0 || len > buf.size) break
                    var offset = 0
                    while (offset < len) {
                        val read = reader!!.read(buf, offset, len - offset)
                        if (read < 0) throw EOFException()
                        offset += read
                    }
                    val json = String(buf, 0, len, Charsets.UTF_8)
                    val msg = parseControlMessage(json)
                    if (msg != null) onMessage(msg as ControlMessage)
                }
            } catch (_: Exception) { }
            finally {
                isConnected = false
                onDisconnected()
            }
        }

        fun send(message: ControlMessage) {
            scope.launch {
                sendMutex.withLock {
                    try {
                        writeOneMessage(writer!!, message.toJson())
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to send control message", e)
                    }
                }
            }
        }

        fun disconnect() {
            isConnected = false
            readJob?.cancel()
            cleanUp()
            scope.cancel()
        }

        private fun cleanUp() {
            try { socket?.close() } catch (_: Exception) {}
        }
    }

    // ── Shared helpers ──────────────────────────────────────────────

    /**
     * Read exactly one length-prefixed JSON message from [reader].
     * Returns the JSON string, or null on EOF / error.
     */
    private fun readOneMessage(reader: DataInputStream): String? {
        return try {
            val len = reader.readInt()
            if (len <= 0 || len > 65536) return null
            val buf = ByteArray(len)
            var offset = 0
            while (offset < len) {
                val read = reader.read(buf, offset, len - offset)
                if (read < 0) return null
                offset += read
            }
            String(buf, 0, len, Charsets.UTF_8)
        } catch (_: EOFException) { null }
          catch (_: SocketException) { null }
          catch (_: IOException) { null }
    }

    /**
     * Write one length-prefixed JSON message to [writer].
     */
    private fun writeOneMessage(writer: DataOutputStream, json: String) {
        val bytes = json.toByteArray(Charsets.UTF_8)
        writer.writeInt(bytes.size)
        writer.write(bytes)
        writer.flush()
    }
}
