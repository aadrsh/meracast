package com.meracast.miracast.source

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.os.Build
import android.util.Log
import com.meracast.common.AppConstants

/**
 * Captures ***internal*** device audio output (not microphone) using
 * the MediaProjection API.
 *
 * On Android 10+ (API 29), MediaProjection can create an AudioRecord
 * that captures the device's mixed audio output — exactly what the user
 * hears from speakers.  This is the correct source for screen casting:
 * you want to send game/music/video audio, not the room's ambient sound.
 *
 * The captured PCM is encoded to AAC via MediaCodec and delivered as
 * ADTS‑framed AAC frames.
 */
class AudioCapturer {

    companion object {
        private const val TAG = "${AppConstants.TAG}.AudioCap"
        private const val SAMPLE_RATE = 48000
        private const val CHANNEL_COUNT = 2
        private const val BIT_RATE = 128_000
        private const val AAC_PROFILE = MediaCodecInfo.CodecProfileLevel.AACObjectLC
        private const val PCM_BUF_MS = 100  // 100 ms capture chunks
    }

    private var audioRecord: AudioRecord? = null
    private var mediaCodec: MediaCodec? = null
    private var isCapturing = false
    private var mediaProjection: MediaProjection? = null

    // Statistics exposed for the debug overlay
    var captureBitrate: Long = 0L
    var framesCaptured: Long = 0L

    var onAacFrame: ((ByteArray, Long) -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    private var presentationTimeUs: Long = 0L

    /**
     * Initialise the AAC encoder and prepare for internal‑audio capture.
     *
     * @param projection The user‑granted MediaProjection (from screen‑capture consent).
     *                   This is what authorises internal audio capture.
     */
    fun initialize(projection: MediaProjection) {
        this.mediaProjection = projection

        try {
            // -- AAC encoder --
            val aacMime = "audio/mp4a-latm"
            val format = MediaFormat.createAudioFormat(aacMime, SAMPLE_RATE, CHANNEL_COUNT).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
                setInteger(MediaFormat.KEY_AAC_PROFILE, AAC_PROFILE)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 8192)
            }

            mediaCodec = MediaCodec.createEncoderByType(aacMime)
            mediaCodec?.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)

            // -- AudioRecord via MediaProjection (internal audio, NOT microphone) --
            val audioFormat = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                .build()

