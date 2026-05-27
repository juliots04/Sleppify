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

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == ACTION_STOP) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
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
            // No notification provided — cannot satisfy foreground contract, stop immediately
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

    override fun onDestroy() {
        super.onDestroy()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    companion object {
        const val ACTION_START = "com.example.sleppify.action.START_KEEP_ALIVE"
        const val ACTION_STOP = "com.example.sleppify.action.STOP_KEEP_ALIVE"
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

