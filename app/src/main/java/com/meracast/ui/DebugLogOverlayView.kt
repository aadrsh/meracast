package com.meracast.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View

/**
 * Overlay that renders real‑time receiving logs on top of the preview.
 *
 * Fetches the latest log lines from [DebugLogStore] and draws them
 * in a scroll‑style window with a semi‑transparent background.
 * The overlay auto‑refreshes while visible.
 */
class DebugLogOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    @Volatile
    var isVisible: Boolean = false

    // ── Paints ─────────────────────────────────────────────────────────

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(160, 0, 0, 0)
    }

    private val timePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(180, 180, 180, 180)   // grey timestamp
        textSize = 22f
        typeface = Typeface.MONOSPACE
    }

    private val msgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(220, 200, 230, 255)   // light blue
        textSize = 22f
        typeface = Typeface.MONOSPACE
    }

    private val warnPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(220, 255, 200, 100)   // amber
        textSize = 22f
        typeface = Typeface.MONOSPACE
    }

    private val errPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(220, 255, 120, 120)   // salmon red
        textSize = 22f
        typeface = Typeface.MONOSPACE
    }

    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(220, 255, 255, 255)
        textSize = 28f
        typeface = Typeface.DEFAULT_BOLD
    }

    private val lineHeight = 30f
    private val padH = 16f
    private val padV = 12f
    private val maxLines = 18

    // ── Drawing ────────────────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!isVisible) return

        val logs = DebugLogStore.last(maxLines)

        val w = width.toFloat()
        val h = padV + titlePaint.textSize + padV * 0.5f + maxLines * lineHeight + padV
        val y0 = height.toFloat() - h

        // Background (always draw so the panel is visible even when empty)
        canvas.drawRoundRect(0f, y0, w, y0 + h, 12f, 12f, bgPaint)

        // Title bar
        var x = padH
        var y = y0 + padV + titlePaint.textSize
        canvas.drawText("⎋  RECEIVE LOG", x, y, titlePaint)

        // "Waiting…" hint when empty, CLEAR hint on the right
        if (logs.isEmpty()) {
            timePaint.color = Color.argb(120, 180, 180, 180)
            val hint = "Waiting for logs…"
            canvas.drawText(hint, x, y + lineHeight * 2, timePaint)
        } else {
            val clearHint = "CLEAR"
            val clearW = timePaint.measureText(clearHint)
            timePaint.color = Color.argb(120, 255, 255, 255)
            canvas.drawText(clearHint, w - padH - clearW, y, timePaint)
        }

        y += padV * 0.5f

        // Log lines — newest at the bottom
        for (line in logs) {
            val sep = line.indexOf("] ")
            val ts = if (sep > 0) line.substring(0, sep + 1) else ""
            val msg = if (sep > 0) line.substring(sep + 2) else line

            // Choose paint based on severity keywords
            val paint = when {
                msg.contains("error", ignoreCase = true) ||
                msg.contains("fail", ignoreCase = true) ||
                msg.contains("exception", ignoreCase = true) -> errPaint
                msg.contains("warn", ignoreCase = true) -> warnPaint
                else -> msgPaint
            }

            y += lineHeight

            // Draw timestamp (truncated)
            timePaint.color = Color.argb(160, 160, 160, 160)
            canvas.drawText(ts, x, y, timePaint)

            // Draw message
            val msgX = x + timePaint.measureText(ts) + 6f
            val maxW = w - msgX - padH
            val truncated = if (paint.measureText(msg) > maxW) {
                msg.take(msg.length * maxW.toInt() / paint.measureText(msg).toInt() - 3) + "..."
            } else msg
            canvas.drawText(truncated, msgX, y, paint)
        }

        // Keep refreshing — always call this so new logs picked up
        postInvalidateOnAnimation()
    }
}
