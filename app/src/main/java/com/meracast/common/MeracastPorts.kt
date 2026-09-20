package com.meracast.common

/** Ports for the Meracast app-to-app protocol suite. */
object MeracastPorts {
    /** RTSP control port (Miracast standard). */
    const val RTSP = 7236

    /** Control protocol port (JSON-over-TCP). */
    const val CONTROL = 7237

    /** HTTP media streaming server. */
    const val HTTP_MEDIA = 7238

    /** RTP media stream start. */
    const val RTP_BASE = 19000
}

/** MIME types used in media file listing. */
object MediaMimeTypes {
    val VIDEO_EXTS = mapOf(
        "mp4" to "video/mp4",
        "mkv" to "video/x-matroska",
        "avi" to "video/x-msvideo",
        "mov" to "video/quicktime",
        "wmv" to "video/x-ms-wmv",
        "flv" to "video/x-flv",
        "webm" to "video/webm",
        "ts" to "video/mp2t",
        "3gp" to "video/3gpp"
    )

    val AUDIO_EXTS = mapOf(
        "mp3" to "audio/mpeg",
        "aac" to "audio/aac",
        "wav" to "audio/wav",
        "flac" to "audio/flac",
        "ogg" to "audio/ogg",
        "opus" to "audio/opus",
        "m4a" to "audio/mp4",
        "wma" to "audio/x-ms-wma"
    )

    val ALL = VIDEO_EXTS + AUDIO_EXTS

    fun forFile(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return ALL[ext] ?: "application/octet-stream"
    }

    fun isVideo(mime: String) = mime.startsWith("video/")
    fun isAudio(mime: String) = mime.startsWith("audio/")
}
