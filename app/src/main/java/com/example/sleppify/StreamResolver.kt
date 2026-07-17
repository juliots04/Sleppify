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

    // Side-cache for the muxed itag-18 VIDEO URL, populated opportunistically during the AUDIO
    // extraction (same fetchPage → C1). Kept SEPARATE from urlCache on purpose: urlCache must stay
    // DIRECT while audio plays so isVideoSource()/isVideoPresentation() never flip the hero to
    // video with the audio stream running. Same 3.5h TTL discipline as urlCache.
    private val videoPrefetchCache = ConcurrentHashMap<String, CachedStream>()

    // Espejo de videoPrefetchCache para el lado de AUDIO: preserva la URL DIRECT cuando el modo
    // video pisa la entrada de urlCache, y se abastece gratis desde la extracción de video (mismo
    // fetchPage). Sin esto, el swap Video→Canción pagaba un round-trip completo de NewPipe y
    // volvía con una URL NUEVA (clave distinta en exo_stream_cache → conexión fría al CDN)
    // aunque los bytes del audio recién reproducido ya estuvieran en disco.
    private val audioPrefetchCache = ConcurrentHashMap<String, CachedStream>()

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
            // Resolve now so playCurrentTrack finds it ready. Con los itags de la calidad
            // elegida: este warm-up seedea urlCache y resolveStreamUrl lo devuelve tal cual,
            // así que resolver aquí en máxima calidad saltaba el ajuste del usuario.
            val url = resolveViaNewPipe(videoId, getPreferredItags(context), context)
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
                // Pista descargada en modo Canción: el playback irá al archivo offline — no
                // gastar red pre-resolviendo un stream que nunca se va a pedir.
                if (!preferVideoMode && OfflineAudioStore.hasOfflineAudio(appCtx, videoId)) {
                    return@execute
                }
                // Registrar el in-flight ANTES de resolver: un resolveStreamUrl concurrente
                // (el playback que este pre-resolve intenta adelantar) espera ESTE resultado
                // vía su rama de dedup en vez de duplicar el fetchPage.
                val future = java.util.concurrent.CompletableFuture<String?>()
                if (inFlightResolutions.putIfAbsent(videoId, future) != null) return@execute
                try {
                    // Con los itags de la calidad elegida (ver preResolveCurrentTrack).
                    val url = resolveViaNewPipe(videoId, getPreferredItags(appCtx), appCtx)
                    if (!url.isNullOrBlank()) {
                        urlCache[videoId] = CachedStream(url, SourceType.DIRECT, System.currentTimeMillis())
                        Log.d(TAG, "preResolveQueue ok videoId=$videoId")
                    }
                    future.complete(url)
                } catch (e: Exception) {
                    Log.w(TAG, "preResolveQueue failed videoId=$videoId", e)
                    future.completeExceptionally(e)
                } finally {
                    inFlightResolutions.remove(videoId)
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
        val next = cookieHeader?.trim() ?: ""
        // Cookie SIN cambios (p.ej. restoreCachedStreamingSessionState en cada arranque): no-op.
        // Antes limpiaba urlCache/prefetch incondicionalmente — una carrera de arranque tiraba a
        // la basura el pre-resolve que warmUp acababa de sembrar y enfriaba el primer play.
        if (next == authCookieHeader) return
        authCookieHeader = next
        if (authCookieHeader.isNotBlank()) {
            urlCache.clear()
            videoPrefetchCache.clear()
            audioPrefetchCache.clear()
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

        // Candado duro "No reproducir videos musicales": con el ajuste activo NINGÚN camino
        // sirve un stream de video, aunque preferVideoMode haya quedado armado (es session-only
        // y el candado debe sobrevivir reinicios de proceso).
        if (preferVideoMode && !noMusicVideosBlocked(context)) {
            urlCache[videoId]?.let { cached ->
                if (cached.type == SourceType.VIDEO
                    && System.currentTimeMillis() - cached.timestamp < DIRECT_CACHE_TTL_MS) {
                    Log.d(TAG, "cache[$videoId] type=VIDEO")
                    return cached.url
                }
            }
            // C1: side-cache hit — the video URL was already extracted (for free) during a prior
            // audio resolve. Promote it into urlCache as VIDEO (the mode actually being loaded IS
            // video now) so isVideoSource() reports correctly, and SKIP the network round-trip.
            videoPrefetchCache[videoId]?.let { pf ->
                if (System.currentTimeMillis() - pf.timestamp < DIRECT_CACHE_TTL_MS) {
                    stashDirectAsAudioPrefetch(videoId)
                    urlCache[videoId] = CachedStream(pf.url, SourceType.VIDEO, pf.timestamp)
                    Log.d(TAG, "video_prefetch[$videoId] hit (skipped network)")
                    return pf.url
                }
                videoPrefetchCache.remove(videoId)
            }
            val videoUrl = resolveVideoViaNewPipe(videoId, context)
            if (!videoUrl.isNullOrBlank()) {
                val now = System.currentTimeMillis()
                stashDirectAsAudioPrefetch(videoId)
                urlCache[videoId] = CachedStream(videoUrl, SourceType.VIDEO, now)
                videoPrefetchCache[videoId] = CachedStream(videoUrl, SourceType.VIDEO, now)
                Log.d(TAG, "newpipe_video[$videoId] ok (video mode)")
                return videoUrl
            }
            // No muxed video available → fall through to audio.
        }

        // 0. Reintento tras un FALLO DE REPRODUCCIÓN (no de resolución): la URL de NewPipe resolvió
        // bien pero su stream se cuelga/throttlea en el prepare de ExoPlayer (metadata al final del
        // contenedor, throttling del parámetro `n`, etc.). Con forceAlternativeClient se SALTA la
        // caché y NewPipe y se va directo al cliente ANDROID_VR, que entrega URLs directas más
        // reproducibles. Ver [[newpipe-fallback-android-vr]].
        if (forceAlternativeClient) {
            urlCache.remove(videoId)
            audioPrefetchCache.remove(videoId)
            val itags = getPreferredItags(context)
            val fb = try {
                resolveViaInnerTubeFallback(videoId, itags)?.url
            } catch (e: Exception) {
                Log.w(TAG, "forced ANDROID_VR[$videoId] failed: ${e.message}")
                null
            }
            if (!fb.isNullOrBlank()) {
                val now = System.currentTimeMillis()
                urlCache[videoId] = CachedStream(fb, SourceType.DIRECT, now)
                persistDiskCache(context, videoId, fb)
                Log.d(TAG, "resolve[$videoId] via ANDROID_VR (forced alt client)")
                return fb
            }
            // Si ANDROID_VR también falla, caer al camino normal (NewPipe) como último recurso.
            Log.w(TAG, "resolve[$videoId] forced alt client empty — falling back to NewPipe path")
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

        // 1b. Side-cache de audio: la URL DIRECT preservada al entrar a modo video, o extraída
        // gratis durante una resolución de video (mismo fetchPage). Promoverla evita el round-trip
        // de NewPipe del swap Video→Canción y reutiliza la MISMA URL cuya cabecera ya está en
        // exo_stream_cache → prepare casi desde disco.
        audioPrefetchCache[videoId]?.let { pf ->
            if (System.currentTimeMillis() - pf.timestamp < DIRECT_CACHE_TTL_MS) {
                urlCache[videoId] = CachedStream(pf.url, SourceType.DIRECT, pf.timestamp)
                Log.d(TAG, "audio_prefetch[$videoId] hit (skipped network)")
                return pf.url
            }
            audioPrefetchCache.remove(videoId)
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

        // 3. NewPipe (audio-only resolver), con FALLBACK a InnerTube ANDROID_VR si falla
        val itags = getPreferredItags(context)
        val future = java.util.concurrent.CompletableFuture<String?>()
        inFlightResolutions[videoId] = future
        try {
            var directUrl: String? = null
            try {
                directUrl = resolveViaNewPipe(videoId, itags, context)
            } catch (e: Exception) {
                Log.w(TAG, "newpipe[$videoId] failed: ${e.javaClass.simpleName} — ${e.message}")
            }
            if (directUrl.isNullOrBlank()) {
                // Fallback (NUNCA el camino principal): NewPipe roto por un cambio de YouTube o
                // sin streams — pedir el /player de InnerTube con el cliente ANDROID_VR.
                directUrl = resolveViaInnerTubeFallback(videoId, itags)?.url
            }
            if (!directUrl.isNullOrBlank()) {
                val now = System.currentTimeMillis()
                urlCache[videoId] = CachedStream(directUrl, SourceType.DIRECT, now)
                persistDiskCache(context, videoId, directUrl)
                future.complete(directUrl)
                return directUrl
            }
            future.complete(null)
        } finally {
            inFlightResolutions.remove(videoId)
        }

        Log.w(TAG, "resolve[$videoId] no stream resolved (newpipe + fallback)")
        return null
    }

    /** Lee el ajuste "No reproducir videos musicales" (default ON). Ver gate en [resolveStreamUrl]. */
    private fun noMusicVideosBlocked(context: Context): Boolean = try {
        context.getSharedPreferences(AppConstants.PREFS_SETTINGS, Context.MODE_PRIVATE)
            .getBoolean(CloudSyncManager.KEY_NO_MUSIC_VIDEOS, true)
    } catch (e: Exception) {
        false
    }

    /**
     * Invalida TODAS las URLs ya resueltas (memoria + side-caches + la entrada de disco). Se
     * llama al cambiar la calidad de audio/video en Ajustes: las URLs cacheadas quedaron
     * resueltas con los itags/altura de la calidad anterior y sobrevivirían hasta el TTL de 3.5h.
     */
    @JvmStatic
    fun clearResolvedUrlCache(context: Context) {
        urlCache.clear()
        videoPrefetchCache.clear()
        audioPrefetchCache.clear()
        try {
            context.getSharedPreferences(DISK_CACHE_PREFS, Context.MODE_PRIVATE)
                .edit().clear().apply()
        } catch (e: Exception) {
            Log.w(TAG, "clearResolvedUrlCache disk clear failed", e)
        }
        Log.d(TAG, "resolved-url caches cleared (quality change)")
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
        videoPrefetchCache.remove(videoId)
        audioPrefetchCache.remove(videoId)
    }

    @JvmStatic
    fun markSuccess(videoId: String?) {
        // No-op: direct CDN sources have no server whose success we need to record.
    }

    @JvmStatic
    fun markFailed(videoId: String?) {
        if (videoId.isNullOrBlank()) return
        urlCache.remove(videoId)
        videoPrefetchCache.remove(videoId)
        audioPrefetchCache.remove(videoId)
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
                return downloadSourceViaFallback(videoId)
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
            downloadSourceViaFallback(videoId)
        }
    }

    /** Fallback de descarga vía InnerTube ANDROID_VR (mismo criterio de contenedor que NewPipe). */
    private fun downloadSourceViaFallback(videoId: String): AudioDownloadSource? {
        val fb = resolveViaInnerTubeFallback(videoId, PREFERRED_ITAGS) ?: return null
        return AudioDownloadSource(fb.url, fb.itag in OPUS_ITAGS, fb.itag)
    }

    /**
     * Resolves a muxed (video+audio) mp4-360 progressive URL via NewPipe for music-video streaming.
     * Prefers itag 18 (the classic 360p mp4); otherwise the lowest-resolution muxed mp4 (light and
     * reliable). Returns null when the track has no directly-playable muxed stream — the caller then
     * falls back to audio. Must run on a background thread.
     */
    private fun resolveVideoViaNewPipe(videoId: String, context: Context? = null): String? {
        return try {
            ensureNewPipeInitialized()
            val extractor = ServiceList.YouTube.getStreamExtractor("https://www.youtube.com/watch?v=$videoId")
            extractor.fetchPage()
            // C1 inverso: este fetchPage ya está pagado — extraer también la URL de audio
            // preferida y abastecer el side-cache, así el camino de vuelta (Video→Canción)
            // resuelve con CERO red en vez de otro round-trip de NewPipe.
            try {
                val audioItags = context?.let { getPreferredItags(it) } ?: PREFERRED_ITAGS
                val audioUrl = extractPreferredAudioUrl(extractor, audioItags)
                if (!audioUrl.isNullOrBlank()) {
                    audioPrefetchCache[videoId] =
                        CachedStream(audioUrl, SourceType.DIRECT, System.currentTimeMillis())
                }
            } catch (e: Exception) {
                Log.w(TAG, "audio_prefetch[$videoId] failed: ${e.message}")
            }
            extractMuxedVideoUrl(extractor, getPreferredVideoHeight(context))
        } catch (e: Exception) {
            Log.w(TAG, "video[$videoId] resolve failed: ${e.javaClass.simpleName} — ${e.message}")
            null
        }
    }

    /**
     * Picks the muxed itag-18 (else the lowest-resolution muxed mp4) direct URL from an ALREADY
     * fetched [extractor]. videoStreams are the MUXED (video+audio) streams — ExoPlayer plays them
     * directly, no on-device muxing; videoOnlyStreams (DASH) are deliberately ignored.
     *
     * This is the core of C1: pulling a second URL out of a StreamInfo we already paid one
     * fetchPage() for is nearly free, so the audio resolve can also stock the video side-cache.
     */
    private fun extractMuxedVideoUrl(extractor: StreamExtractor, targetHeight: Int = DEFAULT_VIDEO_TARGET_HEIGHT): String? {
        val muxed = extractor.videoStreams ?: emptyList()
        val playable = muxed.filter { !it.content.isNullOrBlank() }
        val progressive = playable.filter { it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }
            .ifEmpty { playable }
        if (progressive.isEmpty()) return null
        // Mejor stream muxed cuya altura no exceda el objetivo del usuario (empate → itag 18,
        // el mp4 clásico y más fiable). Los de resolución desconocida (MAX_VALUE) nunca entran
        // como candidatos; si ningún stream cabe en el objetivo, el de menor resolución
        // disponible (el comportamiento previo) para que el video siempre reproduzca.
        val byHeight = progressive.map { it to parseResolutionHeight(it.resolution) }
        val chosen = byHeight.filter { it.second <= targetHeight }
            .maxWithOrNull(compareBy({ it.second }, { it.first.itag == 18 }))?.first
            ?: byHeight.minByOrNull { it.second }?.first
            ?: return null
        return chosen.content
    }

    // "Normal (360p)" — el itag 18 clásico. Techo para "Alta" = 4320 (la mejor muxed disponible
    // sin dejar pasar streams de resolución desconocida, que parsean a Int.MAX_VALUE).
    private const val DEFAULT_VIDEO_TARGET_HEIGHT = 360

    /**
     * Altura máxima de video según el ajuste "Calidad de video" del usuario para la red actual
     * (Wi-Fi vs datos). Con context null (rutas legacy sin Context) se mantiene el clásico 360p.
     */
    private fun getPreferredVideoHeight(context: Context?): Int {
        if (context == null) return DEFAULT_VIDEO_TARGET_HEIGHT
        return try {
            val prefs = context.getSharedPreferences(AppConstants.PREFS_SETTINGS, Context.MODE_PRIVATE)
            val key = if (isOnWifi(context)) CloudSyncManager.KEY_VIDEO_QUALITY_WIFI
                      else CloudSyncManager.KEY_VIDEO_QUALITY_MOBILE
            when (prefs.getString(key, CloudSyncManager.STREAMING_QUALITY_MEDIUM)) {
                CloudSyncManager.STREAMING_QUALITY_LOW -> 240
                CloudSyncManager.STREAMING_QUALITY_HIGH -> 4320
                else -> DEFAULT_VIDEO_TARGET_HEIGHT
            }
        } catch (e: Exception) {
            DEFAULT_VIDEO_TARGET_HEIGHT
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

    /** Copia la entrada DIRECT vigente de urlCache al side-cache de audio (si sigue fresca) antes
     *  de que el modo video la pise. urlCache conserva la semántica «tipo de la ÚLTIMA URL
     *  entregada para playback»; el side-cache es solo la memoria del camino de vuelta. */
    private fun stashDirectAsAudioPrefetch(videoId: String) {
        urlCache[videoId]?.let { cached ->
            if (cached.type == SourceType.DIRECT
                && System.currentTimeMillis() - cached.timestamp < DIRECT_CACHE_TTL_MS) {
                audioPrefetchCache[videoId] = cached
            }
        }
    }

    /**
     * Deshace la entrada VIDEO de urlCache cuando el playback REAL volvió a audio SIN pasar por
     * resolveStreamUrl (p.ej. el hot-swap Video→Canción tomó el archivo offline directamente).
     * Restaura la entrada DIRECT desde el side-cache de audio si sigue fresca; si no, elimina la
     * VIDEO. Sin esto, isVideoSource() seguiría reportando video para un commit que suena como
     * audio y el siguiente consumidor del cache heredaría el tipo equivocado.
     */
    @JvmStatic
    fun demoteVideoEntry(videoId: String?) {
        if (videoId.isNullOrBlank()) return
        val cached = urlCache[videoId] ?: return
        if (cached.type != SourceType.VIDEO) return
        val audio = audioPrefetchCache[videoId]
        if (audio != null && System.currentTimeMillis() - audio.timestamp < DIRECT_CACHE_TTL_MS) {
            urlCache[videoId] = CachedStream(audio.url, SourceType.DIRECT, audio.timestamp)
        } else {
            urlCache.remove(videoId)
        }
    }

    /**
     * The muxed video URL for [videoId] if it is ALREADY known — from the C1 side-cache (extracted
     * during a prior audio resolve) or a live VIDEO urlCache entry — with NO network fetch. Returns
     * null when a fetch would be needed. Used by the player to (a) warm the video head into the
     * media cache (C2) and (b) decide whether a hot-swap can use the fast (already-warm) timings.
     */
    @JvmStatic
    fun getPrefetchedVideoUrl(videoId: String?): String? {
        if (videoId.isNullOrBlank() || videoId.startsWith("local_")) return null
        val now = System.currentTimeMillis()
        videoPrefetchCache[videoId]?.let { pf ->
            if (now - pf.timestamp < DIRECT_CACHE_TTL_MS) return pf.url
            videoPrefetchCache.remove(videoId)
        }
        urlCache[videoId]?.let { cached ->
            if (cached.type == SourceType.VIDEO && now - cached.timestamp < DIRECT_CACHE_TTL_MS) {
                return cached.url
            }
        }
        return null
    }

    /**
     * Eagerly resolves and side-caches the muxed video URL for [videoId] so a later Canción→Video tap
     * skips the NewPipe fetch (C1) even on tracks whose audio was served from cache (where the free
     * C1 extraction never ran). Instant no-op when the URL is already known. Deliberately does NOT
     * touch [urlCache]: audio keeps playing as DIRECT and isVideoSource() stays audio until the mode
     * actually flips. Metadata-only (does not download media bytes). Must run on a background thread.
     */
    @JvmStatic
    fun prefetchVideoUrl(context: Context, videoId: String?): String? {
        if (videoId.isNullOrBlank() || videoId.startsWith("local_")) return null
        getPrefetchedVideoUrl(videoId)?.let { return it }
        val url = resolveVideoViaNewPipe(videoId, context)
        if (!url.isNullOrBlank()) {
            videoPrefetchCache[videoId] = CachedStream(url, SourceType.VIDEO, System.currentTimeMillis())
            Log.d(TAG, "prefetchVideoUrl[$videoId] side-cached")
        }
        return url
    }

    // ─── Fallback InnerTube (cliente ANDROID_VR) ─────────────────────────

    // Cuando NewPipe falla (un cambio de YouTube rompió el extractor, bot-check, parsing), se pide
    // el /player de InnerTube DIRECTAMENTE con el cliente ANDROID_VR: según la guía PO-Token de
    // yt-dlp (2026) es el único cliente que NO exige PO token, y al ser cliente Android entrega
    // URLs de googlevideo ya descifradas (sin signatureCipher, sin JS). Verificado en vivo:
    // /player OK + GET 206 con bytes de audio reales, servidos con cualquier User-Agent (la URL
    // se ata a la IP). Única limitación conocida: videos "made for kids" no disponibles.
    // Es un FALLBACK, nunca el camino principal — NewPipe sigue siendo la resolución primaria.
    private const val VR_CLIENT_VERSION = "1.62.27"
    private const val VR_USER_AGENT =
        "com.google.android.apps.youtube.vr.oculus/1.62.27 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip"

    /** URL de audio directa + itag elegidos del fallback (el itag decide el contenedor al descargar). */
    private data class FallbackAudio(val url: String, val itag: Int)

    private fun resolveViaInnerTubeFallback(videoId: String, preferredItags: IntArray): FallbackAudio? {
        return try {
            val body = org.json.JSONObject().apply {
                put("context", org.json.JSONObject().put("client", org.json.JSONObject().apply {
                    put("clientName", "ANDROID_VR")
                    put("clientVersion", VR_CLIENT_VERSION)
                    put("deviceMake", "Oculus")
                    put("deviceModel", "Quest 3")
                    put("osName", "Android")
                    put("osVersion", "12L")
                    put("androidSdkVersion", 32)
                    put("hl", "es")
                }))
                put("videoId", videoId)
                put("contentCheckOk", true)
                put("racyCheckOk", true)
            }.toString().toByteArray(Charsets.UTF_8)

            val connection = (URL("https://www.youtube.com/youtubei/v1/player?prettyPrint=false")
                .openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 8000
                readTimeout = 10000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("User-Agent", VR_USER_AGENT)
            }
            val responseText = try {
                connection.outputStream.use { it.write(body) }
                if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                    Log.w(TAG, "fallback[$videoId] player HTTP ${connection.responseCode}")
                    return null
                }
                connection.inputStream.bufferedReader().use { it.readText() }
            } finally {
                connection.disconnect()
            }

            val root = org.json.JSONObject(responseText)
            val status = root.optJSONObject("playabilityStatus")?.optString("status", "") ?: ""
            if (status != "OK") {
                Log.w(TAG, "fallback[$videoId] playability=$status")
                return null
            }
            val formats = root.optJSONObject("streamingData")
                ?.optJSONArray("adaptiveFormats") ?: return null
            val byItag = HashMap<Int, FallbackAudio>()
            var anyAudio: FallbackAudio? = null
            for (i in 0 until formats.length()) {
                val f = formats.optJSONObject(i) ?: continue
                if (!f.optString("mimeType", "").startsWith("audio/")) continue
                val u = f.optString("url", "")
                if (u.isEmpty()) continue // cifrada — no debería ocurrir con ANDROID_VR
                val candidate = FallbackAudio(u, f.optInt("itag", -1))
                byItag[candidate.itag] = candidate
                if (anyAudio == null) anyAudio = candidate
            }
            var chosen: FallbackAudio? = null
            for (itag in preferredItags) {
                val candidate = byItag[itag]
                if (candidate != null) {
                    chosen = candidate
                    break
                }
            }
            if (chosen == null) chosen = anyAudio
            if (chosen != null) Log.d(TAG, "fallback[$videoId] ok (ANDROID_VR itag=${chosen.itag})")
            chosen
        } catch (e: Exception) {
            Log.w(TAG, "fallback[$videoId] failed: ${e.javaClass.simpleName} — ${e.message}")
            null
        }
    }

    private fun resolveViaNewPipe(
        videoId: String,
        preferredItags: IntArray = PREFERRED_ITAGS,
        context: Context? = null
    ): String? {
        ensureNewPipeInitialized()
        val url = "https://www.youtube.com/watch?v=$videoId"
        val extractor: StreamExtractor = ServiceList.YouTube.getStreamExtractor(url)
        extractor.fetchPage()

        // C1: opportunistically stock the video side-cache from THIS SAME extraction (no extra
        // fetchPage). Lets a later Audio→Video swap skip the network round-trip. Does NOT touch
        // urlCache, so the primary source type stays DIRECT while audio plays.
        try {
            val videoUrl = extractMuxedVideoUrl(extractor, getPreferredVideoHeight(context))
            if (!videoUrl.isNullOrBlank()) {
                videoPrefetchCache[videoId] = CachedStream(videoUrl, SourceType.VIDEO, System.currentTimeMillis())
            }
        } catch (e: Exception) {
            Log.w(TAG, "video_prefetch[$videoId] failed: ${e.message}")
        }

        val audioUrl = extractPreferredAudioUrl(extractor, preferredItags)
        if (audioUrl.isNullOrBlank()) {
            Log.w(TAG, "newpipe[$videoId] no audio streams")
            return null
        }
        // Abastece el side-cache de audio también en la resolución normal: si más tarde el modo
        // video pisa la entrada DIRECT de urlCache, el camino de vuelta sigue sin tocar la red.
        audioPrefetchCache[videoId] = CachedStream(audioUrl, SourceType.DIRECT, System.currentTimeMillis())
        return audioUrl
    }

    /** Elige la mejor URL de audio directa de un [extractor] YA fetcheado (mismo criterio de
     *  itags que la resolución normal). Contraparte de [extractMuxedVideoUrl] para el lado de
     *  audio: sacar una segunda URL de un StreamInfo ya pagado es prácticamente gratis. */
    private fun extractPreferredAudioUrl(extractor: StreamExtractor, preferredItags: IntArray): String? {
        val audioStreams: List<AudioStream> = extractor.audioStreams ?: emptyList()
        if (audioStreams.isEmpty()) return null

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
