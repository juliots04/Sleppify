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
    private const val DISK_CACHE_PREFS = "stream_resolver_cache"
    private const val DISK_KEY_VIDEO_ID = "last_video_id"
    private const val DISK_KEY_URL = "last_url"
    private const val DISK_KEY_TIMESTAMP = "last_timestamp"

    // Preferred audio itags: 141=AAC256k, 140=AAC128k, 251=Opus160k, 250=Opus70k, 249=Opus50k
    private val PREFERRED_ITAGS = intArrayOf(141, 140, 251, 250, 249)

    private val ITAGS_LOW = intArrayOf(249, 250)           // Opus 50k, 70k
    private val ITAGS_MEDIUM = intArrayOf(250, 140, 249)   // Opus 70k, AAC 128k, Opus 50k
    private val ITAGS_HIGH = intArrayOf(251, 140, 250)     // Opus 160k, AAC 128k, Opus 70k
    private val ITAGS_VERY_HIGH = intArrayOf(141, 251, 140) // AAC 256k, Opus 160k, AAC 128k

    @Volatile private var authCookieHeader: String = ""
    private val newPipeInitialized = AtomicBoolean(false)

    private enum class SourceType { DIRECT, PROXY }
    private data class CachedStream(val url: String, val type: SourceType, val timestamp: Long)
    private val urlCache = ConcurrentHashMap<String, CachedStream>()
    // Dedup: in-flight resolutions so parallel callers wait on the same result
    private val inFlightResolutions = ConcurrentHashMap<String, java.util.concurrent.CompletableFuture<String?>>()

    // ─── Warm-up (call from MainActivity.onCreate on a background thread) ──────

    /**
     * Pre-initializes NewPipe and seeds the in-memory cache with the last
     * played track's stream URL (from disk). Call early from MainActivity.
     */
    @JvmStatic
    fun warmUp(context: Context) {
        val appCtx = context.applicationContext
        Thread({
            // 0. Load auth cookies from prefs (was previously on main thread)
            loadAuthCookiesFromPrefs(appCtx)
            // 1. Restore disk-cached URL into memory (instant, no network)
            restoreDiskCache(appCtx)
            // 2. Initialize NewPipe so the first resolveStreamUrl skips init overhead
            ensureNewPipeInitialized()
            // 3. Pre-resolve the current track if the disk cache is expired/missing
            preResolveCurrentTrack(appCtx)
        }, "StreamResolver-warmup").start()
    }

    private fun restoreDiskCache(context: Context) {
        try {
            val prefs = context.getSharedPreferences(DISK_CACHE_PREFS, Context.MODE_PRIVATE)
            val videoId = prefs.getString(DISK_KEY_VIDEO_ID, "") ?: return
            val url = prefs.getString(DISK_KEY_URL, "") ?: return
            val ts = prefs.getLong(DISK_KEY_TIMESTAMP, 0L)
            if (videoId.isBlank() || url.isBlank()) return
            if (System.currentTimeMillis() - ts > DIRECT_CACHE_TTL_MS) return
            // Seed in-memory cache so resolveStreamUrl returns instantly
            urlCache.putIfAbsent(videoId, CachedStream(url, SourceType.DIRECT, ts))
            Log.d(TAG, "disk_cache restored videoId=$videoId")
        } catch (e: Exception) {
            Log.w(TAG, "restoreDiskCache failed", e)
        }
    }

    private fun persistDiskCache(context: Context, videoId: String, url: String) {
        try {
            context.getSharedPreferences(DISK_CACHE_PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(DISK_KEY_VIDEO_ID, videoId)
                .putString(DISK_KEY_URL, url)
                .putLong(DISK_KEY_TIMESTAMP, System.currentTimeMillis())
                .apply()
        } catch (e: Exception) {
            Log.w(TAG, "persistDiskCache failed", e)
        }
    }

    private fun preResolveCurrentTrack(context: Context) {
        try {
            val snapshot = PlaybackHistoryStore.load(context)
            val track = snapshot.currentTrack() ?: return
            val videoId = track.videoId
            if (videoId.isBlank() || videoId.startsWith("local_")) return
            // Already in memory cache? Skip.
            urlCache[videoId]?.let {
                if (System.currentTimeMillis() - it.timestamp < DIRECT_CACHE_TTL_MS) return
            }
            // Resolve now so playCurrentTrack finds it ready
            val url = resolveViaNewPipe(videoId)
            if (!url.isNullOrBlank()) {
                urlCache[videoId] = CachedStream(url, SourceType.DIRECT, System.currentTimeMillis())
                persistDiskCache(context, videoId, url)
                Log.d(TAG, "warmup pre-resolved videoId=$videoId")
            }
        } catch (e: Exception) {
            Log.w(TAG, "preResolveCurrentTrack failed", e)
        }
    }

    /**
     * Pre-resolves a list of video IDs in parallel on background threads.
     * Useful after login to warm the cache for the first few tracks in the queue.
     */
    @JvmStatic
    fun preResolveQueue(context: Context, videoIds: List<String>) {
        val appCtx = context.applicationContext
        val executor = java.util.concurrent.Executors.newFixedThreadPool(
            videoIds.size.coerceAtMost(3)
        )
        for (videoId in videoIds.take(5)) {
            if (videoId.isBlank() || videoId.startsWith("local_")) continue
            val cached = urlCache[videoId]
            if (cached != null && System.currentTimeMillis() - cached.timestamp < DIRECT_CACHE_TTL_MS) continue
            executor.execute {
                try {
                    val url = resolveViaNewPipe(videoId)
                    if (!url.isNullOrBlank()) {
                        urlCache[videoId] = CachedStream(url, SourceType.DIRECT, System.currentTimeMillis())
                        Log.d(TAG, "preResolveQueue ok videoId=$videoId")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "preResolveQueue failed videoId=$videoId", e)
                }
            }
        }
        executor.shutdown()
    }

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
     * Uses NewPipeExtractor only (no proxy for streaming).
     * Must be called from a background thread (network I/O).
     */
    @JvmStatic
    @JvmOverloads
    fun resolveStreamUrl(context: Context, videoId: String?, forceAlternativeClient: Boolean = false): String? {
        if (videoId.isNullOrBlank()) return null
        if (videoId.startsWith("local_")) return null

        // If "No reproducir videos musicales" is OFF → use proxy (streams video)
        val prefs = context.getSharedPreferences(AppConstants.PREFS_SETTINGS, Context.MODE_PRIVATE)
        val noMusicVideos = prefs.getBoolean(CloudSyncManager.KEY_NO_MUSIC_VIDEOS, true)
        if (!noMusicVideos) {
            val proxyUrl = ProxyStreamResolver.resolveStreamUrl(videoId)
            if (!proxyUrl.isNullOrBlank()) {
                urlCache[videoId] = CachedStream(proxyUrl, SourceType.PROXY, System.currentTimeMillis())
                Log.d(TAG, "proxy_video[$videoId] ok (music videos enabled)")
                return proxyUrl
            }
        }

        // 1. Cache check (in-memory, seeded by warmUp or previous resolve)
        urlCache[videoId]?.let { cached ->
            if (cached.type == SourceType.DIRECT
                && System.currentTimeMillis() - cached.timestamp < DIRECT_CACHE_TTL_MS) {
                Log.d(TAG, "cache[$videoId] type=DIRECT")
                return cached.url
            }
            urlCache.remove(videoId)
        }

        // 2. Dedup: if another thread is already resolving this videoId, wait for it
        val existingFuture = inFlightResolutions[videoId]
        if (existingFuture != null) {
            return try {
                val result = existingFuture.get(15, java.util.concurrent.TimeUnit.SECONDS)
                Log.d(TAG, "dedup[$videoId] reused in-flight result")
                result
            } catch (e: Exception) {
                Log.w(TAG, "dedup[$videoId] wait failed", e)
                null
            }
        }

        // 3. NewPipe (audio-only resolver)
        val itags = getPreferredItags(context)
        val future = java.util.concurrent.CompletableFuture<String?>()
        inFlightResolutions[videoId] = future
        try {
            val directUrl = resolveViaNewPipe(videoId, itags)
            if (!directUrl.isNullOrBlank()) {
                val now = System.currentTimeMillis()
                urlCache[videoId] = CachedStream(directUrl, SourceType.DIRECT, now)
                persistDiskCache(context, videoId, directUrl)
                Log.d(TAG, "newpipe[$videoId] ok")
                future.complete(directUrl)
                return directUrl
            }
            future.complete(null)
        } catch (e: Exception) {
            Log.w(TAG, "newpipe[$videoId] failed: ${e.javaClass.simpleName} — ${e.message}")
            future.completeExceptionally(e)
        } finally {
            inFlightResolutions.remove(videoId)
        }

        Log.w(TAG, "newpipe[$videoId] no stream resolved")
        return null
    }

    /**
     * Returns the preferred itag order based on the user's quality preference
     * for the current network type (WiFi vs mobile data).
     */
    private fun getPreferredItags(context: Context): IntArray {
        val prefs = context.getSharedPreferences(AppConstants.PREFS_SETTINGS, Context.MODE_PRIVATE)
        val onWifi = isOnWifi(context)
        val qualityKey = if (onWifi) CloudSyncManager.KEY_STREAMING_QUALITY_WIFI
                         else CloudSyncManager.KEY_STREAMING_QUALITY_MOBILE
        val quality = prefs.getString(qualityKey, CloudSyncManager.STREAMING_QUALITY_MEDIUM)
            ?: CloudSyncManager.STREAMING_QUALITY_MEDIUM
        return when (quality) {
            CloudSyncManager.STREAMING_QUALITY_LOW -> ITAGS_LOW
            CloudSyncManager.STREAMING_QUALITY_MEDIUM -> ITAGS_MEDIUM
            CloudSyncManager.STREAMING_QUALITY_HIGH -> ITAGS_HIGH
            CloudSyncManager.STREAMING_QUALITY_VERY_HIGH -> ITAGS_VERY_HIGH
            else -> ITAGS_MEDIUM
        }
    }

    @JvmStatic
    fun isOnWifi(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
            ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)
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

    private fun resolveViaNewPipe(videoId: String, preferredItags: IntArray = PREFERRED_ITAGS): String? {
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

        for (preferredItag in preferredItags) {
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
