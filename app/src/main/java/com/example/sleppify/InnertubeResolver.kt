package com.example.sleppify

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * Native YouTube InnerTube stream resolver.
 *
 * Uses the YouTube Music InnerTube API (`/youtubei/v1/player`) to resolve direct
 * googlevideo.com stream URLs on-device, without going through the Sleppify proxy servers.
 *
 * Falls back to [ProxyStreamResolver] when:
 *  - No auth cookies are available (user not logged in)
 *  - The InnerTube API returns an error or unplayable status
 *  - The resolved URL is empty or a network error occurs
 *
 * Architecture:
 *   ExoPlayer → googlevideo.com (direct, via InnerTube)
 *   ExoPlayer → proxy /api/stream/<videoId> (fallback only)
 */
object InnertubeResolver {

    private const val TAG = "InnertubeResolver"

    // InnerTube player endpoint (same one the YT Music web app uses)
    private const val PLAYER_ENDPOINT = "https://music.youtube.com/youtubei/v1/player?prettyPrint=false"

    // WEB_REMIX client (YouTube Music web) — works with auth cookies, returns
    // pre-signed URLs that don't need signature deciphering.
    private const val CLIENT_NAME = "WEB_REMIX"
    private const val CLIENT_VERSION = "1.20241111.01.00"

    // Cache resolved URLs for 3.5 hours (googlevideo URLs expire ~6h)
    private const val CACHE_TTL_MS = 3 * 60 * 60 * 1000L + 30 * 60 * 1000L

    // Preferred itags in priority order:
    //  140 = AAC 128kbps m4a (best for music streaming)
    //  141 = AAC 256kbps m4a (premium quality)
    //  251 = Opus 160kbps webm
    //  250 = Opus 70kbps webm
    //  249 = Opus 50kbps webm
    //  18  = 360p pre-muxed mp4 (audio+video, last resort)
    //  22  = 720p pre-muxed mp4 (audio+video, last resort)
    private val PREFERRED_ITAGS = intArrayOf(141, 140, 251, 250, 249, 18, 22)

    // --- State ---
    @Volatile
    private var authCookieHeader: String = ""

    private data class CachedStream(val url: String, val timestamp: Long)
    private val urlCache = ConcurrentHashMap<String, CachedStream>()

    // ─── Public API (matches existing call sites) ────────────────────────

