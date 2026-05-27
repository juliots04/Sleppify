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
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class YouTubeMusicService @JvmOverloads constructor(
    private val executor: ExecutorService = SHARED_EXECUTOR
) {

    private val mainHandler = Handler(Looper.getMainLooper())

    // ----- Public interfaces -----

    interface SearchCallback {
        fun onSuccess(tracks: List<TrackResult>)
        fun onError(error: String)
    }

    interface SearchPageCallback {
        fun onSuccess(pageResult: SearchPageResult)
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

    // ----- Public data classes (field-accessible from Java via @JvmField) -----

    class TrackResult(
        @JvmField val resultType: String,
        @JvmField val contentId: String,
        rawTitle: String,
        rawSubtitle: String,
        @JvmField val thumbnailUrl: String
    ) {
        @JvmField val title: String = androidx.core.text.HtmlCompat.fromHtml(rawTitle, androidx.core.text.HtmlCompat.FROM_HTML_MODE_LEGACY).toString()
        @JvmField val subtitle: String = androidx.core.text.HtmlCompat.fromHtml(rawSubtitle, androidx.core.text.HtmlCompat.FROM_HTML_MODE_LEGACY).toString()
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
        @JvmField val thumbnailUrl: String
    )

    class HomeBrowseResult(
        @JvmField val genericMixes: MutableList<MixResult>,
        @JvmField val personalMixes: MutableList<MixResult>,
        @JvmField val allSections: MutableList<HomeSection>
    )

    class HomeSection(
        @JvmField val title: String,
        @JvmField val items: List<MixResult>
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

    private class ApiErrorDetails(val reason: String, val message: String)

    private class TrackTempData(val title: String, val artist: String, val thumbnailUrl: String)

    private class VideoPlaybackInfo(val rawDuration: String, val embeddable: Boolean)

    private class PublicVideoFilterInfo(val embeddable: Boolean, val durationSeconds: Int)

    // ----- Public API -----

    fun searchTracks(query: String, maxResults: Int, callback: SearchCallback) {
        searchTracksPaged(query, maxResults, null, object : SearchPageCallback {
            override fun onSuccess(pageResult: SearchPageResult) {
                callback.onSuccess(pageResult.tracks)
            }

            override fun onError(error: String) {
                callback.onError(error)
            }
        })
    }

    fun searchTracksPaged(
        query: String,
        maxResults: Int,
        pageToken: String?,
        callback: SearchPageCallback
    ) {
        val normalized = query.trim()
        if (normalized.isEmpty()) {
            callback.onError("Escribe algo para buscar.")
            return
        }

        val apiKey = (BuildConfig.YOUTUBE_DATA_API_KEY ?: "").trim()
        if (apiKey.isEmpty()) {
            callback.onError("Configura YOUTUBE_DATA_API_KEY para habilitar busqueda.")
            return
        }

        executor.execute {
            try {
                val pageResult = performSearchRequest(normalized, maxOf(1, maxResults), apiKey, pageToken)
                mainHandler.post { callback.onSuccess(pageResult) }
            } catch (e: Exception) {
                val error = e.message ?: "No se pudo completar la busqueda."
                mainHandler.post { callback.onError(error) }
            }
        }
    }

    /** Primary search via YouTube Music Innertube API — no API key needed, no quota. */
    fun searchTracksViaInnertube(query: String, maxResults: Int, callback: SearchPageCallback) {
        val normalized = query.trim()
        if (normalized.isEmpty()) {
            callback.onError("Escribe algo para buscar.")
            return
        }
        executor.execute {
            try {
                val pageResult = performInnertubeSearchRequest(normalized, maxResults)
                mainHandler.post { callback.onSuccess(pageResult) }
            } catch (e: Exception) {
                val error = e.message ?: "No se pudo completar la busqueda."
                mainHandler.post { callback.onError(error) }
            }
        }
    }

    /** Continue an Innertube search using a previously returned continuation token. */
    fun continueInnertubeSearch(continuationToken: String, maxResults: Int, callback: SearchPageCallback) {
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
    private fun performInnertubeSearchRequest(query: String, maxResults: Int): SearchPageResult {
        val endpoint = "https://music.youtube.com/youtubei/v1/search?prettyPrint=false"
        
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

        // Always use a filter to get 20+ results per page with proper continuation.
        // Without filter, YTMusic only returns ~3 items per category (~12 total playable).
        // Params from ytmusicapi reference (no URL-encoding needed — body is JSON):
        //   songs:  EgWKAQIIAWoMEA4QChADEAQQCRAF
        //   videos: EgWKAQIQAWoMEA4QChADEAQQCRAF
        val searchParams = if (isVideoQuery) {
            "EgWKAQIQAWoMEA4QChADEAQQCRAF" // Videos filter
        } else {
            "EgWKAQIIAWoMEA4QChADEAQQCRAF" // Songs filter (default)
        }

        val clientContext = JSONObject().apply {
            put("clientName", "WEB_REMIX")
            put("clientVersion", buildClientVersion())
            put("hl", "en")
        }

        val body = JSONObject().apply {
            put("context", JSONObject().apply { put("client", clientContext) })
            put("query", query)
            put("params", searchParams)
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
        val rootJson: JSONObject
        try {
            connection.outputStream.use { it.write(body) }
            val statusCode = connection.responseCode
            val responseBody = readResponse(connection, statusCode >= 400)
            if (statusCode != HttpURLConnection.HTTP_OK) {
                throw IllegalStateException("Innertube search error $statusCode")
            }
            rootJson = JSONObject(responseBody)
        } finally {
            connection.disconnect()
        }

        var results = parseInnertubeSearchResults(rootJson, maxResults).toMutableList()
        if (results.isEmpty()) {
            val topKeys = rootJson.keys().asSequence().toList()
            val contentsKeys = rootJson.optJSONObject("contents")?.keys()?.asSequence()?.toList()
            Log.w("YouTubeMusicService", "[INNERTUBE_DBG] 0 results. topKeys=$topKeys contentsKeys=$contentsKeys")
            Log.w("YouTubeMusicService", "[INNERTUBE_DBG] raw=${rootJson.toString().take(2000)}")
        }

        // Return first page immediately — further pages loaded via continueInnertubeSearch + scroll
        val continuationToken = extractSearchContinuationToken(rootJson) ?: ""
        return SearchPageResult(results, continuationToken)
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
                            artist = runs.optJSONObject(0)?.optString("text", "") ?: ""
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

                results.add(TrackResult("video", videoId, title, artist, thumbUrl))
            }
        } catch (e: Exception) {
            Log.w("YouTubeMusicService", "parseInnertubeSearchContinuation error: ${e.message}")
        }
        return results
    }

    fun fetchMyPlaylists(accessToken: String, maxResults: Int, callback: PlaylistsCallback) {
        val token = accessToken.trim()
        if (token.isEmpty()) {
            callback.onError("No hay token OAuth para cargar la biblioteca.")
            return
        }

        executor.execute {
            try {
                val playlists = performMyPlaylistsRequest(token, maxOf(1, maxResults))
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
        if (token.isEmpty() || playlistId.isEmpty() || videoId.isEmpty()) {
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
        val token = accessToken.trim()
        if (token.isEmpty()) {
            callback.onError("No hay token OAuth para cargar canciones.")
            return
        }

        val normalizedPlaylistId = playlistId.trim()
        if (normalizedPlaylistId.isEmpty()) {
            callback.onError("Playlist invalida.")
            return
        }

        executor.execute {
            try {
                var tracks: List<PlaylistTrackResult> =
                    if (SPECIAL_LIKED_VIDEOS_ID == normalizedPlaylistId) {
                        val musicLikes = performMusicLikesTracksRequest(token, maxOf(1, maxResults))
                        if (musicLikes.isEmpty()) {
                            performLikedVideosTracksRequest(token, maxOf(1, maxResults))
                        } else musicLikes
                    } else {
                        performPlaylistTracksRequest(token, normalizedPlaylistId, maxOf(1, maxResults))
                    }
                mainHandler.post { callback.onSuccess(tracks) }
            } catch (e: Exception) {
                val error = e.message ?: "No se pudo cargar canciones."
                mainHandler.post { callback.onError(error) }
            }
        }
    }

    fun fetchPlaylistMeta(
        accessToken: String,
        playlistId: String,
        callback: PlaylistMetaCallback
    ) {
        val token = accessToken.trim()
        val normalizedPlaylistId = playlistId.trim()
        if (token.isEmpty() || normalizedPlaylistId.isEmpty()) {
            callback.onError("Sin datos para leer metadata de playlist.")
            return
        }

        executor.execute {
            try {
                val result = fetchPlaylistById(token, normalizedPlaylistId)
                if (result == null) {
                    mainHandler.post { callback.onError("No se encontro metadata de playlist.") }
                    return@execute
                }
                mainHandler.post { callback.onSuccess(result) }
            } catch (e: Exception) {
                val error = e.message ?: "No se pudo leer metadata de playlist."
                mainHandler.post { callback.onError(error) }
            }
        }
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
        if (cookieHeader.isEmpty() || playlistId.isEmpty()) {
            callback.onError("Datos insuficientes para cargar tracks del mix.")
            return
        }
        executor.execute {
            try {
                val tracks = performMixTracksRequest(cookieHeader, playlistId)
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
                        } catch (_: Exception) { }
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
        val token = accessToken.trim()
        if (token.isEmpty() || videoIds.isEmpty()) {
            callback.onSuccess(emptyMap())
            return
        }

        executor.execute {
            try {
                val infoMap = fetchVideoPlaybackInfoByIdsInBatches(token, videoIds)
                val result = HashMap<String, String>()
                for ((id, info) in infoMap) {
                    val formatted = formatYoutubeDuration(info.rawDuration)
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

    // ----- HTTP helper -----

    @Throws(Exception::class)
    private inline fun <T> executeGet(
        endpoint: String,
        authorization: String? = null,
        parser: (JSONObject) -> T
    ): T {
        val connection = openGetConnection(endpoint, authorization)
        try {
            val statusCode = connection.responseCode
            val body = readResponse(connection, statusCode >= 400)
            if (statusCode != HttpURLConnection.HTTP_OK) {
                throw IllegalStateException(buildApiErrorMessage(body, statusCode))
            }
            return parser(JSONObject(body))
        } finally {
            connection.disconnect()
        }
    }

    // ----- Network request implementations -----

    @Throws(Exception::class)
    private fun performSearchRequest(
        query: String,
        maxResults: Int,
        apiKey: String,
        pageToken: String?
    ): SearchPageResult {
        var endpoint = "$API_BASE_URL/search?part=snippet" +
                "&type=video" +
                "&videoCategoryId=10" +
                "&videoEmbeddable=true" +
                "&maxResults=" + minOf(50, maxResults) +
                "&q=" + safeUrlEncode(query) +
                "&key=" + safeUrlEncode(apiKey)

        val normalizedPageToken = pageToken?.trim().orEmpty()
        if (normalizedPageToken.isNotEmpty()) {
            endpoint += "&pageToken=" + safeUrlEncode(normalizedPageToken)
        }

        return executeGet(endpoint) { root ->
            val items = root.optJSONArray("items")
            val nextPageToken = root.optString("nextPageToken", "").trim()
            var result = ArrayList<TrackResult>()
            if (items == null) return@executeGet SearchPageResult(result, nextPageToken)

            items.forEachObject { item ->
                val idObject = item.optJSONObject("id") ?: return@forEachObject
                val snippet = item.optJSONObject("snippet") ?: return@forEachObject

                val kind = idObject.optString("kind", "")
                if (!kind.endsWith("#video")) return@forEachObject

                val resultType = "video"
                val contentId = idObject.optString("videoId", "").trim()
                if (contentId.isEmpty()) return@forEachObject

                val title = snippet.optString("title", "").trim()
                if (title.isEmpty()) return@forEachObject

                val channelTitle = snippet.optString("channelTitle", "").trim()
                if (!shouldIncludeMusicSearchResult(title, channelTitle)) return@forEachObject

                val subtitle = buildSearchSubtitle(resultType, channelTitle)
                val thumbnailUrl = extractYouTubeThumbnail(snippet.optJSONObject("thumbnails"))

                result.add(TrackResult(resultType, contentId, title, subtitle, thumbnailUrl))
            }

            if (result.isNotEmpty()) {
                val videoIds = ArrayList<String>()
                for (item in result) {
                    if (item.isVideo() && !TextUtils.isEmpty(item.videoId)) {
                        videoIds.add(item.videoId)
                    }
                }

                if (videoIds.isNotEmpty()) {
                    try {
                        val filterMap = fetchPublicVideoFiltersByIds(apiKey, videoIds)
                        if (filterMap.isNotEmpty()) {
                            val filtered = ArrayList<TrackResult>(result.size)
                            for (item in result) {
                                if (!item.isVideo() || TextUtils.isEmpty(item.videoId)) {
                                    filtered.add(item)
                                    continue
                                }

                                val info = filterMap[item.videoId]
                                if (info == null) {
                                    filtered.add(item)
                                    continue
                                }

                                if (!info.embeddable) continue
                                if (info.durationSeconds in 1 until MIN_PUBLIC_MUSIC_DURATION_SECONDS) continue

                                filtered.add(item)
                            }
                            result = filtered
                        }
                    } catch (_: Exception) {
                        // Si falla el filtro de embebibles, mantenemos resultados para no bloquear búsqueda.
                    }
                }
            }

            SearchPageResult(result, nextPageToken)
        }
    }

    @Throws(Exception::class)
    private fun performMyPlaylistsRequest(accessToken: String, maxResults: Int): List<PlaylistResult> {
        val targetCount = maxOf(1, maxResults)
        var pageToken = ""
        val result = ArrayList<PlaylistResult>()

        while (result.size < targetCount) {
            val pageSize = minOf(YOUTUBE_PAGE_MAX_RESULTS, targetCount - result.size)
            var endpoint = "$API_BASE_URL/playlists?part=snippet,contentDetails,status" +
                    "&mine=true" +
                    "&maxResults=$pageSize"
            if (pageToken.isNotEmpty()) {
                endpoint += "&pageToken=" + safeUrlEncode(pageToken)
            }

            val nextToken = executeGet(endpoint, "Bearer $accessToken") { root ->
                val items = root.optJSONArray("items") ?: JSONArray()
                items.forEachObject { item ->
                    val playlistId = item.optString("id", "").trim()
                    val snippet = item.optJSONObject("snippet") ?: return@forEachObject
                    val contentDetails = item.optJSONObject("contentDetails") ?: return@forEachObject
                    if (playlistId.isEmpty()) return@forEachObject

                    val title = snippet.optString("title", "").trim()
                    if (title.isEmpty()) return@forEachObject

                    val itemCount = contentDetails.optInt("itemCount", 0)
                    val owner = snippet.optString("channelTitle", "").trim()
                    val thumbnailUrl = extractYouTubeThumbnail(snippet.optJSONObject("thumbnails"))
                    val status = item.optJSONObject("status")
                    val privacyStatus = status?.optString("privacyStatus", "")?.trim().orEmpty()
                    val publishedAt = snippet.optString("publishedAt", "").trim()

                    result.add(
                        PlaylistResult(
                            playlistId, title, owner, itemCount,
                            thumbnailUrl, privacyStatus, publishedAt
                        )
                    )
                    if (result.size >= targetCount) return@forEachObject
                }
                root.optString("nextPageToken", "").trim()
            }

            pageToken = nextToken
            if (pageToken.isEmpty()) break
        }

        var likesResult: PlaylistResult? = null
        val musicLikes = fetchPlaylistById(accessToken, MUSIC_LIKES_PLAYLIST_ID)
        if (musicLikes != null) {
            likesResult = toSpecialLikesCollection(musicLikes, "Tu cuenta de YouTube Music")
        }

        if (likesResult == null) {
            val likesPlaylistId = resolveLikesPlaylistId(accessToken)
            if (likesPlaylistId.isNotEmpty()) {
                val youtubeLikes = fetchPlaylistById(accessToken, likesPlaylistId)
                if (youtubeLikes != null) {
                    likesResult = toSpecialLikesCollection(youtubeLikes, "Tu cuenta de YouTube")
                }
            }
        }

        if (likesResult == null) {
            likesResult = buildFallbackLikedVideosCollection(accessToken)
        }

        if (likesResult != null) {
            upsertPlaylistAtTop(result, likesResult)
        }

        return result
    }

    @Throws(Exception::class)
    private fun resolveLikesPlaylistId(accessToken: String): String {
        val endpoint = "$API_BASE_URL/channels?part=contentDetails&mine=true&maxResults=1"
        return executeGet(endpoint, "Bearer $accessToken") { root ->
            val items = root.optJSONArray("items")
            if (items == null || items.length() == 0) return@executeGet ""
            val first = items.optJSONObject(0) ?: return@executeGet ""
            val contentDetails = first.optJSONObject("contentDetails") ?: return@executeGet ""
            val relatedPlaylists = contentDetails.optJSONObject("relatedPlaylists") ?: return@executeGet ""
            relatedPlaylists.optString("likes", "").trim()
        }
    }

    @Throws(Exception::class)
    private fun fetchPlaylistById(accessToken: String, playlistId: String): PlaylistResult? {
        val endpoint = "$API_BASE_URL/playlists?part=snippet,contentDetails,status" +
                "&id=" + safeUrlEncode(playlistId) +
                "&maxResults=1"

        return executeGet<PlaylistResult?>(endpoint, "Bearer $accessToken") { root ->
            val items = root.optJSONArray("items")
            if (items == null || items.length() == 0) return@executeGet null

            val item = items.optJSONObject(0) ?: return@executeGet null

            val resolvedId = item.optString("id", "").trim()
            val snippet = item.optJSONObject("snippet") ?: return@executeGet null
            val contentDetails = item.optJSONObject("contentDetails") ?: return@executeGet null
            if (resolvedId.isEmpty()) return@executeGet null

            var title = snippet.optString("title", "").trim()
            if (title.isEmpty()) title = SPECIAL_LIKED_VIDEOS_TITLE

            val owner = snippet.optString("channelTitle", "").trim()
            val itemCount = maxOf(0, contentDetails.optInt("itemCount", 0))
            val thumbnailUrl = extractYouTubeThumbnail(snippet.optJSONObject("thumbnails"))
            val status = item.optJSONObject("status")
            val privacyStatus = status?.optString("privacyStatus", "")?.trim().orEmpty()
            val publishedAt = snippet.optString("publishedAt", "").trim()

            val lowered = title.lowercase(Locale.US)
            if (lowered.contains("liked") || lowered.contains("gusta")) {
                title = SPECIAL_LIKED_VIDEOS_TITLE
            }

            PlaylistResult(
                resolvedId, title, owner, itemCount,
                thumbnailUrl, privacyStatus, publishedAt
            )
        }
    }

    @Throws(Exception::class)
    private fun buildFallbackLikedVideosCollection(accessToken: String): PlaylistResult? {
        val endpoint = "$API_BASE_URL/videos?part=snippet&myRating=like&maxResults=1"
        return executeGet<PlaylistResult?>(endpoint, "Bearer $accessToken") { root ->
            val pageInfo = root.optJSONObject("pageInfo")
            val total = if (pageInfo == null) 0 else maxOf(0, pageInfo.optInt("totalResults", 0))

            val items = root.optJSONArray("items")
            if (total <= 0 && (items == null || items.length() == 0)) return@executeGet null

            var thumbnailUrl = ""
            if (items != null && items.length() > 0) {
                val first = items.optJSONObject(0)
                if (first != null) {
                    val snippet = first.optJSONObject("snippet")
                    if (snippet != null) {
                        thumbnailUrl = extractYouTubeThumbnail(snippet.optJSONObject("thumbnails"))
                    }
                }
            }

            PlaylistResult(
                SPECIAL_LIKED_VIDEOS_ID,
                SPECIAL_LIKED_VIDEOS_TITLE,
                "Tu cuenta de YouTube",
                total,
                thumbnailUrl,
                "private",
                ""
            )
        }
    }

    private fun upsertPlaylistAtTop(list: MutableList<PlaylistResult>, playlist: PlaylistResult) {
        val existingIndex = list.indexOfFirst { it.playlistId == playlist.playlistId }
        if (existingIndex >= 0) list.removeAt(existingIndex)
        list.add(0, playlist)
    }

    @Throws(Exception::class)
    private fun performPlaylistTracksRequest(
        accessToken: String,
        playlistId: String,
        maxResults: Int
    ): List<PlaylistTrackResult> {
        val targetCount = maxOf(1, maxResults)
        var pageToken = ""
        val videoMap = LinkedHashMap<String, TrackTempData>()

        while (videoMap.size < targetCount) {
            val pageSize = minOf(YOUTUBE_PAGE_MAX_RESULTS, targetCount - videoMap.size)
            var endpoint = "$API_BASE_URL/playlistItems?part=snippet,contentDetails" +
                    "&playlistId=" + safeUrlEncode(playlistId) +
                    "&maxResults=$pageSize"
            if (pageToken.isNotEmpty()) {
                endpoint += "&pageToken=" + safeUrlEncode(pageToken)
            }

            val nextToken = executeGet(endpoint, "Bearer $accessToken") { root ->
                val items = root.optJSONArray("items")
                items?.forEachObject { item ->
                    val contentDetails = item.optJSONObject("contentDetails") ?: return@forEachObject
                    val snippet = item.optJSONObject("snippet") ?: return@forEachObject

                    val videoId = contentDetails.optString("videoId", "").trim()
                    if (videoId.isEmpty()) return@forEachObject

                    val title = snippet.optString("title", "").trim()
                    if (title.isEmpty() ||
                        "deleted video".equals(title, ignoreCase = true) ||
                        "private video".equals(title, ignoreCase = true)
                    ) return@forEachObject

                    var artist = snippet.optString("videoOwnerChannelTitle", "").trim()
                    if (artist.isEmpty()) artist = snippet.optString("channelTitle", "").trim()
                    if (artist.isEmpty()) artist = "Unknown artist"

                    val thumbnailUrl = extractYouTubeThumbnail(snippet.optJSONObject("thumbnails"))
                    videoMap[videoId] = TrackTempData(title, artist, thumbnailUrl)
                    if (videoMap.size >= targetCount) return@forEachObject
                }
                root.optString("nextPageToken", "").trim()
            }

            pageToken = nextToken
            if (pageToken.isEmpty()) break
        }

        val result = ArrayList<PlaylistTrackResult>()
        val playbackInfoMap = fetchVideoPlaybackInfoByIdsInBatches(accessToken, ArrayList(videoMap.keys))
        for ((videoId, data) in videoMap) {
            val playbackInfo = playbackInfoMap[videoId]
            if (playbackInfo != null && !playbackInfo.embeddable) continue

            val durationSeconds = parseYoutubeDurationSeconds(playbackInfo?.rawDuration)
            if (durationSeconds in 1 until MIN_PUBLIC_MUSIC_DURATION_SECONDS) continue
            if (!shouldIncludeMusicSearchResult(data.title, data.artist)) continue

            var duration = formatYoutubeDuration(playbackInfo?.rawDuration)
            if (TextUtils.isEmpty(duration)) duration = "--:--"

            result.add(PlaylistTrackResult(videoId, data.title, data.artist, duration, data.thumbnailUrl))
            if (result.size >= targetCount) break
        }

        return result
    }

    @Throws(Exception::class)
    private fun performLikedVideosTracksRequest(
        accessToken: String,
        maxResults: Int
    ): List<PlaylistTrackResult> {
        val targetCount = maxOf(1, maxResults)
        var pageToken = ""
        val seenVideoIds = HashSet<String>()
        val result = ArrayList<PlaylistTrackResult>()

        while (result.size < targetCount) {
            val pageSize = minOf(YOUTUBE_PAGE_MAX_RESULTS, targetCount - result.size)
            var endpoint = "$API_BASE_URL/videos?part=snippet,contentDetails,status" +
                    "&myRating=like" +
                    "&maxResults=$pageSize"
            if (pageToken.isNotEmpty()) {
                endpoint += "&pageToken=" + safeUrlEncode(pageToken)
            }

            val nextToken = executeGet(endpoint, "Bearer $accessToken") { root ->
                val items = root.optJSONArray("items")
                items?.forEachObject { item ->
                    val videoId = item.optString("id", "").trim()
                    val snippet = item.optJSONObject("snippet") ?: return@forEachObject
                    val contentDetails = item.optJSONObject("contentDetails")
                    val status = item.optJSONObject("status")
                    if (videoId.isEmpty() || !seenVideoIds.add(videoId)) return@forEachObject

                    if (status != null && !status.optBoolean("embeddable", true)) return@forEachObject

                    val title = snippet.optString("title", "").trim()
                    if (title.isEmpty() ||
                        "deleted video".equals(title, ignoreCase = true) ||
                        "private video".equals(title, ignoreCase = true)
                    ) return@forEachObject

                    var artist = snippet.optString("channelTitle", "").trim()
                    if (artist.isEmpty()) artist = "Unknown artist"

                    val rawDuration = contentDetails?.optString("duration", "")?.trim().orEmpty()
                    val durationSec = parseYoutubeDurationSeconds(rawDuration)
                    if (durationSec in 1 until MIN_PUBLIC_MUSIC_DURATION_SECONDS) return@forEachObject
                    if (!shouldIncludeMusicSearchResult(title, artist)) return@forEachObject

                    var duration = formatYoutubeDuration(rawDuration)
                    if (duration.isEmpty()) duration = "--:--"

                    val thumbnailUrl = extractYouTubeThumbnail(snippet.optJSONObject("thumbnails"))
                    result.add(PlaylistTrackResult(videoId, title, artist, duration, thumbnailUrl))
                    if (result.size >= targetCount) return@forEachObject
                }
                root.optString("nextPageToken", "").trim()
            }

            pageToken = nextToken
            if (pageToken.isEmpty()) break
        }

        return result
    }

    private fun performMusicLikesTracksRequest(
        accessToken: String,
        maxResults: Int
    ): List<PlaylistTrackResult> {
        return try {
            performPlaylistTracksRequest(accessToken, MUSIC_LIKES_PLAYLIST_ID, maxResults)
        } catch (_: Exception) {
            ArrayList()
        }
    }

    private fun toSpecialLikesCollection(
        source: PlaylistResult,
        ownerName: String
    ): PlaylistResult = PlaylistResult(
        SPECIAL_LIKED_VIDEOS_ID,
        SPECIAL_LIKED_VIDEOS_TITLE,
        ownerName,
        source.itemCount,
        source.thumbnailUrl,
        source.privacyStatus,
        source.publishedAt
    )

    @Throws(Exception::class)
    private fun fetchVideoPlaybackInfoByIdsInBatches(
        accessToken: String,
        videoIds: List<String>
    ): Map<String, VideoPlaybackInfo> {
        val result = HashMap<String, VideoPlaybackInfo>()
        if (videoIds.isEmpty()) return result

        var start = 0
        while (start < videoIds.size) {
            val end = minOf(start + YOUTUBE_PAGE_MAX_RESULTS, videoIds.size)
            val batch = ArrayList(videoIds.subList(start, end))
            result.putAll(fetchVideoPlaybackInfoByIds(accessToken, batch))
            start += YOUTUBE_PAGE_MAX_RESULTS
        }
        return result
    }

    @Throws(Exception::class)
    private fun fetchVideoPlaybackInfoByIds(
        accessToken: String,
        videoIds: List<String>
    ): Map<String, VideoPlaybackInfo> {
        if (videoIds.isEmpty()) return emptyMap()

        val ids = videoIds.take(50).joinToString(",")
        val endpoint = "$API_BASE_URL/videos?part=contentDetails,status" +
                "&id=" + safeUrlEncode(ids)

        return executeGet(endpoint, "Bearer $accessToken") { root ->
            val playbackInfoMap = HashMap<String, VideoPlaybackInfo>()
            val items = root.optJSONArray("items") ?: return@executeGet playbackInfoMap

            items.forEachObject { item ->
                val id = item.optString("id", "").trim()
                if (id.isEmpty()) return@forEachObject

                val contentDetails = item.optJSONObject("contentDetails")
                val status = item.optJSONObject("status")

                val rawDuration = contentDetails?.optString("duration", "")?.trim().orEmpty()
                val embeddable = status == null || status.optBoolean("embeddable", true)
                playbackInfoMap[id] = VideoPlaybackInfo(rawDuration, embeddable)
            }
            playbackInfoMap
        }
    }

    @Throws(Exception::class)
    private fun fetchPublicVideoFiltersByIds(
        apiKey: String,
        videoIds: List<String>
    ): Map<String, PublicVideoFilterInfo> {
        if (videoIds.isEmpty()) return emptyMap()

        val ids = videoIds.take(50).joinToString(",")
        val endpoint = "$API_BASE_URL/videos?part=contentDetails,status" +
                "&id=" + safeUrlEncode(ids) +
                "&key=" + safeUrlEncode(apiKey)

        return executeGet(endpoint) { root ->
            val filterMap = HashMap<String, PublicVideoFilterInfo>()
            val items = root.optJSONArray("items") ?: return@executeGet filterMap

            items.forEachObject { item ->
                val id = item.optString("id", "").trim()
                if (id.isEmpty()) return@forEachObject

                val contentDetails = item.optJSONObject("contentDetails")
                val rawDuration = contentDetails?.optString("duration", "")?.trim().orEmpty()
                val durationSeconds = parseYoutubeDurationSeconds(rawDuration)

                val status = item.optJSONObject("status")
                val embeddable = status == null || status.optBoolean("embeddable", true)
                filterMap[id] = PublicVideoFilterInfo(embeddable, durationSeconds)
            }
            filterMap
        }
    }

    private fun parseYoutubeDurationSeconds(rawDuration: String?): Int {
        if (rawDuration.isNullOrEmpty()) return 0
        val hours = extractDurationComponent(rawDuration, 'H')
        val minutes = extractDurationComponent(rawDuration, 'M')
        val seconds = extractDurationComponent(rawDuration, 'S')
        return maxOf(0, hours * 3600 + minutes * 60 + seconds)
    }

    private fun formatYoutubeDuration(rawDuration: String?): String {
        if (rawDuration.isNullOrEmpty()) return "--:--"

        val hours = extractDurationComponent(rawDuration, 'H')
        val minutes = extractDurationComponent(rawDuration, 'M')
        val seconds = extractDurationComponent(rawDuration, 'S')

        return if (hours > 0) {
            String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%d:%02d", minutes, seconds)
        }
    }

    private fun extractDurationComponent(rawDuration: String, component: Char): Int {
        val markerIndex = rawDuration.indexOf(component)
        if (markerIndex <= 0) return 0

        var start = markerIndex - 1
        while (start >= 0 && rawDuration[start].isDigit()) {
            start--
        }

        val number = rawDuration.substring(start + 1, markerIndex)
        if (number.isEmpty()) return 0

        return try {
            number.toInt()
        } catch (_: NumberFormatException) {
            0
        }
    }

    @Throws(Exception::class)
    private fun openGetConnection(endpoint: String, authorization: String? = null): HttpURLConnection {
        val url = URL(endpoint)
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = 14000
        connection.readTimeout = 18000
        connection.useCaches = false
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("User-Agent", "Sleppify-Android/1.0")
        if (!authorization.isNullOrEmpty()) {
            connection.setRequestProperty("Authorization", authorization)
        }
        return connection
    }

    private fun buildSearchSubtitle(resultType: String, channelTitle: String): String {
        return when (resultType) {
            "playlist" -> if (channelTitle.isEmpty()) "Playlist" else "Playlist • $channelTitle"
            "channel" -> if (channelTitle.isEmpty()) "Artista" else "Artista • $channelTitle"
            else -> channelTitle
        }
    }

    private fun shouldIncludeMusicSearchResult(title: String, channelTitle: String?): Boolean {
        val normalizedTitle = title.lowercase(Locale.US)

        if (containsAny(normalizedTitle, "#shorts", " shorts", "shorts ")) return false

        if (containsAny(
                normalizedTitle,
                "podcast", "interview", "entrevista", "explica", "explains",
                "reaction", "trailer", "teaser", "documental", "noticias", "news"
            )
        ) return false

        return true
    }

    private fun extractYouTubeThumbnail(thumbnails: JSONObject?): String {
        if (thumbnails == null) return ""
        // Prefer medium (320×180) — sharp enough for list thumbnails (50dp ≈ 150px)
        // and search results, while being ~10x smaller than maxres (1280×720).
        // Glide .override() handles final sizing; the player cover has its own URL chain.
        val qualityOrder = arrayOf("medium", "high", "default", "standard", "maxres")
        for (quality in qualityOrder) {
            val url = readThumbnailUrl(thumbnails, quality)
            if (url.isNotEmpty()) return url
        }
        return ""
    }

    private fun readThumbnailUrl(thumbnails: JSONObject, quality: String): String {
        val obj = thumbnails.optJSONObject(quality) ?: return ""
        return obj.optString("url", "").trim()
    }

    private fun buildApiErrorMessage(rawBody: String, statusCode: Int): String {
        val details = parseApiError(rawBody)
        val reasonNormalized = details.reason.lowercase(Locale.US)
        val messageNormalized = details.message.lowercase(Locale.US)

        if (statusCode == 401) {
            return "Token de YouTube expirado o invalido. Reconecta tu cuenta."
        }
        if (statusCode == 403) {
            return buildForbiddenApiErrorMessage(reasonNormalized, messageNormalized, details)
        }

        if (details.message.isNotEmpty()) {
            return if (details.reason.isNotEmpty()) {
                "YouTube API $statusCode (${details.reason}): ${details.message}"
            } else {
                "YouTube API $statusCode: ${details.message}"
            }
        }
        return "YouTube API $statusCode: solicitud no valida."
    }

    private fun buildForbiddenApiErrorMessage(
        reasonNormalized: String,
        messageNormalized: String,
        details: ApiErrorDetails
    ): String {
        if (containsAny(
                reasonNormalized,
                "quotaexceeded", "dailylimitexceeded", "ratelimitexceeded", "userratelimitexceeded"
            ) ||
            containsAny(messageNormalized, "quota", "rate limit", "daily limit")
        ) {
            return "Cuota de YouTube agotada. Intenta mas tarde o revisa la cuota en Google Cloud."
        }

        if (containsAny(reasonNormalized, "accessnotconfigured", "servicedisabled") ||
            containsAny(messageNormalized, "api has not been used", "api is disabled", "service disabled")
        ) {
            return "YouTube Data API no esta habilitada para este proyecto en Google Cloud."
        }

        if (containsAny(
                reasonNormalized,
                "insufficientpermissions", "forbidden", "playlistforbidden", "authorizationrequired"
            ) ||
            containsAny(
                messageNormalized,
                "insufficient permission", "not properly authorized", "youtube.readonly"
            )
        ) {
            return "Faltan permisos OAuth de YouTube. Pulsa Conectar y acepta youtube.readonly."
        }

        if (details.message.isNotEmpty()) {
            val reasonLabel = if (details.reason.isEmpty()) "forbidden" else details.reason
            return "YouTube API 403 ($reasonLabel): ${details.message}"
        }

        return "Sin permiso para YouTube API (403)."
    }

    private fun parseApiError(rawBody: String): ApiErrorDetails {
        if (rawBody.isEmpty()) return ApiErrorDetails("", "")

        return try {
            val root = JSONObject(rawBody)
            val error = root.optJSONObject("error") ?: return ApiErrorDetails("", "")

            var message = error.optString("message", "").trim()
            var reason = ""

            val errors = error.optJSONArray("errors")
            if (errors != null && errors.length() > 0) {
                val first = errors.optJSONObject(0)
                if (first != null) {
                    reason = first.optString("reason", "").trim()
                    if (message.isEmpty()) {
                        message = first.optString("message", "").trim()
                    }
                }
            }

            ApiErrorDetails(reason, message)
        } catch (_: Exception) {
            ApiErrorDetails("", "")
        }
    }

    private fun readResponse(connection: HttpURLConnection, fromErrorStream: Boolean): String {
        return try {
            if (fromErrorStream && connection.errorStream == null) return ""

            val stream = if (fromErrorStream) connection.errorStream else connection.inputStream
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
        val url = URL("$API_BASE_URL/playlistItems?part=snippet")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Authorization", "Bearer $token")
        conn.setRequestProperty("Content-Type", "application/json")
        conn.doOutput = true

        val snippet = JSONObject()
        snippet.put("playlistId", playlistId)
        val resourceId = JSONObject()
        resourceId.put("kind", "youtube#video")
        resourceId.put("videoId", videoId)
        snippet.put("resourceId", resourceId)

        val root = JSONObject()
        root.put("snippet", snippet)

        conn.outputStream.use { os ->
            val input = root.toString().toByteArray(StandardCharsets.UTF_8)
            os.write(input, 0, input.size)
        }

        val code = conn.responseCode
        return code in 200..299
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
        connection.setRequestProperty("Cookie", cookieHeader)
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
                        mixes.add(MixResult(playlistId, title, subtitle, thumbUrl))
                    }
                }
            }
        } catch (e: Exception) {
            Log.w("YouTubeMusicService", "parseHomeMixes error: ${e.message}")
        }
        return mixes
    }

    @Throws(Exception::class)
    private fun performMixTracksRequest(cookieHeader: String, playlistId: String): List<TrackResult> {
        val endpoint = "https://music.youtube.com/youtubei/v1/next?prettyPrint=false"
        val bodyJson = JSONObject().apply {
            put("context", JSONObject().apply {
                put("client", JSONObject().apply {
                    put("clientName", "WEB_REMIX")
                    put("clientVersion", buildClientVersion())
                    put("hl", "es")
                })
            })
            put("playlistId", playlistId)
            put("isAudioOnly", true)
            put("enablePersistentPlaylistPanel", true)
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
        connection.setRequestProperty("Cookie", cookieHeader)
        try {
            connection.outputStream.use { it.write(bodyJson) }
            val statusCode = connection.responseCode
            val responseBody = readResponse(connection, statusCode >= 400)
            if (statusCode != HttpURLConnection.HTTP_OK) {
                throw IllegalStateException("Mix tracks error $statusCode")
            }
            return parseMixTracks(JSONObject(responseBody))
        } finally {
            connection.disconnect()
        }
    }

    private fun parseMixTracks(root: JSONObject): List<TrackResult> {
        val tracks = mutableListOf<TrackResult>()
        try {
            val playlist = root.optJSONObject("contents")
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
                ?: return tracks

            for (i in 0 until playlist.length()) {
                val renderer = playlist.optJSONObject(i)
                    ?.optJSONObject("playlistPanelVideoRenderer") ?: continue

                val title = renderer.optJSONObject("title")
                    ?.optJSONArray("runs")
                    ?.optJSONObject(0)
                    ?.optString("text", "") ?: ""
                if (title.isEmpty()) continue

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

                val videoId = renderer.optString("videoId", "").trim()
                if (videoId.isEmpty()) continue

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
                // Encode duration in subtitle with tab separator for downstream parsing
                val subtitleWithDuration = if (duration.isNotEmpty()) "$artist\t$duration" else artist

                tracks.add(TrackResult("video", videoId, title, subtitleWithDuration, thumbUrl))
            }
        } catch (e: Exception) {
            Log.w("YouTubeMusicService", "parseMixTracks error: ${e.message}")
        }
        return tracks
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

    private fun postInnerTubeBrowse(endpoint: String, body: ByteArray, cookieHeader: String): String {
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
        connection.setRequestProperty("Cookie", cookieHeader)
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

    private fun extractContinuationToken(root: JSONObject): String? {
        return root.optJSONObject("contents")
            ?.optJSONObject("singleColumnBrowseResultsRenderer")
            ?.optJSONArray("tabs")
            ?.optJSONObject(0)
            ?.optJSONObject("tabRenderer")
            ?.optJSONObject("content")
            ?.optJSONObject("sectionListRenderer")
            ?.optJSONArray("continuations")
            ?.optJSONObject(0)
            ?.optJSONObject("nextContinuationData")
            ?.optString("continuation", "")?.takeIf { it.isNotEmpty() }
    }

    private fun extractContinuationTokenFromContinuation(contJson: JSONObject): String? {
        return contJson.optJSONObject("continuationContents")
            ?.optJSONObject("sectionListContinuation")
            ?.optJSONArray("continuations")
            ?.optJSONObject(0)
            ?.optJSONObject("nextContinuationData")
            ?.optString("continuation", "")?.takeIf { it.isNotEmpty() }
    }

    private fun parseContinuationSections(contJson: JSONObject, result: HomeBrowseResult) {
        val sections = contJson.optJSONObject("continuationContents")
            ?.optJSONObject("sectionListContinuation")
            ?.optJSONArray("contents")
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
            sectionItems.add(MixResult(playlistId, title, subtitle, thumbUrl))
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
        connection.setRequestProperty("Cookie", cookieHeader)

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
                            val subtitle = cardShelf.optJSONObject("subtitle")
                                ?.optJSONArray("runs")
                                ?.optJSONObject(0)
                                ?.optString("text", "") ?: ""
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
                                    val trackArtist = flexColumns?.optJSONObject(1)
                                        ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
                                        ?.optJSONObject("text")?.optJSONArray("runs")
                                        ?.optJSONObject(0)?.optString("text", "") ?: ""
                                    val thumbs2 = renderer.optJSONObject("thumbnail")
                                        ?.optJSONObject("musicThumbnailRenderer")
                                        ?.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
                                    val thumbUrl2 = thumbs2?.let {
                                        it.optJSONObject(it.length() - 1)?.optString("url", "") ?: ""
                                    } ?: ""
                                    if (trackTitle.isNotEmpty() && results.none { it.videoId == vid }) {
                                        results.add(TrackResult("video", vid, trackTitle, trackArtist, thumbUrl2))
                                    }
                                }
                            }
                        }
                    }
                    
                    // 2. Procesar las listas estándares (musicShelfRenderer)
                    val shelf = section.optJSONObject("musicShelfRenderer")
                        ?.optJSONArray("contents") ?: continue
                    for (i in 0 until shelf.length()) {
                        if (results.size >= maxResults) return results
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
                                    artist = runs.optJSONObject(0)?.optString("text", "") ?: ""
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
 
                        if (results.none { it.videoId == videoId }) {
                            results.add(TrackResult("video", videoId, title, artist, thumbUrl))
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

        val apiKey = (BuildConfig.YOUTUBE_DATA_API_KEY ?: "").trim()
        if (apiKey.isEmpty()) {
            callback.onError("Configura YOUTUBE_DATA_API_KEY.")
            return
        }

        val appContext = context.applicationContext
        val maxResults = maxOf(5, maxCandidates * 3)

        executor.execute {
            try {
                val pageResult = performSearchRequest(normalized, maxResults, apiKey, null)
                val originalId = originalVideoId.trim()

                val eligible = ArrayList<TrackResult>()
                for (item in pageResult.tracks) {
                    if (!item.isVideo() || TextUtils.isEmpty(item.videoId)) continue
                    if (item.videoId == originalId) continue
                    eligible.add(item)
                    if (eligible.size >= maxResults) break
                }

                if (eligible.isEmpty()) {
                    mainHandler.post { callback.onSuccess(emptyList()) }
                    return@execute
                }

                val videoIds = eligible.map { it.videoId }
                val filterMap = try {
                    fetchPublicVideoFiltersByIds(apiKey, videoIds)
                } catch (_: Exception) {
                    emptyMap()
                }

                val candidates = ArrayList<ReplacementCandidate>()
                for (item in eligible) {
                    val info = filterMap[item.videoId]
                    if (info != null) {
                        if (!info.embeddable) continue
                        if (info.durationSeconds in 1 until MIN_PUBLIC_MUSIC_DURATION_SECONDS) continue
                    }

                    val durationSeconds = info?.durationSeconds ?: 0
                    val durationStr = if (durationSeconds > 0) {
                        val h = durationSeconds / 3600
                        val m = (durationSeconds % 3600) / 60
                        val s = durationSeconds % 60
                        if (h > 0) String.format(java.util.Locale.US, "%d:%02d:%02d", h, m, s)
                        else String.format(java.util.Locale.US, "%d:%02d", m, s)
                    } else "--:--"

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

    companion object {
        private const val API_BASE_URL = "https://www.googleapis.com/youtube/v3"
        private const val YT_SCOPE_READONLY = "https://www.googleapis.com/auth/youtube.readonly"
        private const val MUSIC_LIKES_PLAYLIST_ID = "LM"

        @JvmField
        val SPECIAL_LIKED_VIDEOS_ID = "__liked_videos__"

        private const val SPECIAL_LIKED_VIDEOS_TITLE = "Me gusta"
        private const val YOUTUBE_PAGE_MAX_RESULTS = 50
        private const val MIN_PUBLIC_MUSIC_DURATION_SECONDS = 70

        private val SHARED_EXECUTOR: ExecutorService = Executors.newFixedThreadPool(3)

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
