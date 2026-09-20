package com.meracast.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View

/**
 * Heads‑up display overlay that renders real‑time streaming stats
 * directly onto the preview surface.
 *
 * All values are injected via [updateStats] so the View itself has
 * no dependency on the engine classes — it just draws what it's told.
 */
class DebugOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // ── Stats snapshot (thread‑safe via volatile / copy) ───────────────
    @Volatile
    var isVisible: Boolean = true

    data class Snapshot(
        val state: String = "—",
        val mode: String = "—",
        val videoFps: Float = 0f,
        val videoBitrateKbps: Long = 0L,
        val audioBitrateKbps: Long = 0L,
        val audioFrames: Long = 0L,
        val uptimeSec: Long = 0L,
        val resolution: String = "—",
        val compatProfile: String = "—",
        val peerName: String = "—"
    )

    @Volatile
    private var snap: Snapshot = Snapshot()

    fun updateStats(s: Snapshot) { snap = s }

    // ── Paints ─────────────────────────────────────────────────────────

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(140, 0, 0, 0)
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(220, 144, 238, 144)  // light green
        textSize = 32f
        typeface = Typeface.MONOSPACE
        isFakeBoldText = true
    }

    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(220, 255, 255, 255)
        textSize = 38f
        typeface = Typeface.DEFAULT_BOLD
    }

    private val lineHeight = 46f
    private val pad = 20f

    // ── Drawing ────────────────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!isVisible) return

        val s = snap
        val x = pad
        var y = pad + titlePaint.textSize

        // Background pill
        val w = width.toFloat()
        val h = 18 * lineHeight
        canvas.drawRoundRect(0f, 0f, w * 0.48f, h, 16f, 16f, bgPaint)

        // Title
        canvas.drawText("⚙  MIRACASTHUB DEBUG", x, y, titlePaint)
        y += lineHeight * 1.4f

        drawLine(canvas, "Mode", s.mode, x, y); y += lineHeight
        drawLine(canvas, "State", s.state, x, y); y += lineHeight
        drawLine(canvas, "Peer", s.peerName, x, y); y += lineHeight
        drawLine(canvas, "Profile", s.compatProfile, x, y); y += lineHeight * 1.3f

        drawLine(canvas, "Video FPS", "%.1f".format(s.videoFps), x, y); y += lineHeight
        drawLine(canvas, "Video Bitrate", "${s.videoBitrateKbps} kbps", x, y); y += lineHeight
        drawLine(canvas, "Resolution", s.resolution, x, y); y += lineHeight * 1.3f

        drawLine(canvas, "Audio Bitrate", "${s.audioBitrateKbps} kbps", x, y); y += lineHeight
        drawLine(canvas, "Audio Frames", "${s.audioFrames}", x, y); y += lineHeight * 1.3f

        drawLine(canvas, "Uptime", formatUptime(s.uptimeSec), x, y)

        // Keep drawing
        postInvalidateOnAnimation()
    }

    private fun drawLine(canvas: Canvas, label: String, value: String, x: Float, y: Float) {
        textPaint.color = Color.argb(180, 200, 200, 200)  // grey label
        canvas.drawText(label, x, y, textPaint)

        textPaint.color = Color.argb(220, 144, 238, 144)  // green value
        val valueX = x + 320f
        canvas.drawText(value, valueX, y, textPaint)
    }

    private fun formatUptime(sec: Long): String =
        "%02d:%02d:%02d".format(sec / 3600, (sec % 3600) / 60, sec % 60)
}
