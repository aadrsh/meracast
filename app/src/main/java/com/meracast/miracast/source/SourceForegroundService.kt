package com.meracast.miracast.source

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.meracast.common.AppConstants

/**
 * Foreground service to keep the Miracast source alive during screen capture.
 *
 * Android requires a foreground service with MEDIA_PROJECTION type
 * when using MediaProjection for screen recording.
 */
class SourceForegroundService : Service() {

    companion object {
        private const val TAG = "${AppConstants.TAG}.FgService"
        const val ACTION_STOP_SOURCE = "com.meracast.action.STOP_SOURCE"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        Log.d(TAG, "Foreground service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_SOURCE -> {
                Log.d(TAG, "Stop action received")
                stopSelf()
            }
            else -> {
                val notification = createNotification()
                startForeground(AppConstants.NOTIFICATION_ID_SOURCE, notification)
                Log.d(TAG, "Foreground service started")
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        Log.d(TAG, "Foreground service destroyed")
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                AppConstants.CHANNEL_ID_SOURCE,
                "Miracast Source",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Notification for Miracast screen casting"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, AppConstants.CHANNEL_ID_SOURCE)
            .setContentTitle("MiracastHub")
            .setContentText("Casting screen to Miracast device")
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }
}
