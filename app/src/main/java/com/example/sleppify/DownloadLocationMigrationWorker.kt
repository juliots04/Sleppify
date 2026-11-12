package com.example.sleppify

import android.content.Context
import androidx.work.*

/** Moves owned downloads without requiring a network connection or downloading them again. */
class DownloadLocationMigrationWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val context = applicationContext
        val metadata = ListenHistoryStore.getAll(context).associateBy { OfflineAudioStore.normalizeTrackId(it.videoId) }
        val ids = OfflineAudioStore.listDownloadedTrackIds(context) + DownloadLocationStore.ids(context)
        var moved = 0
        var failed = 0
        var lastFailure = ""
        for (id in ids) {
            if (isStopped) return Result.failure()
            val track = metadata[id]
            if (DownloadLocationStore.publish(context, id, track?.title, track?.artist)) moved++ else {
                failed++
                lastFailure = DownloadLocationStore.lastError(context)
            }
            setProgressAsync(workDataOf("moved" to moved, "failed" to failed, "total" to ids.size))
        }
        PlaybackEventBus.notifyPlaybackSnapshotUpdated()
        val data = workDataOf("moved" to moved, "failed" to failed, "total" to ids.size,
            "error" to lastFailure.take(1000))
        return if (failed == 0) Result.success(data) else Result.failure(data)
    }

    companion object {
        const val WORK_NAME = "download_location_migration"
        const val CREATED_TAG = "download_location_created_"
        fun enqueue(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<DownloadLocationMigrationWorker>()
                    .addTag(CREATED_TAG + System.currentTimeMillis()).build())
        }
    }
}
