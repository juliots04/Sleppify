package com.example.sleppify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * In-process target for the media notification action buttons (prev / play-pause / next).
 *
 * These used to fire PendingIntent.getActivity(MainActivity), which on Android <= 12 brings the
 * whole app to the foreground on every tap (the system media controls on 13+ talk to the
 * MediaSession directly and never hit these actions). A broadcast stays invisible:
 * - Activity alive (the normal case while music plays): dispatch the action to it directly.
 * - Activity gone: fall back to launching it like before. Android 12 blocks activity starts
 *   from notification-triggered receivers (trampoline restriction), so there the tap is dropped;
 *   reopening the app restores playback from the snapshot anyway.
 */
class MediaActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (MainActivity.dispatchMediaAction(action)) return

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            try {
                context.startActivity(
                    Intent(context, MainActivity::class.java)
                        .setAction(action)
                        .addFlags(
                            Intent.FLAG_ACTIVITY_NEW_TASK
                                or Intent.FLAG_ACTIVITY_SINGLE_TOP
                                or Intent.FLAG_ACTIVITY_CLEAR_TOP
                        )
                )
            } catch (e: Exception) {
                android.util.Log.w("MediaActionReceiver", "Fallback activity launch failed", e)
            }
        }
    }
}
