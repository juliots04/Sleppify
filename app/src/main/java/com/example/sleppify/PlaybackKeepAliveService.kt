package com.example.sleppify

import android.app.Notification
import android.app.Service
import androidx.core.app.NotificationCompat
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
            val initialNotif = latestNotification ?: buildFallbackNotification()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, initialNotif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            } else {
                startForeground(NOTIFICATION_ID, initialNotif)
            }
        } catch (e: Exception) {
            Log.e("PlaybackKeepAlive", "onCreate: failed to start foreground", e)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == ACTION_STOP) {
            latestNotification = null
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
                val fallback = latestNotification ?: buildFallbackNotification()
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
        latestNotification = notification
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
        // Reuse the EXACT channel + icon of the real media notification (SongPlayerFragment) so the
        // in-place startForeground swap on id 11031 is seamless and never flashes a distinct-looking
        // "Sleppify" entry. This exists only to satisfy the foreground contract in the brief window
        // before onStartCommand replaces it with the real MediaStyle notification.
        val channelId = CHANNEL_ID
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(android.app.NotificationManager::class.java)
            if (nm != null) {
                try {
                    nm.deleteNotificationChannel("sleppify_media_playback")
                    nm.deleteNotificationChannel("sleppify_media_playback_v2")
                    nm.deleteNotificationChannel("sleppify_media_playback_v3")
                } catch (e: Exception) {
                    // Ignored
                }
                if (nm.getNotificationChannel(channelId) == null) {
                    val channel = android.app.NotificationChannel(
                        channelId, "Reproducción", android.app.NotificationManager.IMPORTANCE_LOW
                    ).apply {
                        description = "Control de reproducción de música"
                        setShowBadge(false)
                        lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                        setSound(null, null)
                        enableVibration(false)
                    }
                    nm.createNotificationChannel(channel)
                }
            } else {
                Log.w("PlaybackKeepAlive", "NotificationManager is null, notification may fail to display")
            }
        }
        return NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_notification_cat)
            .setContentTitle("Sleppify")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
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
        const val CHANNEL_ID = "sleppify_media_playback_v4"

        @Volatile
        private var latestNotification: Notification? = null

        @JvmStatic
        fun start(context: Context, notification: Notification) {
            latestNotification = notification
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
            latestNotification = null
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

