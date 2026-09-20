package com.meracast.common

import org.json.JSONArray
import org.json.JSONObject

/**
 * Shared message types for the Meracast app-to-app control protocol.
 *
 * Transport: TCP socket with length-prefixed JSON (see [ControlSocket]).
 *
 * ── Command flow (peer → peer) ──
 *   list_files       →  file_list (response)
 *   play             →  playing (response)
 *   pause            →  paused (response)
 *   seek             →  seeking (response)
 *   set_volume       →  volume_changed (response)
 *   set_quality      →  quality_changed (response)
 *   status           →  status (response)
 *
 * ── Event flow (source → sink) ──
 *   media_info       →  sent when source starts serving a file
 *   buffering        →  sent when sink buffer state changes
 *   position         →  periodic playback position updates
 *   error            →  error state
 */
sealed class ControlMessage {

    // ── Sink → Source commands ──────────────────────────────────────

    /** Request list of available media files on the source. */
    data class ListFiles(
        val path: String = "/"
    ) : ControlMessage()

    /** Play a media file at an optional start position (seconds). */
    data class Play(
        val mediaId: String,
        val position: Double = 0.0
    ) : ControlMessage()

    /** Pause current playback. */
    class Pause : ControlMessage()

    /** Resume from pause. */
    class Resume : ControlMessage()

    /** Seek to a position in seconds. */
    data class Seek(
        val position: Double
    ) : ControlMessage()

    /** Stop playback and return to idle. */
    class Stop : ControlMessage()

    /** Set playback volume (0.0 – 1.0). */
    data class SetVolume(
        val volume: Float
    ) : ControlMessage()

    /** Request current status (position, state, etc.). */
    class GetStatus : ControlMessage()

    /** Set preferred bitrate for adaptive streaming. */
    data class SetQuality(
        val maxBitrate: Int     // bits per second
    ) : ControlMessage()

    /** Request source to start real-time screen casting. */
    data class StartScreenCast(
        val port: Int = 19000   // RTP port to use
    ) : ControlMessage()

    /** Source confirms screen cast has started. */
    data class ScreenCastStarted(
        val port: Int = 19000
    ) : ControlMessage()

    // ── Source → Sink responses / events ────────────────────────────

    /** Response to ListFiles — available media on the source device. */
    data class FileList(
        val files: List<FileEntry>,
        val path: String = "/"
    ) : ControlMessage()

    /** Notification that a file is being served at a given HTTP URL. */
    data class MediaInfo(
        val mediaId: String,
        val httpUrl: String,
        val duration: Double,       // seconds
        val mimeType: String,
        val fileSize: Long,
        val availableQualities: List<QualityEntry> = emptyList()
    ) : ControlMessage()

    /** Playback state change. */
    data class Playing(
        val mediaId: String,
        val position: Double = 0.0
    ) : ControlMessage()

    data class Paused(
        val mediaId: String,
        val position: Double
    ) : ControlMessage()

    data class Seeking(
        val position: Double
    ) : ControlMessage()

    data class VolumeChanged(
        val volume: Float
    ) : ControlMessage()

    data class QualityChanged(
        val maxBitrate: Int
    ) : ControlMessage()

    /** Periodic position update from sink. */
    data class PositionUpdate(
        val position: Double,
        val duration: Double,
        val bufferedPercent: Int,
        val state: String     // "playing" | "paused" | "buffering" | "idle"
    ) : ControlMessage()

    // ── Handshake messages ──────────────────────────────────────────

    /**
     * HELLO from the source device — sent immediately after TCP connect.
     * The sink waits for this before processing any other commands.
     */
    data class HelloSource(
        val deviceName: String = android.os.Build.MODEL,
        val deviceModel: String = android.os.Build.MODEL,
        val version: Int = 1,
        val capabilities: List<String> = listOf("media", "cast")
    ) : ControlMessage()

    /**
     * HELLO from the sink device — sent in reply to [HelloSource].
     * Confirms the sink is alive and tells the source what it can do.
     */
    data class HelloSink(
        val deviceName: String = android.os.Build.MODEL,
        val deviceModel: String = android.os.Build.MODEL,
        val version: Int = 1,
        val capabilities: List<String> = listOf("playback", "cast")
    ) : ControlMessage()

    /** Error event. */
    data class Error(
        val code: String,
        val message: String
    ) : ControlMessage()

    // ── Helper types ────────────────────────────────────────────────

