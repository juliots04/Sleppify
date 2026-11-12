package com.example.sleppify

import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.text.TextUtils
import android.util.Log
import androidx.work.Data
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.Collections
import java.util.Locale
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

class OfflinePlaylistDownloadWorker(
    context: Context,
    params: WorkerParameters
) : Worker(context, params) {
    @Volatile private var storageFailure = false

    override fun doWork(): Result {
        val token = inputData.getString(INPUT_MANIFEST)
        val manifest = if (token == null) null else try {
            OfflineDownloadManifest.read(OfflineDownloadScheduler.directory(applicationContext), token)
        } catch (error: java.io.IOException) {
            Log.e(TAG, "Cannot read download manifest", error)
            return Result.failure()
        }
        val result = download(manifest)
        // Keep the payload across retries and process death; release it after terminal work.
        if (token != null && result !is Result.Retry) {
            OfflineDownloadManifest.delete(OfflineDownloadScheduler.directory(applicationContext), token)
        }
        return result
    }

    private fun download(manifest: OfflineDownloadManifest?): Result {
        val appContext = applicationContext
        val ids = manifest?.ids ?: inputData.getStringArray(INPUT_VIDEO_IDS)
        if (ids == null || ids.isEmpty()) {
            return Result.failure(
                Data.Builder()
                    .putInt(OUTPUT_TOTAL, 0)
                    .putInt(OUTPUT_DOWNLOADED, 0)
                    .putString(OUTPUT_REASON, OUTPUT_REASON_NONE)
                    .build()
            )
        }

        val playlistId = inputData.getString(INPUT_PLAYLIST_ID)?.trim().orEmpty()
        val playlistTitle = normalizePlaylistTitle(
            inputData.getString(INPUT_PLAYLIST_TITLE),
            inputData.getString(INPUT_PLAYLIST_ID)
        )
        val titles = manifest?.titles ?: inputData.getStringArray(INPUT_TITLES)
        val artists = manifest?.artists ?: inputData.getStringArray(INPUT_ARTISTS)
        val durations = manifest?.durations ?: inputData.getStringArray(INPUT_DURATIONS)

        val pendingTotal = ids.size
        val alreadyOfflineCount = maxOf(0, inputData.getInt(INPUT_ALREADY_OFFLINE_COUNT, 0))
        val totalWithVideoId = inputData.getInt(INPUT_TOTAL_WITH_VIDEO_ID, pendingTotal + alreadyOfflineCount)
        val total = maxOf(pendingTotal + alreadyOfflineCount, totalWithVideoId)
        var done = minOf(total, alreadyOfflineCount)
        var downloaded = minOf(total, alreadyOfflineCount)
        val userInitiated = inputData.getBoolean(INPUT_USER_INITIATED, false)
        val manualQueue = inputData.getBoolean(INPUT_MANUAL_QUEUE, false)
        val maxParallelDownloads =
            if (manualQueue) MAX_PARALLEL_DOWNLOADS_MANUAL else MAX_PARALLEL_DOWNLOADS_AUTO
        var encounteredNoNetwork = false
        val activeTrackIds = Collections.synchronizedSet(HashSet<String>())
        val activeTrackProgressFractions = Collections.synchronizedMap(HashMap<String, Float>())

        val initialSnapshot = snapshotActiveProgress(activeTrackIds, activeTrackProgressFractions)
        publishProgress(playlistTitle, done, total, downloaded, "", initialSnapshot.trackIds, initialSnapshot.fractions)

        val executor = Executors.newFixedThreadPool(maxParallelDownloads)
        val completionService = ExecutorCompletionService<TrackDownloadResult>(executor)
        var submittedTasks = 0

        try {
            for (i in ids.indices) {
                if (isStopped) {
                    Log.w(TAG, "doWork: worker stopped before enqueue, retrying")
                    return Result.retry()
                }

                val trackIndex = i
                completionService.submit {
                    processSingleTrackDownload(
                        appContext, userInitiated, trackIndex, ids, titles, artists, durations,
                        activeTrackIds, activeTrackProgressFractions
                    )
                }
                submittedTasks++
            }

            for (i in 0 until submittedTasks) {
                if (isStopped) {
                    Log.w(TAG, "doWork: worker stopped, retrying")
                    return Result.retry()
                }

                var trackResult: TrackDownloadResult? = null
                while (trackResult == null) {
                    if (isStopped) {
                        Log.w(TAG, "doWork: worker stopped while waiting for result, retrying")
                        return Result.retry()
                    }

                    try {
                        val future = completionService.poll(650L, TimeUnit.MILLISECONDS)
                        if (future == null) {
                            val activeSnapshot = snapshotActiveProgress(activeTrackIds, activeTrackProgressFractions)
                            val currentId = if (activeSnapshot.trackIds.isNotEmpty()) activeSnapshot.trackIds[0] else ""
                            publishProgress(
                                playlistTitle, done, total, downloaded, currentId,
                                activeSnapshot.trackIds, activeSnapshot.fractions
                            )
                            continue
                        }

                        trackResult = future.get() ?: TrackDownloadResult.failed("", "")
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        trackResult = TrackDownloadResult.failed("", "")
                    } catch (e: Exception) {
                        Log.e(TAG, "track:future_exception message=" + e.message, e)
                        trackResult = TrackDownloadResult.failed("", "")
                    }
                }

                if (trackResult.noNetworkEncountered) encounteredNoNetwork = true
                if (trackResult.downloaded) {
                    downloaded = minOf(total, downloaded + 1)
                }

                done = minOf(total, done + 1)
                val activeSnapshot = snapshotActiveProgress(activeTrackIds, activeTrackProgressFractions)
                val currentId = if (activeSnapshot.trackIds.isNotEmpty()) activeSnapshot.trackIds[0] else trackResult.trackId
                publishProgress(
                    playlistTitle, done, total, downloaded, currentId,
                    activeSnapshot.trackIds, activeSnapshot.fractions
                )
            }
        } finally {
            executor.shutdownNow()
        }

        val reason = when {
            storageFailure -> OUTPUT_REASON_STORAGE
            encounteredNoNetwork -> OUTPUT_REASON_NO_NETWORK
            downloaded <= 0 -> OUTPUT_REASON_NO_MATCH
            downloaded < total -> OUTPUT_REASON_PARTIAL
            else -> OUTPUT_REASON_NONE
        }

        if (OUTPUT_REASON_NO_NETWORK == reason || (OUTPUT_REASON_PARTIAL == reason && !manualQueue && runAttemptCount < 3)) {
            // Manual single-track jobs share ONE unique APPEND chain (so the counter observers see
            // them). An endlessly-retrying head job would block every download appended behind it
            // (head-of-line blocking), so manual jobs get a bounded number of attempts and then
            // let the chain advance; the user can re-tap download. Playlist (auto) jobs keep
            // retrying to finish incomplete downloads.
            if (manualQueue && runAttemptCount >= MANUAL_QUEUE_MAX_RUN_ATTEMPTS - 1) {
                Log.w(TAG, "doWork:network_unavailable manual_give_up attempt=$runAttemptCount")
                return Result.success(
                    Data.Builder()
                        .putInt(OUTPUT_TOTAL, total)
                        .putInt(OUTPUT_DOWNLOADED, downloaded)
                        .putString(OUTPUT_REASON, reason)
                        .build()
                )
            }
            Log.w(TAG, "doWork:retrying reason=$reason attempt=$runAttemptCount")
            return Result.retry()
        }

        // Persist playlist-level offline state so the home/library ring + check icon are correct
        // on cold start WITHOUT having to open the playlist first. Only do this for full-playlist
        // downloads (manualQueue == false); single-track manual enqueues carry total=1 and would
        // otherwise wrongly mark the whole playlist complete. The async disk scan in
        // MusicPlayerFragment later overwrites these with the same disk truth.
        if (!manualQueue && playlistId.isNotEmpty() && total > 0) {
            val fraction = maxOf(0f, minOf(1f, downloaded.toFloat() / total.toFloat()))
            persistPlaylistOfflineState(appContext, playlistId, downloaded >= total, fraction)
        }

        // Notify the UI once on batch completion so the library tile / playlist header can
        // recompute offline-complete state from disk — covers batches that were entirely
        // already-offline (which never fire the per-track notify above).
        PlaybackEventBus.notifyPlaybackSnapshotUpdated()

        return Result.success(
            Data.Builder()
                .putInt(OUTPUT_TOTAL, total)
                .putInt(OUTPUT_DOWNLOADED, downloaded)
                .putString(OUTPUT_REASON, reason)
                .build()
        )
    }

    private fun publishProgress(
        playlistTitle: String,
        done: Int,
        total: Int,
        downloaded: Int,
        currentId: String?,
        activeTrackIds: Array<String>,
        activeFractions: FloatArray
    ) {
        setProgressAsync(
            Data.Builder()
                .putInt(PROGRESS_DONE, done)
                .putInt(PROGRESS_TOTAL, total)
                .putString(PROGRESS_PLAYLIST_TITLE, playlistTitle)
                .putString(PROGRESS_CURRENT_ID, currentId ?: "")
                .putString(PROGRESS_LAST_ID, currentId ?: "")
                .putStringArray(PROGRESS_ACTIVE_IDS, activeTrackIds)
                .putFloatArray(PROGRESS_ACTIVE_FRACTIONS, activeFractions)
                .putInt(PROGRESS_DOWNLOADED, downloaded)
                .build()
        )
    }

    private fun processSingleTrackDownload(
        context: Context,
        userInitiated: Boolean,
        trackIndex: Int,
        ids: Array<String?>,
        titles: Array<String?>?,
        artists: Array<String?>?,
        durations: Array<String?>?,
        activeTrackIds: MutableSet<String>,
        activeTrackProgressFractions: MutableMap<String, Float>
    ): TrackDownloadResult {
        val rawId = ids[trackIndex]
        val id = rawId?.trim().orEmpty()
        val title = safeArrayValue(titles, trackIndex)
        val artist = safeArrayValue(artists, trackIndex)
        val expectedDurationLabel = safeArrayValue(durations, trackIndex)

        if (TextUtils.isEmpty(id)) return TrackDownloadResult.failed(id, title)

        // Local device files are already on disk — skip download
        if (LocalFilesStore.isLocalVideoId(id)) {
            return TrackDownloadResult.downloaded(id, title, false)
        }

        var downloaded = OfflineAudioStore.hasValidatedOfflineAudio(context, id, expectedDurationLabel)
        var noNetworkEncountered = false
        var addedToActive = false
        val networkIssueTracker = NetworkIssueTracker()

        if (downloaded) {
            if (!DownloadLocationStore.publish(context, id, title, artist)) storageFailure = true
            return TrackDownloadResult.downloaded(id, title, false)
        }

        try {
            addedToActive = activeTrackIds.add(id)
            activeTrackProgressFractions[id] = 0f

            val progressReporter = ProgressReporter { progressFraction ->
                val safeProgress = maxOf(0f, minOf(1f, progressFraction))
                activeTrackProgressFractions[id] = safeProgress
            }

            if (!isDownloadAllowedByCurrentNetworkPolicy(context, networkIssueTracker)) {
                noNetworkEncountered = true
                Log.w(TAG, "track:blocked_by_policy id=$id")
                OfflineAudioStore.markOfflineAudioState(id, false)
                return TrackDownloadResult.failed(id, title, true)
            }

            downloaded = downloadTrack(context, id, networkIssueTracker, progressReporter)
            noNetworkEncountered = noNetworkEncountered || networkIssueTracker.hasIssue()

            if (!downloaded) {
                Log.w(TAG, "track:failed id=$id network=$noNetworkEncountered")
                OfflineAudioStore.markOfflineAudioState(id, false)
                return TrackDownloadResult.failed(id, title, noNetworkEncountered)
            }

            val validation = OfflineAudioStore.validateOfflineAudio(context, id, expectedDurationLabel)
            if (validation == AudioValidationPolicy.Result.UNREADABLE) {
                Log.w(TAG, "track:temporarily_unreadable id=$id; keeping original file")
                return TrackDownloadResult.failed(id, title, noNetworkEncountered)
            }
            if (validation == AudioValidationPolicy.Result.INVALID) {
                Log.w(TAG, "track:validation_failed id=$id expectedDuration='$expectedDurationLabel' retrying once")
                // Validation already removed confirmed corrupt data. Retry the download once.
                progressReporter.onProgress(0.05f)
                val retryOk = downloadTrack(context, id, networkIssueTracker, progressReporter)
                noNetworkEncountered = noNetworkEncountered || networkIssueTracker.hasIssue()
                if (!retryOk || !OfflineAudioStore.hasValidatedOfflineAudio(context, id, expectedDurationLabel)) {
                    Log.w(TAG, "track:retry_validation_failed id=$id")
                    OfflineAudioStore.markOfflineAudioState(id, false)
                    return TrackDownloadResult.failed(id, title, noNetworkEncountered)
                }
            }

            if (!DownloadLocationStore.publish(context, id, title, artist)) storageFailure = true
            progressReporter.onProgress(1f)
            OfflineAudioStore.markOfflineAudioState(id, true)
            // Notify UI that a track was downloaded (so playlist offline state can be recalculated)
            PlaybackEventBus.notifyPlaybackSnapshotUpdated()
            return TrackDownloadResult.downloaded(id, title, noNetworkEncountered)
        } finally {
            if (addedToActive) activeTrackIds.remove(id)
            activeTrackProgressFractions.remove(id)
            // Brief gap between CDN fetches so back-to-back NewPipe resolves don't hammer YouTube.
            try { Thread.sleep(150L) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        }
    }

    private fun snapshotActiveProgress(
        activeTrackIds: MutableSet<String>,
        activeTrackProgressFractions: MutableMap<String, Float>
    ): ActiveProgressSnapshot {
        synchronized(activeTrackIds) {
            synchronized(activeTrackProgressFractions) {
                val ids = activeTrackIds.toTypedArray()
                val fractions = FloatArray(ids.size)
                for (i in ids.indices) {
                    val value = activeTrackProgressFractions[ids[i]]
                    fractions[i] = if (value == null) 0f else maxOf(0f, minOf(1f, value))
                }
                return ActiveProgressSnapshot(ids, fractions)
            }
        }
    }

    private fun normalizePlaylistTitle(playlistTitle: String?, playlistId: String?): String {
        val resolvedTitle = playlistTitle?.trim().orEmpty()
        if (resolvedTitle.isNotEmpty()) return resolvedTitle
        val resolvedId = playlistId?.trim().orEmpty()
        if (resolvedId.isNotEmpty()) return resolvedId
        return "Playlist"
    }

    /**
     * Mirror of MusicPlayerFragment's persistPlaylistOfflineComplete/Fraction: writes the
     * playlist-level offline-complete flag and download fraction into the streaming-cache prefs
     * using the exact same keys MusicPlayerFragment reads at cold start. Keeping the keys/prefs
     * file identical is what lets the home ring + check icon be correct before the async disk
     * scan runs.
     */
    private fun persistPlaylistOfflineState(
        context: Context,
        playlistId: String,
        complete: Boolean,
        fraction: Float
    ) {
        if (playlistId.isEmpty()) return
        context.getSharedPreferences(AppConstants.PREFS_STREAMING_CACHE, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(PREF_PLAYLIST_OFFLINE_COMPLETE_PREFIX + playlistId, complete)
            .putFloat(PREF_PLAYLIST_OFFLINE_FRACTION_PREFIX + playlistId, maxOf(0f, minOf(1f, fraction)))
            .apply()
    }

    private fun isDownloadAllowedByCurrentNetworkPolicy(
        context: Context,
        networkIssueTracker: NetworkIssueTracker
    ): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false

        val hasInternet = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        if (!hasInternet) {
            networkIssueTracker.markIssue()
            return false
        }

        val prefs: SharedPreferences = context.getSharedPreferences(CloudSyncManager.PREFS_SETTINGS, Context.MODE_PRIVATE)
        val allowMobileData = prefs.getBoolean(CloudSyncManager.KEY_OFFLINE_DOWNLOAD_ALLOW_MOBILE_DATA, false)
        if (allowMobileData) return true

        val usesCellular = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
        val usesWifi = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        val usesEthernet = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        val isMetered = connectivityManager.isActiveNetworkMetered

        if ((usesCellular && !usesWifi && !usesEthernet) || (isMetered && !usesWifi && !usesEthernet)) {
            networkIssueTracker.markIssue()
            return false
        }
        return true
    }

    private fun safeArrayValue(values: Array<String?>?, index: Int): String {
        if (values == null || index < 0 || index >= values.size) return ""
        return values[index]?.trim().orEmpty()
    }

    private fun downloadTrack(
        context: Context,
        videoId: String,
        networkIssueTracker: NetworkIssueTracker,
        progressReporter: ProgressReporter?
    ): Boolean {
        if (isStopped || networkIssueTracker.hasIssue()) return false
        progressReporter?.onProgress(0.05f)

        val ok = try {
            // Direct googlevideo-CDN audio download (NewPipe-resolved URL). The resolver reports a
            // real 0..1 fraction (from Content-Length) and owns the offline file path/extension.
            SleppifyDownloaderResolver.downloadTrackAudio(context, videoId, onProgress = { fraction ->
                progressReporter?.onProgress(fraction)
            })
        } catch (e: Exception) {
            if (isLikelyNetworkException(e)) networkIssueTracker.markIssue()
            Log.w(TAG, "cdn:exception id=$videoId msg=${e.message}")
            false
        }

        if (ok) {
            progressReporter?.onProgress(0.99f)
            return true
        }

        return false
    }

    private fun isLikelyNetworkException(throwable: Throwable?): Boolean {
        var cursor: Throwable? = throwable
        while (cursor != null) {
            if (cursor is UnknownHostException ||
                cursor is SocketTimeoutException ||
                cursor is ConnectException ||
                cursor is SocketException ||
                cursor is InterruptedIOException ||
                cursor is SSLException
            ) return true

            val message = cursor.message
            if (message != null) {
                val normalized = message.lowercase(Locale.US)
                if (normalized.contains("unable to resolve host") ||
                    normalized.contains("no address associated with hostname") ||
                    normalized.contains("failed to connect") ||
                    normalized.contains("connection timed out") ||
                    normalized.contains("network is unreachable")
                ) return true
            }

            cursor = cursor.cause
        }
        return false
    }

    private fun interface ProgressReporter {
        fun onProgress(progressFraction: Float)
    }

    private class ActiveProgressSnapshot(
        val trackIds: Array<String>,
        val fractions: FloatArray
    )

    private class TrackDownloadResult private constructor(
        val trackId: String,
        val trackTitle: String,
        val downloaded: Boolean,
        val noNetworkEncountered: Boolean
    ) {
        companion object {
            fun downloaded(trackId: String?, trackTitle: String?, noNetworkEncountered: Boolean): TrackDownloadResult =
                TrackDownloadResult(trackId.orEmpty(), trackTitle.orEmpty(), true, noNetworkEncountered)

            fun failed(trackId: String?, trackTitle: String?, noNetworkEncountered: Boolean = false): TrackDownloadResult =
                TrackDownloadResult(trackId.orEmpty(), trackTitle.orEmpty(), false, noNetworkEncountered)
        }
    }

    private class NetworkIssueTracker {
        @Volatile
        private var issue = false

        fun markIssue() { issue = true }
        fun hasIssue(): Boolean = issue
    }

    companion object {
        private const val TAG = "OfflineDlWorker"

        // Must stay identical to MusicPlayerFragment's PREF_PLAYLIST_OFFLINE_COMPLETE_PREFIX /
        // PREF_PLAYLIST_OFFLINE_FRACTION_PREFIX (same streaming-cache prefs file) so the values
        // persisted here are read back as the cold-start fallback for the library ring/check.
        private const val PREF_PLAYLIST_OFFLINE_COMPLETE_PREFIX = "playlist_offline_complete_"
        private const val PREF_PLAYLIST_OFFLINE_FRACTION_PREFIX = "playlist_offline_fraction_"

        @JvmField val INPUT_PLAYLIST_ID = "input_playlist_id"
        @JvmField val INPUT_MANIFEST = "input_manifest"
        @JvmField val INPUT_VIDEO_IDS = "input_video_ids"
        @JvmField val INPUT_TITLES = "input_titles"
        @JvmField val INPUT_ARTISTS = "input_artists"
        @JvmField val INPUT_DURATIONS = "input_durations"
        @JvmField val INPUT_ALREADY_OFFLINE_COUNT = "input_already_offline_count"
        @JvmField val INPUT_TOTAL_WITH_VIDEO_ID = "input_total_with_video_id"
        @JvmField val INPUT_PLAYLIST_TITLE = "input_playlist_title"
        @JvmField val INPUT_USER_INITIATED = "input_user_initiated"
        @JvmField val INPUT_MANUAL_QUEUE = "input_manual_queue"

        @JvmField val PROGRESS_DONE = "progress_done"
        @JvmField val PROGRESS_TOTAL = "progress_total"
        @JvmField val PROGRESS_LAST_ID = "progress_last_id"
        @JvmField val PROGRESS_CURRENT_ID = "progress_current_id"
        @JvmField val PROGRESS_ACTIVE_IDS = "progress_active_ids"
        @JvmField val PROGRESS_ACTIVE_FRACTIONS = "progress_active_fractions"
        @JvmField val PROGRESS_DOWNLOADED = "progress_downloaded"
        @JvmField val PROGRESS_PLAYLIST_TITLE = "progress_playlist_title"

        @JvmField val OUTPUT_DOWNLOADED = "output_downloaded"
        @JvmField val OUTPUT_TOTAL = "output_total"
        @JvmField val OUTPUT_REASON = "output_reason"
        @JvmField val OUTPUT_REASON_NONE = "none"
        @JvmField val OUTPUT_REASON_NO_NETWORK = "no_network"
        @JvmField val OUTPUT_REASON_NO_MATCH = "no_match"
        @JvmField val OUTPUT_REASON_PARTIAL = "partial"
        @JvmField val OUTPUT_REASON_STORAGE = "storage"

        private const val MAX_PARALLEL_DOWNLOADS_AUTO = 3
        private const val MAX_PARALLEL_DOWNLOADS_MANUAL = 3
        // Manual (single-track) jobs share one unique APPEND chain; bound their retries so a
        // stuck head job can't block every appended download behind it (head-of-line blocking).
        private const val MANUAL_QUEUE_MAX_RUN_ATTEMPTS = 2
    }
}
