package com.meracast.miracast.sink

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.Log
import com.meracast.common.AppConstants
import java.nio.ByteBuffer

/**
 * Decodes AAC frames to 16‑bit PCM using Android's MediaCodec audio decoder.
 *
 * Buffers decoded PCM and delivers it in fixed‑size chunks suitable for
 * direct playback via AudioTrack.
 */
class AudioDecoder {

    companion object {
        private const val TAG = "${AppConstants.TAG}.AudioDec"
        private const val MIME_TYPE = "audio/mp4a-latm"
        private const val SAMPLE_RATE = 48000
        private const val CHANNEL_COUNT = 2

        /** PCM output chunk size in bytes (~50 ms at stereo 16‑bit 48 kHz) */
        private const val PCM_CHUNK_SIZE = 48_000 * 2 * 2 / 20  // 9600 bytes

        // ADTS header constants
        private const val ADTS_HEADER_LEN = 7
        private const val ADTS_SYNC_HI: Byte = 0xFF.toByte()
    }

    private var mediaCodec: MediaCodec? = null
    @Volatile
    private var isDecoding = false

    // PCM buffer reassembly
    private val pcmBuffer = ByteArray(PCM_CHUNK_SIZE * 4)  // ~200 ms buffer
    private var pcmOffset = 0

    // Statistics
    var framesDecoded = 0
    var totalPcmBytes = 0L

    /** Callback delivering decoded PCM data ready for AudioTrack. */
    var onPcmData: ((ByteArray) -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    /**
     * Initialise the AAC decoder.
     * Must be called on a thread with a Looper.
     */
    fun initialize() {
        try {
            val format = MediaFormat.createAudioFormat(
                MIME_TYPE, SAMPLE_RATE, CHANNEL_COUNT
            ).apply {
                // Tell the decoder it will receive ADTS‑framed AAC
                setInteger(MediaFormat.KEY_IS_ADTS, 1)
                setInteger(MediaFormat.KEY_AAC_PROFILE,
                    MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            }

            mediaCodec = MediaCodec.createDecoderByType(MIME_TYPE)
            mediaCodec?.configure(format, null, null, 0)
            mediaCodec?.start()

            isDecoding = true
            Log.d(TAG, "AAC decoder initialised ($SAMPLE_RATE Hz, $CHANNEL_COUNT ch)")

            // Kick off the output‑pump thread
            startOutputPump()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialise AAC decoder", e)
            onError?.invoke("Audio decoder init: ${e.message}")
        }
    }

    /**
     * Background thread that continuously drains decoded PCM frames.
     */
    private fun startOutputPump() {
        Thread({
            try {
                val bufferInfo = MediaCodec.BufferInfo()

                while (isDecoding && mediaCodec != null) {
                    val codec = mediaCodec ?: break

                    val outputIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000)

                    when {
                        outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                            // No output yet, yield
                            Thread.yield()
                        }
                        outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            Log.d(TAG, "Decoder output format: ${codec.outputFormat}")
                        }
                        outputIndex >= 0 -> {
                            val outputBuffer = codec.getOutputBuffer(outputIndex)
                            if (outputBuffer != null && bufferInfo.size > 0) {
                                outputBuffer.position(bufferInfo.offset)
                                outputBuffer.limit(bufferInfo.offset + bufferInfo.size)

                                val pcm = ByteArray(bufferInfo.size)
                                outputBuffer.get(pcm)
                                totalPcmBytes += pcm.size

                                // Accumulate into our buffer and flush in chunks
                                accumulateAndFlush(pcm)
                                framesDecoded++
                            }
                            codec.releaseOutputBuffer(outputIndex, false)
                        }
                    }
                }
            } catch (e: IllegalStateException) {
                // Expected during shutdown — codec is released by stopDecoding()
                // while the pump thread is inside the loop. Silently exit.
                Log.d(TAG, "Audio decoder pump exiting (${e.message})")
            } catch (e: Exception) {
                Log.w(TAG, "Audio decoder pump error", e)
            }
        }, "AudioDecoderPump").start()
    }

    /**
     * Accumulate raw PCM and dispatch in fixed‑sized chunks.
     */
    private fun accumulateAndFlush(pcm: ByteArray) {
        // Copy into our circular-ish buffer
        val space = pcmBuffer.size - pcmOffset
        if (space < pcm.size) {
            // Buffer full – flush everything we have
            val chunk = pcmBuffer.copyOfRange(0, pcmOffset)
            onPcmData?.invoke(chunk)
            pcmOffset = 0
        }

        System.arraycopy(pcm, 0, pcmBuffer, pcmOffset, pcm.size)
        pcmOffset += pcm.size

        // Flush if we have at least one chunk
        while (pcmOffset >= PCM_CHUNK_SIZE) {
            val chunk = pcmBuffer.copyOfRange(0, PCM_CHUNK_SIZE)
            onPcmData?.invoke(chunk)

            // Shift remaining data to front
            val remaining = pcmOffset - PCM_CHUNK_SIZE
            if (remaining > 0) {
                System.arraycopy(pcmBuffer, PCM_CHUNK_SIZE, pcmBuffer, 0, remaining)
            }
            pcmOffset = remaining
        }
    }

    /**
     * Feed an AAC frame (with ADTS header) into the decoder.
     */
    fun feedAacFrame(aacFrame: ByteArray) {
        if (!isDecoding || mediaCodec == null) return

        try {
            val codec = mediaCodec ?: return
            val inputIndex = codec.dequeueInputBuffer(10_000)
            if (inputIndex < 0) return

            val inputBuffer = codec.getInputBuffer(inputIndex) ?: return
            inputBuffer.clear()
            inputBuffer.put(aacFrame)

            codec.queueInputBuffer(
                inputIndex,
                0,
                aacFrame.size,
                0,  // pts – not critical for audio
                0
            )
        } catch (e: Exception) {
            Log.w(TAG, "feedAacFrame error", e)
        }
    }

    /**
     * Reset decoder state (call between streams).
     */
    fun flush() {
        try {
            mediaCodec?.flush()
        } catch (_: Exception) {}
        pcmOffset = 0
        framesDecoded = 0
        totalPcmBytes = 0
    }

    /**
     * Stop decoding and release.
     */
    fun stopDecoding() {
        isDecoding = false
        try {
            mediaCodec?.stop()
            mediaCodec?.release()
        } catch (_: Exception) {}
        mediaCodec = null
        pcmOffset = 0
    }

    fun release() {
        stopDecoding()
    }
}
