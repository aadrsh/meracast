package com.meracast.media

import android.content.ContentResolver
import android.net.Uri
import android.util.Log
import com.meracast.common.AppConstants
import com.meracast.common.MeracastPorts
import com.meracast.ui.DebugLogStore
import kotlinx.coroutines.*
import java.io.*
import java.net.ServerSocket
import java.net.URLDecoder
import java.nio.channels.FileChannel

/**
 * Lightweight HTTP/1.1 media server on the source device.
 *
 * Serves media files with Range support (RFC 7233) so the sink can
 * seek to any position.  The sink uses ExoPlayer which sends HTTP
 * range requests automatically.
 *
 * Uses [ContentResolver] + [android.content.res.AssetFileDescriptor]
 * for file access, which works on Android 11+ scoped storage.
 *
 * Usage:
 *   val server = MediaHttpServer(contentResolver)
 *   server.serveFile("movie.mp4", "/path/to/movie.mp4", "video/mp4")
 *   // sink connects via http://<source-ip>:7238/media/0
 */
class MediaHttpServer(
    private val contentResolver: ContentResolver
) {

    companion object {
        private const val TAG = "${AppConstants.TAG}.HttpSrv"
        private const val BUFFER_SIZE = 8192
    }

    private var serverSocket: ServerSocket? = null
    private var isRunning = false
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Currently served files: mediaId -> (uri, mimeType, fileSize)
    private val servedFiles = mutableMapOf<String, ServedFile>()

    private data class ServedFile(
        val uri: Uri,
        val mimeType: String,
        val fileSize: Long
    )

    /**
     * Register a file to be served and generate its mediaId.
     * Returns the HTTP URL the sink should connect to.
     *
     * @param uri      Content URI from MediaStore (content://...) or file Uri
     * @param mimeType MIME type (e.g. "video/mp4")
     * @param serverIp The source device's Wi‑Fi IP
     * @return The full http:// URL for the sink, or null if the file can't be opened
     */
    fun serveFile(
        uri: Uri,
        mimeType: String,
        serverIp: String
    ): String? {
        val fileSize = try {
            contentResolver.openAssetFileDescriptor(uri, "r")?.use { fd ->
                fd.length.takeIf { it > 0 } ?: fd.declaredLength.takeIf { it > 0 } ?: -1L
            } ?: return null
        } catch (e: SecurityException) {
            Log.e(TAG, "Permission denied for $uri: ${e.message}")
            DebugLogStore.log("HTTP: Permission denied — ${e.message}")
            throw e  // re-throw so caller can request permission
        } catch (e: Exception) {
            Log.e(TAG, "Cannot open $uri: ${e.message}")
            DebugLogStore.log("HTTP: Cannot open file — ${e.message}")
            return null
        }

        if (fileSize <= 0) {
            Log.w(TAG, "File $uri has zero/unknown size")
            return null
        }

        val mediaId = "media_${servedFiles.size}"
        servedFiles[mediaId] = ServedFile(uri, mimeType, fileSize)
        val url = "http://$serverIp:${MeracastPorts.HTTP_MEDIA}/$mediaId"
        DebugLogStore.log("HTTP: Serving \"$uri\" ($fileSize bytes) at $url")
        return url
    }

    /**
     * Start the HTTP server.  Call once during source init.
     * Accepts connections in a loop, handles one request per connection.
     */
    fun start() {
        if (isRunning) return
        isRunning = true
        scope.launch {
            try {
                serverSocket = ServerSocket(MeracastPorts.HTTP_MEDIA, 5)
                Log.d(TAG, "Media HTTP server listening on port ${MeracastPorts.HTTP_MEDIA}")
                DebugLogStore.log("HTTP: Media server on port ${MeracastPorts.HTTP_MEDIA}")

                while (isRunning) {
                    try {
                        val client = serverSocket?.accept() ?: break
                        scope.launch { handleRequest(client) }
                    } catch (_: Exception) { if (!isRunning) break }
                }
            } catch (e: Exception) {
                if (isRunning) {
                    Log.e(TAG, "HTTP server error", e)
                    DebugLogStore.log("HTTP: Server error — ${e.message}")
                }
            }
        }
    }

    private suspend fun handleRequest(client: java.net.Socket) {
        try {
            client.soTimeout = 30000
            val reader = BufferedReader(InputStreamReader(client.getInputStream()))
            val writer = BufferedOutputStream(client.getOutputStream())

            // Parse the request line
            val requestLine = reader.readLine() ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) { sendError(writer, 400); return }

            val method = parts[0]
            val rawPath = parts[1]

            // Parse headers
            var rangeHeader: String? = null

            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
                val colon = line.indexOf(':')
                if (colon > 0) {
                    val key = line.substring(0, colon).trim().lowercase()
                    val value = line.substring(colon + 1).trim()
                    if (key == "range") rangeHeader = value
                }
            }

            if (method != "GET") { sendError(writer, 405); return }

            // Extract mediaId from path
            val mediaId = URLDecoder.decode(rawPath, "UTF-8").trimStart('/')
            val served = servedFiles[mediaId]

            if (served == null) {
                sendError(writer, 404)
                return
            }

            val fileSize = served.fileSize
            val mimeType = served.mimeType

            // Open the file via ContentResolver (works on all API levels)
            val assetFd = contentResolver.openAssetFileDescriptor(served.uri, "r")
            if (assetFd == null) {
                sendError(writer, 404)
                return
            }

            assetFd.use { fd ->
                val inputStream = fd.createInputStream()
                val channel = inputStream.channel
                val startOffset = fd.startOffset

                // Parse Range header
                val range = parseRange(rangeHeader, fileSize)

                if (range != null) {
                    // Partial content
                    val (start, end) = range
                    val contentLen = end - start + 1
                    val responseHeaders = buildString {
                        append("HTTP/1.1 206 Partial Content\r\n")
                        append("Content-Type: $mimeType\r\n")
                        append("Content-Length: $contentLen\r\n")
                        append("Content-Range: bytes $start-$end/$fileSize\r\n")
                        append("Accept-Ranges: bytes\r\n")
                        append("Connection: close\r\n")
                        append("\r\n")
                    }
                    writer.write(responseHeaders.toByteArray())
                    writer.flush()

                    channel.position(startOffset + start)
                    var remaining = contentLen
                    val buf = java.nio.ByteBuffer.allocate(BUFFER_SIZE)
                    while (remaining > 0) {
                        buf.limit(minOf(buf.capacity().toLong(), remaining).toInt())
                        val read = channel.read(buf)
                        if (read < 0) break
                        writer.write(buf.array(), 0, read)
                        remaining -= read
                        buf.clear()
                    }
                } else {
                    // Full content
                    val responseHeaders = buildString {
                        append("HTTP/1.1 200 OK\r\n")
                        append("Content-Type: $mimeType\r\n")
                        append("Content-Length: $fileSize\r\n")
                        append("Accept-Ranges: bytes\r\n")
                        append("Connection: close\r\n")
                        append("\r\n")
                    }
                    writer.write(responseHeaders.toByteArray())
                    writer.flush()

                    channel.position(startOffset)
                    var remaining = fileSize
                    val buf = java.nio.ByteBuffer.allocate(BUFFER_SIZE)
                    while (remaining > 0) {
                        buf.limit(minOf(buf.capacity().toLong(), remaining).toInt())
                        val read = channel.read(buf)
                        if (read < 0) break
                        writer.write(buf.array(), 0, read)
                        remaining -= read
                        buf.clear()
                    }
                }

                writer.flush()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Request handling error: ${e.message}")
        } finally {
            try { client.close() } catch (_: Exception) {}
        }
    }

    private fun parseRange(rangeHeader: String?, fileSize: Long): Pair<Long, Long>? {
        if (rangeHeader == null) return null
        val match = Regex("bytes=(\\d*)-(\\d*)").find(rangeHeader) ?: return null
        val startStr = match.groupValues[1]
        val endStr = match.groupValues[2]
        val start = if (startStr.isEmpty()) 0L else startStr.toLongOrNull() ?: return null
        val end = if (endStr.isEmpty()) fileSize - 1 else endStr.toLongOrNull() ?: return null
        if (start < 0 || end < start || end >= fileSize) return null
        return Pair(start, end)
    }

    private fun sendError(writer: OutputStream, code: Int) {
        val messages = mapOf(400 to "Bad Request", 404 to "Not Found", 405 to "Method Not Allowed", 500 to "Internal Server Error")
        val msg = messages[code] ?: "Error"
        val body = "<h1>$code $msg</h1>"
        val response = "HTTP/1.1 $code $msg\r\nContent-Length: ${body.length}\r\nContent-Type: text/html\r\nConnection: close\r\n\r\n$body"
        try {
            writer.write(response.toByteArray())
            writer.flush()
        } catch (_: Exception) {}
    }

    fun stop() {
        isRunning = false
        try { serverSocket?.close() } catch (_: Exception) {}
        scope.cancel()
    }
}
