package com.meracast.miracast.source

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import com.meracast.common.AppConstants
import com.meracast.ui.DebugLogStore

/**
 * OPUS audio encoder for Android 10+ (API 29+).
 *
 * On devices running API 29+, Android's MediaCodec can encode OPUS
 * audio.  OPUS delivers better quality than AAC at the same bitrate,
 * especially for music and voice.
 *
 * Falls back gracefully if OPUS encoding is not available (API < 29).
 */
class OpusEncoder {

    companion object {
        private const val TAG = "${AppConstants.TAG}.OpusEnc"
        private const val SAMPLE_RATE = 48000
        private const val CHANNEL_COUNT = 2
        private const val BIT_RATE = 96000       // 96 kbps — transparent quality
        private const val PCM_BUF_MS = 60         // 60 ms frames (OPUS default)
        private const val KEY_OPUS_APPLICATION = "opus-application"
        private const val APPLICATION_AUDIO = "audio"
    }

    private var mediaCodec: MediaCodec? = null
    private var isEncoding = false
    var framesEncoded: Long = 0L
    var onOpusFrame: ((ByteArray, Long) -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    /**
     * Check if OPUS encoding is supported on this device.
     */
    fun isAvailable(): Boolean {
        if (Build.VERSION.SDK_INT < 29) {
            DebugLogStore.log("OPUS: Not available — requires API 29+")
            return false
        }
        val available = try {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).findEncoderForFormat(
                MediaFormat().apply { setString(MediaFormat.KEY_MIME, MediaFormat.MIMETYPE_AUDIO_OPUS) }
            ) != null
        } catch (_: Exception) { false }

        if (available) DebugLogStore.log("OPUS: Encoder available on this device")
        else DebugLogStore.log("OPUS: Encoder NOT available — will use AAC")
        return available
    }

    /**
     * Initialize the OPUS encoder.
     * @param pcmInput PCM audio data (16-bit signed, 48000Hz, stereo)
     */
    fun initialize() {
        if (!isAvailable()) {
            onError?.invoke("OPUS encoder not available")
            return
        }

        try {
            val format = MediaFormat.createAudioFormat(
                MediaFormat.MIMETYPE_AUDIO_OPUS,
                SAMPLE_RATE, CHANNEL_COUNT
            ).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, SAMPLE_RATE * CHANNEL_COUNT * 2 / 10)
                // OPUS application: audio, voice, or low-delay
                setString(KEY_OPUS_APPLICATION, APPLICATION_AUDIO)
                // VBR mode: 0 = CBR, 1 = constrained VBR, 2 = full VBR
                if (Build.VERSION.SDK_INT >= 30) {
                    setInteger("vbr-mode", 1)  // constrained VBR
                }
            }

            mediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
            mediaCodec?.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            mediaCodec?.start()
            isEncoding = true
            framesEncoded = 0
            DebugLogStore.log("OPUS: Encoder initialized (${BIT_RATE / 1000} kbps, $CHANNEL_COUNT ch)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize OPUS encoder", e)
            DebugLogStore.log("OPUS: Init failed — ${e.message}")
            isEncoding = false
        }
    }

    /**
     * Feed PCM audio data to the encoder.
     *
     * @param pcmData Signed 16-bit PCM audio
     * @param presentationTimeUs Presentation timestamp
     */
    fun encodePcm(pcmData: ByteArray, presentationTimeUs: Long) {
        val codec = mediaCodec ?: return
        if (!isEncoding) return

        try {
            val inputIndex = codec.dequeueInputBuffer(10000)
            if (inputIndex >= 0) {
                val inputBuffer = codec.getInputBuffer(inputIndex) ?: return
                inputBuffer.clear()
                inputBuffer.put(pcmData)
                codec.queueInputBuffer(inputIndex, 0, pcmData.size, presentationTimeUs, 0)
            }

            // Get output
            val bufferInfo = MediaCodec.BufferInfo()
            var outputIndex = codec.dequeueOutputBuffer(bufferInfo, 10000)

            while (outputIndex >= 0) {
                val outputBuffer = codec.getOutputBuffer(outputIndex) ?: break
                val frame = ByteArray(bufferInfo.size)
                outputBuffer.get(frame)

                onOpusFrame?.invoke(frame, bufferInfo.presentationTimeUs)
                framesEncoded++

                codec.releaseOutputBuffer(outputIndex, false)
                outputIndex = codec.dequeueOutputBuffer(bufferInfo, 0)
            }
        } catch (e: Exception) {
            Log.w(TAG, "OPUS encode error: ${e.message}")
        }
    }

    fun stop() {
        isEncoding = false
        try {
            mediaCodec?.stop()
            mediaCodec?.release()
        } catch (_: Exception) {}
        mediaCodec = null
        DebugLogStore.log("OPUS: Encoder stopped (${framesEncoded} frames)")
    }
}
