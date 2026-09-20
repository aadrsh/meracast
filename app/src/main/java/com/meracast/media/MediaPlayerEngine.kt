package com.meracast.media

import android.content.Context
import android.net.Uri
import android.util.Log
import android.view.Surface
import android.view.SurfaceView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import com.meracast.common.AppConstants
import com.meracast.common.ControlMessage
import com.meracast.ui.DebugLogStore
import kotlinx.coroutines.*

/**
 * ExoPlayer wrapper for the sink device.
 *
 * Plays media files streamed from the source device's [MediaHttpServer].
 * Supports:
 *   - Play / Pause / Resume / Stop
 *   - Seek (via ExoPlayer's built-in seek)
 *   - Volume control
 *   - Buffer quality feedback via [onPositionUpdate]
 *   - Automatic HTTP range requests (ExoPlayer handles this natively)
 */
class MediaPlayerEngine(private val context: Context) {

    companion object {
        private const val TAG = "${AppConstants.TAG}.MediaPlayer"
        private const val POSITION_UPDATE_INTERVAL_MS = 500L
    }

    private var player: ExoPlayer? = null
    private var positionUpdateJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var currentMediaId: String? = null
    private var currentUrl: String? = null

    // Callbacks
    var onPositionUpdate: ((ControlMessage.PositionUpdate) -> Unit)? = null
    var onPlaybackStateChanged: ((String) -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    /**
     * Prepare and start playback of a media file at the given HTTP URL.
     *
     * @param mediaId  The source-assigned media ID
     * @param httpUrl  The full http:// URL served by the source's [MediaHttpServer]
     * @param surface  The Surface to render video onto (can be a SurfaceView's surface)
     * @param startPosition  Seek to this position on start (seconds)
     */
    fun play(
        mediaId: String,
        httpUrl: String,
        surface: Surface?,
        startPosition: Double = 0.0
    ) {
        DebugLogStore.log("MEDIA: Playing $mediaId from $httpUrl")

        currentMediaId = mediaId
        currentUrl = httpUrl

        // Release previous player
        release()

        val dataSourceFactory = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(5000)
            .setReadTimeoutMs(15000)

        val player = ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .build()
            .also { this.player = it }

        // Attach surface for video
        if (surface != null) {
            player.setVideoSurface(surface)
        }

        // Seek to start position if > 0
        if (startPosition > 0.0) {
            player.seekTo((startPosition * 1000).toLong())
        }

        // Wire event listeners
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                val stateName = when (playbackState) {
                    Player.STATE_IDLE -> "idle"
                    Player.STATE_BUFFERING -> "buffering"
                    Player.STATE_READY -> "ready"
                    Player.STATE_ENDED -> "ended"
                    else -> "unknown"
                }
                Log.d(TAG, "Playback state: $stateName")
                DebugLogStore.log("MEDIA: State → $stateName")
                onPlaybackStateChanged?.invoke(stateName)

                if (playbackState == Player.STATE_ENDED) {
                    player.seekTo(0)
                    player.stop()
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                DebugLogStore.log("MEDIA: ${if (isPlaying) "Playing" else "Paused"}")
            }

            override fun onPlayerError(error: PlaybackException) {
                val msg = "Playback error [${error.errorCodeName}]: ${error.message}"
                Log.e(TAG, msg)
                DebugLogStore.log("MEDIA: ✗ $msg")
                onError?.invoke(msg)
            }
        })

        // Build MediaItem from the HTTP URL
        val mediaItem = MediaItem.fromUri(Uri.parse(httpUrl))
        player.setMediaItem(mediaItem)
        player.prepare()
        player.play()

        startPositionUpdates()
    }

    fun pause() {
        player?.pause()
    }

    fun resume() {
        player?.play()
    }

    fun seekTo(positionSeconds: Double) {
        player?.seekTo((positionSeconds * 1000).toLong())
    }

    fun setVolume(volume: Float) {
        player?.volume = volume.coerceIn(0f, 1f)
    }

    fun stop() {
        positionUpdateJob?.cancel()
        player?.stop()
    }

    fun getCurrentPosition(): Double = (player?.currentPosition ?: 0L) / 1000.0
    fun getDuration(): Double = (player?.duration ?: 0L) / 1000.0
    fun isPlaying(): Boolean = player?.isPlaying ?: false
    fun getBufferedPercent(): Int = player?.bufferedPercentage ?: 0

    fun release() {
        positionUpdateJob?.cancel()
        player?.release()
        player = null
        currentMediaId = null
        currentUrl = null
    }

    private fun startPositionUpdates() {
        positionUpdateJob?.cancel()
        positionUpdateJob = scope.launch {
            while (isActive) {
                delay(POSITION_UPDATE_INTERVAL_MS)
                val p = player ?: break
                val state = when {
                    p.playbackState == Player.STATE_BUFFERING -> "buffering"
                    p.isPlaying -> "playing"
                    p.playbackState == Player.STATE_READY -> "paused"
                    else -> "idle"
                }
                onPositionUpdate?.invoke(ControlMessage.PositionUpdate(
                    position = p.currentPosition / 1000.0,
                    duration = p.duration / 1000.0,
                    bufferedPercent = p.bufferedPercentage,
                    state = state
                ))
            }
        }
    }
}
