package com.example.sleppify

import android.content.Context

/**
 * Stream URL resolver — delegates to ProxyStreamResolver (Sleppify proxy servers).
 * Kept as a thin wrapper to preserve existing call sites across the app.
 */
object InnertubeResolver {

    @JvmStatic
    fun loadAuthCookiesFromPrefs(context: Context) {
        // No-op: proxy resolver doesn't need auth cookies
    }

    @JvmStatic
    fun setAuthCookies(cookieHeader: String?) {
        // No-op
    }

    @JvmStatic
    fun getAuthCookieHeader(): String = ""

    @JvmStatic
    fun invalidate(videoId: String?) {
        ProxyStreamResolver.invalidate(videoId)
    }

    @JvmStatic
    @JvmOverloads
    fun resolveStreamUrl(context: Context, videoId: String?, forceAlternativeClient: Boolean = false): String? {
        return ProxyStreamResolver.resolveStreamUrl(videoId)
    }

    @JvmStatic
    fun getHeadersFor(videoId: String?): Map<String, String> {
        // googlevideo.com URLs don't require auth headers
        return emptyMap()
    }
}
