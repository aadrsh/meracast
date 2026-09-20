package com.meracast.ui

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Thread‑safe ring buffer that captures real‑time receiving logs
 * for the debug overlay.
 *
 * Usage from any engine component:
 *   DebugLogStore.log("RTSP: PLAY received → starting stream")
 *
 * The store keeps the most recent [MAX_LINES] entries and
 * discards the oldest when full.  All reads/writes are synchronized.
 */
object DebugLogStore {

    private const val MAX_LINES = 150

    private val logLines = mutableListOf<String>()
    private val dateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    /**
     * Append a timestamped log line.
     */
    fun log(message: String) {
        val ts = dateFormat.format(Date())
        synchronized(this) {
            logLines.add("[$ts] $message")
            if (logLines.size > MAX_LINES) {
                logLines.removeAt(0)
            }
        }
    }

    /**
     * Return a copy of all stored log lines.
     */
    fun getLogs(): List<String> = synchronized(this) {
        logLines.toList()
    }

    /**
     * Return the last [n] log lines (newest last).
     */
    fun last(n: Int): List<String> = synchronized(this) {
        logLines.takeLast(n)
    }

    /**
     * Clear all logs.
     */
    fun clear() {
        synchronized(this) { logLines.clear() }
    }
}
