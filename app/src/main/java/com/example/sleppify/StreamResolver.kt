package com.example.sleppify

import android.content.Context
import android.util.Log
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.StreamExtractor
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Single source of truth for YouTube stream resolution and auth cookies.
 *
 * Replaces InnertubeResolver and NewPipeHttpDownloader.
 *
 * Resolution order:
 *  1. In-memory cache (3.5h for direct URLs, 4h for proxy)
 *  2. NewPipeExtractor → direct googlevideo.com URL (primary)
 *  3. ProxyStreamResolver → /api/stream/<videoId> (fallback, sends X-Youtube-Cookie)
 *
 * Also manages auth cookies used by CommentsBottomSheet and proxy authentication.
 */
object StreamResolver {

    private const val TAG = "StreamResolver"
    private const val DIRECT_CACHE_TTL_MS = 3 * 60 * 60 * 1000L + 30 * 60 * 1000L // 3.5h
    private const val PROXY_CACHE_TTL_MS = 4 * 60 * 60 * 1000L                    // 4h

    // Preferred audio itags: 141=AAC256k, 140=AAC128k, 251=Opus160k, 250=Opus70k, 249=Opus50k
    private val PREFERRED_ITAGS = intArrayOf(141, 140, 251, 250, 249)

    @Volatile private var authCookieHeader: String = ""
    private val newPipeInitialized = AtomicBoolean(false)

    private enum class SourceType { DIRECT, PROXY }
    private data class CachedStream(val url: String, val type: SourceType, val timestamp: Long)
    private val urlCache = ConcurrentHashMap<String, CachedStream>()

    // ─── Cookie management (used by CommentsBottomSheet, MusicPlayerFragment, etc.) ───

