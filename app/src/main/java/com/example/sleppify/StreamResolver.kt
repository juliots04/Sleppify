package com.example.sleppify

import android.content.Context
import android.util.Log
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamExtractor
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Single source of truth for YouTube stream resolution and auth cookies.
 *
 * Everything resolves through NewPipeExtractor to a direct googlevideo.com URL — no proxy:
 *  - Audio (default): best AAC/opus audio stream (see [resolveStreamUrl]).
 *  - Video (when "No reproducir videos musicales" is OFF): muxed mp4-360 / itag 18
 *    ([resolveVideoViaNewPipe]) so the player can show the music video.
 *  - Offline download: [resolveAudioDownloadSource] for SleppifyDownloaderResolver.
 * Results are cached in memory (3.5h) and the last URL persisted to disk for warm start.
 *
 * Also manages the auth cookies used by CommentsBottomSheet and SAPISIDHASH signing.
 */
object StreamResolver {

    private const val TAG = "StreamResolver"
    private const val DIRECT_CACHE_TTL_MS = 3 * 60 * 60 * 1000L + 30 * 60 * 1000L // 3.5h
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

    // Opus/WebM audio itags (everything else in PREFERRED_ITAGS is AAC/m4a).
    private val OPUS_ITAGS = intArrayOf(249, 250, 251, 600, 774)

    /** A directly-downloadable audio stream: the googlevideo CDN URL plus the container
     *  (webm/opus vs m4a/AAC) so the offline file is saved with the right extension. */
    data class AudioDownloadSource(val url: String, val isWebm: Boolean, val itag: Int)

    private enum class SourceType { DIRECT, VIDEO }
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
            val cookie = prefs.getString(AppConstants.PREF_LAST_YOUTUBE_WEB_COOKIE, "") ?: ""
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

    // ── Playback mode (Audio | Video pill in the player) ──────────────────
    // Session-wide switch, default AUDIO. When VIDEO, resolveStreamUrl returns the muxed mp4-360
    // (itag 18) so every path (playback, prefetch, gapless pre-buffer) transparently serves video.
    @Volatile private var preferVideoMode = false

    @JvmStatic
    fun setPreferVideoMode(enabled: Boolean) {
        preferVideoMode = enabled
    }

    @JvmStatic
    fun isPreferVideoMode(): Boolean = preferVideoMode

