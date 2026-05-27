package com.example.sleppify

import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Resolves YouTube stream URLs via Sleppify proxy servers using round-robin with failover.
 * Returns proxy streaming URLs (the proxy handles Range/seeking server-side).
 *
 * Architecture: ExoPlayer → proxy /api/stream/<videoId> → googlevideo.com
 * The proxy resolves yt-dlp once, caches the CDN URL, and proxies bytes with Range support.
 */
object ProxyStreamResolver {

    private const val TAG = "ProxyStreamResolver"
    private const val CACHE_EXPIRY_MS = 4 * 60 * 60 * 1000L // 4 hours

    private val STREAM_SERVERS = arrayOf(
        "https://sleppifydownload.alwaysdata.net",
        "https://sleppifydownload2.alwaysdata.net",
        "https://sleppifydownloader2.alwaysdata.net"
    )

    private data class CachedUrl(val url: String, val timestamp: Long)

    private val urlCache = ConcurrentHashMap<String, CachedUrl>()
    private val roundRobinIndex = AtomicInteger(0)

    /**
     * Returns the streaming proxy URL for the given videoId.
     * Uses round-robin server selection. No health check — if the server is down,
     * ExoPlayer will fail and the caller handles retry/failover. This avoids a
     * blocking 1-2s GET per resolution.
     */
    @JvmStatic
    fun resolveStreamUrl(videoId: String?): String? {
        if (videoId.isNullOrBlank()) return null

        // Check cache first
        urlCache[videoId]?.let {
            if (System.currentTimeMillis() - it.timestamp < CACHE_EXPIRY_MS) {
                return it.url
            } else {
                urlCache.remove(videoId)
            }
        }

        val serverIndex = roundRobinIndex.getAndUpdate { (it + 1) % STREAM_SERVERS.size }
        val server = STREAM_SERVERS[serverIndex]
        val streamUrl = "$server/api/stream/$videoId"

        urlCache[videoId] = CachedUrl(streamUrl, System.currentTimeMillis())
        Log.d(TAG, "Resolved $videoId via server $serverIndex")
        return streamUrl
    }

    /**
     * Invalidates cached URL for the given videoId.
     */
    @JvmStatic
    fun invalidate(videoId: String?) {
        if (videoId.isNullOrBlank()) return
        urlCache.remove(videoId)
    }

    /**
     * Clears entire URL cache.
     */
    @JvmStatic
    fun clearCache() {
        urlCache.clear()
    }
}
