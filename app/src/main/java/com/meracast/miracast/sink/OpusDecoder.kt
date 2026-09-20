package com.meracast.miracast.sink

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import com.meracast.common.AppConstants
import com.meracast.ui.DebugLogStore

/**
 * OPUS audio decoder for Android.
 *
 * OPUS decoding is available on all Android versions (API 21+),
 * but encoding (OpusEncoder) requires API 29+.
 */
class OpusDecoder {

    companion object {
        private const val TAG = "${AppConstants.TAG}.OpusDec"
        private const val SAMPLE_RATE = 48000
        private const val CHANNEL_COUNT = 2
    }

    private var mediaCodec: MediaCodec? = null
    private var isInitialized = false
    var framesDecoded: Long = 0L
    var onPcmFrame: ((ByteArray, Long) -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    /**
     * Initialize the OPUS decoder.
     */
    fun initialize() {
        if (isInitialized) return

        try {
            val format = MediaFormat.createAudioFormat(
                MediaFormat.MIMETYPE_AUDIO_OPUS,
                SAMPLE_RATE, CHANNEL_COUNT
            ).apply {
                // Android's OPUS decoder expects this info in the config
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 4096)
            }

            mediaCodec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
            mediaCodec?.configure(format, null, null, 0)
            mediaCodec?.start()
            isInitialized = true
            DebugLogStore.log("OPUS: Decoder initialized")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize OPUS decoder", e)
            DebugLogStore.log("OPUS: Init failed — ${e.message}")
            onError?.invoke("OPUS decoder: ${e.message}")
        }
    }

    /**
     * Feed an OPUS frame to the decoder.
     *
     * @param opusFrame  Encoded OPUS data
     * @param presentationTimeUs  Presentation timestamp
     */
    fun decodeFrame(opusFrame: ByteArray, presentationTimeUs: Long) {
        val codec = mediaCodec ?: return

        try {
            val inputIndex = codec.dequeueInputBuffer(10000)
            if (inputIndex >= 0) {
                val inputBuffer = codec.getInputBuffer(inputIndex) ?: return
                inputBuffer.clear()
                inputBuffer.put(opusFrame)
                codec.queueInputBuffer(inputIndex, 0, opusFrame.size, presentationTimeUs, 0)
            }

            val bufferInfo = MediaCodec.BufferInfo()
            var outputIndex = codec.dequeueOutputBuffer(bufferInfo, 10000)

            while (outputIndex >= 0) {
                val outputBuffer = codec.getOutputBuffer(outputIndex) ?: break
                val pcm = ByteArray(bufferInfo.size)
                outputBuffer.get(pcm)

                onPcmFrame?.invoke(pcm, bufferInfo.presentationTimeUs)
                framesDecoded++

                codec.releaseOutputBuffer(outputIndex, false)
                outputIndex = codec.dequeueOutputBuffer(bufferInfo, 0)
            }
        } catch (e: Exception) {
            Log.w(TAG, "OPUS decode error: ${e.message}")
        }
    }

    fun stop() {
        isInitialized = false
        try {
            mediaCodec?.stop()
            mediaCodec?.release()
        } catch (_: Exception) {}
        mediaCodec = null
        DebugLogStore.log("OPUS: Decoder stopped ($framesDecoded frames)")
    }
}
