package com.example.sleppify

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.work.*
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** Serializes user edits per playlist; queued writes survive leaving the screen or process death. */
class PlaylistSyncWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val ctx = applicationContext
        val key = inputData.getString("playlist") ?: return Result.failure()
        val videoId = inputData.getString("video") ?: return Result.failure()
        val add = inputData.getBoolean("add", true)
        val cookie = cookie(ctx)
        if (cookie.isBlank() || accountFingerprint(ctx, cookie) != inputData.getString("account")) {
            return reportError("La sesión de YouTube cambió. Vuelve a guardar la canción para sincronizarla.")
        }
        val service = YouTubeMusicService()
        var creating = false
        return try {
            val playlistId = if (key.startsWith(CustomPlaylistsStore.CUSTOM_PLAYLIST_PREFIX)) {
                val name = key.removePrefix(CustomPlaylistsStore.CUSTOM_PLAYLIST_PREFIX)
                if (!CustomPlaylistsStore.getAllPlaylistNames(ctx).contains(name)) return Result.success()
                val tracks = CustomPlaylistsStore.getTracksFromPlaylist(ctx, name)
                if (tracks.any { it.videoId == videoId } != add) return Result.success()
                val prefs = ctx.getSharedPreferences(MAPPINGS, Context.MODE_PRIVATE)
                val mappingKey = inputData.getString("owner") + ":" + key
                var remoteId = prefs.getString(mappingKey, null)
                if (remoteId == null) {
                    if (!add) return Result.success()
                    creating = true
                    remoteId = service.createPrivatePlaylist(cookie, name, tracks.map { it.videoId }.filter(::isYouTubeVideo))
                    check(prefs.edit().putString(mappingKey, remoteId).commit()) { "No se pudo registrar la playlist sincronizada." }
                    return Result.success()
                }
                remoteId
            } else {
                val id = key.removePrefix(CustomPlaylistsStore.YT_MIRROR_PREFIX)
                if (CustomPlaylistsStore.isTrackInYtMirror(ctx, id, videoId) != add) return Result.success()
                id
            }
            service.syncPlaylistMembership(cookie, playlistId, videoId, add)
            PlaybackEventBus.notifyPlaybackSnapshotUpdated()
            Result.success()
        } catch (error: Exception) {
            // Retrying a membership edit is safe: the service reads membership before writing.
            // A create with a lost response is ambiguous, so never automatically create it again.
            if (!creating && runAttemptCount < 4 && (error is java.io.IOException
                        || error.message?.contains(Regex("error 5[0-9]{2}")) == true)) {
                Result.retry()
            } else reportError(error.message ?: "No se pudo sincronizar la playlist con YouTube Music.")
        }
    }

    private fun reportError(message: String): Result {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(applicationContext, "El cambio solo se aplicó en Sleppify. $message", Toast.LENGTH_LONG).show()
        }
        // An edit rejected by YouTube must not cancel later edits in the same queue.
        return Result.success(workDataOf("sync_error" to message.take(1000)))
    }

    companion object {
        private const val MAPPINGS = "youtube_local_playlist_mappings"
        private fun isYouTubeVideo(id: String) = PlaylistMembershipPlan.isYouTubeVideo(id)

        private fun cookie(context: Context): String = StreamResolver.getAuthCookieHeader().ifBlank {
            context.getSharedPreferences(AppConstants.PREFS_PLAYER_STATE, Context.MODE_PRIVATE)
                .getString(AppConstants.PREF_LAST_YOUTUBE_WEB_COOKIE, "").orEmpty()
        }

        private fun accountFingerprint(context: Context, cookie: String): String {
            val values = cookie.split(';').map { it.trim().split('=', limit = 2) }
                .filter { it.size == 2 }.associate { it[0] to it[1] }
            val identity = AuthManager.getInstance(context).getCurrentUser()?.uid.orEmpty() + ":" +
                (values["SAPISID"] ?: values["__Secure-3PAPISID"] ?: cookie)
            return MessageDigest.getInstance("SHA-256").digest(identity.toByteArray())
                .joinToString("") { "%02x".format(it) }
        }

        @JvmStatic fun enqueue(context: Context, playlistKey: String, videoId: String, add: Boolean) {
            if (!isYouTubeVideo(videoId)) return
            val ctx = context.applicationContext
            val cookie = cookie(ctx)
            if (cookie.isBlank()) return
            val account = accountFingerprint(ctx, cookie)
            val owner = AuthManager.getInstance(ctx).getCurrentUser()?.uid ?: account
            val request = OneTimeWorkRequestBuilder<PlaylistSyncWorker>()
                .setInputData(workDataOf("playlist" to playlistKey, "video" to videoId, "add" to add,
                    "account" to account, "owner" to owner))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(ctx).enqueueUniqueWork("youtube_playlist_edit_$account:$playlistKey",
                ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }
    }
}
