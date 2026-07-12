package com.example.sleppify

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock

/**
 * Downloads a track for offline use by pulling its audio **directly from Google's CDN**
 * (googlevideo.com), using the same NewPipe-resolved stream URL the player streams from
 * ([StreamResolver.resolveAudioDownloadSource]).
 *
 * This replaces the old proxy path (`/api/descarga`, server-side yt-dlp): there is no second
 * hop and no server-side fetch-then-forward wait — the device downloads the finished file
 * straight from Google at full CDN speed, which is faster and no longer depends on the proxy
 * host being up. The googlevideo URL is pre-signed (deciphered n-param), so the GET needs no
 * cookies and is not throttled.
 *
 * Files are saved audio-only (.m4a / .webm) with the container chosen from the resolved stream;
 * callers locate the result later via [OfflineAudioStore.getExistingOfflineAudioFile] (which
 * searches every extension), so a plain song downloads light and plays as music offline.
 */
object SleppifyDownloaderResolver {

    private const val TAG = "SleppifyDL"

    // Kept so existing callers that do `index % SERVER_COUNT` collapse to 0 (single logical source).
    const val SERVER_COUNT = 1

    private const val CONNECT_TIMEOUT_MS = 12000
    private const val READ_TIMEOUT_MS = 30000
    private const val MIN_VALID_BYTES = 24L * 1024L // 24KB, matches the worker's floor
    private const val MAX_ATTEMPTS = 2              // re-resolve + re-download on a hard failure
    private const val MAX_STALL_RETRIES = 5         // transient drops → retry the same chunk

    // googlevideo throttles a single sustained stream to ~playback speed. Requesting the file in
    // discrete chunks via its `&range=START-END` URL parameter signals "download" mode and serves
    // each chunk at full CDN speed — the same trick yt-dlp uses. 1MB keeps every chunk comfortably
    // under the throttle threshold (a typical song is only a handful of chunks).
    private const val CHUNK_SIZE = 1L * 1024 * 1024L

    private const val UA =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/131.0.0.0 Mobile Safari/537.36"

    private val downloadLocks = ConcurrentHashMap<String, ReentrantLock>()

    private fun lockFor(videoId: String): ReentrantLock =
        downloadLocks.computeIfAbsent(videoId) { ReentrantLock() }

    /**
     * Downloads [videoId]'s audio for offline use, straight from the googlevideo CDN.
     * The saved file's extension (.m4a / .webm) is chosen from the resolved stream format.
     * Retried up to [MAX_ATTEMPTS] times (each attempt re-resolves a fresh CDN URL).
     * Returns true on success, false if every attempt failed. Call from a background thread.
     */
    @JvmStatic
    @JvmOverloads
    fun downloadTrackAudio(
        context: Context,
        videoId: String,
        onProgress: ((Float) -> Unit)? = null
    ): Boolean {
        if (videoId.isBlank()) return false

        val lock = lockFor(videoId)
        lock.lock()
        try {
            // Another concurrent task may have just finished this exact track (any extension).
            val existing = OfflineAudioStore.getExistingOfflineAudioFile(context, videoId)
            if (existing.isFile && existing.length() >= MIN_VALID_BYTES) {
                Log.d(TAG, "cdn_ok id=$videoId reason=already_downloaded")
                return true
            }
            // A stale/corrupt partial of ANY extension would shadow the fresh file in lookups
            // (getExistingOfflineAudioFile returns the first non-empty match by extension priority).
            if (existing.isFile) OfflineAudioStore.deleteOfflineAudio(context, videoId)

            val startMs = System.currentTimeMillis()
            for (attempt in 0 until MAX_ATTEMPTS) {
                onProgress?.invoke(0.03f)

                val source = StreamResolver.resolveAudioDownloadSource(videoId)
                if (source == null || source.url.isBlank()) {
                    Log.w(TAG, "cdn_fail id=$videoId reason=no_stream attempt=$attempt")
                    continue
                }

                val targetFile = OfflineAudioStore.getOfflineAudioFileForFormat(context, videoId, source.isWebm)
                targetFile.parentFile?.let { if (!it.exists()) it.mkdirs() }
                val tempFile = File(targetFile.absolutePath + ".tmp")
                if (tempFile.isFile) tempFile.delete()

                val downloaded = downloadDirect(source.url, tempFile, onProgress)
                if (downloaded && finalizeDownload(tempFile, targetFile, videoId, startMs)) {
                    return true
                }
                if (tempFile.isFile) tempFile.delete()
            }

            Log.w(TAG, "cdn_fail id=$videoId all_attempts_failed elapsed=${System.currentTimeMillis() - startMs}ms")
            return false
        } finally {
            // Keep the lock instance (computeIfAbsent + remove cannot give per-id mutual exclusion).
            lock.unlock()
        }
    }