    /**
     * Resolves a playable stream URL for [videoId] via NewPipe — audio by default, or the muxed
     * mp4-360 music video while the player's Audio|Video pill is on VIDEO ([setPreferVideoMode]).
     * A track with no muxed video falls back to audio so it always plays.
     * Must be called from a background thread (network I/O).
     */
    @JvmStatic
    @JvmOverloads
    fun resolveStreamUrl(context: Context, videoId: String?, forceAlternativeClient: Boolean = false): String? {
        if (videoId.isNullOrBlank()) return null
        if (videoId.startsWith("local_")) return null

        if (preferVideoMode) {
            urlCache[videoId]?.let { cached ->
                if (cached.type == SourceType.VIDEO
                    && System.currentTimeMillis() - cached.timestamp < DIRECT_CACHE_TTL_MS) {
                    Log.d(TAG, "cache[$videoId] type=VIDEO")
                    return cached.url
                }
            }
            val videoUrl = resolveVideoViaNewPipe(videoId)
            if (!videoUrl.isNullOrBlank()) {
                urlCache[videoId] = CachedStream(videoUrl, SourceType.VIDEO, System.currentTimeMillis())
                Log.d(TAG, "newpipe_video[$videoId] ok (video mode)")
                return videoUrl
            }
            // No muxed video available → fall through to audio.
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
     * Returns HTTP headers to attach to the ExoPlayer/MediaPlayer data source. Every source is now a
     * direct googlevideo.com URL (audio or muxed video), so all get the browser User-Agent + Origin/
     * Referer that googlevideo expects — no proxy, no cookies.
     */
    @JvmStatic
    fun getHeadersFor(@Suppress("UNUSED_PARAMETER") videoId: String?): Map<String, String> {
        return mapOf(
            "User-Agent" to "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36",
            "Origin" to "https://music.youtube.com",
            "Referer" to "https://music.youtube.com/"
        )
    }

    @JvmStatic
    fun invalidate(videoId: String?) {
        if (videoId.isNullOrBlank()) return
        urlCache.remove(videoId)
    }

    @JvmStatic
    fun markSuccess(videoId: String?) {
        // No-op: direct CDN sources have no server whose success we need to record.
    }

    @JvmStatic
    fun markFailed(videoId: String?) {
        if (videoId.isNullOrBlank()) return
        urlCache.remove(videoId)
    }

    // ─── NewPipe internals ────────────────────────────────────────────────

    private fun ensureNewPipeInitialized() {
        if (newPipeInitialized.compareAndSet(false, true)) {
            NewPipe.init(NewPipeDownloader)
            Log.d(TAG, "NewPipe initialized")
        }
    }

    /**
     * Resolves the best directly-downloadable audio stream for [videoId] via NewPipe.
     * Returns the raw googlevideo CDN URL (deciphered, throttle-free) + container so the
     * offline downloader can pull the bytes straight from Google's CDN — no proxy.
     *
     * Prefers PROGRESSIVE_HTTP streams (a plain GET yields the whole file); m4a/AAC first for
     * maximum Android compatibility (see [PREFERRED_ITAGS]). Must run on a background thread.
     */
    @JvmStatic
    fun resolveAudioDownloadSource(videoId: String?): AudioDownloadSource? {
        if (videoId.isNullOrBlank() || videoId.startsWith("local_")) return null
        return try {
            ensureNewPipeInitialized()
            val extractor = ServiceList.YouTube.getStreamExtractor("https://www.youtube.com/watch?v=$videoId")
            extractor.fetchPage()

            val audioStreams: List<AudioStream> = extractor.audioStreams ?: emptyList()
            if (audioStreams.isEmpty()) {
                Log.w(TAG, "download[$videoId] no audio streams")
                return null
            }

            // Only streams that expose a real, directly-fetchable URL. Prefer progressive
            // (single-GET) delivery; fall back to any URL-bearing stream if none are progressive.
            val downloadable = audioStreams.filter { !it.content.isNullOrBlank() }
            val progressive = downloadable.filter { it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }
                .ifEmpty { downloadable }
            if (progressive.isEmpty()) return null

            val byItag = progressive.associateBy { it.itag }
            val chosen = PREFERRED_ITAGS.firstOrNull { byItag.containsKey(it) }?.let { byItag[it] }
                ?: progressive.first()

            val suffix = chosen.format?.suffix
            val isWebm = when {
                suffix != null -> suffix.equals("webm", true) || suffix.equals("opus", true)
                else -> chosen.itag in OPUS_ITAGS
            }
            Log.d(TAG, "download[$videoId] itag=${chosen.itag} webm=$isWebm")
            AudioDownloadSource(chosen.content, isWebm, chosen.itag)
        } catch (e: Exception) {
            Log.w(TAG, "download[$videoId] resolve failed: ${e.javaClass.simpleName} — ${e.message}")
            null
        }
    }

    /**
     * Resolves a muxed (video+audio) mp4-360 progressive URL via NewPipe for music-video streaming.
     * Prefers itag 18 (the classic 360p mp4); otherwise the lowest-resolution muxed mp4 (light and
     * reliable). Returns null when the track has no directly-playable muxed stream — the caller then
     * falls back to audio. Must run on a background thread.
     */
    private fun resolveVideoViaNewPipe(videoId: String): String? {
        return try {
            ensureNewPipeInitialized()
            val extractor = ServiceList.YouTube.getStreamExtractor("https://www.youtube.com/watch?v=$videoId")
            extractor.fetchPage()
            // videoStreams are the MUXED (video+audio) streams — ExoPlayer plays them directly,
            // no on-device muxing. videoOnlyStreams (DASH) are deliberately ignored here.
            val muxed = extractor.videoStreams ?: emptyList()
            val playable = muxed.filter { !it.content.isNullOrBlank() }
            val progressive = playable.filter { it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }
                .ifEmpty { playable }
            if (progressive.isEmpty()) return null
            val byItag = progressive.associateBy { it.itag }
            val chosen = byItag[18]
                ?: progressive.minByOrNull { parseResolutionHeight(it.resolution) }
                ?: return null
            chosen.content
        } catch (e: Exception) {
            Log.w(TAG, "video[$videoId] resolve failed: ${e.javaClass.simpleName} — ${e.message}")
            null
        }
    }

    /** "360p" / "720p60" → 360 / 720; unknown resolutions sort last. */
    private fun parseResolutionHeight(resolution: String?): Int {
        if (resolution.isNullOrBlank()) return Int.MAX_VALUE
        return resolution.takeWhile { it.isDigit() }.toIntOrNull() ?: Int.MAX_VALUE
    }

    /** True when the cached stream for [videoId] is the muxed music-video source (not audio). */
    @JvmStatic
    fun isVideoSource(videoId: String?): Boolean {
        if (videoId.isNullOrBlank()) return false
        return urlCache[videoId]?.type == SourceType.VIDEO
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
