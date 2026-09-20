package com.meracast.miracast.source

import android.app.Application
import android.media.projection.MediaProjection
import android.util.Log
import com.meracast.common.AppConstants
import com.meracast.miracast.rtp.RtpSender
import com.meracast.ui.DebugLogStore
import java.net.InetAddress

/**
 * Real-time screen casting over network (no Wi‑Fi Direct).
 *
 * Wires:
 *   [ScreenCapturer] (H.264) + [AudioCapturer] (AAC)
 *        → [RtpSender] → UDP to sink
 */
class ScreenStreamer(private val application: Application) {

    companion object {
        private const val TAG = "${AppConstants.TAG}.ScrStream"
    }

    private val screenCapturer = ScreenCapturer(application)
    private val audioCapturer = AudioCapturer()
    private val rtpSender = RtpSender()

    private var isStreaming = false
    private var mediaProjection: MediaProjection? = null
    private var captureWidth = 1280
    private var captureHeight = 720

    var onStateChanged: ((String) -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    fun initialize(width: Int = 1280, height: Int = 720) {
        captureWidth = width
        captureHeight = height
        screenCapturer.initialize(width, height)
        DebugLogStore.log("CAST: Screen streamer initialized (${width}x${height})")
    }

    fun setMediaProjection(projection: MediaProjection) {
        this.mediaProjection = projection
        audioCapturer.initialize(projection)
    }

    fun startCasting(sinkIp: String, port: Int = 19000) {
        if (isStreaming) return
        isStreaming = true

        try {
            val addr = InetAddress.getByName(sinkIp)
            rtpSender.start(addr, port)

            DebugLogStore.log("CAST: Starting screen cast to $sinkIp:$port")

            audioCapturer.startCapture()
            screenCapturer.startEncoding(
                mediaProjection ?: throw IllegalStateException("No MediaProjection set"),
                captureWidth, captureHeight
            )

            var pts = 0L
            val increment = 1_000_000L / 30

            screenCapturer.onEncodedNalUnit = { nalData, isKeyFrame ->
                if (isStreaming) {
                    pts += increment
                    rtpSender.sendVideo(nalData, pts, isKeyFrame)
                }
            }

            audioCapturer.onAacFrame = { aacFrame, ptsUs ->
                if (isStreaming) {
                    rtpSender.sendAudio(aacFrame, ptsUs)
                }
            }

            screenCapturer.onError = { err ->
                DebugLogStore.log("CAST: Screen error — $err")
                onError?.invoke(err)
            }
            audioCapturer.onError = { Log.w(TAG, "Audio: $it") }

            onStateChanged?.invoke("STREAMING")
            DebugLogStore.log("CAST: Screen cast active — sending to $sinkIp:$port")
        } catch (e: Exception) {
            isStreaming = false
            val msg = "Start cast failed: ${e.message}"
            Log.e(TAG, msg, e)
            DebugLogStore.log("CAST: ✗ $msg")
            onError?.invoke(msg)
        }
    }

    fun stopCasting() {
        isStreaming = false
        screenCapturer.stopEncoding()
        audioCapturer.stopCapture()
        rtpSender.stop()
        onStateChanged?.invoke("IDLE")
        DebugLogStore.log("CAST: Screen cast stopped")
    }

    fun release() {
        stopCasting()
        screenCapturer.release()
    }
}
