package com.example.sleppify

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log

/**
 * A lightweight foreground service whose sole purpose is to keep the app process alive
 * while SongPlayerFragment plays audio in the background. It attaches to the exact same
 * notification ID used by SongPlayerFragment.
 */
class PlaybackKeepAliveService : Service() {

    private var notificationDetached = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // Immediately satisfy the foreground contract to prevent
        // ForegroundServiceDidNotStartInTimeException on slow devices or process restarts.
        try {
            val fallback = buildFallbackNotification()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, fallback, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            } else {
                startForeground(NOTIFICATION_ID, fallback)
            }
        } catch (e: Exception) {
            Log.e("PlaybackKeepAlive", "onCreate: failed to start foreground", e)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == ACTION_STOP) {
            safeStopForeground()
            stopSelf()
            return START_NOT_STICKY
        }
        if (action == ACTION_STOP_FOREGROUND) {
            // Downgrade: remove foreground status but keep the notification visible (dismissable)
            notificationDetached = true
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_DETACH)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(false)
            }
            stopSelf()
            return START_NOT_STICKY
        }

        val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra(EXTRA_NOTIFICATION, Notification::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_NOTIFICATION)
        }
        if (notification == null) {
            // No notification provided — must still call startForeground() to satisfy the
            // contract before stopping, otherwise Android kills the process.
            try {
                val fallback = buildFallbackNotification()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(NOTIFICATION_ID, fallback, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
                } else {
                    startForeground(NOTIFICATION_ID, fallback)
                }
            } catch (e: Exception) {
                Log.e("PlaybackKeepAlive", "Failed to start foreground with fallback", e)
            }
            safeStopForeground()
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.e("PlaybackKeepAlive", "Failed to start foreground", e)
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    private fun safeStopForeground() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    private fun buildFallbackNotification(): Notification {
        val channelId = "sleppify_playback"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(android.app.NotificationManager::class.java)
            if (nm?.getNotificationChannel(channelId) == null) {
                val channel = android.app.NotificationChannel(
                    channelId, "Playback", android.app.NotificationManager.IMPORTANCE_LOW
                )
                nm?.createNotificationChannel(channel)
            }
        }
        return Notification.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Sleppify")
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (!notificationDetached) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        }
    }

    companion object {
        const val ACTION_START = "com.example.sleppify.action.START_KEEP_ALIVE"
        const val ACTION_STOP = "com.example.sleppify.action.STOP_KEEP_ALIVE"
        const val ACTION_STOP_FOREGROUND = "com.example.sleppify.action.STOP_FOREGROUND_KEEP_NOTIF"
        const val EXTRA_NOTIFICATION = "extra_notification"
        const val NOTIFICATION_ID = 11031 // Same as SongPlayerFragment MEDIA_NOTIFICATION_ID

        @JvmStatic
        fun start(context: Context, notification: Notification) {
            val intent = Intent(context, PlaybackKeepAliveService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_NOTIFICATION, notification)
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.e("PlaybackKeepAlive", "Could not start keep alive service", e)
            }
        }

        /**
         * Stop the foreground service but KEEP the notification visible (dismissable by user).
         * Used when playback is paused — the notification stays so the user can resume.
         */
        @JvmStatic
        fun stopForegroundKeepNotification(context: Context) {
            val intent = Intent(context, PlaybackKeepAliveService::class.java).apply {
                action = ACTION_STOP_FOREGROUND
            }
            try {
                context.startService(intent)
            } catch (e: Exception) {
                Log.w("PlaybackKeepAlive", "Could not stop foreground (keep notif)", e)
            }
        }

        @JvmStatic
        fun stop(context: Context) {
            val intent = Intent(context, PlaybackKeepAliveService::class.java).apply {
                action = ACTION_STOP
            }
            try {
                context.startService(intent)
            } catch (e: Exception) {
                Log.w("PlaybackKeepAlive", "Could not stop keep alive service", e)
            }
        }
    }
}

