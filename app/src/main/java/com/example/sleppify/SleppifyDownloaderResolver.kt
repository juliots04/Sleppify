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

        val body = JSONObject()
            .apply { put("url", "https://www.youtube.com/watch?v=$videoId") }
            .toString()
            .toByteArray(StandardCharsets.UTF_8)

        val existingBytes = if (targetFile.isFile) targetFile.length() else 0L
        val isResume = existingBytes >= MIN_VALID_VIDEO_BYTES / 2

        val startMs = System.currentTimeMillis()

        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = VIDEO_READ_TIMEOUT_MS
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", "video/mp4, */*")
                setRequestProperty("User-Agent", "Sleppify-Android/1.0")
                if (isResume) {
                    setRequestProperty("Range", "bytes=$existingBytes-")
                }
                outputStream.use { it.write(body) }
            }

            val code = connection.responseCode
            val resuming = isResume && code == HttpURLConnection.HTTP_PARTIAL
            val freshStart = code == HttpURLConnection.HTTP_OK

            if (!resuming && !freshStart) {
                val errBody = try { connection.errorStream?.bufferedReader()?.readText()?.take(300) } catch (_: Exception) { null }
                Log.w(TAG, "video_proxy_fail id=$videoId $serverLabel http=$code elapsed=${System.currentTimeMillis() - startMs}ms err=$errBody")
                return false
            }

            targetFile.parentFile?.let { if (!it.exists()) it.mkdirs() }

            var totalBytes = if (resuming) existingBytes else 0L
            if (!resuming && targetFile.isFile) {
                targetFile.delete()
            }

            val appendMode = resuming
            connection.inputStream.use { input ->
                FileOutputStream(targetFile, appendMode).use { output ->
                    val buf = ByteArray(16384)
                    var n: Int
                    while (input.read(buf).also { n = it } != -1) {
                        output.write(buf, 0, n)
                        totalBytes += n
                        onProgress?.invoke(totalBytes)
                    }
                }
            }

            val elapsed = System.currentTimeMillis() - startMs
            if (totalBytes < MIN_VALID_VIDEO_BYTES) {
                Log.w(TAG, "video_proxy_fail id=$videoId $serverLabel reason=too_small bytes=$totalBytes elapsed=${elapsed}ms")
                targetFile.delete()
                return false
            }

            Log.d(TAG, "video_proxy_ok id=$videoId $serverLabel bytes=$totalBytes elapsed=${elapsed}ms")
            true
        } catch (e: Exception) {
            Log.w(TAG, "video_proxy_exception id=$videoId $serverLabel reason=${e.javaClass.simpleName} msg=${e.message} elapsed=${System.currentTimeMillis() - startMs}ms")
            false
        } finally {
            connection?.disconnect()
        }
    }

}
