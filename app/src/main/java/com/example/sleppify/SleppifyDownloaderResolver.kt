package com.example.sleppify

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock

/**
 * Downloads a track for offline use from the single Sleppify proxy host
 * ([AppConstants.PROXY_BASE_URL]) via its /api/descarga endpoint: the server fetches the track
 * server-side (yt-dlp) and streams the finished mp4 back with a Content-Length.
 */
object SleppifyDownloaderResolver {

    private const val TAG = "SleppifyDL"

    // Single host now (was a 3-server round-robin). One endpoint, retried on transient errors.
    private val DOWNLOAD_ENDPOINT = "${AppConstants.PROXY_BASE_URL}/api/descarga"

    // Kept so existing callers that do `index % SERVER_COUNT` collapse to 0 (single host).
    const val SERVER_COUNT = 1

    private const val MAX_ATTEMPTS = 2

    private const val CONNECT_TIMEOUT_MS = 10000
    private const val VIDEO_READ_TIMEOUT_MS = 120000
    private const val MIN_VALID_VIDEO_BYTES = 24L * 1024L // 24KB to match worker limit

    private val downloadLocks = ConcurrentHashMap<String, ReentrantLock>()

    private fun lockFor(videoId: String): ReentrantLock =
        downloadLocks.computeIfAbsent(videoId) { ReentrantLock() }

    /**
     * Downloads [videoId] for offline use into [targetFile] by asking the single Sleppify proxy host
     * to fetch it server-side (yt-dlp) and stream it back. Retried up to [MAX_ATTEMPTS] times on a
     * transient error / busy (503). [serverIndex] is kept for source compatibility and ignored.
     *
     * Downloads VIDEO mp4 (the server falls back to audio-only for tracks with no real video, so
     * plain songs stay light). Returns true on success, false if every attempt failed.
     */
    fun downloadVideoViaProxy(
        context: Context,
        videoId: String,
        targetFile: File,
        @Suppress("UNUSED_PARAMETER") serverIndex: Int = 0,
        onProgress: ((Float) -> Unit)? = null
    ): Boolean {
        if (videoId.isBlank()) return false

        val lock = lockFor(videoId)
        lock.lock()
        try {
            // Another concurrent task may have just finished this exact file.
            if (targetFile.exists() && targetFile.length() >= MIN_VALID_VIDEO_BYTES) {
                Log.d(TAG, "video_proxy_ok id=$videoId reason=already_downloaded")
                return true
            }

            val tempFile = File(targetFile.absolutePath + ".tmp")
            if (tempFile.isFile) tempFile.delete()

            val cookie = StreamResolver.getAuthCookieHeader()
            val payload = JSONObject()
                .put("url", "https://www.youtube.com/watch?v=$videoId")
                .put("format", "video")
                .toString()
                .toByteArray(StandardCharsets.UTF_8)

            val startMs = System.currentTimeMillis()
            // Single host, retried on transient busy/error (503 "Server busy, retry").
            for (attempt in 0 until MAX_ATTEMPTS) {
                val downloaded = downloadFromServer(
                    DOWNLOAD_ENDPOINT, attempt, videoId, payload, cookie, tempFile, onProgress
                )
                if (downloaded && finalizeDownload(tempFile, targetFile, videoId, attempt, startMs)) {
                    return true
                }
                if (tempFile.isFile) tempFile.delete()
            }

            Log.w(TAG, "video_proxy_fail id=$videoId all_attempts_failed elapsed=${System.currentTimeMillis() - startMs}ms")
            return false
        } finally {
            // Keep the lock instance in the map (do NOT remove): computeIfAbsent + remove cannot
            // provide per-id mutual exclusion. The bounded per-process leak is negligible.
            lock.unlock()
        }
    }

    /** POSTs to the proxy's /api/descarga and streams the result into [tempFile]. Returns true if
     *  a complete, large-enough file was written; false on any HTTP/IO error (caller tries next server). */
    private fun downloadFromServer(
        endpoint: String,
        server: Int,
        videoId: String,
        payload: ByteArray,
        cookie: String,
        tempFile: File,
        onProgress: ((Float) -> Unit)?
    ): Boolean {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = CONNECT_TIMEOUT_MS
                // The server downloads the whole track BEFORE sending the first byte, so this must
                // cover the full server-side fetch time.
                readTimeout = VIDEO_READ_TIMEOUT_MS
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "audio/mp4, video/mp4, */*")
                setRequestProperty("User-Agent", "Sleppify-Android/1.0")
                if (cookie.isNotBlank()) setRequestProperty("X-Youtube-Cookie", cookie)
            }
            connection.outputStream.use { it.write(payload) }

            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                val err = try { connection.errorStream?.bufferedReader()?.readText()?.take(200) } catch (_: Exception) { null }
                Log.w(TAG, "video_proxy_fail id=$videoId server=$server http=$code err=$err")
                return false
            }

            val expected = connection.getHeaderField("Content-Length")?.toLongOrNull() ?: -1L
            tempFile.parentFile?.let { if (!it.exists()) it.mkdirs() }

            var total = 0L
            connection.inputStream.use { input ->
                FileOutputStream(tempFile, false).use { output ->
                    val buf = ByteArray(16384)
                    var n: Int
                    while (input.read(buf).also { n = it } != -1) {
                        output.write(buf, 0, n)
                        total += n
                        if (onProgress != null) {
                            val fraction = if (expected > 0L) {
                                (total.toDouble() / expected).coerceIn(0.02, 0.99).toFloat()
                            } else {
                                (1.0 - kotlin.math.exp(-total / 4_000_000.0)).coerceIn(0.02, 0.95).toFloat()
                            }
                            onProgress.invoke(fraction)
                        }
                    }
                }
            }

            if (expected > 0L && total < expected) {
                Log.w(TAG, "video_proxy_fail id=$videoId server=$server truncated got=$total expected=$expected")
                return false
            }
            if (total < MIN_VALID_VIDEO_BYTES) {
                Log.w(TAG, "video_proxy_fail id=$videoId server=$server too_small bytes=$total")
                return false
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "video_proxy_exception id=$videoId server=$server reason=${e.javaClass.simpleName} msg=${e.message}")
            false
        } finally {
            connection?.disconnect()
        }
    }

    /** Validates the freshly-downloaded temp file is playable, then atomically renames to target. */
    private fun finalizeDownload(
        tempFile: File,
        targetFile: File,
        videoId: String,
        server: Int,
        startMs: Long
    ): Boolean {
        if (!isPlayable(tempFile)) {
            Log.w(TAG, "video_proxy_fail id=$videoId server=$server corrupt_or_not_playable")
            return false
        }
        if (targetFile.exists()) targetFile.delete()
        if (!tempFile.renameTo(targetFile)) {
            Log.w(TAG, "video_proxy_fail id=$videoId reason=rename_failed")
            return false
        }
        Log.d(TAG, "video_proxy_ok id=$videoId server=$server bytes=${targetFile.length()} elapsed=${System.currentTimeMillis() - startMs}ms")
        return true
    }

    private fun isPlayable(file: File): Boolean {
        return try {
            val extractor = android.media.MediaExtractor()
            try {
                extractor.setDataSource(file.absolutePath)
                for (i in 0 until extractor.trackCount) {
                    val mime = extractor.getTrackFormat(i).getString(android.media.MediaFormat.KEY_MIME)
                    if (mime != null && (mime.startsWith("audio/") || mime.startsWith("video/"))) {
                        return true
                    }
                }
                false
            } finally {
                extractor.release()
            }
        } catch (e: Exception) {
            false
        }
    }

}