    data class FileEntry(
        val id: String,
        val name: String,
        val path: String,
        val size: Long,
        val mimeType: String,
        val duration: Double = 0.0     // 0.0 if unknown
    )

    data class QualityEntry(
        val label: String,      // "720p", "1080p"
        val width: Int,
        val height: Int,
        val bitrate: Int        // bps
    )
}

// ── JSON serialisation ──────────────────────────────────────────────

/**
 * Encode a [ControlMessage] to a JSON string for transport.
 */
fun ControlMessage.toJson(): String {
    return when (this) {
        is ControlMessage.ListFiles -> JSONObject().apply {
            put("cmd", "list_files")
            put("path", path)
        }.toString()

        is ControlMessage.Play -> JSONObject().apply {
            put("cmd", "play")
            put("mediaId", mediaId)
            put("position", position)
        }.toString()

        is ControlMessage.Pause -> JSONObject().apply {
            put("cmd", "pause")
        }.toString()

        is ControlMessage.Resume -> JSONObject().apply {
            put("cmd", "resume")
        }.toString()

        is ControlMessage.Seek -> JSONObject().apply {
            put("cmd", "seek")
            put("position", position)
        }.toString()

        is ControlMessage.Stop -> JSONObject().apply {
            put("cmd", "stop")
        }.toString()

        is ControlMessage.SetVolume -> JSONObject().apply {
            put("cmd", "set_volume")
            put("volume", volume)
        }.toString()

        is ControlMessage.GetStatus -> JSONObject().apply {
            put("cmd", "status")
        }.toString()

        is ControlMessage.SetQuality -> JSONObject().apply {
            put("cmd", "set_quality")
            put("maxBitrate", maxBitrate)
        }.toString()

        is ControlMessage.StartScreenCast -> JSONObject().apply {
            put("cmd", "start_screen_cast")
            put("port", port)
        }.toString()

        is ControlMessage.ScreenCastStarted -> JSONObject().apply {
            put("event", "screen_cast_started")
            put("port", port)
        }.toString()

        is ControlMessage.FileList -> JSONObject().apply {
            put("event", "file_list")
            put("path", path)
            put("files", JSONArray(files.map { f ->
                JSONObject().apply {
                    put("id", f.id)
                    put("name", f.name)
                    put("path", f.path)
                    put("size", f.size)
                    put("mimeType", f.mimeType)
                    put("duration", f.duration)
                }
            }))
        }.toString()

        is ControlMessage.MediaInfo -> JSONObject().apply {
            put("event", "media_info")
            put("mediaId", mediaId)
            put("httpUrl", httpUrl)
            put("duration", duration)
            put("mimeType", mimeType)
            put("fileSize", fileSize)
            put("qualities", JSONArray(availableQualities.map { q ->
                JSONObject().apply {
                    put("label", q.label)
                    put("width", q.width)
                    put("height", q.height)
                    put("bitrate", q.bitrate)
                }
            }))
        }.toString()

        is ControlMessage.Playing -> JSONObject().apply {
            put("event", "playing")
            put("mediaId", mediaId)
            put("position", position)
        }.toString()

        is ControlMessage.Paused -> JSONObject().apply {
            put("event", "paused")
            put("mediaId", mediaId)
            put("position", position)
        }.toString()

        is ControlMessage.Seeking -> JSONObject().apply {
            put("event", "seeking")
            put("position", position)
        }.toString()

        is ControlMessage.VolumeChanged -> JSONObject().apply {
            put("event", "volume_changed")
            put("volume", volume)
        }.toString()

        is ControlMessage.QualityChanged -> JSONObject().apply {
            put("event", "quality_changed")
            put("maxBitrate", maxBitrate)
        }.toString()

        is ControlMessage.PositionUpdate -> JSONObject().apply {
            put("event", "position")
            put("position", position)
            put("duration", duration)
            put("bufferedPercent", bufferedPercent)
            put("state", state)
        }.toString()

        is ControlMessage.HelloSource -> JSONObject().apply {
            put("cmd", "hello_source")
            put("deviceName", deviceName)
            put("deviceModel", deviceModel)
            put("version", version)
            put("capabilities", JSONArray(capabilities))
        }.toString()

        is ControlMessage.HelloSink -> JSONObject().apply {
            put("cmd", "hello_sink")
            put("deviceName", deviceName)
            put("deviceModel", deviceModel)
            put("version", version)
            put("capabilities", JSONArray(capabilities))
        }.toString()

        is ControlMessage.Error -> JSONObject().apply {
            put("event", "error")
            put("code", code)
            put("message", message)
        }.toString()
    }
}

