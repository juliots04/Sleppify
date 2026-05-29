package com.example.sleppify

import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

/**
 * Downloads video via three parallel Sleppify proxy servers.
 *
 * Server 0: sleppifydownloader.alwaysdata.net
 * Server 1: sleppifydownload2.alwaysdata.net
 * Server 2: sleppifydownloader2.alwaysdata.net
 *
 * Supports resumable downloads via HTTP Range header — partial files
 * are continued from where they left off instead of restarting.
 */
object SleppifyDownloaderResolver {

    private const val TAG = "SleppifyDL"

    private val VIDEO_ENDPOINTS = arrayOf(
        "https://sleppifydownload.alwaysdata.net/api/video",
        "https://sleppifydownload2.alwaysdata.net/api/video",
        "https://sleppifydownloader2.alwaysdata.net/api/video"
    )

    const val SERVER_COUNT = 3

    private const val CONNECT_TIMEOUT_MS = 10000
    private const val VIDEO_READ_TIMEOUT_MS = 120000
    private const val MIN_VALID_VIDEO_BYTES = 500_000L

    /**
     * Downloads 720p mp4 video (fallback 360p) for [videoId] via server [serverIndex] into [targetFile].
     * Supports resumable downloads via HTTP Range header.
     * Returns true on success, false on any failure.
     */
    fun downloadVideoViaProxy(
        videoId: String,
        targetFile: File,
        serverIndex: Int = 0,
        onProgress: ((Long) -> Unit)? = null
    ): Boolean {
        if (videoId.isBlank()) return false
        val endpoint = VIDEO_ENDPOINTS[serverIndex.coerceIn(0, VIDEO_ENDPOINTS.size - 1)]
        val serverLabel = "vs$serverIndex"
        val urlString = endpoint.replace("/api/video", "/api/stream/$videoId")

        val tempFile = File(targetFile.absolutePath + ".tmp")
        val existingBytes = if (tempFile.isFile) tempFile.length() else 0L
        val isResume = existingBytes >= MIN_VALID_VIDEO_BYTES / 2

        val startMs = System.currentTimeMillis()

        var totalBytes = if (isResume) existingBytes else 0L
        if (!isResume && tempFile.isFile) {
            tempFile.delete()
        }

        var retryCount = 0
        val MAX_RETRIES = 5
        var success = false
        var lastException: Exception? = null

        while (retryCount <= MAX_RETRIES && !success) {
            var connection: HttpURLConnection? = null
            try {
                val isAppend = totalBytes > 0
                connection = (URL(urlString).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = CONNECT_TIMEOUT_MS
                    // Use a shorter read timeout since we will retry on timeout
                    readTimeout = 30000
                    doOutput = false
                    setRequestProperty("Accept", "video/mp4, */*")
                    setRequestProperty("User-Agent", "Sleppify-Android/1.0")
                    if (isAppend) {
                        setRequestProperty("Range", "bytes=$totalBytes-")
                    }
                }

                val code = connection.responseCode
                if (code == 416) {
                    // Range Not Satisfiable -> we already downloaded the whole file!
                    success = true
                    break
                }
                
                val resumingNow = isAppend && code == HttpURLConnection.HTTP_PARTIAL
                val freshStart = code == HttpURLConnection.HTTP_OK

                if (!resumingNow && !freshStart) {
                    val errBody = try { connection.errorStream?.bufferedReader()?.readText()?.take(300) } catch (_: Exception) { null }
                    Log.w(TAG, "video_proxy_fail id=$videoId $serverLabel http=$code elapsed=${System.currentTimeMillis() - startMs}ms err=$errBody")
                    return false
                }

                tempFile.parentFile?.let { if (!it.exists()) it.mkdirs() }

                connection.inputStream.use { input ->
                    FileOutputStream(tempFile, isAppend).use { output ->
                        val buf = ByteArray(16384)
                        var n: Int
                        while (input.read(buf).also { n = it } != -1) {
                            output.write(buf, 0, n)
                            totalBytes += n
                            onProgress?.invoke(totalBytes)
                        }
                    }
                }
                
                // If it reached here without exception, stream finished successfully.
                success = true
            } catch (e: Exception) {
                lastException = e
                retryCount++
                Log.w(TAG, "video_proxy_exception id=$videoId $serverLabel attempt=$retryCount reason=${e.javaClass.simpleName} msg=${e.message}")
                if (retryCount <= MAX_RETRIES) {
                    try { Thread.sleep(2000) } catch (ie: InterruptedException) { Thread.currentThread().interrupt(); break }
                }
            } finally {
                connection?.disconnect()
            }
        }

        val elapsed = System.currentTimeMillis() - startMs
        if (!success) {
            Log.w(TAG, "video_proxy_fail id=$videoId $serverLabel failed_after_retries last_err=${lastException?.javaClass?.simpleName} bytes=$totalBytes elapsed=${elapsed}ms")
            return false
        }

        if (totalBytes < MIN_VALID_VIDEO_BYTES) {
            Log.w(TAG, "video_proxy_fail id=$videoId $serverLabel reason=too_small bytes=$totalBytes elapsed=${elapsed}ms")
            tempFile.delete()
            return false
        }

        var isPlayable = false
        try {
            val extractor = android.media.MediaExtractor()
            extractor.setDataSource(tempFile.absolutePath)
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(android.media.MediaFormat.KEY_MIME)
                if (mime != null && (mime.startsWith("audio/") || mime.startsWith("video/"))) {
                    isPlayable = true
                    break
                }
            }
            extractor.release()
        } catch (e: Exception) {
            // Not playable or corrupt
        }

        if (!isPlayable) {
            Log.w(TAG, "video_proxy_fail id=$videoId reason=corrupt_or_not_playable")
            tempFile.delete()
            return false
        }

        if (targetFile.exists()) {
            targetFile.delete()
        }
        val renamed = tempFile.renameTo(targetFile)
        if (!renamed) {
            Log.w(TAG, "video_proxy_fail id=$videoId reason=rename_failed")
            return false
        }

        Log.d(TAG, "video_proxy_ok id=$videoId $serverLabel bytes=$totalBytes elapsed=${elapsed}ms")
        return true
    }

}
