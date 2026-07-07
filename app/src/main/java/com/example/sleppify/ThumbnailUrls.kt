package com.example.sleppify

/**
 * YouTube/Google-CDN thumbnail URL sizing — the core trick the open-source YT Music clients
 * (Metrolist, RiMusic, ViMusic) use to keep lists smooth: request the image FROM THE CDN at the
 * size of the view instead of full-size, so the network transfer, disk cache and decode are all
 * tiny for a 48-56dp row thumbnail.
 *
 * Logic adapted from Metrolist's `String.resize()` (ui/utils/YouTubeUtils.kt), the most
 * actively-maintained variant (2026). Two host families support size params:
 *  - lh3/yt3.googleusercontent.com URLs ending in "=wW-hH...": rewritten to "=w{w}-h{h}-p-l90-rj"
 *    (p = smart crop, l90 = JPEG q90, rj = return JPEG). Matching BOTH lh3 and yt3 matters —
 *    YouTube migrated album art to yt3; an lh3-only match silently no-ops and the UI upscales a
 *    blurry ~60px image.
 *  - yt3.ggpht.com avatar URLs ending in "=sN": get "-s{size}" appended.
 *  - i.ytimg.com video thumbs have fixed variants and are returned untouched.
 */
object ThumbnailUrls {

    private val GUSERCONTENT_SIZED =
        Regex("https://(?:lh3|yt3)\\.googleusercontent\\.com/.*=w(\\d+)-h(\\d+).*")
    private val GGPHT_SIZED = Regex("https://yt3\\.ggpht\\.com/.*=s(\\d+)")

    /**
     * Returns [url] rewritten to request roughly [sizePx] x [sizePx] from the CDN, or the
     * original URL when the host doesn't support size params. Safe on null/blank.
     */
    @JvmStatic
    fun atSize(url: String?, sizePx: Int): String? {
        if (url.isNullOrBlank() || sizePx <= 0) return url

        GUSERCONTENT_SIZED.matchEntire(url)?.let {
            return url.substringBefore("=w") + "=w$sizePx-h$sizePx-p-l90-rj"
        }
        if (GGPHT_SIZED.matches(url)) {
            return "$url-s$sizePx"
        }
        return url
    }

    /** dp → px against the display metrics, for callers sizing a request to a dp-defined view. */
    @JvmStatic
    fun dpToPx(context: android.content.Context, dp: Int): Int {
        return (dp * context.resources.displayMetrics.density).toInt()
    }
}