    /**
     * Called on app startup to hydrate cookies from SharedPreferences.
     */
    @JvmStatic
    fun loadAuthCookiesFromPrefs(context: Context) {
        try {
            val prefs = context.getSharedPreferences("player_state", Context.MODE_PRIVATE)
            val cookie = prefs.getString("stream_last_youtube_web_cookie", "") ?: ""
            if (cookie.isNotBlank()) {
                authCookieHeader = cookie.trim()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load auth cookies from prefs", e)
        }
    }

    /**
     * Called when the user completes a YouTube Music web session login.
     */
    @JvmStatic
    fun setAuthCookies(cookieHeader: String?) {
        authCookieHeader = cookieHeader?.trim() ?: ""
        if (authCookieHeader.isNotBlank()) {
            // Clear cache so new cookies are used for fresh resolutions
            urlCache.clear()
        }
    }

    /**
     * Returns the current auth cookie header string.
     */
    @JvmStatic
    fun getAuthCookieHeader(): String = authCookieHeader

    /**
     * Invalidates cached URL for the given videoId.
     */
    @JvmStatic
    fun invalidate(videoId: String?) {
        if (videoId.isNullOrBlank()) return
        urlCache.remove(videoId)
        ProxyStreamResolver.invalidate(videoId)
    }

    /**
     * Main entry point: resolves a direct stream URL for the given [videoId].
     *
     * 1. Checks in-memory cache
     * 2. Tries native InnerTube API with auth cookies
     * 3. Falls back to ProxyStreamResolver on any failure
     *
     * This method performs network I/O — call from a background thread.
     */
    @JvmStatic
    @JvmOverloads
    fun resolveStreamUrl(context: Context, videoId: String?, forceAlternativeClient: Boolean = false): String? {
        if (videoId.isNullOrBlank()) return null

        // 1. Check cache
        urlCache[videoId]?.let { cached ->
            if (System.currentTimeMillis() - cached.timestamp < CACHE_TTL_MS) {
                return cached.url
            } else {
                urlCache.remove(videoId)
            }
        }

        // 2. Try native InnerTube (only if we have auth cookies)
        if (authCookieHeader.isNotBlank() && !forceAlternativeClient) {
            try {
                val nativeUrl = resolveViaInnertube(videoId)
                if (!nativeUrl.isNullOrBlank()) {
                    urlCache[videoId] = CachedStream(nativeUrl, System.currentTimeMillis())
                    return nativeUrl
                }
            } catch (e: Exception) {
                Log.w(TAG, "InnerTube failed for $videoId: ${e.javaClass.simpleName} — ${e.message}")
            }
        }

        // 3. Fallback to proxy servers
        return ProxyStreamResolver.resolveStreamUrl(videoId)
    }

    /**
     * Returns HTTP headers that should be set on the media source for the resolved URL.
     * For direct googlevideo.com URLs we need a proper User-Agent.
     */
    @JvmStatic
    fun getHeadersFor(videoId: String?): Map<String, String> {
        if (videoId.isNullOrBlank()) return emptyMap()
        val cached = urlCache[videoId] ?: return emptyMap()
        // Only add headers if URL is a direct googlevideo.com URL (not a proxy URL)
        return if (cached.url.contains("googlevideo.com")) {
            mapOf(
                "User-Agent" to "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36",
                "Origin" to "https://music.youtube.com",
                "Referer" to "https://music.youtube.com/"
            )
        } else {
            // It's a proxy fallback URL
            val proxyHeaders = mutableMapOf<String, String>(
                "User-Agent" to "Sleppify-Android/1.0"
            )
            if (authCookieHeader.isNotBlank()) {
                proxyHeaders["X-Youtube-Cookie"] = authCookieHeader
            }
            proxyHeaders
        }
    }

    // ─── Private InnerTube resolution ────────────────────────────────────

    /**
     * Calls the YouTube Music InnerTube player endpoint to get a direct stream URL.
     * Returns the best audio URL or null on any failure.
     */
    private fun resolveViaInnertube(videoId: String): String? {
        val payload = buildPlayerPayload(videoId)

        val conn = (URL(PLAYER_ENDPOINT).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 8000
            readTimeout = 12000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36")
            setRequestProperty("Origin", "https://music.youtube.com")
            setRequestProperty("Referer", "https://music.youtube.com/")
            if (authCookieHeader.isNotBlank()) {
                setRequestProperty("Cookie", authCookieHeader)
                val sapisidHash = generateSapisidHash()
                if (sapisidHash.isNotBlank()) {
                    setRequestProperty("Authorization", sapisidHash)
                }
            }
        }

        try {
            // Send POST body
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { writer ->
                writer.write(payload)
                writer.flush()
            }

            val responseCode = conn.responseCode
            if (responseCode != 200) {
                val errBody = try {
                    conn.errorStream?.bufferedReader()?.readText()?.take(500)
                } catch (_: Exception) { null }
                Log.w(TAG, "InnerTube HTTP $responseCode for $videoId: $errBody")
                return null
            }

            // Read response
            val responseBody = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { it.readText() }
            return parseStreamUrl(responseBody, videoId)
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Builds the JSON payload for the InnerTube player request.
     */
    private fun buildPlayerPayload(videoId: String): String {
        return """
        {
            "videoId": "$videoId",
            "context": {
                "client": {
                    "clientName": "$CLIENT_NAME",
                    "clientVersion": "$CLIENT_VERSION",
                    "hl": "es",
                    "gl": "US",
                    "experimentIds": [],
                    "experimentsToken": "",
                    "utcOffsetMinutes": -300,
                    "musicAppInfo": {
                        "pwaInstallabilityStatus": "PWA_INSTALLABILITY_STATUS_CAN_BE_INSTALLED",
                        "webDisplayMode": "WEB_DISPLAY_MODE_BROWSER",
                        "storeDigitalGoodsApiSupportStatus": {
                            "playStoreDigitalGoodsApiSupportStatus": "DIGITAL_GOODS_API_SUPPORT_STATUS_UNSUPPORTED"
                        }
                    }
                },
                "user": {
                    "lockedSafetyMode": false
                }
            },
            "playbackContext": {
                "contentPlaybackContext": {
                    "signatureTimestamp": 20073
                }
            }
        }
        """.trimIndent()
    }

    /**
     * Parses the InnerTube player response JSON and extracts the best audio stream URL.
     */
    private fun parseStreamUrl(responseBody: String, videoId: String): String? {
        val json = JSONObject(responseBody)

        // Check playability status
        val playabilityStatus = json.optJSONObject("playabilityStatus")
        val status = playabilityStatus?.optString("status", "") ?: ""
        if (status != "OK") {
            val reason = playabilityStatus?.optString("reason", "unknown") ?: "unknown"
            Log.w(TAG, "Video $videoId not playable: status=$status reason=$reason")
            return null
        }

        val streamingData = json.optJSONObject("streamingData") ?: run {
            Log.w(TAG, "No streamingData in response for $videoId")
            return null
        }

        // Try adaptiveFormats first (audio-only streams, best quality)
        val adaptiveFormats = streamingData.optJSONArray("adaptiveFormats")
        // Then try regular formats (pre-muxed streams like itag 18/22)
        val regularFormats = streamingData.optJSONArray("formats")

        // Build a map of itag → url from both arrays
        val itagUrlMap = mutableMapOf<Int, String>()

        for (array in listOfNotNull(adaptiveFormats, regularFormats)) {
            for (i in 0 until array.length()) {
                val format = array.optJSONObject(i) ?: continue
                val itag = format.optInt("itag", -1)
                if (itag < 0) continue

                // Get URL directly, or from signatureCipher
                var url = format.optString("url", "")

                if (url.isBlank()) {
                    // If URL is not directly available, the stream requires signature deciphering.
                    // Skip these — the proxy fallback will handle them via yt-dlp.
                    val cipher = format.optString("signatureCipher", "")
                    continue
                }

                itagUrlMap[itag] = url
            }
        }

        if (itagUrlMap.isEmpty()) {
            Log.w(TAG, "No playable formats found for $videoId (all may require signature deciphering)")
            return null
        }

        // Pick the best format by our preference order
        for (preferredItag in PREFERRED_ITAGS) {
            itagUrlMap[preferredItag]?.let { url ->
                return url
            }
        }

        // If none of our preferred itags matched, pick any audio format
        for (array in listOfNotNull(adaptiveFormats, regularFormats)) {
            for (i in 0 until array.length()) {
                val format = array.optJSONObject(i) ?: continue
                val mimeType = format.optString("mimeType", "")
                val url = format.optString("url", "")
                if (url.isNotBlank() && (mimeType.startsWith("audio/") || mimeType.contains("mp4"))) {
                    return url
                }
            }
        }

        Log.w(TAG, "Could not find any suitable stream for $videoId")
        return null
    }

    /**
     * Generates a SAPISIDHASH authentication token from the auth cookies.
     * This is used by YouTube to verify authenticated API requests.
     * Returns empty string if SAPISID cookie is not available.
     */
    private fun generateSapisidHash(): String {
        // Extract SAPISID or __Secure-3PAPISID from cookie header
        val sapisid = extractCookieValue("SAPISID")
            ?: extractCookieValue("__Secure-3PAPISID")
            ?: return ""

        val timestamp = System.currentTimeMillis() / 1000
        val origin = "https://music.youtube.com"
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

    /**
     * Extracts a specific cookie value from the stored cookie header.
     */
    private fun extractCookieValue(cookieName: String): String? {
        if (authCookieHeader.isBlank()) return null
        // Cookie header format: "NAME1=VALUE1; NAME2=VALUE2; ..."
        return authCookieHeader.split(";")
            .map { it.trim() }
            .firstOrNull { it.startsWith("$cookieName=") }
            ?.substringAfter("=")
            ?.trim()
    }
}