/**
 * Decode a JSON string back to a [ControlMessage].
 * Returns null if the JSON doesn't match any known message or event type.
 */
fun parseControlMessage(json: String): ControlMessage? {
    return try {
        val obj = JSONObject(json)

        // Commands (cmd field)
        when (obj.optString("cmd")) {
            "list_files" -> ControlMessage.ListFiles(obj.optString("path", "/"))
            "play" -> ControlMessage.Play(
                obj.getString("mediaId"),
                obj.optDouble("position", 0.0)
            )
            "pause" -> ControlMessage.Pause()
            "resume" -> ControlMessage.Resume()
            "seek" -> ControlMessage.Seek(obj.getDouble("position"))
            "stop" -> ControlMessage.Stop()
            "set_volume" -> ControlMessage.SetVolume(obj.getDouble("volume").toFloat())
            "status" -> ControlMessage.GetStatus()
            "set_quality" -> ControlMessage.SetQuality(obj.getInt("maxBitrate"))
            "start_screen_cast" -> ControlMessage.StartScreenCast(obj.optInt("port", 19000))

            "hello_source" -> ControlMessage.HelloSource(
                deviceName = obj.optString("deviceName", "Unknown Source"),
                deviceModel = obj.optString("deviceModel", ""),
                version = obj.optInt("version", 1),
                capabilities = obj.optJSONArray("capabilities")?.let { arr ->
                    (0 until arr.length()).map { arr.getString(it) }
                } ?: emptyList()
            )

            "hello_sink" -> ControlMessage.HelloSink(
                deviceName = obj.optString("deviceName", "Unknown Sink"),
                deviceModel = obj.optString("deviceModel", ""),
                version = obj.optInt("version", 1),
                capabilities = obj.optJSONArray("capabilities")?.let { arr ->
                    (0 until arr.length()).map { arr.getString(it) }
                } ?: emptyList()
            )

            else -> {
                // Events (event field)
                when (obj.optString("event")) {
                    "file_list" -> {
                        val arr = obj.optJSONArray("files") ?: JSONArray()
                        val files = (0 until arr.length()).map { i ->
                            val f = arr.getJSONObject(i)
                            ControlMessage.FileEntry(
                                id = f.getString("id"),
                                name = f.getString("name"),
                                path = f.optString("path", "/"),
                                size = f.getLong("size"),
                                mimeType = f.getString("mimeType"),
                                duration = f.optDouble("duration", 0.0)
                            )
                        }
                        ControlMessage.FileList(files, obj.optString("path", "/"))
                    }
                    "media_info" -> {
                        val qArr = obj.optJSONArray("qualities") ?: JSONArray()
                        val quals = (0 until qArr.length()).map { i ->
                            val q = qArr.getJSONObject(i)
                            ControlMessage.QualityEntry(
                                label = q.getString("label"),
                                width = q.getInt("width"),
                                height = q.getInt("height"),
                                bitrate = q.getInt("bitrate")
                            )
                        }
                        ControlMessage.MediaInfo(
                            mediaId = obj.getString("mediaId"),
                            httpUrl = obj.getString("httpUrl"),
                            duration = obj.getDouble("duration"),
                            mimeType = obj.getString("mimeType"),
                            fileSize = obj.getLong("fileSize"),
                            availableQualities = quals
                        )
                    }
                    "playing" -> ControlMessage.Playing(
                        obj.getString("mediaId"),
                        obj.optDouble("position", 0.0)
                    )
                    "paused" -> ControlMessage.Paused(
                        obj.getString("mediaId"),
                        obj.getDouble("position")
                    )
                    "seeking" -> ControlMessage.Seeking(obj.getDouble("position"))
                    "volume_changed" -> ControlMessage.VolumeChanged(obj.getDouble("volume").toFloat())
                    "quality_changed" -> ControlMessage.QualityChanged(obj.getInt("maxBitrate"))
                    "position" -> ControlMessage.PositionUpdate(
                        position = obj.getDouble("position"),
                        duration = obj.getDouble("duration"),
                        bufferedPercent = obj.getInt("bufferedPercent"),
                        state = obj.getString("state")
                    )
                    "screen_cast_started" -> ControlMessage.ScreenCastStarted(obj.optInt("port", 19000))
                    "error" -> ControlMessage.Error(
                        obj.getString("code"),
                        obj.getString("message")
                    )
                    else -> null
                }
            }
        }
    } catch (e: Exception) {
        null
    }
}
