package com.example.sleppify

import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.LinkedHashMap
import java.util.Locale
import java.util.HashSet
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class YouTubeMusicService @JvmOverloads constructor(
    private val executor: ExecutorService = SHARED_EXECUTOR
) {

    private val TAG = "YouTubeMusicService"
    private val mainHandler = Handler(Looper.getMainLooper())

    // Autocomplete runs on its own tiny pool so a typed suggestion never waits in line behind a
    // heavy full-search / playlist-hydration job on the shared 3-thread executor. That queueing
    // was the "the suggestions lag while it loads library music" latency.
    private val suggestionsExecutor: ExecutorService = SUGGESTIONS_EXECUTOR

    // ----- Public interfaces -----

    interface SearchCallback {
        fun onSuccess(tracks: List<TrackResult>)
        fun onError(error: String)
    }

    interface SearchPageCallback {
        fun onSuccess(pageResult: SearchPageResult)
        fun onError(error: String)
    }

    interface SearchSuggestionsCallback {
        fun onSuccess(suggestions: List<String>)
        fun onError(error: String)
    }

    interface ArtistSearchCallback {
        fun onSuccess(artist: ArtistResult)
        fun onError(error: String)
    }

    interface PlaylistsCallback {
        fun onSuccess(playlists: List<PlaylistResult>)
        fun onError(error: String)
    }

    interface PlaylistTracksCallback {
        fun onSuccess(tracks: List<PlaylistTrackResult>)
        fun onError(error: String)
    }

    interface SimpleResultCallback {
        fun onResult(success: Boolean, error: String?)
    }

    interface PlaylistMetaCallback {
        fun onSuccess(playlist: PlaylistResult)
        fun onError(error: String)
    }

    interface VideoDurationCallback {
        fun onSuccess(durations: Map<String, String>)
        fun onError(error: String)
    }

    interface MixesCallback {
        fun onSuccess(mixes: List<MixResult>)
        fun onError(error: String)
    }

    interface MixTracksCallback {
        fun onSuccess(tracks: List<TrackResult>)
        fun onError(error: String)
    }

    interface ChannelNameCallback {
        fun onSuccess(channelName: String, channelPhotoUrl: String)
        fun onError(error: String)
    }

    interface HomeBrowseCallback {
        fun onSuccess(result: HomeBrowseResult)
        fun onError(error: String)
    }

    interface CoversRemixesCallback {
        fun onSuccess(tracks: List<TrackResult>)
        fun onError(error: String)
    }

    interface LibraryArtistsCallback {
        fun onSuccess(artists: List<ArtistResult>)
        fun onError(error: String)
    }

    interface ArtistPageCallback {
        fun onSuccess(page: ArtistPage)
        fun onError(error: String)
    }

    // ----- Public data classes (field-accessible from Java via @JvmField) -----

    class TrackResult @JvmOverloads constructor(
        @JvmField val resultType: String,
        @JvmField val contentId: String,
        rawTitle: String,
        rawSubtitle: String,
        @JvmField val thumbnailUrl: String,
        // Optional standalone duration ("2:12"); album rows carry it in a fixedColumn, most other
        // sources fold it into the subtitle and leave this empty. Default keeps every existing
        // 5-arg constructor call (Kotlin + Java) working unchanged.
        rawDuration: String = ""
    ) {
        @JvmField val title: String = androidx.core.text.HtmlCompat.fromHtml(rawTitle, androidx.core.text.HtmlCompat.FROM_HTML_MODE_LEGACY).toString()
        @JvmField val subtitle: String = androidx.core.text.HtmlCompat.fromHtml(rawSubtitle, androidx.core.text.HtmlCompat.FROM_HTML_MODE_LEGACY).toString()
        @JvmField val duration: String = rawDuration.trim()
        @JvmField
        val videoId: String = if ("video" == resultType) contentId else ""

        fun isVideo(): Boolean = "video" == resultType && !TextUtils.isEmpty(contentId)

        fun getWatchUrl(): String {
            if (TextUtils.isEmpty(contentId)) return "https://music.youtube.com/"
            return when (resultType) {
                "playlist" -> "https://music.youtube.com/playlist?list=" + safeUrlEncode(contentId)
                "channel" -> "https://www.youtube.com/channel/" + safeUrlEncode(contentId)
                else -> "https://music.youtube.com/watch?v=" + safeUrlEncode(contentId)
            }
        }
    }

    class SearchPageResult(
        @JvmField val tracks: List<TrackResult>,
        @JvmField val nextPageToken: String
    )

    class PlaylistResult(
        @JvmField val playlistId: String,
        rawTitle: String,
        rawOwnerName: String,
        @JvmField val itemCount: Int,
        @JvmField val thumbnailUrl: String,
        @JvmField val privacyStatus: String,
        @JvmField val publishedAt: String
    ) {
        @JvmField val title: String = androidx.core.text.HtmlCompat.fromHtml(rawTitle, androidx.core.text.HtmlCompat.FROM_HTML_MODE_LEGACY).toString()
        @JvmField val ownerName: String = androidx.core.text.HtmlCompat.fromHtml(rawOwnerName, androidx.core.text.HtmlCompat.FROM_HTML_MODE_LEGACY).toString()
        
        fun getOpenUrl(): String =
            "https://music.youtube.com/playlist?list=" + safeUrlEncode(playlistId)
    }

    /** A real YouTube Music artist page (from browse(channelId)). Fields are @JvmField for Java. */
    class ArtistPage(
        @JvmField val name: String,
        @JvmField val subtitle: String,
        @JvmField val thumbnailUrl: String,
        @JvmField val topSongs: List<TrackResult>,
        @JvmField val albums: List<PlaylistResult>,
        // browseId of the artist's full "songs" playlist (usually "VL<playlistId>"), taken from the
        // songs shelf's bottomEndpoint. Empty when the shelf carries no "more" link — the artist
        // page's "Ver más" button only shows when this is non-empty.
        @JvmField val moreSongsBrowseId: String = ""
    )

    class PlaylistTrackResult(
        @JvmField val videoId: String,
        rawTitle: String,
        rawArtist: String,
        @JvmField val duration: String,
        @JvmField val thumbnailUrl: String
    ) {
        @JvmField val title: String = androidx.core.text.HtmlCompat.fromHtml(rawTitle, androidx.core.text.HtmlCompat.FROM_HTML_MODE_LEGACY).toString()
        @JvmField val artist: String = androidx.core.text.HtmlCompat.fromHtml(rawArtist, androidx.core.text.HtmlCompat.FROM_HTML_MODE_LEGACY).toString()
    }

    class MixResult(
        @JvmField val playlistId: String,
        @JvmField val title: String,
        @JvmField val subtitle: String,
        @JvmField val thumbnailUrl: String,
        // navigationEndpoint `params` token captured from the home card. YT-generated mixes/recaps
        // (Replay/Archive/Recap) only return their tracks from the InnerTube /next (watch) — or a
        // browse — request when this exact token accompanies the playlistId; without it the panel
        // comes back empty (the "opens empty" / "No se pudo cargar la radio" bugs). Optional and
        // defaulted so every existing 4-arg MixRes(...) call site and old cache JSON stay valid.
        @JvmField val params: String = ""
    )

    class HomeBrowseResult(
        @JvmField val genericMixes: MutableList<MixResult>,
        @JvmField val personalMixes: MutableList<MixResult>,
        @JvmField val allSections: MutableList<HomeSection>,
        // Canciones del shelf "Selección rápida" del home (musicResponsiveListItemRenderer rows,
        // no cards) — el resto de secciones son playlists/mixes. Defaulted so the existing 3-arg
        // constructor call keeps working.
        @JvmField val quickPicks: MutableList<TrackResult> = mutableListOf()
    )

    class HomeSection(
        @JvmField val title: String,
        @JvmField val items: List<MixResult>
    )

    class ArtistResult(
        @JvmField val channelId: String,
        @JvmField val name: String,
        @JvmField val subtitle: String,
        @JvmField val thumbnailUrl: String
    )

    class ReplacementCandidate(
        @JvmField val videoId: String,
        @JvmField val title: String,
        @JvmField val artist: String,
        @JvmField val duration: String,
        @JvmField val thumbnailUrl: String,
        @JvmField val durationSeconds: Int
    )

    interface ReplacementCandidatesCallback {
        fun onSuccess(candidates: List<ReplacementCandidate>)
        fun onError(error: String)
    }

    // ----- Private data holders -----

    private class WatchPlaylistResult(
        val tracks: List<TrackResult>,
        val relatedBrowseId: String,
        val lyricsBrowseId: String
    )

    // ----- Public API -----

    /** Primary search via YouTube Music Innertube API — no API key needed, no quota. */
    fun searchTracksViaInnertube(query: String, maxResults: Int, cookieHeader: String = "", callback: SearchPageCallback) {
        val normalized = query.trim()
        if (normalized.isEmpty()) {
            callback.onError("Escribe algo para buscar.")
            return
        }
        executor.execute {
            try {
                val pageResult = performInnertubeSearchRequest(normalized, maxResults, cookieHeader)
                mainHandler.post { callback.onSuccess(pageResult) }
            } catch (e: Exception) {
                val error = e.message ?: "No se pudo completar la busqueda."
                mainHandler.post { callback.onError(error) }
            }
        }
    }

    /** YT Music autocomplete via music/get_search_suggestions — the same suggestions YTM shows. */
    fun fetchSearchSuggestions(query: String, cookieHeader: String = "", callback: SearchSuggestionsCallback) {
        val normalized = query.trim()
        if (normalized.isEmpty()) {
            callback.onSuccess(emptyList())
            return
        }
        suggestionsExecutor.execute {
            try {
                val clientContext = JSONObject().apply {
                    put("clientName", "WEB_REMIX")
                    put("clientVersion", buildClientVersion())
                    put("hl", "es")
                }
                val body = JSONObject().apply {
                    put("context", JSONObject().apply { put("client", clientContext) })
                    put("input", normalized)
                }.toString().toByteArray(StandardCharsets.UTF_8)

                val conn = URL("https://music.youtube.com/youtubei/v1/music/get_search_suggestions?prettyPrint=false")
                    .openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.connectTimeout = 8000
                conn.readTimeout = 8000
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.setRequestProperty("Accept", "application/json")
                conn.setRequestProperty("User-Agent", "Mozilla/5.0")
                conn.setRequestProperty("Origin", "https://music.youtube.com")
                conn.setRequestProperty("Referer", "https://music.youtube.com/")
                if (cookieHeader.isNotEmpty()) {
                    conn.setRequestProperty("Cookie", cookieHeader)
                }
                val root: JSONObject
                try {
                    conn.outputStream.use { it.write(body) }
                    val status = conn.responseCode
                    val responseBody = readResponse(conn, status >= 400)
                    if (status != HttpURLConnection.HTTP_OK) {
                        throw IllegalStateException("Suggestions error $status")
                    }
                    root = JSONObject(responseBody)
                } finally {
                    conn.disconnect()
                }

                val suggestions = mutableListOf<String>()
                val sections = root.optJSONArray("contents") ?: JSONArray()
                for (i in 0 until sections.length()) {
                    val items = sections.optJSONObject(i)
                        ?.optJSONObject("searchSuggestionsSectionRenderer")
                        ?.optJSONArray("contents") ?: continue
                    for (j in 0 until items.length()) {
                        val runs = items.optJSONObject(j)
                            ?.optJSONObject("searchSuggestionRenderer")
                            ?.optJSONObject("suggestion")
                            ?.optJSONArray("runs") ?: continue
                        val sb = StringBuilder()
                        for (k in 0 until runs.length()) {
                            sb.append(runs.optJSONObject(k)?.optString("text").orEmpty())
                        }
                        val text = sb.toString().trim()
                        if (text.isNotEmpty()) suggestions.add(text)
                    }
                }
                mainHandler.post { callback.onSuccess(suggestions) }
            } catch (e: Exception) {
                val error = e.message ?: "No se pudieron cargar sugerencias."
                mainHandler.post { callback.onError(error) }
            }
        }
    }

    /** Continue an Innertube search using a previously returned continuation token. */
    fun continueInnertubeSearch(continuationToken: String, maxResults: Int, cookieHeader: String = "", callback: SearchPageCallback) {
        if (continuationToken.isEmpty()) {
            callback.onSuccess(SearchPageResult(emptyList(), ""))
            return
        }
        executor.execute {
            try {
                val clientContext = JSONObject().apply {
                    put("clientName", "WEB_REMIX")
                    put("clientVersion", buildClientVersion())
                    put("hl", "en")
                }
                val endpoint = "https://music.youtube.com/youtubei/v1/search?prettyPrint=false"
                val body = JSONObject().apply {
                    put("context", JSONObject().apply { put("client", clientContext) })
                    put("continuation", continuationToken)
                }.toString().toByteArray(StandardCharsets.UTF_8)

                val conn = URL(endpoint).openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.connectTimeout = 12000
                conn.readTimeout = 15000
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.setRequestProperty("Accept", "application/json")
                conn.setRequestProperty("User-Agent", "Mozilla/5.0")
                conn.setRequestProperty("Origin", "https://music.youtube.com")
                conn.setRequestProperty("Referer", "https://music.youtube.com/")
                if (cookieHeader.isNotEmpty()) {
                    conn.setRequestProperty("Cookie", cookieHeader)
                }
                val contJson: JSONObject
                try {
                    conn.outputStream.use { it.write(body) }
                    val status = conn.responseCode
                    val responseBody = readResponse(conn, status >= 400)
                    if (status != HttpURLConnection.HTTP_OK) {
                        throw IllegalStateException("Innertube continuation error $status")
                    }
                    contJson = JSONObject(responseBody)
                } finally {
                    conn.disconnect()
                }
                val tracks = parseInnertubeSearchContinuation(contJson, maxResults)
                val nextToken = extractSearchContinuationTokenFromContinuation(contJson) ?: ""
                mainHandler.post { callback.onSuccess(SearchPageResult(tracks, nextToken)) }
            } catch (e: Exception) {
                val error = e.message ?: "No se pudo continuar la busqueda."
                mainHandler.post { callback.onError(error) }
            }
        }
    }

    @Throws(Exception::class)
    private fun performInnertubeSearchRequest(query: String, maxResults: Int, cookieHeader: String = ""): SearchPageResult {
        // Detectar si el usuario busca contenido tipo video (subtítulos, letras, en vivo, covers, karaoke, etc.)
        val lower = query.lowercase(Locale.ROOT)
        val isVideoQuery = lower.contains("sub") ||
                           lower.contains("español") ||
                           lower.contains("spanish") ||
                           lower.contains("lyrics") ||
                           lower.contains("letra") ||
                           lower.contains("cover") ||
                           lower.contains("live") ||
                           lower.contains("en vivo") ||
                           lower.contains("traducido") ||
                           lower.contains("karaoke") ||
                           lower.contains("video") ||
                           lower.contains("clip") ||
                           lower.contains("subtitulado") ||
                           lower.contains("traduccion")

        // Params from ytmusicapi reference (no URL-encoding needed — body is JSON):
        //   songs:  EgWKAQIIAWoMEA4QChADEAQQCRAF
        //   videos: EgWKAQIQAWoMEA4QChADEAQQCRAF
        val searchParams = if (isVideoQuery) {
            "EgWKAQIQAWoMEA4QChADEAQQCRAF" // Videos filter
        } else {
            "EgWKAQIIAWoMEA4QChADEAQQCRAF" // Songs filter (default)
        }

        // Both legs run CONCURRENTLY — they are independent requests, and running them back to
        // back doubled first-page latency. The filtered leg goes on its own thread (not the
        // shared executor, to avoid starving the pool this method already runs on).
        //
        // Filtered (Songs/Videos) leg: a deep single-type shelf. It supplies the continuation
        // token for infinite scroll plus extra tracks beyond the unfiltered page (appended at
        // the tail, deduped, so it never disturbs the YTM page order).
        var filteredResults: List<TrackResult> = emptyList()
        var filteredToken = ""
        var filteredError: Exception? = null
        val filteredThread = Thread({
            try {
                val filteredJson = postInnertubeSearch(query, searchParams, cookieHeader)
                filteredResults = parseInnertubeSearchResults(filteredJson, maxResults)
                filteredToken = extractSearchContinuationToken(filteredJson) ?: ""
            } catch (e: Exception) {
                filteredError = e
                Log.w("YouTubeMusicService", "[INNERTUBE] Filtered search failed: ${e.message}")
            }
        }, "innertube-filtered-search")
        filteredThread.start()

        // Unfiltered leg (inline): its shelf order (top result → songs → videos) is exactly what
        // the YT Music search page shows, so it drives the visible ranking of the first page.
        var pageResults: List<TrackResult> = emptyList()
        var pageToken = ""
        var pageError: Exception? = null
        try {
            val pageJson = postInnertubeSearch(query, null, cookieHeader)
            pageResults = parseInnertubeSearchResults(pageJson, maxResults)
            pageToken = extractSearchContinuationToken(pageJson) ?: ""
            if (pageResults.isEmpty()) logEmptyInnertubeResponse(pageJson)
        } catch (e: Exception) {
            pageError = e
            Log.w("YouTubeMusicService", "[INNERTUBE] Unfiltered search failed: ${e.message}")
        }

        try {
            // Bounded by the connection's own 14s/18s timeouts; join guards against a hang.
            filteredThread.join(40_000)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }

        // Both legs failed → propagate so the caller can fall back to the Data API.
        if (pageError != null && filteredError != null) throw pageError

        val results = pageResults.toMutableList()
        val existingIds = results.mapTo(HashSet()) { it.videoId }
        for (track in filteredResults) {
            if (track.videoId !in existingIds) {
                results.add(track)
                existingIds.add(track.videoId)
            }
        }
        // One leg errored and the surviving leg parsed nothing → treat as failure too, so the
        // caller's Data-API fallback still fires instead of showing an empty "no results" page.
        if (results.isEmpty()) {
            (pageError ?: filteredError)?.let { throw it }
        }
        // Prefer the filtered continuation: it pages through the full Songs/Videos shelf.
        val continuationToken = filteredToken.ifEmpty { pageToken }

        // Return first page immediately — further pages loaded via continueInnertubeSearch + scroll
        return SearchPageResult(results, continuationToken)
    }

    /** Resolve an artist's channelId by name via an Artists-filtered Innertube search. */
    fun searchArtistByName(name: String, cookieHeader: String = "", callback: ArtistSearchCallback) {
        val normalized = name.trim()
        if (normalized.isEmpty()) {
            callback.onError("Artista vacío.")
            return
        }
        executor.execute {
            try {
                // ytmusicapi artists filter param
                val root = postInnertubeSearch(normalized, "EgWKAQIgAWoMEA4QChADEAQQCRAF", cookieHeader)
                val artist = extractFirstArtistFromSearch(root)
                if (artist != null && artist.channelId.startsWith("UC")) {
                    mainHandler.post { callback.onSuccess(artist) }
                } else {
                    mainHandler.post { callback.onError("No se encontró el artista.") }
                }
            } catch (e: Exception) {
                val error = e.message ?: "No se encontró el artista."
                mainHandler.post { callback.onError(error) }
            }
        }
    }

    private fun extractFirstArtistFromSearch(root: JSONObject): ArtistResult? {
        val rootContents = root.optJSONObject("contents") ?: return null
        val allSectionContents = mutableListOf<JSONArray>()
        val tabbed = rootContents.optJSONObject("tabbedSearchResultsRenderer")
        if (tabbed != null) {
            val tabs = tabbed.optJSONArray("tabs") ?: JSONArray()
            for (t in 0 until tabs.length()) {
                val c = tabs.optJSONObject(t)
                    ?.optJSONObject("tabRenderer")
                    ?.optJSONObject("content")
                    ?.optJSONObject("sectionListRenderer")
                    ?.optJSONArray("contents") ?: continue
                allSectionContents.add(c)
            }
        } else {
            rootContents.optJSONObject("sectionListRenderer")
                ?.optJSONArray("contents")?.let { allSectionContents.add(it) }
        }
        for (contents in allSectionContents) {
            for (c in 0 until contents.length()) {
                val items = contents.optJSONObject(c)
                    ?.optJSONObject("musicShelfRenderer")
                    ?.optJSONArray("contents") ?: continue
                for (i in 0 until items.length()) {
                    val renderer = items.optJSONObject(i)
                        ?.optJSONObject("musicResponsiveListItemRenderer") ?: continue
                    val artist = parseArtistFromRenderer(renderer) ?: continue
                    if (artist.channelId.startsWith("UC")) return artist
                }
            }
        }
        return null
    }

    /** POST one Innertube search request (optionally filtered) and return the parsed response. */
    @Throws(Exception::class)
    private fun postInnertubeSearch(query: String, searchParams: String?, cookieHeader: String): JSONObject {
        val clientContext = JSONObject().apply {
            put("clientName", "WEB_REMIX")
            put("clientVersion", buildClientVersion())
            put("hl", "en")
        }
        val body = JSONObject().apply {
            put("context", JSONObject().apply { put("client", clientContext) })
            put("query", query)
            if (searchParams != null) put("params", searchParams)
        }.toString().toByteArray(StandardCharsets.UTF_8)

        val connection = URL("https://music.youtube.com/youtubei/v1/search?prettyPrint=false")
            .openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.connectTimeout = 14000
        connection.readTimeout = 18000
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("User-Agent", "Mozilla/5.0")
        connection.setRequestProperty("Origin", "https://music.youtube.com")
        connection.setRequestProperty("Referer", "https://music.youtube.com/")
        if (cookieHeader.isNotEmpty()) {
            connection.setRequestProperty("Cookie", cookieHeader)
        }
        try {
            connection.outputStream.use { it.write(body) }
            val statusCode = connection.responseCode
            val responseBody = readResponse(connection, statusCode >= 400)
            if (statusCode != HttpURLConnection.HTTP_OK) {
                throw IllegalStateException("Innertube search error $statusCode")
            }
            return JSONObject(responseBody)
        } finally {
            connection.disconnect()
        }
    }

    private fun logEmptyInnertubeResponse(rootJson: JSONObject) {
        val topKeys = rootJson.keys().asSequence().toList()
        val contentsKeys = rootJson.optJSONObject("contents")?.keys()?.asSequence()?.toList()
        Log.w("YouTubeMusicService", "[INNERTUBE_DBG] 0 results. topKeys=$topKeys contentsKeys=$contentsKeys")
        Log.w("YouTubeMusicService", "[INNERTUBE_DBG] raw=${rootJson.toString().take(2000)}")
    }

    private fun extractSearchContinuationToken(root: JSONObject): String? {
        val rootContents = root.optJSONObject("contents") ?: return null

        // Collect all sectionListRenderer content arrays (tabbed vs flat)
        val allSections = mutableListOf<JSONArray>()

        val tabbed = rootContents.optJSONObject("tabbedSearchResultsRenderer")
        if (tabbed != null) {
            val tabs = tabbed.optJSONArray("tabs") ?: JSONArray()
            for (t in 0 until tabs.length()) {
                val c = tabs.optJSONObject(t)
                    ?.optJSONObject("tabRenderer")
                    ?.optJSONObject("content")
                    ?.optJSONObject("sectionListRenderer")
                    ?.optJSONArray("contents") ?: continue
                allSections.add(c)
            }
        } else {
            val flat = rootContents.optJSONObject("sectionListRenderer")
                ?.optJSONArray("contents")
            if (flat != null) allSections.add(flat)
        }

        for (contents in allSections) {
            for (c in 0 until contents.length()) {
                val shelf = contents.optJSONObject(c)
                    ?.optJSONObject("musicShelfRenderer") ?: continue
                val token = shelf.optJSONArray("continuations")
                    ?.optJSONObject(0)
                    ?.optJSONObject("nextContinuationData")
                    ?.optString("continuation", "")?.takeIf { it.isNotEmpty() }
                if (token != null) return token
            }
        }
        return null
    }

    private fun extractSearchContinuationTokenFromContinuation(contJson: JSONObject): String? {
        return contJson.optJSONObject("continuationContents")
            ?.optJSONObject("musicShelfContinuation")
            ?.optJSONArray("continuations")
            ?.optJSONObject(0)
            ?.optJSONObject("nextContinuationData")
            ?.optString("continuation", "")?.takeIf { it.isNotEmpty() }
    }

    private fun parseInnertubeSearchContinuation(contJson: JSONObject, limit: Int): List<TrackResult> {
        val results = mutableListOf<TrackResult>()
        try {
            val shelf = contJson.optJSONObject("continuationContents")
                ?.optJSONObject("musicShelfContinuation")
                ?.optJSONArray("contents") ?: return results
            for (i in 0 until shelf.length()) {
                if (results.size >= limit) break
                val renderer = shelf.optJSONObject(i)
                    ?.optJSONObject("musicResponsiveListItemRenderer") ?: continue
                val videoId = renderer.optJSONObject("playlistItemData")
                    ?.optString("videoId", "")?.trim() ?: ""
                if (videoId.isEmpty()) continue

                val flexColumns = renderer.optJSONArray("flexColumns")
                var title = ""
                var artist = ""
                if (flexColumns != null && flexColumns.length() > 0) {
                    title = flexColumns.optJSONObject(0)
                        ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
                        ?.optJSONObject("text")
                        ?.optJSONArray("runs")
                        ?.optJSONObject(0)
                        ?.optString("text", "") ?: ""
                    if (flexColumns.length() > 1) {
                        val runs = flexColumns.optJSONObject(1)
                            ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
                            ?.optJSONObject("text")
                            ?.optJSONArray("runs")
                        if (runs != null && runs.length() > 0) {
                            val sb = StringBuilder()
                            for (r in 0 until runs.length()) {
                                sb.append(runs.optJSONObject(r)?.optString("text", "") ?: "")
                            }
                            artist = sb.toString()
                        }
                    }
                }
                if (title.isEmpty()) continue

                val thumbs = renderer.optJSONObject("thumbnail")
                    ?.optJSONObject("musicThumbnailRenderer")
                    ?.optJSONObject("thumbnail")
                    ?.optJSONArray("thumbnails")
                val thumbUrl = thumbs?.let {
                    it.optJSONObject(it.length() - 1)?.optString("url", "") ?: ""
                } ?: ""

                // Same as parseInnertubeSearchResults: the length lives in a fixedColumn, not the
                // subtitle. Without this, scroll-loaded (page 2+) rows show "Artist" with no duration.
                val duration = artistRunsToText(
                    renderer.optJSONArray("fixedColumns")?.optJSONObject(0)
                        ?.optJSONObject("musicResponsiveListItemFixedColumnRenderer")?.optJSONObject("text")
                )

                results.add(TrackResult("video", videoId, title, artist, thumbUrl, duration))
            }
        } catch (e: Exception) {
            Log.w("YouTubeMusicService", "parseInnertubeSearchContinuation error: ${e.message}")
        }
        return results
    }

    fun fetchMyPlaylists(accessToken: String, maxResults: Int, callback: PlaylistsCallback) {
        // Migrado de la Data API OAuth a InnerTube browse (FEmusic_liked_playlists) con la cookie web.
        // El accessToken queda ignorado (compat de firma). Sin cuota.
        val cookieHeader = StreamResolver.getAuthCookieHeader().trim()
        if (cookieHeader.isEmpty()) {
            callback.onError("No hay sesión web para cargar la biblioteca.")
            return
        }

        executor.execute {
            try {
                val playlists = performLibraryPlaylistsBrowseRequest(cookieHeader)
                mainHandler.post { callback.onSuccess(playlists) }
            } catch (e: Exception) {
                val error = e.message ?: "No se pudo cargar la biblioteca."
                mainHandler.post { callback.onError(error) }
            }
        }
    }

    fun insertTrackToPlaylist(
        accessToken: String,
        playlistId: String,
        videoId: String,
        callback: SimpleResultCallback
    ) {
        val token = accessToken.trim()
        // El añadir a playlist ahora va por InnerTube (cookie web), no por el token OAuth.
        if (playlistId.isEmpty() || videoId.isEmpty()) {
            callback.onResult(false, "Parametros invalidos.")
            return
        }

        executor.execute {
            try {
                val ok = performInsertPlaylistTrackRequest(token, playlistId, videoId)
                mainHandler.post { callback.onResult(ok, if (ok) null else "No se pudo añadir a la playlist.") }
            } catch (e: Exception) {
                Log.e("YouTubeMusicService", "Error inserting playlist track", e)
                mainHandler.post { callback.onResult(false, e.message) }
            }
        }
    }

    fun fetchPlaylistTracks(
        accessToken: String,
        playlistId: String,
        maxResults: Int,
        callback: PlaylistTracksCallback
    ) {
        val normalizedPlaylistId = playlistId.trim()
        if (normalizedPlaylistId.isEmpty()) {
            callback.onError("Playlist invalida.")
            return
        }

        // Migrado de la Data API OAuth a InnerTube browse (VL<id>, con continuaciones) usando la
        // cookie web. "Me gusta" (SPECIAL_LIKED_VIDEOS_ID) resuelve a la playlist LM (Liked Music).
        val cookieHeader = StreamResolver.getAuthCookieHeader().trim()
        if (cookieHeader.isEmpty()) {
            callback.onError("No hay sesión web para cargar canciones.")
            return
        }

        executor.execute {
            try {
                val resolvedId =
                    if (SPECIAL_LIKED_VIDEOS_ID == normalizedPlaylistId) "LM" else normalizedPlaylistId
                val tracks = performPlaylistTracksBrowseRequest(cookieHeader, resolvedId, maxOf(1, maxResults))
                mainHandler.post { callback.onSuccess(tracks) }
            } catch (e: Exception) {
                val error = e.message ?: "No se pudo cargar canciones."
                mainHandler.post { callback.onError(error) }
            }
        }
    }

    /**
     * Fallback loader for playlists the OAuth Data API can't read (YTM server-generated RECAP /
     * auto-mix lists): resolves them through the InnerTube browse endpoint with the web cookie,
     * exactly as MPRE albums bypass OAuth. browseId is 'VL' + the bare playlist id; the response's
     * musicPlaylistShelfRenderer is parsed by the same [parseAlbumTracks] path the albums use.
     * No continuation paging — the initial shelf covers the small generated playlists this targets.
     */
    fun fetchPlaylistTracksViaBrowse(
        cookieHeader: String,
        playlistId: String,
        callback: PlaylistTracksCallback
    ) {
        fetchPlaylistTracksViaBrowse(cookieHeader, playlistId, "", callback)
    }

    /**
     * @param params navigationEndpoint token from the home card. A generated RECAP/auto-mix list is
     *   browse-readable only with this token; it's added to the browse body when present. Empty for
     *   ordinary auto-generated playlists (RDCLAK…), whose bare VL+id browse already returns tracks.
     */
    fun fetchPlaylistTracksViaBrowse(
        cookieHeader: String,
        playlistId: String,
        params: String,
        callback: PlaylistTracksCallback
    ) {
        val id = playlistId.trim()
        if (id.isEmpty()) {
            callback.onError("Playlist invalida.")
            return
        }
        val browseParams = params.trim()
        executor.execute {
            try {
                val browseId = if (id.startsWith("VL")) id else "VL$id"
                val endpoint = "https://music.youtube.com/youtubei/v1/browse?prettyPrint=false"
                val body = JSONObject().apply {
                    put("context", JSONObject().apply {
                        put("client", JSONObject().apply {
                            put("clientName", "WEB_REMIX")
                            put("clientVersion", buildClientVersion())
                            put("hl", "es")
                        })
                    })
                    put("browseId", browseId)
                    if (browseParams.isNotEmpty()) put("params", browseParams)
                }.toString().toByteArray(StandardCharsets.UTF_8)
                val responseBody = postInnerTubeBrowse(endpoint, body, cookieHeader.trim())
                val rows = parseAlbumTracks(JSONObject(responseBody))
                val result = ArrayList<PlaylistTrackResult>()
                for (t in rows) {
                    if (t.videoId.isEmpty()) continue
                    val duration = if (TextUtils.isEmpty(t.duration)) "--:--" else t.duration
                    result.add(PlaylistTrackResult(t.videoId, t.title, t.subtitle, duration, t.thumbnailUrl))
                }
                mainHandler.post { callback.onSuccess(result) }
            } catch (e: Exception) {
                mainHandler.post { callback.onError(e.message ?: "No se pudo cargar la playlist.") }
            }
        }
    }

    fun fetchPlaylistMeta(
        accessToken: String,
        playlistId: String,
        callback: PlaylistMetaCallback
    ) {
        // La metadata detallada opcional (visibilidad/fecha de publicación) venía de la Data API OAuth.
        // Al eliminar la dependencia de la API ya no se consulta; el encabezado conserva su metadata
        // de respaldo (el conteo de canciones se deriva de las canciones cargadas vía browse).
        callback.onError("Metadata detallada no disponible sin la Data API.")
    }

    fun getYoutubeReadonlyScope(): String = YT_SCOPE_READONLY

    fun fetchHomeMixes(cookieHeader: String, callback: MixesCallback) {
        if (cookieHeader.isEmpty()) {
            callback.onError("No hay sesión web para cargar mixes.")
            return
        }
        executor.execute {
            try {
                val mixes = performHomeMixesBrowseRequest(cookieHeader)
                mainHandler.post { callback.onSuccess(mixes) }
            } catch (e: Exception) {
                val error = e.message ?: "No se pudieron cargar los mixes."
                mainHandler.post { callback.onError(error) }
            }
        }
    }

    fun fetchMixTracks(cookieHeader: String, playlistId: String, callback: MixTracksCallback) {
        fetchMixTracks(cookieHeader, playlistId, "", callback)
    }

    /**
     * @param params navigationEndpoint token captured from the home card. Empty for a plain song
     *   radio (RDAMVM…, which resolves off its seed videoId alone); non-empty for YT-generated
     *   personal mixes (Replay/Archive/Recap) that /next only fills when the token is forwarded.
     */
    fun fetchMixTracks(cookieHeader: String, playlistId: String, params: String, callback: MixTracksCallback) {
        if (playlistId.isEmpty()) {
            callback.onError("Datos insuficientes para cargar tracks del mix.")
            return
        }
        executor.execute {
            try {
                val tracks = performMixTracksRequest(cookieHeader.trim(), playlistId.trim(), params.trim())
                mainHandler.post { callback.onSuccess(tracks) }
            } catch (e: Exception) {
                val error = e.message ?: "No se pudieron cargar tracks del mix."
                mainHandler.post { callback.onError(error) }
            }
        }
    }

    fun fetchYouTubeChannelName(cookieHeader: String, callback: ChannelNameCallback) {
        if (cookieHeader.isEmpty()) {
            callback.onError("No hay sesión web.")
            return
        }
        executor.execute {
            try {
                val result = performAccountMenuRequest(cookieHeader)
                mainHandler.post { callback.onSuccess(result.first, result.second) }
            } catch (e: Exception) {
                mainHandler.post { callback.onError(e.message ?: "Error obteniendo nombre de canal.") }
            }
        }
    }

    fun fetchHomeBrowse(cookieHeader: String, callback: HomeBrowseCallback) {
        if (cookieHeader.isEmpty()) {
            callback.onError("No hay sesión web para cargar home.")
            return
        }
        executor.execute {
            try {
                val result = performHomeBrowseFullRequest(cookieHeader)
                mainHandler.post { callback.onSuccess(result) }
            } catch (e: Exception) {
                mainHandler.post { callback.onError(e.message ?: "Error cargando home.") }
            }
        }
    }

    fun fetchLibraryArtists(cookieHeader: String, callback: LibraryArtistsCallback) {
        if (cookieHeader.isEmpty()) {
            callback.onError("No hay sesión web para cargar artistas.")
            return
        }
        executor.execute {
            try {
                val artists = performLibraryArtistsBrowseRequest(cookieHeader)
                mainHandler.post { callback.onSuccess(artists) }
            } catch (e: Exception) {
                mainHandler.post { callback.onError(e.message ?: "Error cargando artistas.") }
            }
        }
    }

    fun fetchCoversAndRemixes(cookieHeader: String, trackTitles: List<String>, callback: CoversRemixesCallback) {
        if (cookieHeader.isEmpty() || trackTitles.isEmpty()) {
            callback.onSuccess(emptyList())
            return
        }
        executor.execute {
            try {
                val allResults = mutableListOf<TrackResult>()
                val seenIds = mutableSetOf<String>()
                for (title in trackTitles.take(5)) {
                    val queries = listOf("$title remix")
                    for (q in queries) {
                        try {
                            val results = performInnertubeSearch(cookieHeader, q, 5)
                            for (r in results) {
                                if (r.videoId.isNotEmpty() && seenIds.add(r.videoId)) {
                                    allResults.add(r)
                                }
                            }
                        } catch (e: Exception) { Log.w(TAG, "Failed to parse search result item", e) }
                        if (allResults.size >= 20) break
                    }
                    if (allResults.size >= 20) break
                }
                mainHandler.post { callback.onSuccess(allResults) }
            } catch (e: Exception) {
                mainHandler.post { callback.onError(e.message ?: "Error buscando covers/remixes.") }
            }
        }
    }

    /**
     * Fetches durations for a list of video IDs using OAuth token.
     * Returns a map of videoId -> formatted duration (e.g. "3:45").
     */
    fun fetchVideoDurations(
        accessToken: String,
        videoIds: List<String>,
        callback: VideoDurationCallback
    ) {
        if (videoIds.isEmpty()) {
            callback.onSuccess(emptyMap())
            return
        }

        executor.execute {
            try {
                val result = HashMap<String, String>()
                // Vía InnerTube /player (ANDROID_VR) — sin cuota. Cap para no disparar cientos de
                // peticiones en listas enormes (es un enriquecimiento best-effort de duraciones).
                for (rawId in videoIds.take(60)) {
                    val id = rawId.trim()
                    if (id.isEmpty()) continue
                    val formatted = fetchDurationViaInnertubePlayer(id)
                    if (formatted.isNotEmpty() && formatted != "--:--") {
                        result[id] = formatted
                    }
                }
                mainHandler.post { callback.onSuccess(result) }
            } catch (e: Exception) {
                val error = e.message ?: "No se pudo obtener duraciones."
                mainHandler.post { callback.onError(error) }
            }
        }
    }

    /** Duración de un video vía InnerTube /player (cliente ANDROID_VR, sin PO token ni cuota). */
    private fun fetchDurationViaInnertubePlayer(videoId: String): String {
        return try {
            val body = JSONObject().apply {
                put("context", JSONObject().put("client", JSONObject().apply {
                    put("clientName", "ANDROID_VR")
                    put("clientVersion", "1.62.27")
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
            }.toString().toByteArray(StandardCharsets.UTF_8)

            val connection = (URL("https://www.youtube.com/youtubei/v1/player?prettyPrint=false")
                .openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 8000
                readTimeout = 10000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty(
                    "User-Agent",
                    "com.google.android.apps.youtube.vr.oculus/1.62.27 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip"
                )
            }
            val responseText = try {
                connection.outputStream.use { it.write(body) }
                if (connection.responseCode != HttpURLConnection.HTTP_OK) return ""
                connection.inputStream.bufferedReader().use { it.readText() }
            } finally {
                connection.disconnect()
            }
            val root = JSONObject(responseText)
            val secs = root.optJSONObject("videoDetails")?.optString("lengthSeconds", "")?.toIntOrNull() ?: return ""
            if (secs <= 0) return ""
            val h = secs / 3600
            val m = (secs % 3600) / 60
            val s = secs % 60
            if (h > 0) String.format(java.util.Locale.US, "%d:%02d:%02d", h, m, s)
            else String.format(java.util.Locale.US, "%d:%02d", m, s)
        } catch (e: Exception) {
            ""
        }
    }

    private fun readResponse(connection: HttpURLConnection, fromErrorStream: Boolean): String {
        return try {
            val stream = if (fromErrorStream) {
                connection.errorStream ?: return ""
            } else {
                connection.inputStream ?: return ""
            }
            BufferedReader(InputStreamReader(stream, StandardCharsets.UTF_8)).use { reader ->
                val builder = StringBuilder()
                var line = reader.readLine()
                while (line != null) {
                    builder.append(line)
                    line = reader.readLine()
                }
                builder.toString()
            }
        } catch (_: Exception) {
            ""
        }
    }

    @Throws(Exception::class)
    private fun performInsertPlaylistTrackRequest(
        token: String,
        playlistId: String,
        videoId: String
    ): Boolean {
        // Migrado de la Data API OAuth (/playlistItems) a InnerTube edit_playlist con la cookie web
        // (firmada con SAPISIDHASH por postInnerTubeBrowse). El token queda ignorado. Sin cuota.
        val cookieHeader = StreamResolver.getAuthCookieHeader().trim()
        if (cookieHeader.isEmpty()) return false
        val bareId = if (playlistId.length > 2 && playlistId.startsWith("VL")) playlistId.substring(2) else playlistId
        val endpoint = "https://music.youtube.com/youtubei/v1/browse/edit_playlist?prettyPrint=false"
        val body = JSONObject().apply {
            put("context", JSONObject().apply {
                put("client", JSONObject().apply {
                    put("clientName", "WEB_REMIX")
                    put("clientVersion", buildClientVersion())
                    put("hl", "es")
                })
            })
            put("playlistId", bareId)
            put("actions", JSONArray().apply {
                put(JSONObject().apply {
                    put("action", "ACTION_ADD_VIDEO")
                    put("addedVideoId", videoId)
                })
            })
        }.toString().toByteArray(StandardCharsets.UTF_8)
        return try {
            val response = postInnerTubeBrowse(endpoint, body, cookieHeader)
            JSONObject(response).optString("status", "").equals("STATUS_SUCCEEDED", ignoreCase = true)
        } catch (e: Exception) {
            Log.w(TAG, "edit_playlist add failed: ${e.message}")
            false
        }
    }

    @Throws(Exception::class)
    private fun performHomeMixesBrowseRequest(cookieHeader: String): List<MixResult> {
        val endpoint = "https://music.youtube.com/youtubei/v1/browse?prettyPrint=false"
        val body = JSONObject().apply {
            put("context", JSONObject().apply {
                put("client", JSONObject().apply {
                    put("clientName", "WEB_REMIX")
                    put("clientVersion", buildClientVersion())
                    put("hl", "es")
                })
            })
            put("browseId", "FEmusic_home")
        }.toString().toByteArray(StandardCharsets.UTF_8)

        val url = URL(endpoint)
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.connectTimeout = 14000
        connection.readTimeout = 18000
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("User-Agent", "Mozilla/5.0")
        connection.setRequestProperty("Origin", "https://music.youtube.com")
        connection.setRequestProperty("Referer", "https://music.youtube.com/")
        if (cookieHeader.isNotEmpty()) {
            connection.setRequestProperty("Cookie", cookieHeader)
        }
        try {
            connection.outputStream.use { it.write(body) }
            val statusCode = connection.responseCode
            val responseBody = readResponse(connection, statusCode >= 400)
            if (statusCode != HttpURLConnection.HTTP_OK) {
                throw IllegalStateException("Browse home error $statusCode")
            }
            return parseHomeMixes(JSONObject(responseBody))
        } finally {
            connection.disconnect()
        }
    }

    private fun parseHomeMixes(root: JSONObject): List<MixResult> {
        val mixes = mutableListOf<MixResult>()
        try {
            val tabs = root.optJSONObject("contents")
                ?.optJSONObject("singleColumnBrowseResultsRenderer")
                ?.optJSONArray("tabs")
                ?: return mixes

            val tabContent = tabs.optJSONObject(0)
                ?.optJSONObject("tabRenderer")
                ?.optJSONObject("content")
                ?.optJSONObject("sectionListRenderer")
                ?.optJSONArray("contents")
                ?: return mixes

            for (s in 0 until tabContent.length()) {
                val section = tabContent.optJSONObject(s) ?: continue
                val carousel = section.optJSONObject("musicCarouselShelfRenderer") ?: continue
                val headerTitle = carousel.optJSONObject("header")
                    ?.optJSONObject("musicCarouselShelfBasicHeaderRenderer")
                    ?.optJSONObject("title")
                    ?.optJSONArray("runs")
                    ?.optJSONObject(0)
                    ?.optString("text", "") ?: ""

                val lower = headerTitle.lowercase()
                val isMixSection = lower.contains("mix") || lower.contains("escucha")
                        || lower.contains("tu")
                        || lower.contains("para ti")
                        || lower.contains("listen again")
                        || lower.contains("your")

                val items = carousel.optJSONArray("contents") ?: continue
                for (i in 0 until items.length()) {
                    val item = items.optJSONObject(i) ?: continue
                    val renderer = item.optJSONObject("musicTwoRowItemRenderer") ?: continue

                    val browseEndpoint = renderer.optJSONObject("navigationEndpoint")
                        ?.optJSONObject("watchPlaylistEndpoint")
                    val watchEndpoint = renderer.optJSONObject("navigationEndpoint")
                        ?.optJSONObject("watchEndpoint")
                    val browseEp = renderer.optJSONObject("navigationEndpoint")
                        ?.optJSONObject("browseEndpoint")

                    var playlistId = browseEndpoint?.optString("playlistId", "") ?: ""
                    if (playlistId.isEmpty()) playlistId = watchEndpoint?.optString("playlistId", "") ?: ""
                    if (playlistId.isEmpty()) playlistId = browseEp?.optString("browseId", "") ?: ""

                    // Same params capture as parseCarouselIntoResult (generated mixes need it for /next).
                    var navParams = browseEndpoint?.optString("params", "") ?: ""
                    if (navParams.isEmpty()) navParams = watchEndpoint?.optString("params", "") ?: ""
                    if (navParams.isEmpty()) navParams = browseEp?.optString("params", "") ?: ""

                    val title = renderer.optJSONObject("title")
                        ?.optJSONArray("runs")
                        ?.optJSONObject(0)
                        ?.optString("text", "") ?: ""

                    val subtitleRuns = renderer.optJSONObject("subtitle")?.optJSONArray("runs")
                    val subtitle = buildString {
                        if (subtitleRuns != null) {
                            for (r in 0 until subtitleRuns.length()) {
                                append(subtitleRuns.optJSONObject(r)?.optString("text", "") ?: "")
                            }
                        }
                    }

                    val thumbnails = renderer.optJSONObject("thumbnailRenderer")
                        ?.optJSONObject("musicThumbnailRenderer")
                        ?.optJSONObject("thumbnail")
                        ?.optJSONArray("thumbnails")
                    val thumbUrl = thumbnails?.let {
                        it.optJSONObject(it.length() - 1)?.optString("url", "") ?: ""
                    } ?: ""

                    if (playlistId.isEmpty() && title.isEmpty()) continue

                    val titleLower = title.lowercase()
                    val isMix = isMixSection || titleLower.contains("mix")
                            || titleLower.contains("supermix")
                            || titleLower.contains("radio")
                            || playlistId.startsWith("RDAMVM")
                            || playlistId.startsWith("RDEM")
                            || playlistId.startsWith("RDTMAK")

                    if (isMix) {
                        mixes.add(MixResult(playlistId, title, subtitle, thumbUrl, navParams))
                    }
                }
            }
        } catch (e: Exception) {
            Log.w("YouTubeMusicService", "parseHomeMixes error: ${e.message}")
        }
        return mixes
    }

    private fun performMixTracksRequest(
        cookieHeader: String,
        playlistId: String,
        params: String = ""
    ): List<TrackResult> {
        val normalizedPlaylistId = playlistId.trim()
        if (normalizedPlaylistId.isEmpty()) return emptyList()

        val seedVideoId = extractRadioSeedVideoId(normalizedPlaylistId)
        val watchResult = performWatchPlaylistRequest(cookieHeader, normalizedPlaylistId, seedVideoId, params)
        if (watchResult.tracks.isEmpty()) {
            throw IllegalStateException("No se pudo cargar la radio. Inténtalo más tarde.")
        }
        return watchResult.tracks
    }

    @Throws(Exception::class)
    private fun performWatchPlaylistRequest(
        cookieHeader: String,
        playlistId: String,
        seedVideoId: String,
        params: String = ""
    ): WatchPlaylistResult {
        val endpoint = "https://music.youtube.com/youtubei/v1/next?prettyPrint=false"
        val bodyJson = JSONObject().apply {
            put("context", JSONObject().apply {
                put("client", JSONObject().apply {
                    put("clientName", "WEB_REMIX")
                    put("clientVersion", buildClientVersion())
                    put("hl", "es")
                })
            })
            put("enablePersistentPlaylistPanel", true)
            put("isAudioOnly", true)
            put("playlistId", playlistId)
            put("tunerSettingValue", "AUTOMIX_SETTING_NORMAL")
            if (seedVideoId.isNotEmpty()) {
                put("videoId", seedVideoId)
            }
            // The home card's own token wins: YT-generated personal mixes (Replay/Archive/Recap)
            // return an empty panel under the generic "wAEB" radio param and only fill when their
            // captured params ride along. Plain song radios (no card token) keep "wAEB".
            put("params", if (params.isNotEmpty()) params else "wAEB")
        }.toString().toByteArray(StandardCharsets.UTF_8)

        val connection = URL(endpoint).openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.connectTimeout = 14000
        connection.readTimeout = 18000
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("User-Agent", "Mozilla/5.0")
        connection.setRequestProperty("Origin", "https://music.youtube.com")
        connection.setRequestProperty("Referer", "https://music.youtube.com/")
        if (cookieHeader.isNotEmpty()) {
            connection.setRequestProperty("Cookie", cookieHeader)
            // Cookie autenticada SIN SAPISIDHASH = 401 intermitente ("No se pudo cargar la
            // radio"). Misma firma que postInnerTubeBrowse.
            val sapisidAuth = StreamResolver.buildSapisidHashForCookie(cookieHeader, "https://music.youtube.com")
            if (sapisidAuth.isNotEmpty()) {
                connection.setRequestProperty("Authorization", sapisidAuth)
            }
        }

        try {
            connection.outputStream.use { it.write(bodyJson) }
            val statusCode = connection.responseCode
            val responseBody = readResponse(connection, statusCode >= 400)
            if (statusCode != HttpURLConnection.HTTP_OK) {
                throw IllegalStateException("Mix tracks error $statusCode")
            }

            val root = JSONObject(responseBody)
            val watchNextRenderer = root.optJSONObject("contents")
                ?.optJSONObject("singleColumnMusicWatchNextResultsRenderer")
                ?.optJSONObject("tabbedRenderer")
                ?.optJSONObject("watchNextTabbedResultsRenderer")

            if (watchNextRenderer == null) {
                return WatchPlaylistResult(emptyList(), "", "")
            }

            val playlistPanel = watchNextRenderer.optJSONArray("tabs")
                ?.optJSONObject(0)
                ?.optJSONObject("tabRenderer")
                ?.optJSONObject("content")
                ?.optJSONObject("musicQueueRenderer")
                ?.optJSONObject("content")
                ?.optJSONObject("playlistPanelRenderer")

            val tracks = mutableListOf<TrackResult>()
            val firstPageItems = playlistPanel?.optJSONArray("contents")
            tracks.addAll(parseMixTracks(firstPageItems))

            var continuationToken = extractPlaylistPanelContinuationToken(root, playlistPanel, firstPageItems)
            var continuationCount = 0
            while (continuationToken != null && continuationCount < MAX_MIX_CONTINUATIONS) {
                continuationCount++
                val continuationRoot = fetchWatchPlaylistContinuation(cookieHeader, continuationToken)
                val continuationItems = extractPlaylistPanelContinuationItems(continuationRoot)
                if (continuationItems == null || continuationItems.length() == 0) {
                    break
                }
                val parsed = parseMixTracks(continuationItems)
                if (parsed.isEmpty()) {
                    break
                }
                appendUniqueTracks(tracks, parsed)
                continuationToken = extractPlaylistPanelContinuationToken(continuationRoot, null, continuationItems)
            }

            val relatedBrowseId = extractWatchTabBrowseId(watchNextRenderer, 2)
            val lyricsBrowseId = extractWatchTabBrowseId(watchNextRenderer, 1)
            return WatchPlaylistResult(tracks, relatedBrowseId, lyricsBrowseId)
        } finally {
            connection.disconnect()
        }
    }

    @Throws(Exception::class)
    private fun fetchWatchPlaylistContinuation(cookieHeader: String, continuationToken: String): JSONObject {
        val endpoint = "https://music.youtube.com/youtubei/v1/next?prettyPrint=false"
        val bodyJson = JSONObject().apply {
            put("context", JSONObject().apply {
                put("client", JSONObject().apply {
                    put("clientName", "WEB_REMIX")
                    put("clientVersion", buildClientVersion())
                    put("hl", "es")
                })
            })
            put("continuation", continuationToken)
        }.toString().toByteArray(StandardCharsets.UTF_8)

        val connection = URL(endpoint).openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.connectTimeout = 14000
        connection.readTimeout = 18000
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("User-Agent", "Mozilla/5.0")
        connection.setRequestProperty("Origin", "https://music.youtube.com")
        connection.setRequestProperty("Referer", "https://music.youtube.com/")
        if (cookieHeader.isNotEmpty()) {
            connection.setRequestProperty("Cookie", cookieHeader)
            // Misma firma SAPISIDHASH que la petición inicial (cookie sin firma = 401).
            val sapisidAuth = StreamResolver.buildSapisidHashForCookie(cookieHeader, "https://music.youtube.com")
            if (sapisidAuth.isNotEmpty()) {
                connection.setRequestProperty("Authorization", sapisidAuth)
            }
        }

        try {
            connection.outputStream.use { it.write(bodyJson) }
            val statusCode = connection.responseCode
            val responseBody = readResponse(connection, statusCode >= 400)
            if (statusCode != HttpURLConnection.HTTP_OK) {
                throw IllegalStateException("Mix continuation error $statusCode")
            }
            return JSONObject(responseBody)
        } finally {
            connection.disconnect()
        }
    }

    private fun parseMixTracks(contents: JSONArray?): List<TrackResult> {
        val tracks = mutableListOf<TrackResult>()
        try {
            if (contents == null) return tracks

            for (i in 0 until contents.length()) {
                val item = contents.optJSONObject(i) ?: continue

                val renderer = item.optJSONObject("playlistPanelVideoRenderer")
                if (renderer != null) {
                    appendPlaylistPanelTrack(renderer, tracks)
                    harvestVideoAvailability(renderer, null)
                    continue
                }

                val wrapper = item.optJSONObject("playlistPanelVideoWrapperRenderer") ?: continue
                val primary = wrapper.optJSONObject("primaryRenderer")
                    ?.optJSONObject("playlistPanelVideoRenderer")
                if (primary != null) {
                    appendPlaylistPanelTrack(primary, tracks)
                    // El wrapper trae la pareja canción↔video en `counterpart` — antes se tiraba.
                    val counterpart = wrapper.optJSONArray("counterpart")
                        ?.optJSONObject(0)
                        ?.optJSONObject("counterpartRenderer")
                        ?.optJSONObject("playlistPanelVideoRenderer")
                    harvestVideoAvailability(primary, counterpart)
                }
            }
        } catch (e: Exception) {
            Log.w("YouTubeMusicService", "parseMixTracks error: ${e.message}")
        }
        return tracks
    }

    /**
     * Cosecha de "¿esta canción tiene video musical?" desde la data del /next que YA recibimos
     * (misma semántica que ytmusicapi parsers/watch.py): un wrapper con `counterpart` = existe la
     * pareja canción↔video (ambos ids marcan YES, guardando qué id reproduce el video); un
     * renderer suelto tipo ATV = NO hay video; un renderer suelto tipo OMV/UGC = él mismo ES el
     * video. Alimenta la pastilla Canción|Video del player sin ninguna petición extra.
     */
    private fun harvestVideoAvailability(renderer: JSONObject, counterpart: JSONObject?) {
        try {
            val videoId = renderer.optString("videoId", "").trim()
            if (videoId.isEmpty()) return
            val cpId = counterpart?.optString("videoId", "")?.trim().orEmpty()
            if (cpId.isNotEmpty()) {
                val selfType = musicVideoTypeOf(renderer)
                // Si el item es la CANCIÓN (ATV), el video es su counterpart; si el item ya es el
                // VIDEO, el video es él mismo.
                val videoSide = if (selfType == "MUSIC_VIDEO_TYPE_ATV" || selfType.isEmpty()) cpId else videoId
                MusicVideoAvailability.put(videoId, true, videoSide)
                MusicVideoAvailability.put(cpId, true, videoSide)
            } else {
                when (musicVideoTypeOf(renderer)) {
                    "MUSIC_VIDEO_TYPE_ATV" -> MusicVideoAvailability.put(videoId, false)
                    "MUSIC_VIDEO_TYPE_OMV", "MUSIC_VIDEO_TYPE_UGC",
                    "MUSIC_VIDEO_TYPE_OFFICIAL_SOURCE_MUSIC" ->
                        MusicVideoAvailability.put(videoId, true, videoId)
                    // Sin musicVideoType no se concluye nada (queda UNKNOWN).
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun musicVideoTypeOf(renderer: JSONObject?): String =
        renderer?.optJSONObject("navigationEndpoint")
            ?.optJSONObject("watchEndpoint")
            ?.optJSONObject("watchEndpointMusicSupportedConfigs")
            ?.optJSONObject("watchEndpointMusicConfig")
            ?.optString("musicVideoType", "")
            .orEmpty()

    interface VideoCounterpartCallback {
        fun onResult(state: MusicVideoAvailability.State)
    }

    /**
     * Probe ligero de disponibilidad de video musical para una canción que no vino de un /next ya
     * parseado: pide su radio (RDAMVM<id>) al /next y cosecha el counterpart/musicVideoType de
     * TODOS los items (la cola entera se aprovecha). Funciona sin cookie. Callback en MAIN con el
     * estado final del store para [videoId].
     */
    fun fetchVideoCounterpart(cookieHeader: String, videoId: String, callback: VideoCounterpartCallback) {
        val id = videoId.trim()
        if (id.isEmpty()) {
            callback.onResult(MusicVideoAvailability.State.UNKNOWN)
            return
        }
        executor.execute {
            try {
                // performWatchPlaylistRequest ya pasa por parseMixTracks, que cosecha la
                // disponibilidad de cada item de la cola como efecto colateral.
                performWatchPlaylistRequest(cookieHeader.trim(), "RDAMVM$id", "")
            } catch (_: Exception) {
                // Sin red / 4xx: el estado se queda UNKNOWN y el caller decide.
            }
            mainHandler.post { callback.onResult(MusicVideoAvailability.get(id)) }
        }
    }

    private fun appendUniqueTracks(target: MutableList<TrackResult>, additions: List<TrackResult>) {
        if (additions.isEmpty()) return
        val seen = HashSet<String>()
        for (item in target) {
            if (!TextUtils.isEmpty(item.videoId)) {
                seen.add(item.videoId)
            }
        }
        for (item in additions) {
            val videoId = item.videoId.trim()
            if (videoId.isEmpty() || !seen.add(videoId)) continue
            target.add(item)
        }
    }

    private fun extractPlaylistPanelContinuationItems(root: JSONObject): JSONArray? {
        val continuationPanel = root.optJSONObject("continuationContents")
            ?.optJSONObject("playlistPanelContinuation")
        val contents = continuationPanel?.optJSONArray("contents")
        if (contents != null) return contents

        return root.optJSONArray("onResponseReceivedActions")
            ?.optJSONObject(0)
            ?.optJSONObject("appendContinuationItemsAction")
            ?.optJSONArray("continuationItems")
    }

    private fun extractPlaylistPanelContinuationToken(
        root: JSONObject,
        playlistPanel: JSONObject?,
        contents: JSONArray?
    ): String? {
        val fromPanel = playlistPanel?.optJSONArray("continuations")
            ?.optJSONObject(0)
            ?.optJSONObject("nextContinuationData")
            ?.optString("continuation", "")?.takeIf { it.isNotEmpty() }
        if (fromPanel != null) return fromPanel

        val fromRootContinuation = root.optJSONObject("continuationContents")
            ?.optJSONObject("playlistPanelContinuation")
            ?.optJSONArray("continuations")
            ?.optJSONObject(0)
            ?.optJSONObject("nextContinuationData")
            ?.optString("continuation", "")?.takeIf { it.isNotEmpty() }
        if (fromRootContinuation != null) return fromRootContinuation

        val fromItems = extractContinuationTokenFromItems(contents)
        if (fromItems != null) return fromItems

        return extractContinuationTokenFromItems(extractPlaylistPanelContinuationItems(root))
    }

    private fun extractContinuationTokenFromItems(contents: JSONArray?): String? {
        if (contents == null || contents.length() == 0) return null

        for (i in contents.length() - 1 downTo 0) {
            val item = contents.optJSONObject(i) ?: continue
            val token = extractContinuationTokenFromItem(item)
            if (!token.isNullOrEmpty()) return token
        }
        return null
    }

    private fun extractContinuationTokenFromItem(item: JSONObject): String? {
        val continuationItem = item.optJSONObject("continuationItemRenderer") ?: return null

        val directToken = continuationItem.optJSONObject("continuationEndpoint")
            ?.optJSONObject("continuationCommand")
            ?.optString("token", "")
            ?.takeIf { it.isNotEmpty() }
        if (directToken != null) return directToken

        val commands = continuationItem.optJSONObject("commandExecutorCommand")
            ?.optJSONArray("commands")
        if (commands != null) {
            for (i in 0 until commands.length()) {
                val command = commands.optJSONObject(i) ?: continue
                val continuationCommand = command.optJSONObject("continuationCommand") ?: continue
                val request = continuationCommand.optString("request", "")
                if (request == "CONTINUATION_REQUEST_TYPE_BROWSE") {
                    val token = continuationCommand.optString("token", "")
                    if (token.isNotEmpty()) return token
                }
            }
        }
        return null
    }

    private fun appendPlaylistPanelTrack(renderer: JSONObject, tracks: MutableList<TrackResult>) {
        if (renderer.has("unplayableText")) return

        val videoId = renderer.optString("videoId", "").trim()
        if (videoId.isEmpty() || tracks.any { it.videoId == videoId }) return

        // Filtrar contenido NO musical que YouTube a veces cuela en un mix/radio (un video de
        // Minecraft, un gameplay, etc.): los items de YT Music llevan
        // `watchEndpointMusicSupportedConfigs` dentro de su watchEndpoint; un video normal de
        // YouTube NO lo lleva. Si el item tiene watchEndpoint pero SIN esa config de música, es un
        // video ajeno → NO va a la cola. (Items sin watchEndpoint quedan; no se filtran de más.)
        val watchEndpoint = renderer.optJSONObject("navigationEndpoint")?.optJSONObject("watchEndpoint")
        if (watchEndpoint != null && !watchEndpoint.has("watchEndpointMusicSupportedConfigs")) {
            return
        }

        val title = renderer.optJSONObject("title")
            ?.optJSONArray("runs")
            ?.optJSONObject(0)
            ?.optString("text", "") ?: ""
        if (title.isEmpty()) return

        val longBylineRuns = renderer.optJSONObject("longBylineText")?.optJSONArray("runs")
        val artist = buildString {
            if (longBylineRuns != null) {
                for (r in 0 until longBylineRuns.length()) {
                    val text = longBylineRuns.optJSONObject(r)?.optString("text", "") ?: ""
                    if (text == " • " || text == " & ") {
                        if (isNotEmpty()) break
                    }
                    append(text)
                }
            }
        }.trim()

        val thumbnails = renderer.optJSONObject("thumbnail")
            ?.optJSONObject("thumbnails")
            ?.optJSONArray("thumbnails")
            ?: renderer.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
        val thumbUrl = thumbnails?.let {
            it.optJSONObject(it.length() - 1)?.optString("url", "") ?: ""
        } ?: ""

        val duration = renderer.optJSONObject("lengthText")
            ?.optJSONArray("runs")?.optJSONObject(0)?.optString("text", "")
            ?: ""
        tracks.add(TrackResult("video", videoId, title, artist, thumbUrl, duration))
    }

    private fun extractWatchTabBrowseId(watchNextRenderer: JSONObject, tabIndex: Int): String {
        return watchNextRenderer.optJSONArray("tabs")
            ?.optJSONObject(tabIndex)
            ?.optJSONObject("tabRenderer")
            ?.optJSONObject("endpoint")
            ?.optJSONObject("browseEndpoint")
            ?.optString("browseId", "")
            ?.takeIf { it.isNotEmpty() }
            ?: ""
    }

    private fun extractRadioSeedVideoId(playlistId: String): String {
        return if (playlistId.startsWith("RDAMVM") && playlistId.length > 6) {
            playlistId.substring(6)
        } else {
            ""
        }
    }

    // ----- Account menu (channel name + photo) -----

    @Throws(Exception::class)
    private fun performAccountMenuRequest(cookieHeader: String): Pair<String, String> {
        val endpoint = "https://music.youtube.com/youtubei/v1/account/account_menu?prettyPrint=false"
        val body = JSONObject().apply {
            put("context", JSONObject().apply {
                put("client", JSONObject().apply {
                    put("clientName", "WEB_REMIX")
                    put("clientVersion", buildClientVersion())
                    put("hl", "es")
                })
            })
        }.toString().toByteArray(StandardCharsets.UTF_8)

        val url = URL(endpoint)
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.connectTimeout = 10000
        connection.readTimeout = 10000
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        connection.setRequestProperty("User-Agent", "Mozilla/5.0")
        connection.setRequestProperty("Origin", "https://music.youtube.com")
        connection.setRequestProperty("Referer", "https://music.youtube.com/")
        connection.setRequestProperty("Cookie", cookieHeader)
        try {
            connection.outputStream.use { it.write(body) }
            val statusCode = connection.responseCode
            val responseBody = readResponse(connection, statusCode >= 400)
            if (statusCode != HttpURLConnection.HTTP_OK) {
                throw IllegalStateException("Account menu error $statusCode")
            }
            return parseAccountMenu(JSONObject(responseBody))
        } finally {
            connection.disconnect()
        }
    }

    private fun parseAccountMenu(root: JSONObject): Pair<String, String> {
        var channelName = ""
        var photoUrl = ""
        try {
            val actions = root.optJSONObject("actions")?.optJSONArray("openPopupAction")
                ?: root.optJSONArray("actions")
            if (actions != null) {
                for (i in 0 until actions.length()) {
                    val action = actions.optJSONObject(i) ?: continue
                    val popup = action.optJSONObject("openPopupAction")
                        ?.optJSONObject("popup")
                        ?.optJSONObject("multiPageMenuRenderer") ?: continue
                    val header = popup.optJSONObject("header")
                        ?.optJSONObject("activeAccountHeaderRenderer") ?: continue
                    channelName = header.optJSONObject("channelHandle")
                        ?.optJSONArray("runs")?.optJSONObject(0)?.optString("text", "") ?: ""
                    if (channelName.isEmpty()) {
                        channelName = header.optJSONObject("accountName")
                            ?.optJSONArray("runs")?.optJSONObject(0)?.optString("text", "") ?: ""
                    }
                    val thumbs = header.optJSONObject("accountPhoto")?.optJSONArray("thumbnails")
                    if (thumbs != null && thumbs.length() > 0) {
                        photoUrl = thumbs.optJSONObject(thumbs.length() - 1)?.optString("url", "") ?: ""
                    }
                    break
                }
            }
            if (channelName.isEmpty()) {
                val header2 = root.optJSONObject("header")
                    ?.optJSONObject("activeAccountHeaderRenderer")
                if (header2 != null) {
                    channelName = header2.optJSONObject("channelHandle")
                        ?.optJSONArray("runs")?.optJSONObject(0)?.optString("text", "") ?: ""
                    if (channelName.isEmpty()) {
                        channelName = header2.optJSONObject("accountName")
                            ?.optJSONArray("runs")?.optJSONObject(0)?.optString("text", "") ?: ""
                    }
                    val thumbs2 = header2.optJSONObject("accountPhoto")?.optJSONArray("thumbnails")
                    if (thumbs2 != null && thumbs2.length() > 0) {
                        photoUrl = thumbs2.optJSONObject(thumbs2.length() - 1)?.optString("url", "") ?: ""
                    }
                }
            }
        } catch (e: Exception) {
            Log.w("YouTubeMusicService", "parseAccountMenu error: ${e.message}")
        }
        if (channelName.startsWith("@")) channelName = channelName.substring(1)
        return Pair(channelName, photoUrl)
    }

    // ----- Library artists browse -----

    @Throws(Exception::class)
    private fun performLibraryArtistsBrowseRequest(cookieHeader: String): List<ArtistResult> {
        val endpoint = "https://music.youtube.com/youtubei/v1/browse?prettyPrint=false"
        val body = JSONObject().apply {
            put("context", JSONObject().apply {
                put("client", JSONObject().apply {
                    put("clientName", "WEB_REMIX")
                    put("clientVersion", buildClientVersion())
                    put("hl", "es")
                })
            })
            put("browseId", "FEmusic_library_corpus_artists")
        }.toString().toByteArray(StandardCharsets.UTF_8)

        val responseBody = postInnerTubeBrowse(endpoint, body, cookieHeader)
        Log.d(TAG, "artists_browse responseLen=${responseBody.length} firstChars=${responseBody.take(500)}")
        val root = JSONObject(responseBody)
        val result = parseLibraryArtists(root)
        Log.d(TAG, "artists_browse parsed=${result.size}")
        return result
    }

    private fun parseLibraryArtists(root: JSONObject): List<ArtistResult> {
        val artists = mutableListOf<ArtistResult>()
        val seenIds = HashSet<String>()

        fun consider(artist: ArtistResult?) {
            if (artist == null || artist.name.isEmpty()) return
            val dedupKey = if (artist.channelId.isNotEmpty()) artist.channelId
                           else "name:" + artist.name.lowercase(Locale.US)
            if (seenIds.add(dedupKey)) artists.add(artist)
        }

        // Collect the section-content arrays from every browse layout YouTube has used for
        // the library. The page used to be a singleColumnBrowseResultsRenderer; YouTube
        // migrated library pages to twoColumnBrowseResultsRenderer, which is what made the
        // artists "stop showing from one day to another". Handle both so it survives either.
        val contentRoot = root.optJSONObject("contents")
        val sectionArrays = mutableListOf<JSONArray>()

        contentRoot?.optJSONObject("singleColumnBrowseResultsRenderer")
            ?.optJSONArray("tabs")?.optJSONObject(0)
            ?.optJSONObject("tabRenderer")?.optJSONObject("content")
            ?.optJSONObject("sectionListRenderer")?.optJSONArray("contents")
            ?.let { sectionArrays.add(it) }

        contentRoot?.optJSONObject("twoColumnBrowseResultsRenderer")?.let { two ->
            two.optJSONObject("secondaryContents")
                ?.optJSONObject("sectionListRenderer")?.optJSONArray("contents")
                ?.let { sectionArrays.add(it) }
            two.optJSONArray("tabs")?.optJSONObject(0)
                ?.optJSONObject("tabRenderer")?.optJSONObject("content")
                ?.optJSONObject("sectionListRenderer")?.optJSONArray("contents")
                ?.let { sectionArrays.add(it) }
        }

        Log.d(TAG, "artists_parse sectionArrays=${sectionArrays.size} contentKeys=${contentRoot?.keys()?.asSequence()?.toList()}")

        for (sections in sectionArrays) {
            for (s in 0 until sections.length()) {
                val section = sections.optJSONObject(s) ?: continue
                // musicShelfRenderer/itemSectionRenderer use "contents"; gridRenderer uses "items".
                val items = section.optJSONObject("musicShelfRenderer")?.optJSONArray("contents")
                    ?: section.optJSONObject("itemSectionRenderer")?.optJSONArray("contents")
                    ?: section.optJSONObject("gridRenderer")?.optJSONArray("items")
                    ?: continue
                for (i in 0 until items.length()) {
                    val item = items.optJSONObject(i) ?: continue
                    val renderer = item.optJSONObject("musicResponsiveListItemRenderer")
                        ?: item.optJSONObject("musicTwoRowItemRenderer")
                        ?: continue
                    consider(parseArtistFromRenderer(renderer))
                }
            }
        }

        // Fallback: the response didn't match any known layout. Rather than returning empty
        // (which previously wiped the cache), deep-scan the whole tree for artist item
        // renderers. Restrict to entries that navigate to a real channel (UC…) so we don't
        // pick up songs/playlists that share the same renderer type.
        if (artists.isEmpty()) {
            Log.w(TAG, "artists_parse known layouts empty — deep scanning response")
            for (renderer in deepCollectItemRenderers(root)) {
                val artist = parseArtistFromRenderer(renderer) ?: continue
                if (artist.channelId.startsWith("UC")) consider(artist)
            }
        }

        Log.d(TAG, "artists_parse parsed=${artists.size}")
        return artists
    }

    /** Walk the entire JSON tree collecting every artist-capable item renderer. */
    private fun deepCollectItemRenderers(root: JSONObject): List<JSONObject> {
        val out = ArrayList<JSONObject>()
        val stack = ArrayList<Any>()
        stack.add(root)
        var guard = 0
        while (stack.isNotEmpty() && guard < 200_000) {
            guard++
            when (val node = stack.removeAt(stack.size - 1)) {
                is JSONObject -> {
                    node.optJSONObject("musicResponsiveListItemRenderer")?.let { out.add(it) }
                    node.optJSONObject("musicTwoRowItemRenderer")?.let { out.add(it) }
                    val keys = node.keys()
                    while (keys.hasNext()) {
                        val v = node.opt(keys.next())
                        if (v is JSONObject || v is JSONArray) stack.add(v)
                    }
                }
                is JSONArray -> {
                    for (i in 0 until node.length()) {
                        val v = node.opt(i)
                        if (v is JSONObject || v is JSONArray) stack.add(v)
                    }
                }
            }
        }
        return out
    }

    private fun parseArtistFromRenderer(renderer: JSONObject): ArtistResult? {
        // Extract channelId from navigation endpoint
        var channelId = ""
        val navEndpoint = renderer.optJSONObject("navigationEndpoint")
            ?: renderer.optJSONObject("overlay")
                ?.optJSONObject("musicItemThumbnailOverlayRenderer")
                ?.optJSONObject("content")
                ?.optJSONObject("musicPlayButtonRenderer")
                ?.optJSONObject("playNavigationEndpoint")

        val browseEp = navEndpoint?.optJSONObject("browseEndpoint")
        if (browseEp != null) {
            channelId = browseEp.optString("browseId", "").trim()
        }

        // musicResponsiveListItemRenderer path
        val flexColumns = renderer.optJSONArray("flexColumns")
        if (flexColumns != null) {
            val name = extractFlexColumnText(flexColumns, 0)
            val subtitle = extractFlexColumnText(flexColumns, 1)
            val thumbnailUrl = extractRendererThumbnail(renderer)

            if (channelId.isEmpty()) {
                // Try to extract from first flex column navigation
                val firstCol = flexColumns.optJSONObject(0)
                    ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
                    ?.optJSONObject("text")
                    ?.optJSONArray("runs")
                    ?.optJSONObject(0)
                val colNav = firstCol?.optJSONObject("navigationEndpoint")
                    ?.optJSONObject("browseEndpoint")
                if (colNav != null) {
                    channelId = colNav.optString("browseId", "").trim()
                }
            }

            if (name.isNotEmpty()) {
                return ArtistResult(channelId, name, subtitle, thumbnailUrl)
            }
        }

        // musicTwoRowItemRenderer path
        val title = renderer.optJSONObject("title")
            ?.optJSONArray("runs")
            ?.optJSONObject(0)
            ?.optString("text", "")?.trim() ?: ""

        val subtitleRuns = renderer.optJSONObject("subtitle")?.optJSONArray("runs")
        val subtitle = buildString {
            if (subtitleRuns != null) {
                for (r in 0 until subtitleRuns.length()) {
                    append(subtitleRuns.optJSONObject(r)?.optString("text", "") ?: "")
                }
            }
        }.trim()

        val thumbnailUrl = renderer.optJSONObject("thumbnailRenderer")
            ?.optJSONObject("musicThumbnailRenderer")
            ?.optJSONObject("thumbnail")
            ?.optJSONArray("thumbnails")
            ?.let { thumbs ->
                if (thumbs.length() > 0) thumbs.optJSONObject(thumbs.length() - 1)?.optString("url", "") ?: ""
                else ""
            } ?: ""

        if (title.isEmpty()) return null

        if (channelId.isEmpty()) {
            val titleNav = renderer.optJSONObject("title")
                ?.optJSONArray("runs")?.optJSONObject(0)
                ?.optJSONObject("navigationEndpoint")
                ?.optJSONObject("browseEndpoint")
            if (titleNav != null) {
                channelId = titleNav.optString("browseId", "").trim()
            }
        }

        return ArtistResult(channelId, title, subtitle, thumbnailUrl)
    }

    private fun extractFlexColumnText(flexColumns: JSONArray, index: Int): String {
        val col = flexColumns.optJSONObject(index) ?: return ""
        val runs = col.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
            ?.optJSONObject("text")
            ?.optJSONArray("runs")
            ?: return ""
        return buildString {
            for (r in 0 until runs.length()) {
                append(runs.optJSONObject(r)?.optString("text", "") ?: "")
            }
        }.trim()
    }

    private fun extractRendererThumbnail(renderer: JSONObject): String {
        val thumbs = renderer.optJSONObject("thumbnail")
            ?.optJSONObject("musicThumbnailRenderer")
            ?.optJSONObject("thumbnail")
            ?.optJSONArray("thumbnails")
            ?: return ""
        if (thumbs.length() == 0) return ""
        return thumbs.optJSONObject(thumbs.length() - 1)?.optString("url", "") ?: ""
    }

    // ----- Full home browse (split generic + personal mixes) -----

    private val MAX_HOME_CONTINUATIONS = 4

    @Throws(Exception::class)
    private fun performHomeBrowseFullRequest(cookieHeader: String): HomeBrowseResult {
        val endpoint = "https://music.youtube.com/youtubei/v1/browse?prettyPrint=false"
        val clientContext = JSONObject().apply {
            put("clientName", "WEB_REMIX")
            put("clientVersion", buildClientVersion())
            put("hl", "es")
        }
        val body = JSONObject().apply {
            put("context", JSONObject().put("client", clientContext))
            put("browseId", "FEmusic_home")
        }.toString().toByteArray(StandardCharsets.UTF_8)

        val responseBody = postInnerTubeBrowse(endpoint, body, cookieHeader)
        val rootJson = JSONObject(responseBody)
        val result = parseHomeBrowseFull(rootJson)

        // Extract continuation token from initial response
        var continuationToken = extractContinuationToken(rootJson)
        var continuationCount = 0

        while (continuationToken != null && continuationCount < MAX_HOME_CONTINUATIONS) {
            continuationCount++
            try {
                val contBody = JSONObject().apply {
                    put("context", JSONObject().put("client", clientContext))
                    put("continuation", continuationToken)
                }.toString().toByteArray(StandardCharsets.UTF_8)

                val contResponse = postInnerTubeBrowse(endpoint, contBody, cookieHeader)
                val contJson = JSONObject(contResponse)
                parseContinuationSections(contJson, result)
                continuationToken = extractContinuationTokenFromContinuation(contJson)
            } catch (e: Exception) {
                Log.w("YouTubeMusicService", "Home browse continuation #$continuationCount failed: ${e.message}")
                break
            }
        }

        return result
    }

    // ----- Artist page browse (real Spotify-style artist page) -----

    /**
     * Fetches a real artist page by browsing the artist's channelId. Returns the header
     * (name + monthly listeners), top songs and albums/singles. Parsing is fully defensive: if the
     * Innertube response shape doesn't match, it returns an empty page and the caller falls back to
     * a plain search.
     */
    fun fetchArtistPage(channelId: String, cookieHeader: String, callback: ArtistPageCallback) {
        val id = channelId.trim()
        if (id.isEmpty()) {
            callback.onError("Artista sin identificador.")
            return
        }
        executor.execute {
            try {
                val page = performArtistPageBrowseRequest(id, cookieHeader)
                mainHandler.post { callback.onSuccess(page) }
            } catch (e: Exception) {
                mainHandler.post { callback.onError(e.message ?: "Error cargando artista.") }
            }
        }
    }

    @Throws(Exception::class)
    private fun performArtistPageBrowseRequest(channelId: String, cookieHeader: String): ArtistPage {
        val endpoint = "https://music.youtube.com/youtubei/v1/browse?prettyPrint=false"
        val body = JSONObject().apply {
            put("context", JSONObject().apply {
                put("client", JSONObject().apply {
                    put("clientName", "WEB_REMIX")
                    put("clientVersion", buildClientVersion())
                    put("hl", "es")
                })
            })
            put("browseId", channelId)
        }.toString().toByteArray(StandardCharsets.UTF_8)
        val responseBody = postInnerTubeBrowse(endpoint, body, cookieHeader)
        val root = JSONObject(responseBody)
        return parseArtistPage(root)
    }

    private fun parseArtistPage(root: JSONObject): ArtistPage {
        var name = ""
        var subtitle = ""
        var thumb = ""
        var moreSongsBrowseId = ""
        val songs = mutableListOf<TrackResult>()
        val albums = mutableListOf<PlaylistResult>()
        try {
            val header = root.optJSONObject("header")
            val hr = header?.optJSONObject("musicImmersiveHeaderRenderer")
                ?: header?.optJSONObject("musicVisualHeaderRenderer")
                ?: header?.optJSONObject("musicDetailHeaderRenderer")
            if (hr != null) {
                name = artistRunsToText(hr.optJSONObject("title"))
                subtitle = artistRunsToText(hr.optJSONObject("subtitle"))
                if (subtitle.isEmpty()) subtitle = artistRunsToText(hr.optJSONObject("secondSubtitle"))
                thumb = artistThumbUrl(
                    hr.optJSONObject("thumbnail")?.optJSONObject("musicThumbnailRenderer")
                        ?: hr.optJSONObject("foregroundThumbnail")?.optJSONObject("musicThumbnailRenderer")
                )
            }
            val sections = root.optJSONObject("contents")
                ?.optJSONObject("singleColumnBrowseResultsRenderer")
                ?.optJSONArray("tabs")?.optJSONObject(0)
                ?.optJSONObject("tabRenderer")?.optJSONObject("content")
                ?.optJSONObject("sectionListRenderer")?.optJSONArray("contents")
            sections?.forEachObject { section ->
                section.optJSONObject("musicShelfRenderer")?.let { shelf ->
                    if (songs.isEmpty()) {
                        shelf.optJSONArray("contents")?.let { songs.addAll(parseArtistTopSongs(it, 30)) }
                        if (songs.isNotEmpty()) {
                            // The songs shelf links the artist's full songs playlist. Newer layouts
                            // put it in bottomEndpoint, older ones behind a moreContentButton.
                            moreSongsBrowseId = shelf.optJSONObject("bottomEndpoint")
                                ?.optJSONObject("browseEndpoint")
                                ?.optString("browseId", "")?.trim() ?: ""
                            if (moreSongsBrowseId.isEmpty()) {
                                moreSongsBrowseId = shelf.optJSONObject("moreContentButton")
                                    ?.optJSONObject("buttonRenderer")
                                    ?.optJSONObject("navigationEndpoint")
                                    ?.optJSONObject("browseEndpoint")
                                    ?.optString("browseId", "")?.trim() ?: ""
                            }
                        }
                    }
                }
                section.optJSONObject("musicCarouselShelfRenderer")?.let { carousel ->
                    carousel.optJSONArray("contents")?.let { albums.addAll(parseArtistAlbums(it)) }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "parseArtistPage failed", e)
        }
        return ArtistPage(name, subtitle, thumb, songs, albums, moreSongsBrowseId)
    }

    private fun parseArtistTopSongs(items: JSONArray, limit: Int): List<TrackResult> {
        val out = mutableListOf<TrackResult>()
        items.forEachObject { item ->
            if (out.size >= limit) return@forEachObject
            val r = item.optJSONObject("musicResponsiveListItemRenderer") ?: return@forEachObject
            var videoId = r.optJSONObject("playlistItemData")?.optString("videoId", "")?.trim() ?: ""
            if (videoId.isEmpty()) {
                videoId = r.optJSONObject("overlay")
                    ?.optJSONObject("musicItemThumbnailOverlayRenderer")
                    ?.optJSONObject("content")
                    ?.optJSONObject("musicPlayButtonRenderer")
                    ?.optJSONObject("playNavigationEndpoint")
                    ?.optJSONObject("watchEndpoint")
                    ?.optString("videoId", "")?.trim() ?: ""
            }
            if (videoId.isEmpty()) return@forEachObject
            val flex = r.optJSONArray("flexColumns")
            val title = artistRunsToText(
                flex?.optJSONObject(0)
                    ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")?.optJSONObject("text")
            )
            if (title.isEmpty()) return@forEachObject
            val artist = artistRunsToText(
                flex?.optJSONObject(1)
                    ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")?.optJSONObject("text")
            )
            val thumb = artistThumbUrl(r.optJSONObject("thumbnail")?.optJSONObject("musicThumbnailRenderer"))
            // Top-songs rows carry their length in a fixedColumn like album rows do; without it
            // the artist-page rows can never show a duration.
            val duration = artistRunsToText(
                r.optJSONArray("fixedColumns")?.optJSONObject(0)
                    ?.optJSONObject("musicResponsiveListItemFixedColumnRenderer")?.optJSONObject("text")
            )
            out.add(TrackResult("video", videoId, title, artist, thumb, duration))
        }
        return out
    }

    private fun parseArtistAlbums(items: JSONArray): List<PlaylistResult> {
        val out = mutableListOf<PlaylistResult>()
        items.forEachObject { item ->
            val r = item.optJSONObject("musicTwoRowItemRenderer") ?: return@forEachObject
            val nav = r.optJSONObject("navigationEndpoint")
            val playlistId = nav?.optJSONObject("watchPlaylistEndpoint")?.optString("playlistId", "")?.trim() ?: ""
            val browseId = nav?.optJSONObject("browseEndpoint")?.optString("browseId", "")?.trim() ?: ""
            val id = if (playlistId.isNotEmpty()) playlistId else browseId
            if (id.isEmpty()) return@forEachObject
            // Keep ONLY the artist's own releases (albums/singles). Album cards carry an album
            // browse id (MPRE...) or an album-playlist id (OLAK5uy...). "Aparece en" / "Fans también
            // escuchan" cards are editorial playlists (VL/PL/RDCLAK...) — those don't belong in an
            // artist's discography, so drop them here.
            val isOwnRelease = browseId.startsWith("MPRE") ||
                    playlistId.startsWith("OLAK5uy") || browseId.startsWith("OLAK5uy")
            if (!isOwnRelease) return@forEachObject
            val title = artistRunsToText(r.optJSONObject("title"))
            if (title.isEmpty()) return@forEachObject
            val sub = artistRunsToText(r.optJSONObject("subtitle"))
            val thumb = artistThumbUrl(r.optJSONObject("thumbnailRenderer")?.optJSONObject("musicThumbnailRenderer"))
            out.add(PlaylistResult(id, title, sub, 0, thumb, "", ""))
        }
        return out
    }

    /**
     * Loads the tracks of an album/single opened from an artist page. Album cards expose an album
     * BROWSE id (starts with "MPRE"), which is NOT a valid playlist id for the OAuth Data API — that
     * endpoint returns empty. So we resolve it through the InnerTube browse endpoint with the web
     * cookie, exactly like radios/mixes, and read the track shelf directly.
     */
    fun fetchAlbumTracks(cookieHeader: String, browseId: String, callback: MixTracksCallback) {
        if (browseId.isEmpty()) {
            callback.onError("Datos insuficientes para cargar el álbum.")
            return
        }
        executor.execute {
            try {
                val tracks = performAlbumTracksRequest(cookieHeader.trim(), browseId.trim())
                mainHandler.post { callback.onSuccess(tracks) }
            } catch (e: Exception) {
                mainHandler.post { callback.onError(e.message ?: "No se pudo cargar el álbum.") }
            }
        }
    }

    private fun performAlbumTracksRequest(cookieHeader: String, browseId: String): List<TrackResult> {
        val endpoint = "https://music.youtube.com/youtubei/v1/browse?prettyPrint=false"
        val body = JSONObject().apply {
            put("context", JSONObject().apply {
                put("client", JSONObject().apply {
                    put("clientName", "WEB_REMIX")
                    put("clientVersion", buildClientVersion())
                    put("hl", "es")
                })
            })
            put("browseId", browseId)
        }.toString().toByteArray(StandardCharsets.UTF_8)
        val responseBody = postInnerTubeBrowse(endpoint, body, cookieHeader)
        return parseAlbumTracks(JSONObject(responseBody))
    }

    private fun parseAlbumTracks(root: JSONObject): List<TrackResult> {
        val out = mutableListOf<TrackResult>()
        try {
            // The track shelf lives in different places depending on the layout the server returns
            // (single- vs two-column). Collect every sectionList we can reach, then take the first
            // that yields a track shelf (musicShelfRenderer / musicPlaylistShelfRenderer).
            val sectionLists = mutableListOf<JSONArray>()
            root.optJSONObject("contents")?.let { contents ->
                contents.optJSONObject("singleColumnBrowseResultsRenderer")
                    ?.optJSONArray("tabs")?.optJSONObject(0)
                    ?.optJSONObject("tabRenderer")?.optJSONObject("content")
                    ?.optJSONObject("sectionListRenderer")?.optJSONArray("contents")
                    ?.let { sectionLists.add(it) }
                contents.optJSONObject("twoColumnBrowseResultsRenderer")?.let { two ->
                    two.optJSONObject("secondaryContents")
                        ?.optJSONObject("sectionListRenderer")?.optJSONArray("contents")
                        ?.let { sectionLists.add(it) }
                    two.optJSONArray("tabs")?.optJSONObject(0)
                        ?.optJSONObject("tabRenderer")?.optJSONObject("content")
                        ?.optJSONObject("sectionListRenderer")?.optJSONArray("contents")
                        ?.let { sectionLists.add(it) }
                }
            }
            for (sections in sectionLists) {
                sections.forEachObject { section ->
                    val shelf = section.optJSONObject("musicShelfRenderer")?.optJSONArray("contents")
                        ?: section.optJSONObject("musicPlaylistShelfRenderer")?.optJSONArray("contents")
                    shelf?.let { out.addAll(parseAlbumTrackRows(it)) }
                }
                if (out.isNotEmpty()) break
            }
        } catch (e: Exception) {
            Log.w(TAG, "parseAlbumTracks failed", e)
        }
        return out
    }

    private fun parseAlbumTrackRows(items: JSONArray): List<TrackResult> {
        val out = mutableListOf<TrackResult>()
        items.forEachObject { item ->
            val r = item.optJSONObject("musicResponsiveListItemRenderer") ?: return@forEachObject
            var videoId = r.optJSONObject("playlistItemData")?.optString("videoId", "")?.trim() ?: ""
            if (videoId.isEmpty()) {
                videoId = r.optJSONObject("overlay")
                    ?.optJSONObject("musicItemThumbnailOverlayRenderer")
                    ?.optJSONObject("content")
                    ?.optJSONObject("musicPlayButtonRenderer")
                    ?.optJSONObject("playNavigationEndpoint")
                    ?.optJSONObject("watchEndpoint")
                    ?.optString("videoId", "")?.trim() ?: ""
            }
            if (videoId.isEmpty()) return@forEachObject
            val flex = r.optJSONArray("flexColumns")
            val title = artistRunsToText(
                flex?.optJSONObject(0)
                    ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")?.optJSONObject("text")
            )
            if (title.isEmpty()) return@forEachObject
            val artist = artistRunsToText(
                flex?.optJSONObject(1)
                    ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")?.optJSONObject("text")
            )
            // Album pages put the track duration in a fixedColumn ("2:12"), not in the subtitle.
            val duration = artistRunsToText(
                r.optJSONArray("fixedColumns")?.optJSONObject(0)
                    ?.optJSONObject("musicResponsiveListItemFixedColumnRenderer")?.optJSONObject("text")
            )
            val thumb = artistThumbUrl(r.optJSONObject("thumbnail")?.optJSONObject("musicThumbnailRenderer"))
            out.add(TrackResult("video", videoId, title, artist, thumb, duration))
        }
        return out
    }

    private fun artistRunsToText(obj: JSONObject?): String {
        val runs = obj?.optJSONArray("runs") ?: return ""
        val sb = StringBuilder()
        for (i in 0 until runs.length()) {
            sb.append(runs.optJSONObject(i)?.optString("text", "") ?: "")
        }
        return sb.toString().trim()
    }

    private fun artistThumbUrl(musicThumbnailRenderer: JSONObject?): String {
        val thumbs = musicThumbnailRenderer?.optJSONObject("thumbnail")?.optJSONArray("thumbnails") ?: return ""
        if (thumbs.length() == 0) return ""
        return thumbs.optJSONObject(thumbs.length() - 1)?.optString("url", "") ?: ""
    }

    // ----- Biblioteca vía InnerTube browse (reemplaza la Data API OAuth) -----

    /** Body de browse WEB_REMIX; browseId para la página inicial o continuation para paginar. */
    private fun buildLibraryBrowseBody(browseId: String?, continuation: String?): ByteArray {
        return JSONObject().apply {
            put("context", JSONObject().apply {
                put("client", JSONObject().apply {
                    put("clientName", "WEB_REMIX")
                    put("clientVersion", buildClientVersion())
                    put("hl", "es")
                })
            })
            if (continuation != null) put("continuation", continuation)
            else if (browseId != null) put("browseId", browseId)
        }.toString().toByteArray(StandardCharsets.UTF_8)
    }

    /** Lista de playlists de la biblioteca del usuario (FEmusic_liked_playlists). */
    @Throws(Exception::class)
    private fun performLibraryPlaylistsBrowseRequest(cookieHeader: String): List<PlaylistResult> {
        val endpoint = "https://music.youtube.com/youtubei/v1/browse?prettyPrint=false"
        val responseBody = postInnerTubeBrowse(endpoint, buildLibraryBrowseBody("FEmusic_liked_playlists", null), cookieHeader)
        val root = JSONObject(responseBody)
        val result = parseLibraryPlaylists(root)
        Log.d(TAG, "library_playlists_browse parsed=${result.size}")
        return result
    }

    private fun parseLibraryPlaylists(root: JSONObject): List<PlaylistResult> {
        val out = ArrayList<PlaylistResult>()
        val seen = HashSet<String>()
        var likedMusic: PlaylistResult? = null

        fun consider(renderer: JSONObject) {
            val browseId = renderer.optJSONObject("navigationEndpoint")
                ?.optJSONObject("browseEndpoint")
                ?.optString("browseId", "")?.trim() ?: ""
            // Sólo tarjetas que navegan a una playlist (VL<id>); ignora "Nueva playlist", etc.
            if (!browseId.startsWith("VL") || browseId.length <= 2) return
            val playlistId = browseId.substring(2)
            if (playlistId.isEmpty()) return

            val title = renderer.optJSONObject("title")?.optJSONArray("runs")?.optJSONObject(0)
                ?.optString("text", "")?.trim() ?: ""
            if (title.isEmpty()) return

            val subtitleRuns = renderer.optJSONObject("subtitle")?.optJSONArray("runs")
            val subtitle = buildString {
                if (subtitleRuns != null) for (r in 0 until subtitleRuns.length()) {
                    append(subtitleRuns.optJSONObject(r)?.optString("text", "") ?: "")
                }
            }.trim()

            val thumbnailUrl = renderer.optJSONObject("thumbnailRenderer")
                ?.optJSONObject("musicThumbnailRenderer")?.optJSONObject("thumbnail")
                ?.optJSONArray("thumbnails")?.let { thumbs ->
                    if (thumbs.length() > 0) thumbs.optJSONObject(thumbs.length() - 1)?.optString("url", "") ?: "" else ""
                } ?: ""

            val itemCount = parseLeadingInt(subtitle)

            // "Liked Music" (VLLM) → colección especial "Me gusta" fijada arriba.
            if (playlistId == "LM") {
                if (likedMusic == null) {
                    likedMusic = PlaylistResult(
                        SPECIAL_LIKED_VIDEOS_ID, SPECIAL_LIKED_VIDEOS_TITLE,
                        "Tu cuenta de YouTube Music", itemCount, thumbnailUrl, "private", ""
                    )
                }
                return
            }
            if (!seen.add(playlistId)) return
            out.add(PlaylistResult(playlistId, title, subtitle, itemCount, thumbnailUrl, "", ""))
        }

        val contentRoot = root.optJSONObject("contents")
        val sectionArrays = mutableListOf<JSONArray>()
        contentRoot?.optJSONObject("singleColumnBrowseResultsRenderer")
            ?.optJSONArray("tabs")?.optJSONObject(0)
            ?.optJSONObject("tabRenderer")?.optJSONObject("content")
            ?.optJSONObject("sectionListRenderer")?.optJSONArray("contents")
            ?.let { sectionArrays.add(it) }
        contentRoot?.optJSONObject("twoColumnBrowseResultsRenderer")?.let { two ->
            two.optJSONObject("secondaryContents")
                ?.optJSONObject("sectionListRenderer")?.optJSONArray("contents")
                ?.let { sectionArrays.add(it) }
            two.optJSONArray("tabs")?.optJSONObject(0)
                ?.optJSONObject("tabRenderer")?.optJSONObject("content")
                ?.optJSONObject("sectionListRenderer")?.optJSONArray("contents")
                ?.let { sectionArrays.add(it) }
        }

        for (sections in sectionArrays) {
            for (s in 0 until sections.length()) {
                val section = sections.optJSONObject(s) ?: continue
                val items = section.optJSONObject("gridRenderer")?.optJSONArray("items")
                    ?: section.optJSONObject("musicShelfRenderer")?.optJSONArray("contents")
                    ?: section.optJSONObject("itemSectionRenderer")?.optJSONArray("contents")
                    ?: continue
                for (i in 0 until items.length()) {
                    val item = items.optJSONObject(i) ?: continue
                    val renderer = item.optJSONObject("musicTwoRowItemRenderer")
                        ?: item.optJSONObject("musicResponsiveListItemRenderer")
                        ?: continue
                    consider(renderer)
                }
            }
        }

        // Fallback: layout desconocido → escaneo profundo del árbol.
        if (out.isEmpty() && likedMusic == null) {
            for (renderer in deepCollectItemRenderers(root)) consider(renderer)
        }

        likedMusic?.let { out.add(0, it) }
        return out
    }

    /** Extrae el primer entero de un texto tipo "Playlist • 25 canciones" → 25. */
    private fun parseLeadingInt(text: String): Int {
        val match = Regex("\\d[\\d.,]*").find(text) ?: return 0
        return match.value.replace(".", "").replace(",", "").toIntOrNull() ?: 0
    }

    /** Canciones de una playlist vía InnerTube browse (VL<id>) con paginación por continuaciones. */
    @Throws(Exception::class)
    private fun performPlaylistTracksBrowseRequest(
        cookieHeader: String,
        playlistId: String,
        maxResults: Int
    ): List<PlaylistTrackResult> {
        val id = playlistId.trim()
        if (id.isEmpty()) return emptyList()
        val browseId = if (id.startsWith("VL")) id else "VL$id"
        val endpoint = "https://music.youtube.com/youtubei/v1/browse?prettyPrint=false"
        val target = maxOf(1, maxResults)
        val out = ArrayList<PlaylistTrackResult>()
        val seen = HashSet<String>()

        fun addRows(rows: List<TrackResult>) {
            for (t in rows) {
                if (t.videoId.isEmpty() || !seen.add(t.videoId)) continue
                val duration = if (TextUtils.isEmpty(t.duration)) "--:--" else t.duration
                out.add(PlaylistTrackResult(t.videoId, t.title, t.subtitle, duration, t.thumbnailUrl))
                if (out.size >= target) return
            }
        }

        val firstRoot = JSONObject(postInnerTubeBrowse(endpoint, buildLibraryBrowseBody(browseId, null), cookieHeader))
        val shelf = findPlaylistShelf(firstRoot)
        shelf?.optJSONArray("contents")?.let { addRows(parseAlbumTrackRows(it)) }
        var continuation = extractPlaylistShelfContinuation(shelf)

        var guard = 0
        while (out.size < target && continuation != null && guard < 12) {
            guard++
            val contRoot = JSONObject(postInnerTubeBrowse(endpoint, buildLibraryBrowseBody(null, continuation), cookieHeader))
            val contShelf = contRoot.optJSONObject("continuationContents")
                ?.optJSONObject("musicPlaylistShelfContinuation")
                ?: contRoot.optJSONObject("continuationContents")?.optJSONObject("musicShelfContinuation")
            val rows = contShelf?.optJSONArray("contents")?.let { parseAlbumTrackRows(it) } ?: emptyList()
            if (rows.isEmpty()) break
            addRows(rows)
            continuation = extractPlaylistShelfContinuation(contShelf)
        }

        return out
    }

    /** Localiza el objeto musicPlaylistShelfRenderer/musicShelfRenderer (no sus contents). */
    private fun findPlaylistShelf(root: JSONObject): JSONObject? {
        val sectionLists = mutableListOf<JSONArray>()
        root.optJSONObject("contents")?.let { contents ->
            contents.optJSONObject("singleColumnBrowseResultsRenderer")
                ?.optJSONArray("tabs")?.optJSONObject(0)
                ?.optJSONObject("tabRenderer")?.optJSONObject("content")
                ?.optJSONObject("sectionListRenderer")?.optJSONArray("contents")
                ?.let { sectionLists.add(it) }
            contents.optJSONObject("twoColumnBrowseResultsRenderer")?.let { two ->
                two.optJSONObject("secondaryContents")
                    ?.optJSONObject("sectionListRenderer")?.optJSONArray("contents")
                    ?.let { sectionLists.add(it) }
                two.optJSONArray("tabs")?.optJSONObject(0)
                    ?.optJSONObject("tabRenderer")?.optJSONObject("content")
                    ?.optJSONObject("sectionListRenderer")?.optJSONArray("contents")
                    ?.let { sectionLists.add(it) }
            }
        }
        for (sections in sectionLists) {
            for (i in 0 until sections.length()) {
                val section = sections.optJSONObject(i) ?: continue
                val shelf = section.optJSONObject("musicPlaylistShelfRenderer")
                    ?: section.optJSONObject("musicShelfRenderer")
                if (shelf != null) return shelf
            }
        }
        return null
    }

    private fun extractPlaylistShelfContinuation(node: JSONObject?): String? {
        val conts = node?.optJSONArray("continuations") ?: return null
        for (i in 0 until conts.length()) {
            val token = conts.optJSONObject(i)?.optJSONObject("nextContinuationData")
                ?.optString("continuation", "")
            if (!token.isNullOrEmpty()) return token
        }
        return null
    }

    private fun postInnerTubeBrowse(endpoint: String, body: ByteArray, cookieHeader: String): String {
        val url = URL(endpoint)
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        // 18s de read era excesivo para un carrusel del home: una respuesta lenta congelaba la
        // sección hasta 18s. 10s falla antes y el reintento con backoff de refreshRecommended
        // (RECOMMENDED_MAX_RETRIES) cubre los timeouts transitorios.
        connection.connectTimeout = 8000
        connection.readTimeout = 10000
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("User-Agent", "Mozilla/5.0")
        connection.setRequestProperty("Origin", "https://music.youtube.com")
        connection.setRequestProperty("Referer", "https://music.youtube.com/")
        if (cookieHeader.isNotEmpty()) {
            connection.setRequestProperty("Cookie", cookieHeader)
            val sapisidAuth = StreamResolver.buildSapisidHashForCookie(cookieHeader, "https://music.youtube.com")
            if (sapisidAuth.isNotEmpty()) {
                connection.setRequestProperty("Authorization", sapisidAuth)
            }
        }
        try {
            connection.outputStream.use { it.write(body) }
            val statusCode = connection.responseCode
            val responseBody = readResponse(connection, statusCode >= 400)
            if (statusCode != HttpURLConnection.HTTP_OK) {
                throw IllegalStateException("Browse home error $statusCode")
            }
            return responseBody
        } finally {
            connection.disconnect()
        }
    }

    /** Real YouTube/YT Music like state for a video, mirrored from the InnerTube likeStatus enum. */
    enum class LikeStatus { LIKE, DISLIKE, INDIFFERENT }

    interface RateCallback {
        fun onSuccess(status: LikeStatus)
        fun onError(error: String)
    }

    interface LikeStatusCallback {
        fun onResult(status: LikeStatus)
    }

    /**
     * Applies a REAL like/dislike on YouTube via the InnerTube like endpoints, authenticated with the
     * user's YT Music web cookie + SAPISID (the same auth the home feed uses) — so it counts exactly
     * like tapping like/dislike on youtube.com / music.youtube.com. [target] LIKE→/like/like,
     * DISLIKE→/like/dislike, INDIFFERENT→/like/removelike (clears a prior like or dislike).
     */
    fun rateSong(cookieHeader: String, videoId: String, target: LikeStatus, callback: RateCallback) {
        val cookie = cookieHeader.trim()
        val id = videoId.trim()
        if (cookie.isEmpty()) {
            callback.onError("Inicia sesión en YouTube Music para dar me gusta.")
            return
        }
        if (id.isEmpty()) {
            callback.onError("Vídeo no válido.")
            return
        }
        executor.execute {
            try {
                val action = when (target) {
                    LikeStatus.LIKE -> "like"
                    LikeStatus.DISLIKE -> "dislike"
                    LikeStatus.INDIFFERENT -> "removelike"
                }
                val endpoint = "https://music.youtube.com/youtubei/v1/like/$action?prettyPrint=false"
                val clientContext = JSONObject().apply {
                    put("clientName", "WEB_REMIX")
                    put("clientVersion", buildClientVersion())
                    put("hl", "es")
                }
                val body = JSONObject().apply {
                    put("context", JSONObject().put("client", clientContext))
                    put("target", JSONObject().put("videoId", id))
                }.toString().toByteArray(StandardCharsets.UTF_8)
                // A 2xx with no exception means the action was applied.
                postInnerTubeBrowse(endpoint, body, cookie)
                mainHandler.post { callback.onSuccess(target) }
            } catch (e: Exception) {
                mainHandler.post { callback.onError(e.message ?: "No se pudo actualizar el me gusta.") }
            }
        }
    }

    /**
     * Reads the REAL current like status of [videoId] from YouTube via the InnerTube watch (/next)
     * endpoint, so the player can reflect what the user has on YT (liked / disliked / neither).
     * Best-effort: on any parse/network issue it reports INDIFFERENT rather than failing the UI.
     */
    fun fetchLikeStatus(cookieHeader: String, videoId: String, callback: LikeStatusCallback) {
        val cookie = cookieHeader.trim()
        val id = videoId.trim()
        if (cookie.isEmpty() || id.isEmpty()) {
            callback.onResult(LikeStatus.INDIFFERENT)
            return
        }
        executor.execute {
            var result = LikeStatus.INDIFFERENT
            try {
                val endpoint = "https://music.youtube.com/youtubei/v1/next?prettyPrint=false"
                val clientContext = JSONObject().apply {
                    put("clientName", "WEB_REMIX")
                    put("clientVersion", buildClientVersion())
                    put("hl", "es")
                }
                val body = JSONObject().apply {
                    put("context", JSONObject().put("client", clientContext))
                    put("videoId", id)
                }.toString().toByteArray(StandardCharsets.UTF_8)
                val response = postInnerTubeBrowse(endpoint, body, cookie)
                result = parseLikeStatusFromNext(JSONObject(response))
            } catch (_: Exception) {
                result = LikeStatus.INDIFFERENT
            }
            mainHandler.post { callback.onResult(result) }
        }
    }

    /** Digs the likeButtonRenderer.likeStatus out of the /next watch response (deep, so defensive). */
    private fun parseLikeStatusFromNext(root: JSONObject): LikeStatus {
        try {
            val panel = root.optJSONObject("contents")
                ?.optJSONObject("singleColumnMusicWatchNextResultsRenderer")
                ?.optJSONObject("tabbedRenderer")
                ?.optJSONObject("watchNextTabbedResultsRenderer")
                ?.optJSONArray("tabs")
                ?.optJSONObject(0)
                ?.optJSONObject("tabRenderer")
                ?.optJSONObject("content")
                ?.optJSONObject("musicQueueRenderer")
                ?.optJSONObject("content")
                ?.optJSONObject("playlistPanelRenderer")
                ?.optJSONArray("contents")
            if (panel != null) {
                for (i in 0 until panel.length()) {
                    val video = panel.optJSONObject(i)?.optJSONObject("playlistPanelVideoRenderer") ?: continue
                    val buttons = video.optJSONObject("menu")
                        ?.optJSONObject("menuRenderer")
                        ?.optJSONArray("topLevelButtons") ?: continue
                    for (b in 0 until buttons.length()) {
                        val lb = buttons.optJSONObject(b)?.optJSONObject("likeButtonRenderer") ?: continue
                        val raw = lb.optString("likeStatus", "")
                        return when (raw) {
                            "LIKE" -> LikeStatus.LIKE
                            "DISLIKE" -> LikeStatus.DISLIKE
                            else -> LikeStatus.INDIFFERENT
                        }
                    }
                }
            }
        } catch (_: Exception) {
        }
        return LikeStatus.INDIFFERENT
    }

    private fun extractContinuationToken(root: JSONObject): String? {
        val sectionList = root.optJSONObject("contents")
            ?.optJSONObject("singleColumnBrowseResultsRenderer")
            ?.optJSONArray("tabs")
            ?.optJSONObject(0)
            ?.optJSONObject("tabRenderer")
            ?.optJSONObject("content")
            ?.optJSONObject("sectionListRenderer")
        val legacy = sectionList
            ?.optJSONArray("continuations")
            ?.optJSONObject(0)
            ?.optJSONObject("nextContinuationData")
            ?.optString("continuation", "")?.takeIf { it.isNotEmpty() }
        if (legacy != null) return legacy
        // Forma MODERNA: un continuationItemRenderer al final del sectionList. Sin esto, cuando
        // YT sirve el home con la forma nueva solo llegaba el primer lote (~4-6 secciones) y el
        // resto del home (p.ej. secciones personalizadas) nunca aparecía.
        return extractContinuationTokenFromItems(sectionList?.optJSONArray("contents"))
    }

    private fun extractContinuationTokenFromContinuation(contJson: JSONObject): String? {
        val legacy = contJson.optJSONObject("continuationContents")
            ?.optJSONObject("sectionListContinuation")
            ?.optJSONArray("continuations")
            ?.optJSONObject(0)
            ?.optJSONObject("nextContinuationData")
            ?.optString("continuation", "")?.takeIf { it.isNotEmpty() }
        if (legacy != null) return legacy
        // Forma moderna: el token viaja como continuationItemRenderer entre los items appendeados.
        return extractContinuationTokenFromItems(modernContinuationItems(contJson))
    }

    /** Items de una continuation moderna (onResponseReceivedActions → appendContinuationItemsAction). */
    private fun modernContinuationItems(contJson: JSONObject): JSONArray? =
        contJson.optJSONArray("onResponseReceivedActions")
            ?.optJSONObject(0)
            ?.optJSONObject("appendContinuationItemsAction")
            ?.optJSONArray("continuationItems")

    private fun parseContinuationSections(contJson: JSONObject, result: HomeBrowseResult) {
        val sections = contJson.optJSONObject("continuationContents")
            ?.optJSONObject("sectionListContinuation")
            ?.optJSONArray("contents")
            ?: modernContinuationItems(contJson)
            ?: return

        for (s in 0 until sections.length()) {
            val section = sections.optJSONObject(s) ?: continue
            val carousel = section.optJSONObject("musicCarouselShelfRenderer") ?: continue
            parseCarouselIntoResult(carousel, result)
        }
    }

    private fun parseHomeBrowseFull(root: JSONObject): HomeBrowseResult {
        val result = HomeBrowseResult(
            mutableListOf<MixResult>(),
            mutableListOf<MixResult>(),
            mutableListOf<HomeSection>()
        )
        try {
            val tabs = root.optJSONObject("contents")
                ?.optJSONObject("singleColumnBrowseResultsRenderer")
                ?.optJSONArray("tabs")
                ?: return result

            val tabContent = tabs.optJSONObject(0)
                ?.optJSONObject("tabRenderer")
                ?.optJSONObject("content")
                ?.optJSONObject("sectionListRenderer")
                ?.optJSONArray("contents")
                ?: return result

            for (s in 0 until tabContent.length()) {
                val section = tabContent.optJSONObject(s) ?: continue
                val carousel = section.optJSONObject("musicCarouselShelfRenderer") ?: continue
                parseCarouselIntoResult(carousel, result)
            }
        } catch (e: Exception) {
            Log.w("YouTubeMusicService", "parseHomeBrowseFull error: ${e.message}")
        }
        return result
    }

    /**
     * One "Selección rápida" song row → TrackResult. Same extraction shape as
     * [parseArtistTopSongs] (playlistItemData/overlay videoId + flexColumn runs); quick-pick rows
     * carry no fixedColumn duration. Returns null for rows without a playable videoId.
     */
    private fun parseQuickPickRow(r: JSONObject): TrackResult? {
        var videoId = r.optJSONObject("playlistItemData")?.optString("videoId", "")?.trim() ?: ""
        if (videoId.isEmpty()) {
            videoId = r.optJSONObject("overlay")
                ?.optJSONObject("musicItemThumbnailOverlayRenderer")
                ?.optJSONObject("content")
                ?.optJSONObject("musicPlayButtonRenderer")
                ?.optJSONObject("playNavigationEndpoint")
                ?.optJSONObject("watchEndpoint")
                ?.optString("videoId", "")?.trim() ?: ""
        }
        if (videoId.isEmpty()) return null
        val flex = r.optJSONArray("flexColumns")
        val title = artistRunsToText(
            flex?.optJSONObject(0)
                ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")?.optJSONObject("text")
        )
        if (title.isEmpty()) return null
        val artist = artistRunsToText(
            flex?.optJSONObject(1)
                ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")?.optJSONObject("text")
        )
        val thumb = artistThumbUrl(r.optJSONObject("thumbnail")?.optJSONObject("musicThumbnailRenderer"))
        return TrackResult("video", videoId, title, artist, thumb)
    }

    private fun parseCarouselIntoResult(carousel: JSONObject, result: HomeBrowseResult) {
        val headerTitle = carousel.optJSONObject("header")
            ?.optJSONObject("musicCarouselShelfBasicHeaderRenderer")
            ?.optJSONObject("title")
            ?.optJSONArray("runs")
            ?.optJSONObject(0)
            ?.optString("text", "") ?: ""

        val sectionItems = mutableListOf<MixResult>()
        val items = carousel.optJSONArray("contents") ?: return

        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: continue
            val renderer = item.optJSONObject("musicTwoRowItemRenderer")
            if (renderer == null) {
                // Quick-picks ("Selección rápida"): the only home shelf made of song rows
                // (musicResponsiveListItemRenderer) instead of cards. Detected by renderer type —
                // not the localized header — so continuation batches route here too.
                item.optJSONObject("musicResponsiveListItemRenderer")?.let { row ->
                    parseQuickPickRow(row)?.let { result.quickPicks.add(it) }
                }
                continue
            }

            val browseEndpoint = renderer.optJSONObject("navigationEndpoint")
                ?.optJSONObject("watchPlaylistEndpoint")
            val watchEndpoint = renderer.optJSONObject("navigationEndpoint")
                ?.optJSONObject("watchEndpoint")
            val browseEp = renderer.optJSONObject("navigationEndpoint")
                ?.optJSONObject("browseEndpoint")

            var playlistId = browseEndpoint?.optString("playlistId", "") ?: ""
            if (playlistId.isEmpty()) playlistId = watchEndpoint?.optString("playlistId", "") ?: ""
            if (playlistId.isEmpty()) {
                playlistId = browseEp?.optString("browseId", "") ?: ""
                // browseIds are the 'VL'-prefixed browse form of the playlist id (VLPL…→PL…,
                // VLRDTMAK…→RDTMAK…, VLLM→LM). Downstream consumers (Data API playlistItems,
                // the RD… mix router in PlaylistDetail) all expect the bare id — a leaked 'VL'
                // id reaches the Data API verbatim and returns an empty tracklist (the
                // RECAP-opens-empty bug).
                if (playlistId.startsWith("VL") && playlistId.length > 2) {
                    playlistId = playlistId.substring(2)
                }
            }

            // Capture the endpoint's `params` token: YT-generated mixes/recaps only resolve their
            // tracks from /next (or browse) when this exact token rides along with the playlistId.
            // Whichever endpoint supplied the id owns the token, so read them in the same priority.
            var navParams = browseEndpoint?.optString("params", "") ?: ""
            if (navParams.isEmpty()) navParams = watchEndpoint?.optString("params", "") ?: ""
            if (navParams.isEmpty()) navParams = browseEp?.optString("params", "") ?: ""

            val title = renderer.optJSONObject("title")
                ?.optJSONArray("runs")
                ?.optJSONObject(0)
                ?.optString("text", "") ?: ""

            val subtitleRuns = renderer.optJSONObject("subtitle")?.optJSONArray("runs")
            val subtitle = buildString {
                if (subtitleRuns != null) {
                    for (r in 0 until subtitleRuns.length()) {
                        append(subtitleRuns.optJSONObject(r)?.optString("text", "") ?: "")
                    }
                }
            }

            val thumbnails = renderer.optJSONObject("thumbnailRenderer")
                ?.optJSONObject("musicThumbnailRenderer")
                ?.optJSONObject("thumbnail")
                ?.optJSONArray("thumbnails")
            val thumbUrl = thumbnails?.let {
                it.optJSONObject(it.length() - 1)?.optString("url", "") ?: ""
            } ?: ""

            if (playlistId.isEmpty() && title.isEmpty()) continue
            sectionItems.add(MixResult(playlistId, title, subtitle, thumbUrl, navParams))
        }

        if (sectionItems.isNotEmpty()) {
            result.allSections.add(HomeSection(headerTitle, sectionItems))
        }

        val lower = headerTitle.lowercase()
        val sectionIsPersonal = lower.contains("my mix") || lower.contains("mi mix")
                || lower.contains("discover mix") || lower.contains("descubre")
                || lower.contains("your") || lower.contains("tu ")
                || lower.contains("para ti") || lower.contains("listen again")
                || lower.contains("escucha de nuevo")
                || lower.contains("mixed for you") || lower.contains("mezclado para ti")
                || lower.contains("your music tuner") || lower.contains("tu sintonizador")
                || lower.contains("similar to") || lower.contains("basado en")

        for (mixItem in sectionItems) {
            val tLow = mixItem.title.lowercase()

            // Genre mixes like "Salsa Mix", "Bachata Mix" are always generic
            val looksLikeGenreMix = tLow.matches(Regex("^[a-záéíóúñü\\s]+mix$"))
                    || tLow.matches(Regex("^[a-záéíóúñü\\s]+radio$"))

            val isPersonal = !looksLikeGenreMix && (
                    sectionIsPersonal
                    || tLow.contains("my mix") || tLow.contains("mi mix")
                    || tLow.contains("discover mix") || tLow.contains("new release mix")
                    || tLow.contains("supermix")
                    || Regex("mix\\s*#?\\s*\\d").containsMatchIn(tLow)
                    || mixItem.playlistId.startsWith("RDEM")
                    || mixItem.playlistId.startsWith("RDTMAK")
                    )

            val isGenericMix = !isPersonal && (
                    tLow.contains("mix") || tLow.contains("radio")
                            || mixItem.playlistId.startsWith("RDAMVM")
                    )

            if (isPersonal) result.personalMixes.add(mixItem)
            else if (isGenericMix) result.genericMixes.add(mixItem)
        }
    }

    // ----- Innertube search (for covers/remixes) -----

    @Throws(Exception::class)
    private fun performInnertubeSearch(cookieHeader: String, query: String, maxResults: Int): List<TrackResult> {
        val endpoint = "https://music.youtube.com/youtubei/v1/search?prettyPrint=false"
        val body = JSONObject().apply {
            put("context", JSONObject().apply {
                put("client", JSONObject().apply {
                    put("clientName", "WEB_REMIX")
                    put("clientVersion", buildClientVersion())
                    put("hl", "es")
                })
            })
            put("query", query)
            put("params", "EgWKAQIIAWoMEA4QChADEAQQCRAF") // Songs filter
        }.toString().toByteArray(StandardCharsets.UTF_8)

        val url = URL(endpoint)
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.connectTimeout = 10000
        connection.readTimeout = 10000
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("User-Agent", "Mozilla/5.0")
        connection.setRequestProperty("Origin", "https://music.youtube.com")
        connection.setRequestProperty("Referer", "https://music.youtube.com/")
        if (cookieHeader.isNotEmpty()) {
            connection.setRequestProperty("Cookie", cookieHeader)
            // Cookie autenticada SIN SAPISIDHASH = 401 intermitente de InnerTube, que aquí se
            // tragaba como lista vacía y hacía DESAPARECER la sección "Covers y remixes".
            val sapisidAuth = StreamResolver.buildSapisidHashForCookie(cookieHeader, "https://music.youtube.com")
            if (sapisidAuth.isNotEmpty()) {
                connection.setRequestProperty("Authorization", sapisidAuth)
            }
        }

        connection.outputStream.use { it.write(body) }
        val statusCode = connection.responseCode
        val responseBody = readResponse(connection, statusCode >= 400)
        connection.disconnect()
        if (statusCode != HttpURLConnection.HTTP_OK) return emptyList()

        return parseInnertubeSearchResults(JSONObject(responseBody), maxResults)
    }

    private fun parseInnertubeSearchResults(root: JSONObject, maxResults: Int): List<TrackResult> {
        val results = mutableListOf<TrackResult>()
        try {
            val rootContents = root.optJSONObject("contents") ?: return results

            // Collect all sectionListRenderer content arrays to parse.
            // Two possible structures from YTMusic:
            //   A) With params filter  → contents.tabbedSearchResultsRenderer.tabs[].tabRenderer.content.sectionListRenderer.contents
            //   B) Without params filter → contents.sectionListRenderer.contents (flat)
            val allSectionContents = mutableListOf<org.json.JSONArray>()

            val tabbed = rootContents.optJSONObject("tabbedSearchResultsRenderer")
            if (tabbed != null) {
                val tabs = tabbed.optJSONArray("tabs") ?: org.json.JSONArray()
                for (t in 0 until tabs.length()) {
                    val c = tabs.optJSONObject(t)
                        ?.optJSONObject("tabRenderer")
                        ?.optJSONObject("content")
                        ?.optJSONObject("sectionListRenderer")
                        ?.optJSONArray("contents") ?: continue
                    allSectionContents.add(c)
                }
            } else {
                val flat = rootContents.optJSONObject("sectionListRenderer")
                    ?.optJSONArray("contents")
                if (flat != null) allSectionContents.add(flat)
            }

            for (contents in allSectionContents) {
                for (c in 0 until contents.length()) {
                    val section = contents.optJSONObject(c) ?: continue
                    
                    // 1. Procesar el top result (musicCardShelfRenderer)
                    val cardShelf = section.optJSONObject("musicCardShelfRenderer")
                    if (cardShelf != null) {
                        var videoId = cardShelf.optJSONObject("onTap")
                            ?.optJSONObject("watchEndpoint")
                            ?.optString("videoId", "") ?: ""
                        if (videoId.isEmpty()) {
                            videoId = cardShelf.optJSONArray("buttons")
                                ?.optJSONObject(0)
                                ?.optJSONObject("buttonRenderer")
                                ?.optJSONObject("command")
                                ?.optJSONObject("watchEndpoint")
                                ?.optString("videoId", "") ?: ""
                        }
                        if (videoId.isEmpty()) {
                            videoId = cardShelf.optJSONObject("title")
                                ?.optJSONArray("runs")
                                ?.optJSONObject(0)
                                ?.optJSONObject("navigationEndpoint")
                                ?.optJSONObject("watchEndpoint")
                                ?.optString("videoId", "") ?: ""
                        }
                        if (videoId.isNotEmpty()) {
                            val title = cardShelf.optJSONObject("title")
                                ?.optJSONArray("runs")
                                ?.optJSONObject(0)
                                ?.optString("text", "") ?: ""
                            val subtitleRuns = cardShelf.optJSONObject("subtitle")
                                ?.optJSONArray("runs")
                            val subtitle = buildString {
                                if (subtitleRuns != null) {
                                    for (r in 0 until subtitleRuns.length()) {
                                        append(subtitleRuns.optJSONObject(r)?.optString("text", "") ?: "")
                                    }
                                }
                            }
                            val thumbs = cardShelf.optJSONObject("thumbnail")
                                ?.optJSONObject("musicThumbnailRenderer")
                                ?.optJSONObject("thumbnail")
                                ?.optJSONArray("thumbnails")
                            val thumbUrl = thumbs?.let {
                                it.optJSONObject(it.length() - 1)?.optString("url", "") ?: ""
                            } ?: ""
                            if (title.isNotEmpty() && results.none { it.videoId == videoId }) {
                                results.add(TrackResult("video", videoId, title, subtitle, thumbUrl))
                            }
                        } else {
                            // Artist card: no direct videoId — extract top tracks from contents[]
                            val cardContents = cardShelf.optJSONArray("contents")
                            if (cardContents != null) {
                                for (k in 0 until cardContents.length()) {
                                    if (results.size >= maxResults) break
                                    val renderer = cardContents.optJSONObject(k)
                                        ?.optJSONObject("musicResponsiveListItemRenderer") ?: continue
                                    val vid = renderer.optJSONObject("playlistItemData")
                                        ?.optString("videoId", "")?.trim() ?: ""
                                    if (vid.isEmpty()) continue
                                    val flexColumns = renderer.optJSONArray("flexColumns")
                                    val trackTitle = flexColumns?.optJSONObject(0)
                                        ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
                                        ?.optJSONObject("text")?.optJSONArray("runs")
                                        ?.optJSONObject(0)?.optString("text", "") ?: ""
                                    val trackArtistRuns = flexColumns?.optJSONObject(1)
                                        ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
                                        ?.optJSONObject("text")?.optJSONArray("runs")
                                    val trackArtist = buildString {
                                        if (trackArtistRuns != null) {
                                            for (r in 0 until trackArtistRuns.length()) {
                                                append(trackArtistRuns.optJSONObject(r)?.optString("text", "") ?: "")
                                            }
                                        }
                                    }
                                    val thumbs2 = renderer.optJSONObject("thumbnail")
                                        ?.optJSONObject("musicThumbnailRenderer")
                                        ?.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
                                    val thumbUrl2 = thumbs2?.let {
                                        it.optJSONObject(it.length() - 1)?.optString("url", "") ?: ""
                                    } ?: ""
                                    val trackDuration = artistRunsToText(
                                        renderer.optJSONArray("fixedColumns")?.optJSONObject(0)
                                            ?.optJSONObject("musicResponsiveListItemFixedColumnRenderer")?.optJSONObject("text")
                                    )
                                    if (trackTitle.isNotEmpty() && results.none { it.videoId == vid }) {
                                        results.add(TrackResult("video", vid, trackTitle, trackArtist, thumbUrl2, trackDuration))
                                    }
                                }
                            }
                        }
                    }
                    
                    // 2. Procesar las listas estándares (musicShelfRenderer or itemSectionRenderer)
                    val shelf = section.optJSONObject("musicShelfRenderer")
                        ?.optJSONArray("contents")
                        ?: section.optJSONObject("itemSectionRenderer")
                            ?.optJSONArray("contents")
                        ?: continue
                    for (i in 0 until shelf.length()) {
                        if (results.size >= maxResults) return results
                        val renderer = shelf.optJSONObject(i)
                            ?.optJSONObject("musicResponsiveListItemRenderer") ?: continue
                        var videoId = renderer.optJSONObject("playlistItemData")
                            ?.optString("videoId", "")?.trim() ?: ""
                        if (videoId.isEmpty()) {
                            // Fallback: extract from overlay play button watchEndpoint
                            videoId = renderer.optJSONObject("overlay")
                                ?.optJSONObject("musicItemThumbnailOverlayRenderer")
                                ?.optJSONObject("content")
                                ?.optJSONObject("musicPlayButtonRenderer")
                                ?.optJSONObject("playNavigationEndpoint")
                                ?.optJSONObject("watchEndpoint")
                                ?.optString("videoId", "")?.trim() ?: ""
                        }
                        if (videoId.isEmpty()) continue
 
                        val flexColumns = renderer.optJSONArray("flexColumns")
                        var title = ""
                        var artist = ""
                        if (flexColumns != null && flexColumns.length() > 0) {
                            title = flexColumns.optJSONObject(0)
                                ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
                                ?.optJSONObject("text")
                                ?.optJSONArray("runs")
                                ?.optJSONObject(0)
                                ?.optString("text", "") ?: ""
                            if (flexColumns.length() > 1) {
                                val runs = flexColumns.optJSONObject(1)
                                    ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
                                    ?.optJSONObject("text")
                                    ?.optJSONArray("runs")
                                if (runs != null && runs.length() > 0) {
                                    val sb = StringBuilder()
                                    for (r in 0 until runs.length()) {
                                        sb.append(runs.optJSONObject(r)?.optString("text", "") ?: "")
                                    }
                                    artist = sb.toString()
                                }
                            }
                        }
                        if (title.isEmpty()) continue
 
                        val thumbs = renderer.optJSONObject("thumbnail")
                            ?.optJSONObject("musicThumbnailRenderer")
                            ?.optJSONObject("thumbnail")
                            ?.optJSONArray("thumbnails")
                        val thumbUrl = thumbs?.let {
                            it.optJSONObject(it.length() - 1)?.optString("url", "") ?: ""
                        } ?: ""

                        // Song rows carry their length in a fixedColumn ("3:20"), not the subtitle.
                        // Pull it into the dedicated duration field so the UI can render
                        // "Artist • 3:20" instead of dropping the duration (or, for solo tracks
                        // whose subtitle IS just the length, rendering "3:20 • 3:20").
                        val duration = artistRunsToText(
                            renderer.optJSONArray("fixedColumns")?.optJSONObject(0)
                                ?.optJSONObject("musicResponsiveListItemFixedColumnRenderer")?.optJSONObject("text")
                        )

                        if (results.none { it.videoId == videoId }) {
                            results.add(TrackResult("video", videoId, title, artist, thumbUrl, duration))
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w("YouTubeMusicService", "parseInnertubeSearch error: ${e.message}")
        }
        return results
    }

    fun searchReplacementCandidates(
        context: android.content.Context,
        query: String,
        originalVideoId: String,
        maxCandidates: Int,
        callback: ReplacementCandidatesCallback
    ) {
        val normalized = query.trim()
        if (normalized.isEmpty()) {
            callback.onError("No hay texto de búsqueda.")
            return
        }

        val cookieHeader = StreamResolver.getAuthCookieHeader().trim()
        val maxResults = maxOf(5, maxCandidates * 3)

        executor.execute {
            try {
                // Búsqueda vía InnerTube (WEB_REMIX) — sin API key ni cuota. Los resultados de música
                // ya traen su duración; filtramos clips demasiado cortos igual que antes.
                val pageResult = performInnertubeSearchRequest(normalized, maxResults, cookieHeader)
                val originalId = originalVideoId.trim()

                val candidates = ArrayList<ReplacementCandidate>()
                for (item in pageResult.tracks) {
                    if (!item.isVideo() || TextUtils.isEmpty(item.videoId)) continue
                    if (item.videoId == originalId) continue

                    val durationSeconds = clockDurationToSeconds(item.duration)
                    if (durationSeconds in 1 until MIN_PUBLIC_MUSIC_DURATION_SECONDS) continue
                    val durationStr = if (item.duration.isNotEmpty() && item.duration != "--:--") item.duration else "--:--"

                    candidates.add(
                        ReplacementCandidate(
                            videoId = item.videoId,
                            title = item.title,
                            artist = item.subtitle,
                            duration = durationStr,
                            thumbnailUrl = item.thumbnailUrl,
                            durationSeconds = durationSeconds
                        )
                    )
                    if (candidates.size >= maxCandidates) break
                }

                mainHandler.post { callback.onSuccess(candidates) }
            } catch (e: Exception) {
                val error = e.message ?: "No se pudieron buscar alternativas."
                mainHandler.post { callback.onError(error) }
            }
        }
    }

    /** "3:45"/"1:02:03" → segundos. 0 si no se puede parsear (p. ej. "--:--"). */
    private fun clockDurationToSeconds(clock: String): Int {
        val trimmed = clock.trim()
        if (trimmed.isEmpty() || trimmed == "--:--") return 0
        val parts = trimmed.split(":")
        return try {
            when (parts.size) {
                3 -> parts[0].toInt() * 3600 + parts[1].toInt() * 60 + parts[2].toInt()
                2 -> parts[0].toInt() * 60 + parts[1].toInt()
                1 -> parts[0].toInt()
                else -> 0
            }
        } catch (_: NumberFormatException) {
            0
        }
    }

    companion object {
        private const val YT_SCOPE_READONLY = "https://www.googleapis.com/auth/youtube.readonly"

        @JvmField
        val SPECIAL_LIKED_VIDEOS_ID = "__liked_videos__"

        private const val SPECIAL_LIKED_VIDEOS_TITLE = "Me gusta"
        private const val MIN_PUBLIC_MUSIC_DURATION_SECONDS = 70
        private const val MAX_MIX_CONTINUATIONS = 4

        private val SHARED_EXECUTOR: ExecutorService = Executors.newFixedThreadPool(3)
        private val SUGGESTIONS_EXECUTOR: ExecutorService = Executors.newFixedThreadPool(2)

        private fun buildClientVersion(): String {
            val sdf = java.text.SimpleDateFormat("yyyyMMdd", Locale.US)
            sdf.timeZone = java.util.TimeZone.getTimeZone("UTC")
            return "1.${sdf.format(java.util.Date())}.01.00"
        }

        private fun safeUrlEncode(value: String): String = try {
            URLEncoder.encode(value, StandardCharsets.UTF_8.name())
        } catch (_: Exception) {
            value
        }

        private fun containsAny(text: String, vararg terms: String): Boolean {
            for (term in terms) if (text.contains(term)) return true
            return false
        }

        private inline fun JSONArray.forEachObject(action: (JSONObject) -> Unit) {
            for (i in 0 until length()) {
                val obj = optJSONObject(i) ?: continue
                action(obj)
            }
        }
    }
}
