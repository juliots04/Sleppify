package com.example.sleppify

import java.util.concurrent.ConcurrentHashMap

/**
 * Resolves YouTube stream URLs via the single Sleppify proxy host ([AppConstants.PROXY_BASE_URL]).
 *
 * Architecture: ExoPlayer → proxy /api/stream/<videoId> → googlevideo.com
 * The proxy resolves yt-dlp once, caches the CDN URL, and proxies bytes with Range support.
 *
 * This used to be a 3-server round-robin with penalty/sticky failover. With a single host that
 * whole machinery is gone: resolving is just "build URL + cache it". [markFailed]/[markSuccess]
 * are kept so existing playback callers don't change — failure simply drops the cached URL.
 */
object ProxyStreamResolver {
    private const val CACHE_EXPIRY_MS = 4 * 60 * 60 * 1000L // 4 hours (googlevideo URLs expire ~6h)

    private data class CachedUrl(val url: String, val timestamp: Long)

    private val urlCache = ConcurrentHashMap<String, CachedUrl>()

    /** Returns the streaming proxy URL for [videoId] (cached for [CACHE_EXPIRY_MS]). */
    @JvmStatic
    fun resolveStreamUrl(videoId: String?): String? {
        if (videoId.isNullOrBlank()) return null

        val now = System.currentTimeMillis()
        urlCache[videoId]?.let {
            if (now - it.timestamp < CACHE_EXPIRY_MS) return it.url
            urlCache.remove(videoId)
        }

        val streamUrl = "${AppConstants.PROXY_BASE_URL}/api/stream/$videoId"
        urlCache[videoId] = CachedUrl(streamUrl, now)
        return streamUrl
    }

    /**
     * A playback error occurred for [videoId] — drop its cached URL so the next resolve is fresh
     * (the proxy will re-resolve a new googlevideo CDN URL). Called from the player's error path.
     */
    @JvmStatic
    fun markFailed(videoId: String?) {
        if (!videoId.isNullOrBlank()) urlCache.remove(videoId)
    }

    /** Kept for API compatibility. With one host there is no "preferred server" to record. */
    @JvmStatic
    fun markSuccess(videoId: String?) {
        // no-op
    }

    /** Invalidates the cached URL for [videoId]. */
    @JvmStatic
    fun invalidate(videoId: String?) {
        if (!videoId.isNullOrBlank()) urlCache.remove(videoId)
    }

    /** Clears the entire URL cache. */
    @JvmStatic
    fun clearCache() {
        urlCache.clear()
    }
}
