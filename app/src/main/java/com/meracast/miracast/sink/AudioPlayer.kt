package com.meracast.miracast.sink

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import com.meracast.common.AppConstants

/**
 * Plays AAC audio frames received over the Miracast stream.
 *
 * Pipeline:
 *   AAC frame → [AudioDecoder] → PCM chunks → [AudioTrack]
 *
 * The [AudioDecoder] handles AAC→PCM conversion via MediaCodec so
 * we deliver clean 16‑bit stereo PCM to AudioTrack — no crackling,
 * no wrong‑format issues.
 */
class AudioPlayer {

    companion object {
        private const val TAG = "${AppConstants.TAG}.AudioPlay"
        private const val SAMPLE_RATE = 48000
        private const val CHANNEL_COUNT = 2
    }

    private var audioTrack: AudioTrack? = null
    private val audioDecoder = AudioDecoder()
    @Volatile
    private var isPlaying = false

    // Statistics
    var framesPlayed: Long = 0L
    var bufferLevelMs: Int = 0

    var onError: ((String) -> Unit)? = null

    fun initialize() {
        // Initialise the decoder
        audioDecoder.initialize()
        audioDecoder.onPcmData = { pcmChunk -> writeToTrack(pcmChunk) }
        audioDecoder.onError = { err -> onError?.invoke("Audio decoder: $err") }

        // AudioTrack
        try {
            val bufferSize = maxOf(
                AudioTrack.getMinBufferSize(
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_OUT_STEREO,
                    AudioFormat.ENCODING_PCM_16BIT
                ),
                SAMPLE_RATE * 2 * 2 * 3 / 10  // 300 ms buffer
            )

            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                    .build())
                .setAudioFormat(AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build())
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            Log.d(TAG, "AudioPlayer initialised (buffer=$bufferSize)")
        } catch (e: Exception) {
            Log.e(TAG, "AudioTrack init failed", e)
            onError?.invoke("AudioTrack init: ${e.message}")
        }
    }

    fun startPlayback() {
        try {
            audioTrack?.play()
            isPlaying = true
            Log.d(TAG, "Playback started")
        } catch (e: Exception) {
            Log.e(TAG, "Playback start failed", e)
            onError?.invoke("Audio start: ${e.message}")
        }
    }

    /**
     * Feed an ADTS‑framed AAC frame into the decoder pipeline.
     */
    fun feedAacFrame(aacFrame: ByteArray, pts: Long) {
        audioDecoder.feedAacFrame(aacFrame)
    }

    /**
     * Write decoded PCM to AudioTrack.
     */
    private fun writeToTrack(pcm: ByteArray) {
        if (!isPlaying || audioTrack == null) return
        try {
            val written = audioTrack?.write(pcm, 0, pcm.size)
            if (written != null && written > 0) {
                framesPlayed++

                // Approximate buffer level (bytes → ms)
                val bufState = audioTrack?.playbackHeadPosition ?: 0
                bufferLevelMs = (audioTrack?.playbackHeadPosition
                    ?.minus(bufState)?.times(1000)?.div(SAMPLE_RATE)) ?: 0
            }
        } catch (e: Exception) {
            Log.w(TAG, "AudioTrack write error", e)
        }
    }

    fun stopPlayback() {
        isPlaying = false
        audioDecoder.stopDecoding()
        try { audioTrack?.stop(); audioTrack?.release() } catch (_: Exception) {}
        audioTrack = null
        Log.d(TAG, "Playback stopped")
    }

    fun flush() {
        audioDecoder.flush()
    }

    fun release() {
        stopPlayback()
        audioDecoder.release()
    }
}
