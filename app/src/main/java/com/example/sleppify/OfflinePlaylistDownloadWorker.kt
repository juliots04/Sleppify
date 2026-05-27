package com.example.sleppify

import android.content.Context
import android.content.SharedPreferences
import android.media.MediaExtractor
import android.media.MediaMetadataRetriever
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.text.TextUtils
import android.util.Log
import androidx.work.Data
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.io.File
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

    override fun doWork(): Result {
        val appContext = applicationContext
        val ids = inputData.getStringArray(INPUT_VIDEO_IDS)
        if (ids == null || ids.isEmpty()) {
            return Result.failure(
                Data.Builder()
                    .putInt(OUTPUT_TOTAL, 0)
                    .putInt(OUTPUT_DOWNLOADED, 0)
                    .putString(OUTPUT_REASON, OUTPUT_REASON_NONE)
                    .build()
            )
        }

        val playlistTitle = normalizePlaylistTitle(
            inputData.getString(INPUT_PLAYLIST_TITLE),
            inputData.getString(INPUT_PLAYLIST_ID)
        )
        val titles = inputData.getStringArray(INPUT_TITLES)
        val artists = inputData.getStringArray(INPUT_ARTISTS)
        val durations = inputData.getStringArray(INPUT_DURATIONS)

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

        for (i in ids.indices) {
            if (isStopped) {
                executor.shutdownNow()
                Log.w(TAG, "doWork: worker stopped before enqueue, retrying")
                return Result.retry()
            }

            val trackIndex = i
            val serverIndex = trackIndex % SleppifyDownloaderResolver.SERVER_COUNT
            completionService.submit {
                processSingleTrackDownload(
                    appContext, userInitiated, trackIndex, serverIndex, ids, titles, artists, durations,
                    activeTrackIds, activeTrackProgressFractions
                )
            }
            submittedTasks++
        }

        var consecutiveFailures = 0

        for (i in 0 until submittedTasks) {
            if (isStopped) {
                executor.shutdownNow()
                Log.w(TAG, "doWork: worker stopped, retrying")
                return Result.retry()
            }

            var trackResult: TrackDownloadResult? = null
            while (trackResult == null) {
                if (isStopped) {
                    executor.shutdownNow()
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
                consecutiveFailures = 0
            } else if (!trackResult.noNetworkEncountered) {
                consecutiveFailures++
                if (consecutiveFailures >= CONSECUTIVE_FAIL_ABORT_THRESHOLD) {
                    Log.w(TAG, "doWork:batch_abort consecutiveFailures=$consecutiveFailures")
                    executor.shutdownNow()
                    encounteredNoNetwork = true
                    break
                }
            }

            done = minOf(total, done + 1)
            val activeSnapshot = snapshotActiveProgress(activeTrackIds, activeTrackProgressFractions)
            val currentId = if (activeSnapshot.trackIds.isNotEmpty()) activeSnapshot.trackIds[0] else trackResult.trackId
            publishProgress(
                playlistTitle, done, total, downloaded, currentId,
                activeSnapshot.trackIds, activeSnapshot.fractions
            )
        }

        executor.shutdownNow()

        val reason = when {
            encounteredNoNetwork -> OUTPUT_REASON_NO_NETWORK
            downloaded <= 0 -> OUTPUT_REASON_NO_MATCH
            else -> OUTPUT_REASON_NONE
        }

        if (OUTPUT_REASON_NO_NETWORK == reason) {
            Log.w(TAG, "doWork:network_unavailable retrying")
            return Result.retry()
        }

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
        serverIndex: Int,
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

            downloaded = downloadTrack(context, id, serverIndex, networkIssueTracker, progressReporter)
            noNetworkEncountered = noNetworkEncountered || networkIssueTracker.hasIssue()

            if (!downloaded) {
                Log.w(TAG, "track:failed id=$id network=$noNetworkEncountered")
                OfflineAudioStore.markOfflineAudioState(id, false)
                return TrackDownloadResult.failed(id, title, noNetworkEncountered)
            }

            if (!validateDownloadedTrackFile(context, id, expectedDurationLabel)) {
                Log.w(TAG, "track:validation_failed id=$id expectedDuration='$expectedDurationLabel' retrying once")
                // Retry once: delete and redownload
                OfflineAudioStore.deleteOfflineAudio(context, id)
                progressReporter.onProgress(0.05f)
                val retryOk = downloadTrack(context, id, serverIndex, networkIssueTracker, progressReporter)
                noNetworkEncountered = noNetworkEncountered || networkIssueTracker.hasIssue()
                if (!retryOk || !validateDownloadedTrackFile(context, id, expectedDurationLabel)) {
                    Log.w(TAG, "track:retry_validation_failed id=$id")
                    OfflineAudioStore.markOfflineAudioState(id, false)
                    return TrackDownloadResult.failed(id, title, noNetworkEncountered)
                }
            }

            progressReporter.onProgress(1f)
            OfflineAudioStore.markOfflineAudioState(id, true)
            // Notify UI that a track was downloaded (so playlist offline state can be recalculated)
            PlaybackEventBus.notifyPlaybackSnapshotUpdated()
            return TrackDownloadResult.downloaded(id, title, noNetworkEncountered)
        } finally {
            if (addedToActive) activeTrackIds.remove(id)
            activeTrackProgressFractions.remove(id)
            // Throttle between downloads to avoid proxy saturation
            try { Thread.sleep(710L) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        }
    }

    private fun snapshotActiveProgress(
        activeTrackIds: MutableSet<String>,
        activeTrackProgressFractions: MutableMap<String, Float>
    ): ActiveProgressSnapshot {
        synchronized(activeTrackIds) {
            val ids = activeTrackIds.toTypedArray()
            val fractions = FloatArray(ids.size)
            for (i in ids.indices) {
                val value = activeTrackProgressFractions[ids[i]]
                fractions[i] = if (value == null) 0f else maxOf(0f, minOf(1f, value))
            }
            return ActiveProgressSnapshot(ids, fractions)
        }
    }

    private fun normalizePlaylistTitle(playlistTitle: String?, playlistId: String?): String {
        val resolvedTitle = playlistTitle?.trim().orEmpty()
        if (resolvedTitle.isNotEmpty()) return resolvedTitle
        val resolvedId = playlistId?.trim().orEmpty()
        if (resolvedId.isNotEmpty()) return resolvedId
        return "Playlist"
    }

    private fun isDownloadAllowedByCurrentNetworkPolicy(
        context: Context,
        networkIssueTracker: NetworkIssueTracker
    ): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false

        val internetReady = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        if (!internetReady) return false

        val prefs: SharedPreferences = context.getSharedPreferences(CloudSyncManager.PREFS_SETTINGS, Context.MODE_PRIVATE)
        val allowMobileData = prefs.getBoolean(CloudSyncManager.KEY_OFFLINE_DOWNLOAD_ALLOW_MOBILE_DATA, false)
        if (allowMobileData) return true

        val usesCellular = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
        val usesWifi = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        val usesEthernet = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)

        if (usesCellular && !usesWifi && !usesEthernet) {
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
        primaryServer: Int,
        networkIssueTracker: NetworkIssueTracker,
        progressReporter: ProgressReporter?
    ): Boolean {
        if (isStopped || networkIssueTracker.hasIssue()) return false
        progressReporter?.onProgress(0.05f)

        val videoTarget = OfflineAudioStore.getOfflineVideoFile(context, videoId)

        val ok = try {
            SleppifyDownloaderResolver.downloadVideoViaProxy(videoId, videoTarget, primaryServer, onProgress = { bytesReceived ->
                val fraction = (bytesReceived.toFloat() / 12_000_000L).coerceIn(0.05f, 0.90f)
                progressReporter?.onProgress(fraction)
            })
        } catch (e: Exception) {
            if (isLikelyNetworkException(e)) networkIssueTracker.markIssue()
            Log.w(TAG, "proxy:exception id=$videoId s=$primaryServer msg=${e.message}")
            false
        }

        if (ok) {
            progressReporter?.onProgress(0.99f)
            return true
        }

        if (videoTarget.isFile) videoTarget.delete()
        return false
    }

    private fun validateDownloadedTrackFile(
        context: Context,
        videoId: String,
        expectedDurationLabel: String?
    ): Boolean {
        val audioFile = OfflineAudioStore.getExistingOfflineAudioFile(context, videoId)
        if (!audioFile.isFile) return false

        val sizeBytes = maxOf(0L, audioFile.length())
        if (sizeBytes < MIN_VALID_AUDIO_FILE_BYTES) {
            OfflineAudioStore.deleteOfflineAudio(context, videoId)
            return false
        }

        val actualDurationSeconds = readAudioDurationSeconds(audioFile)
        if (actualDurationSeconds < DOWNLOAD_DURATION_MIN_SAFE_SECONDS) {
            OfflineAudioStore.deleteOfflineAudio(context, videoId)
            return false
        }

        val channelCount = readAudioChannelCount(audioFile)
        if (channelCount in 1 until MIN_STEREO_CHANNELS) {
            Log.w(TAG, "track:non_stereo_rejected id=$videoId channels=$channelCount")
            OfflineAudioStore.deleteOfflineAudio(context, videoId)
            return false
        }

        // Playability check: verify file can be read by MediaExtractor (detects corrupt/incomplete)
        if (!isFilePlayable(audioFile)) {
            Log.w(TAG, "track:not_playable id=$videoId")
            OfflineAudioStore.deleteOfflineAudio(context, videoId)
            return false
        }

        val expectedDurationSeconds = parseDurationSeconds(expectedDurationLabel)
        if (expectedDurationSeconds <= 0) return true

        val minimumAllowedDuration = maxOf(
            DOWNLOAD_DURATION_MIN_SAFE_SECONDS,
            Math.round(expectedDurationSeconds * DOWNLOAD_DURATION_MATCH_RATIO) - DOWNLOAD_DURATION_LEEWAY_SECONDS
        )

        if (actualDurationSeconds >= minimumAllowedDuration) return true

        Log.w(
            TAG,
            "track:duration_mismatch id=$videoId expectedSec=$expectedDurationSeconds" +
                    " actualSec=$actualDurationSeconds minAllowedSec=$minimumAllowedDuration"
        )
        OfflineAudioStore.deleteOfflineAudio(context, videoId)
        return false
    }

    private fun readAudioDurationSeconds(file: File): Int {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            val durationMsValue = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            if (TextUtils.isEmpty(durationMsValue)) return -1
            val durationMs = durationMsValue!!.trim().toLong()
            if (durationMs <= 0L) return -1
            return maxOf(1L, durationMs / 1000L).toInt()
        } catch (_: Exception) {
            return -1
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun readAudioChannelCount(file: File): Int {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            val trackCount = extractor.trackCount
            for (i in 0 until trackCount) {
                val format = extractor.getTrackFormat(i)
                if (!format.containsKey(android.media.MediaFormat.KEY_MIME)) continue
                val mime = format.getString(android.media.MediaFormat.KEY_MIME)
                if (TextUtils.isEmpty(mime) || mime?.startsWith("audio/") != true) continue
                if (format.containsKey(android.media.MediaFormat.KEY_CHANNEL_COUNT)) {
                    val channels = format.getInteger(android.media.MediaFormat.KEY_CHANNEL_COUNT)
                    if (channels > 0) return channels
                }
            }
            return -1
        } catch (_: Exception) {
            return -1
        } finally {
            runCatching { extractor.release() }
        }
    }

    private fun isFilePlayable(file: File): Boolean {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            if (extractor.trackCount <= 0) return false
            // Verify at least one audio or video track exists
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(android.media.MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/") || mime.startsWith("video/")) return true
            }
            return false
        } catch (_: Exception) {
            return false
        } finally {
            runCatching { extractor.release() }
        }
    }

    private fun parseDurationSeconds(durationLabel: String?): Int {
        if (durationLabel.isNullOrEmpty()) return -1

        val parts = durationLabel.trim().split(":")
        return try {
            when (parts.size) {
                2 -> parts[0].toInt() * 60 + parts[1].toInt()
                3 -> parts[0].toInt() * 3600 + parts[1].toInt() * 60 + parts[2].toInt()
                else -> -1
            }
        } catch (_: Exception) {
            -1
        }
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

        @JvmField val INPUT_PLAYLIST_ID = "input_playlist_id"
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

        private const val CONSECUTIVE_FAIL_ABORT_THRESHOLD = 8
        private const val MAX_PARALLEL_DOWNLOADS_AUTO = 3
        private const val MAX_PARALLEL_DOWNLOADS_MANUAL = 3
        private const val MIN_VALID_AUDIO_FILE_BYTES = 24L * 1024L
        private const val DOWNLOAD_DURATION_MIN_SAFE_SECONDS = 6
        private const val DOWNLOAD_DURATION_MATCH_RATIO = 0.88f
        private const val DOWNLOAD_DURATION_LEEWAY_SECONDS = 8
        private const val MIN_STEREO_CHANNELS = 2
    }
}
