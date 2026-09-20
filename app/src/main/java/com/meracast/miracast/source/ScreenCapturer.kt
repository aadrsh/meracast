package com.meracast.miracast.source

import android.app.Application
import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.util.DisplayMetrics
import android.util.Log
import android.view.Surface
import android.view.WindowManager
import com.meracast.common.AppConstants

/**
 * Captures screen content using MediaProjection and encodes to H.264 via MediaCodec.
 *
 * Callback provides raw H.264 NAL units with start codes (00 00 01).
 */
class ScreenCapturer(private val application: Application) {

    companion object {
        private const val TAG = "${AppConstants.TAG}.ScreenCap"
        private const val MIME_TYPE = "video/avc"
        // Default encoding params
        private const val DEFAULT_WIDTH = 1280
        private const val DEFAULT_HEIGHT = 720
        private const val DEFAULT_DPI = 320
        private const val DEFAULT_FRAME_RATE = 30
        private const val DEFAULT_BITRATE = 5_000_000
        private const val I_FRAME_INTERVAL = 2  // Key frame every 2 seconds
    }

    private var mediaCodec: MediaCodec? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var mediaProjection: MediaProjection? = null
    private val mediaProjectionManager: MediaProjectionManager by lazy {
        application.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    }
    private val displayManager: DisplayManager by lazy {
        application.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    }

    private var isEncoding = false
    private var frameRate = DEFAULT_FRAME_RATE
    private var bitrate = DEFAULT_BITRATE

    // Callback: raw H.264 NAL unit bytes (with 00 00 01 start codes)
    var onEncodedNalUnit: ((ByteArray, Boolean) -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    /**
     * Initialize the MediaCodec encoder.
     * Must be called on a thread with a Looper.
     */
    fun initialize(
        width: Int = DEFAULT_WIDTH,
        height: Int = DEFAULT_HEIGHT,
        fps: Int = DEFAULT_FRAME_RATE,
        bitRate: Int = DEFAULT_BITRATE
    ) {
        this.frameRate = fps
        this.bitrate = bitRate

        try {
            val format = MediaFormat.createVideoFormat(MIME_TYPE, width, height).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL)
                setInteger(MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    // Request low-latency encoding
                    setInteger("vendor.qti-ext-enc-latency.latency.low-latency-mode", 1)
                }
            }

            mediaCodec = MediaCodec.createEncoderByType(MIME_TYPE)
            mediaCodec?.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            mediaCodec?.setOnFrameRenderedListener(null, null)

            Log.d(TAG, "Encoder initialized: ${width}x${height} @ ${fps}fps, ${bitRate}bps")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize encoder", e)
            onError?.invoke("Encoder init failed: ${e.message}")
        }
    }

    /**
     * Start encoding. Creates a Surface from MediaCodec and routes
     * the MediaProjection's VirtualDisplay to it.
     *
     * @param projection The MediaProjection from user consent
     * @param width Desired capture width
     * @param height Desired capture height
     */
    fun startEncoding(
        projection: MediaProjection,
        width: Int = DEFAULT_WIDTH,
        height: Int = DEFAULT_HEIGHT
    ) {
        this.mediaProjection = projection
        val codec = mediaCodec ?: return

        codec.start()
        isEncoding = true

        val inputSurface = codec.createInputSurface()
        if (inputSurface == null) {
            Log.e(TAG, "Failed to create input surface from encoder")
            onError?.invoke("No encoder input surface available")
            return
        }

        // Get display metrics for DPI
        val metrics = DisplayMetrics()
        val wm = application.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        wm.defaultDisplay.getRealMetrics(metrics)

        // Create VirtualDisplay to route screen capture to encoder surface
        virtualDisplay = projection.createVirtualDisplay(
            "MiracastCapture",
            width,
            height,
            metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR or
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION,
            inputSurface,
            null, null
        )

        Log.d(TAG, "VirtualDisplay created: ${width}x${height} @ ${metrics.densityDpi}dpi")

        // Start output processing
        processEncoderOutput()
    }

    /**
     * Process encoded output frames from MediaCodec.
     * Runs on a coroutine thread, pulling output buffers and
     * converting them to NAL units.
     */
    private fun processEncoderOutput() {
        Thread({
            try {
                val bufferInfo = MediaCodec.BufferInfo()

                while (isEncoding && mediaCodec != null) {
                    val codec = mediaCodec ?: break
                    val outputIndex = codec.dequeueOutputBuffer(bufferInfo, 10000)

                    when {
                        outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                            // No output available, continue
                        }
                        outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            Log.d(TAG, "Encoder output format changed: ${codec.outputFormat}")
                        }
                        outputIndex == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> {
                            // Ignore in API 21+
                        }
                        outputIndex >= 0 -> {
                            val outputBuffer = codec.getOutputBuffer(outputIndex)
                            if (outputBuffer != null && bufferInfo.size > 0) {
                                // Read the encoded data
                                outputBuffer.position(bufferInfo.offset)
                                outputBuffer.limit(bufferInfo.offset + bufferInfo.size)

                                val encodedData = ByteArray(bufferInfo.size)
                                outputBuffer.get(encodedData)

                                val isKeyFrame =
                                    (bufferInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0

                                // Deliver the encoded NAL unit
                                onEncodedNalUnit?.invoke(encodedData, isKeyFrame)
                            }

                            codec.releaseOutputBuffer(outputIndex, false)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Encoder output processing error", e)
                onError?.invoke("Encoding error: ${e.message}")
            }
        }, "ScreenEncoder").start()
    }

    /**
     * Stop encoding and clean up.
     */
    fun stopEncoding() {
        isEncoding = false

        try {
            virtualDisplay?.release()
        } catch (_: Exception) {}

        try {
            mediaCodec?.stop()
            mediaCodec?.release()
        } catch (_: Exception) {}

        try {
            mediaProjection?.stop()
        } catch (_: Exception) {}

        virtualDisplay = null
        mediaCodec = null
        mediaProjection = null

        Log.d(TAG, "Screen capture stopped")
    }

    /**
     * Release all resources.
     */
    fun release() {
        stopEncoding()
    }
}