    /**
     * Streams [streamUrl] into [tempFile] in [CHUNK_SIZE] pieces, each fetched with googlevideo's
     * `&range=START-END` URL parameter so the CDN serves it at full speed (no streaming throttle).
     * A chunk shorter than requested marks EOF. Returns true only when a complete, large-enough
     * file was written. A transient chunk failure is retried up to [MAX_STALL_RETRIES] times.
     */
    private fun downloadDirect(
        streamUrl: String,
        tempFile: File,
        onProgress: ((Float) -> Unit)?
    ): Boolean {
        var total = -1L
        var written = 0L
        var stalls = 0

        try {
            FileOutputStream(tempFile, false).use { output ->
                loop@ while (true) {
                    val rangeEnd = written + CHUNK_SIZE - 1
                    val chunkUrl = appendRangeParam(streamUrl, written, rangeEnd)
                    var connection: HttpURLConnection? = null
                    try {
                        connection = (URL(chunkUrl).openConnection() as HttpURLConnection).apply {
                            requestMethod = "GET"
                            connectTimeout = CONNECT_TIMEOUT_MS
                            readTimeout = READ_TIMEOUT_MS
                            setRequestProperty("User-Agent", UA)
                        }

                        val code = connection.responseCode
                        if (code != HttpURLConnection.HTTP_OK && code != HttpURLConnection.HTTP_PARTIAL) {
                            Log.w(TAG, "cdn http=$code at offset=$written")
                            return false
                        }
                        if (total < 0L) total = parseTotalLength(connection, written)

                        var chunkRead = 0L
                        connection.inputStream.use { input ->
                            val buf = ByteArray(64 * 1024)
                            var n: Int
                            while (input.read(buf).also { n = it } != -1) {
                                output.write(buf, 0, n)
                                written += n
                                chunkRead += n
                                if (onProgress != null) {
                                    val fraction = if (total > 0L) {
                                        written.toDouble() / total
                                    } else {
                                        // Total unknown (no Content-Range) → smooth approximation.
                                        1.0 - kotlin.math.exp(-written / 4_000_000.0)
                                    }
                                    onProgress(fraction.coerceIn(0.03, 0.99).toFloat())
                                }
                            }
                        }
                        stalls = 0

                        when {
                            total in 1..written -> break@loop        // reached the known total
                            chunkRead == 0L -> break@loop             // server served nothing → EOF
                            chunkRead < CHUNK_SIZE -> break@loop      // short chunk → last piece
                            // else: a full chunk with more to come → request the next range
                        }
                    } catch (e: Exception) {
                        stalls++
                        if (stalls > MAX_STALL_RETRIES) {
                            Log.w(TAG, "cdn stall give_up written=$written total=$total msg=${e.message}")
                            return false
                        }
                        // Re-request the SAME chunk. Truncate anything partially written past `written`
                        // so the retry appends cleanly from the chunk boundary.
                        try {
                            output.flush()
                            output.channel.truncate(written)
                            output.channel.position(written)
                        } catch (_: Exception) { /* best-effort */ }
                    } finally {
                        connection?.disconnect()
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "cdn_exception reason=${e.javaClass.simpleName} msg=${e.message}")
            return false
        }

        if (written < MIN_VALID_BYTES) {
            Log.w(TAG, "cdn too_small bytes=$written")
            return false
        }
        if (total > 0L && written < total) {
            Log.w(TAG, "cdn truncated got=$written expected=$total")
            return false
        }
        return true
    }

    /** Appends googlevideo's `&range=start-end` download-mode parameter (never streaming-throttled). */
    private fun appendRangeParam(url: String, start: Long, end: Long): String {
        val separator = if (url.contains('?')) '&' else '?'
        return "$url${separator}range=$start-$end"
    }

    /**
     * The FULL file size, taken only from Content-Range's `bytes start-end/TOTAL`. With the
     * `&range=` param the plain Content-Length is just this chunk's length, never the total, so it
     * must not be used here (that would make the first full chunk look like the whole file).
     * Returns -1 when unknown — completion then relies on a short chunk marking EOF.
     */
    private fun parseTotalLength(connection: HttpURLConnection, @Suppress("UNUSED_PARAMETER") written: Long): Long {
        return connection.getHeaderField("Content-Range")
            ?.substringAfterLast('/', "")
            ?.trim()
            ?.toLongOrNull()
            ?.takeIf { it > 0L }
            ?: -1L
    }

    /** Validates the freshly-downloaded temp file is playable, then atomically renames to target. */
    private fun finalizeDownload(
        tempFile: File,
        targetFile: File,
        videoId: String,
        startMs: Long
    ): Boolean {
        if (!isPlayable(tempFile)) {
            Log.w(TAG, "cdn_fail id=$videoId corrupt_or_not_playable")
            return false
        }
        if (targetFile.exists()) targetFile.delete()
        if (!tempFile.renameTo(targetFile)) {
            Log.w(TAG, "cdn_fail id=$videoId reason=rename_failed")
            return false
        }
        Log.d(TAG, "cdn_ok id=$videoId bytes=${targetFile.length()} elapsed=${System.currentTimeMillis() - startMs}ms")
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