    @JvmStatic
    fun loadAuthCookiesFromPrefs(context: Context) {
        try {
            val prefs = context.getSharedPreferences(AppConstants.PREFS_PLAYER_STATE, Context.MODE_PRIVATE)
            val cookie = prefs.getString("stream_last_youtube_web_cookie", "") ?: ""
            if (cookie.isNotBlank()) {
                authCookieHeader = cookie.trim()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load auth cookies from prefs", e)
        }
    }

    @JvmStatic
    fun setAuthCookies(cookieHeader: String?) {
        authCookieHeader = cookieHeader?.trim() ?: ""
        if (authCookieHeader.isNotBlank()) {
            urlCache.clear()
            ProxyStreamResolver.clearCache()
        }
    }

    @JvmStatic
    fun getAuthCookieHeader(): String = authCookieHeader

    /** Builds a SAPISIDHASH Authorization header for YouTube API calls. */
    @JvmStatic
    fun buildSapisidHash(origin: String = "https://music.youtube.com"): String {
        val sapisid = extractCookieValue("SAPISID")
            ?: extractCookieValue("__Secure-3PAPISID")
            ?: return ""
        val timestamp = System.currentTimeMillis() / 1000
        val input = "$timestamp $sapisid $origin"
        return try {
            val digest = java.security.MessageDigest.getInstance("SHA-1")
            val hash = digest.digest(input.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
            "SAPISIDHASH ${timestamp}_${hash}"
        } catch (e: Exception) {
            ""
        }
    }

    // ─── Stream resolution ────────────────────────────────────────────────

    /**
     * Resolves a playable stream URL for [videoId].
     * Primary: NewPipeExtractor. Fallback: proxy server.
     * Must be called from a background thread (network I/O).
     * [forceAlternativeClient] forces the proxy path, skipping NewPipe.
     */
    @JvmStatic
    @JvmOverloads
    fun resolveStreamUrl(context: Context, videoId: String?, forceAlternativeClient: Boolean = false): String? {
        if (videoId.isNullOrBlank()) return null
        if (videoId.startsWith("local_")) return null

        // 1. Cache check
        urlCache[videoId]?.let { cached ->
            val ttl = if (cached.type == SourceType.DIRECT) DIRECT_CACHE_TTL_MS else PROXY_CACHE_TTL_MS
            if (System.currentTimeMillis() - cached.timestamp < ttl) {
                Log.d(TAG, "cache[$videoId] type=${cached.type}")
                return cached.url
            }
            urlCache.remove(videoId)
        }

        // 2. NewPipe (primary)
        if (!forceAlternativeClient) {
            try {
                val directUrl = resolveViaNewPipe(videoId)
                if (!directUrl.isNullOrBlank()) {
                    urlCache[videoId] = CachedStream(directUrl, SourceType.DIRECT, System.currentTimeMillis())
                    Log.d(TAG, "newpipe[$videoId] ok")
                    return directUrl
                }
            } catch (e: Exception) {
                Log.w(TAG, "newpipe[$videoId] failed: ${e.javaClass.simpleName} — ${e.message}")
            }
        }

        // 3. Proxy fallback
        val proxyUrl = ProxyStreamResolver.resolveStreamUrl(videoId)
        if (!proxyUrl.isNullOrBlank()) {
            urlCache[videoId] = CachedStream(proxyUrl, SourceType.PROXY, System.currentTimeMillis())
            Log.d(TAG, "proxy[$videoId] url=$proxyUrl")
        }
        return proxyUrl
    }

    /**
     * Returns HTTP headers to attach to the ExoPlayer/MediaPlayer data source.
     * - Direct googlevideo.com: browser User-Agent + Origin/Referer
     * - Proxy URL: User-Agent + X-Youtube-Cookie (proxy uses it to auth yt-dlp)
     */
    @JvmStatic
    fun getHeadersFor(videoId: String?): Map<String, String> {
        if (videoId.isNullOrBlank()) return buildProxyHeaders()
        val cached = urlCache[videoId] ?: return buildProxyHeaders()
        return if (cached.type == SourceType.DIRECT && cached.url.contains("googlevideo.com")) {
            mapOf(
                "User-Agent" to "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36",
                "Origin" to "https://music.youtube.com",
                "Referer" to "https://music.youtube.com/"
            )
        } else {
            buildProxyHeaders()
        }
    }

    @JvmStatic
    fun invalidate(videoId: String?) {
        if (videoId.isNullOrBlank()) return
        urlCache.remove(videoId)
        ProxyStreamResolver.invalidate(videoId)
    }

    @JvmStatic
    fun markSuccess(videoId: String?) {
        if (videoId.isNullOrBlank()) return
        val cached = urlCache[videoId] ?: return
        if (cached.type == SourceType.PROXY) ProxyStreamResolver.markSuccess(videoId)
    }

    @JvmStatic
    fun markFailed(videoId: String?) {
        if (videoId.isNullOrBlank()) return
        val cached = urlCache[videoId]
        if (cached?.type == SourceType.PROXY) ProxyStreamResolver.markFailed(videoId)
        urlCache.remove(videoId)
    }

    // ─── NewPipe internals ────────────────────────────────────────────────

    private fun ensureNewPipeInitialized() {
        if (newPipeInitialized.compareAndSet(false, true)) {
            NewPipe.init(NewPipeDownloader)
            Log.d(TAG, "NewPipe initialized")
        }
    }

    private fun resolveViaNewPipe(videoId: String): String? {
        ensureNewPipeInitialized()
        val url = "https://www.youtube.com/watch?v=$videoId"
        val extractor: StreamExtractor = ServiceList.YouTube.getStreamExtractor(url)
        extractor.fetchPage()

        val audioStreams: List<AudioStream> = extractor.audioStreams ?: emptyList()
        if (audioStreams.isEmpty()) {
            Log.w(TAG, "newpipe[$videoId] no audio streams")
            return null
        }

        val itagMap = mutableMapOf<Int, String>()
        for (stream in audioStreams) {
            val streamUrl = stream.content ?: continue
            if (streamUrl.isBlank()) continue
            val itag = stream.itag
            if (itag > 0) itagMap[itag] = streamUrl
        }

        for (preferredItag in PREFERRED_ITAGS) {
            itagMap[preferredItag]?.let { return it }
        }

        return audioStreams.firstOrNull { !it.content.isNullOrBlank() }?.content
    }

    // ─── Helpers ─────────────────────────────────────────────────────────

    private fun buildProxyHeaders(): Map<String, String> {
        val headers = mutableMapOf("User-Agent" to "Sleppify-Android/1.0")
        if (authCookieHeader.isNotBlank()) {
            headers["X-Youtube-Cookie"] = authCookieHeader
        }
        return headers
    }

    private fun extractCookieValue(cookieName: String): String? {
        if (authCookieHeader.isBlank()) return null
        return authCookieHeader.split(";")
            .map { it.trim() }
            .firstOrNull { it.startsWith("$cookieName=") }
            ?.substringAfter("=")
            ?.trim()
    }

    // ─── NewPipe HTTP Downloader (embedded) ──────────────────────────────

    private object NewPipeDownloader : Downloader() {
        override fun execute(request: Request): Response {
            val connection = (URL(request.url()).openConnection() as HttpURLConnection).apply {
                requestMethod = request.httpMethod()
                connectTimeout = 12000
                readTimeout = 15000
                setRequestProperty("User-Agent", "Mozilla/5.0")
                for ((key, values) in request.headers()) {
                    for (value in values) addRequestProperty(key, value)
                }
                request.dataToSend()?.let { data ->
                    doOutput = true
                    outputStream.use { it.write(data) }
                }
            }

            val responseCode = connection.responseCode
            val responseMessage = connection.responseMessage ?: ""
            val responseHeaders = connection.headerFields
                ?.filterKeys { it != null }
                ?.mapValues { (_, v) -> v ?: emptyList() }
                ?: emptyMap()
            val body = try {
                connection.inputStream.bufferedReader().use { it.readText() }
            } catch (_: IOException) {
                connection.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
            }
            return Response(responseCode, responseMessage, responseHeaders, body, request.url())
        }
    }
}
