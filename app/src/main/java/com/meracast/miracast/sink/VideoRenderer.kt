package com.meracast.miracast.sink

import android.util.Log
import android.view.Surface
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import com.meracast.common.AppConstants

/**
 * Renders H.264 video to a Surface using MediaCodec decoder.
 *
 * Receives raw H.264 NAL units (with 00 00 01 start codes),
 * decodes them via hardware decoder, and renders to the provided Surface.
 */
class VideoRenderer {

    companion object {
        private const val TAG = "${AppConstants.TAG}.VideoRender"
        private const val MIME_TYPE = "video/avc"
    }

    private var mediaCodec: MediaCodec? = null
    private var inputSurface: Surface? = null
    var outputSurface: Surface? = null
    @Volatile
    private var isDecoding = false
    var width = 0
    var height = 0
    private var formatConfigured = false

    // Callbacks
    var onFrameRendered: (() -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    var onDecoderReady: ((Surface) -> Unit)? = null

    /**
     * Initialize the H.264 decoder with an output surface.
     *
     * @param surface The Surface to render decoded frames onto
     * @param videoWidth Expected video width (can be 0 for auto-detect)
     * @param videoHeight Expected video height (can be 0 for auto-detect)
     */
    fun initialize(surface: Surface, videoWidth: Int = 1920, videoHeight: Int = 1080) {
        this.outputSurface = surface
        this.width = videoWidth
        this.height = videoHeight

        try {
            val format = MediaFormat.createVideoFormat(MIME_TYPE, videoWidth, videoHeight)

            mediaCodec = MediaCodec.createDecoderByType(MIME_TYPE)
            mediaCodec?.configure(format, surface, null, 0)
            mediaCodec?.start()

            isDecoding = true
            formatConfigured = false

            Log.d(TAG, "Video decoder initialized for surface")

            // Start the decode loop
            startDecodeLoop()

            onDecoderReady?.invoke(surface)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize video decoder", e)
            onError?.invoke("Decoder init failed: ${e.message}")
        }
    }

    /**
     * Background thread that drains decoder output and renders frames.
     *
     * Uses a 10 ms timeout on [dequeueOutputBuffer] — this avoids
     * busy-waiting while keeping latency acceptable.
     */
    private fun startDecodeLoop() {
        Thread({
            try {
                val bufferInfo = MediaCodec.BufferInfo()

                while (isDecoding && mediaCodec != null) {
                    val codec = mediaCodec ?: break

                    // Block for up to 10 ms waiting for output
                    val outputIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000)

                    when {
                        outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                            // No frame ready yet — loop back with timeout
                        }
                        outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            Log.d(TAG, "Decoder format: ${codec.outputFormat}")
                        }
                        outputIndex >= 0 -> {
                            val doRender = bufferInfo.size > 0
                            codec.releaseOutputBuffer(outputIndex, doRender)
                            if (doRender) onFrameRendered?.invoke()
                        }
                    }
                }
            } catch (e: IllegalStateException) {
                // Expected during shutdown — codec released by stopDecoding()
                Log.d(TAG, "Video decode loop exiting (${e.message})")
            } catch (e: Exception) {
                Log.w(TAG, "Video decode loop error", e)
            }
        }, "VideoDecoder").start()
    }

    /**
     * Feed an H.264 NAL unit to the decoder.
     *
     * @param nalData Raw H.264 NAL unit with start code prefix
     * @param isKeyFrame True if this is an IDR frame
     * @param pts Presentation timestamp
     */
    fun feedNalUnit(nalData: ByteArray, isKeyFrame: Boolean, pts: Long) {
        if (!isDecoding || mediaCodec == null) return

        try {
            val codec = mediaCodec ?: return

            if (!formatConfigured) {
                // On first key frame, try to extract SPS/PPS for decoder config
                if (isKeyFrame) {
                    formatConfigured = true
                }
            }

            val inputIndex = codec.dequeueInputBuffer(10000)
            if (inputIndex >= 0) {
                val inputBuffer = codec.getInputBuffer(inputIndex)
                if (inputBuffer != null) {
                    inputBuffer.clear()
                    inputBuffer.put(nalData)

                    val flags = if (isKeyFrame) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                    codec.queueInputBuffer(inputIndex, 0, nalData.size, pts, flags)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error feeding NAL unit", e)
        }
    }

    /**
     * Stop decoding and clean up.
     */
    fun stopDecoding() {
        isDecoding = false

        try {
            mediaCodec?.stop()
            mediaCodec?.release()
        } catch (_: Exception) {}

        mediaCodec = null
        Log.d(TAG, "Video decoder stopped")
    }

    /**
     * Release all resources.
     */
    fun release() {
        stopDecoding()
        outputSurface = null
    }
}
