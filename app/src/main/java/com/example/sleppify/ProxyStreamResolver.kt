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
    private const val SERVER_PENALTY_MS = 120_000L // 2 min cooldown for failed servers
    private const val SUCCESS_STICKY_MS = 300_000L // 5 min — prefer last successful server

    private val STREAM_SERVERS = arrayOf(
        "https://sleppifydownload.alwaysdata.net",
        "https://sleppifydownload2.alwaysdata.net",
        "https://sleppifydownloader2.alwaysdata.net"
    )

    private data class CachedUrl(val url: String, val timestamp: Long)

    private val urlCache = ConcurrentHashMap<String, CachedUrl>()
    private val roundRobinIndex = AtomicInteger(0)
    // Tracks when each server last failed (index → timestamp)
    private val serverFailureTimestamps = ConcurrentHashMap<Int, Long>()
    // Tracks the last server that successfully played audio (index → timestamp)
    @Volatile private var lastSuccessfulServerIndex: Int = -1
    @Volatile private var lastSuccessTimestamp: Long = 0L

    /**
     * Returns the streaming proxy URL for the given videoId.
     * Prefers the last server that successfully played audio. Falls back to others
     * only if the preferred server has failed recently.
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

        val now = System.currentTimeMillis()

        // Build priority order: prefer last successful server, then round-robin for the rest
        val preferredIdx = if (lastSuccessfulServerIndex >= 0
            && (now - lastSuccessTimestamp) < SUCCESS_STICKY_MS
        ) lastSuccessfulServerIndex else -1

        val order = mutableListOf<Int>()
        if (preferredIdx >= 0) {
            order.add(preferredIdx)
        }
        // Add remaining servers in round-robin order
        val rrStart = roundRobinIndex.getAndUpdate { (it + 1) % STREAM_SERVERS.size }
        for (i in 0 until STREAM_SERVERS.size) {
            val idx = (rrStart + i) % STREAM_SERVERS.size
            if (idx != preferredIdx) {
                order.add(idx)
            }
        }

        // Pick the first server that isn't penalized
        for ((attempt, idx) in order.withIndex()) {
            val failedAt = serverFailureTimestamps[idx] ?: 0L
            val isLastOption = attempt == order.size - 1
            if (now - failedAt < SERVER_PENALTY_MS && !isLastOption) {
                Log.d(TAG, "Skipping server $idx (failed ${(now - failedAt) / 1000}s ago)")
                continue
            }
            val server = STREAM_SERVERS[idx]
            val streamUrl = "$server/api/stream/$videoId"
            urlCache[videoId] = CachedUrl(streamUrl, now)
            Log.d(TAG, "Resolved $videoId via server $idx" +
                    if (idx == preferredIdx) " (preferred)" else "")
            return streamUrl
        }

        // Should never reach here, but fallback to preferred or first
        val fallbackIdx = if (preferredIdx >= 0) preferredIdx else 0
        val streamUrl = "${STREAM_SERVERS[fallbackIdx]}/api/stream/$videoId"
        urlCache[videoId] = CachedUrl(streamUrl, now)
        return streamUrl
    }

    /**
     * Marks the server that was used for [videoId] as temporarily failed.
     * Called when ExoPlayer reports a source error (HTTP 500, timeout, etc.)
     */
    @JvmStatic
    fun markFailed(videoId: String?) {
        if (videoId.isNullOrBlank()) return
        val cached = urlCache[videoId] ?: return
        for (i in STREAM_SERVERS.indices) {
            if (cached.url.startsWith(STREAM_SERVERS[i])) {
                serverFailureTimestamps[i] = System.currentTimeMillis()
                // If the failed server was the preferred one, clear preference
                if (i == lastSuccessfulServerIndex) {
                    lastSuccessfulServerIndex = -1
                    lastSuccessTimestamp = 0L
                }
                Log.d(TAG, "Marked server $i as failed for ${SERVER_PENALTY_MS / 1000}s")
                break
            }
        }
        // Also invalidate the cached URL so next resolve picks a different server
        urlCache.remove(videoId)
    }

    /**
     * Marks the server that was used for [videoId] as successfully playing.
     * Called when audio is confirmed playing (PlaybackLoadingBus.notifyAudioConfirmed).
     * Future resolutions will prefer this server.
     */
    @JvmStatic
    fun markSuccess(videoId: String?) {
        if (videoId.isNullOrBlank()) return
        val cached = urlCache[videoId] ?: return
        for (i in STREAM_SERVERS.indices) {
            if (cached.url.startsWith(STREAM_SERVERS[i])) {
                lastSuccessfulServerIndex = i
                lastSuccessTimestamp = System.currentTimeMillis()
                // Clear any failure penalty for this server since it just worked
                serverFailureTimestamps.remove(i)
                Log.d(TAG, "Marked server $i as SUCCESS — will prefer for next track")
                break
            }
        }
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