            // Use AudioPlaybackCaptureConfiguration to capture device audio
            // output (not microphone).  Requires API 29+ and the user's
            // MediaProjection consent — which we already have from screen capture.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val playbackConfig = android.media.AudioPlaybackCaptureConfiguration.Builder(projection)
                    .addMatchingUsage(android.media.AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(android.media.AudioAttributes.USAGE_GAME)
                    .addMatchingUsage(android.media.AudioAttributes.USAGE_UNKNOWN)
                    .build()
                audioRecord = AudioRecord.Builder()
                    .setAudioPlaybackCaptureConfig(playbackConfig)
                    .setAudioFormat(audioFormat)
                    .setBufferSizeInBytes(
                        AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_STEREO,
                            AudioFormat.ENCODING_PCM_16BIT)
                    )
                    .build()
            } else {
                // Fallback for older API — won't capture internal audio.
                Log.w(TAG, "API < 29, internal audio unavailable; using mic fallback")
                audioRecord = AudioRecord.Builder()
                    .setAudioSource(android.media.MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                    .setAudioFormat(audioFormat)
                    .setBufferSizeInBytes(
                        AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_STEREO,
                            AudioFormat.ENCODING_PCM_16BIT)
                    )
                    .build()
            }

            Log.d(TAG, "Internal audio capture initialised: ${SAMPLE_RATE}Hz, ${CHANNEL_COUNT}ch, ${BIT_RATE}bps")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialise audio capture", e)
            onError?.invoke("Audio init: ${e.message}")
        }
    }

    /**
     * Start capturing internal audio, encoding to AAC, and delivering frames.
     */
    fun startCapture() {
        val codec = mediaCodec ?: return
        val record = audioRecord ?: return

        codec.start()
        record.startRecording()
        isCapturing = true
        presentationTimeUs = 0L
        framesCaptured = 0
        captureBitrate = 0L

        Log.d(TAG, "Internal audio capture started")

        Thread({
            val pcmBuffer = ByteArray(SAMPLE_RATE / (1000 / PCM_BUF_MS) * 2 * CHANNEL_COUNT)
            val bufferInfo = MediaCodec.BufferInfo()
            var totalBytes = 0L
            var lastTimestamp = System.nanoTime()

            while (isCapturing && mediaCodec != null) {
                val codec = mediaCodec ?: break
                val bytesRead = record.read(pcmBuffer, 0, pcmBuffer.size)
                if (bytesRead <= 0) continue

                totalBytes += bytesRead

                // Rough bitrate tracking (every 50 frames)
                framesCaptured++
                if (framesCaptured % 50 == 0L) {
                    val elapsed = (System.nanoTime() - lastTimestamp) / 1_000_000_000.0
                    if (elapsed > 0) {
                        captureBitrate = (totalBytes * 8 / elapsed).toLong()
                    }
                }

                // Feed PCM to AAC encoder
                val inputIndex = codec.dequeueInputBuffer(20_000)
                if (inputIndex >= 0) {
                    val inputBuffer = codec.getInputBuffer(inputIndex)
                    inputBuffer?.clear()
                    inputBuffer?.put(pcmBuffer, 0, bytesRead)

                    presentationTimeUs += bytesRead * 1_000_000L /
                            (SAMPLE_RATE * CHANNEL_COUNT * 2)
                    codec.queueInputBuffer(inputIndex, 0, bytesRead,
                        presentationTimeUs, 0)
                }

                // Drain encoded output
                var outputIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000)
                while (outputIndex >= 0) {
                    val outputBuffer = codec.getOutputBuffer(outputIndex)
                    if (outputBuffer != null && bufferInfo.size > 0) {
                        outputBuffer.position(bufferInfo.offset)
                        outputBuffer.limit(bufferInfo.offset + bufferInfo.size)

                        val encodedAac = ByteArray(bufferInfo.size)
                        outputBuffer.get(encodedAac)
                        val aacWithAdts = addAdtsHeader(encodedAac)
                        onAacFrame?.invoke(aacWithAdts, presentationTimeUs)
                    }
                    codec.releaseOutputBuffer(outputIndex, false)
                    outputIndex = codec.dequeueOutputBuffer(bufferInfo, 0)
                }
            }
        }, "AudioCapture").start()
    }

    // ── ADTS header (7 bytes) ──────────────────────────────────────────

    private fun addAdtsHeader(aacData: ByteArray): ByteArray {
        val frameLength = aacData.size + 7
        val adts = ByteArray(frameLength)

        adts[0] = 0xFF.toByte()
        adts[1] = 0xF1.toByte()

        val sampleRateIndex = 3  // 48000
        val channelConfig = CHANNEL_COUNT

        adts[2] = ((((AAC_PROFILE - 1) shl 6) or ((sampleRateIndex and 0x0F) shl 2) or
                ((channelConfig shr 2) and 0x01)) and 0xFF).toByte()
        adts[3] = ((((channelConfig and 0x03) shl 6) or ((frameLength shr 11) and 0x03)) and 0xFF).toByte()
        adts[4] = (frameLength shr 3).toByte()
        adts[5] = ((((frameLength shl 5) and 0xE0) or 0x1F) and 0xFF).toByte()
        adts[6] = 0xFC.toByte()

        System.arraycopy(aacData, 0, adts, 7, aacData.size)
        return adts
    }

    fun stopCapture() {
        isCapturing = false
        try { audioRecord?.stop(); audioRecord?.release() } catch (_: Exception) {}
        try { mediaCodec?.stop(); mediaCodec?.release() } catch (_: Exception) {}
        audioRecord = null; mediaCodec = null
        Log.d(TAG, "Audio capture stopped")
    }

    fun release() { stopCapture() }
}
